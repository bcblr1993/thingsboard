///
/// Copyright © 2016-2025 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Injectable } from '@angular/core';
import { select, Store } from '@ngrx/store';
import { AppState } from '../core.state';
import { getCurrentOpenedMenuSections, selectAuth, selectIsAuthenticated } from '../auth/auth.selectors';
import { catchError, filter, first, map, switchMap, take } from 'rxjs/operators';
import { buildUserHome, HomeSection, MenuId, MenuSection, referenceToMenuSection } from '@core/services/menu.models';
import { Observable, of, ReplaySubject, Subject } from 'rxjs';
import { AuthState } from '@core/auth/auth.models';
import { NavigationEnd, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class MenuService {

  private currentMenuSections: Array<MenuSection>;
  private menuSections$: Subject<Array<MenuSection>> = new ReplaySubject<Array<MenuSection>>(1);
  private homeSections$: Subject<Array<HomeSection>> = new ReplaySubject<Array<HomeSection>>(1);
  private availableMenuSections$: Subject<Array<MenuSection>> = new ReplaySubject<Array<MenuSection>>(1);
  private hasMenu$: Subject<boolean> = new ReplaySubject<boolean>(1);
  private menuLoaded$: Subject<boolean> = new ReplaySubject<boolean>(1);
  private availableMenuLinks$ = this.menuSections$.pipe(
    map((items) => this.allMenuLinks(items))
  );

  constructor(private store: Store<AppState>,
              private router: Router,
              private http: HttpClient) {
    // 初始化 menuLoaded$ 为 false
    this.menuLoaded$.next(false);

    this.store.pipe(select(selectIsAuthenticated)).pipe(
      filter(authenticated => authenticated),
      switchMap(() => this.store.pipe(select(selectAuth), take(1))),
      filter(authState => !!authState.authUser)
    ).subscribe((authState) => {
      this.buildMenu(authState);
    });

    this.router.events.pipe(filter(event => event instanceof NavigationEnd)).subscribe(
      () => {
        this.updateOpenedMenuSections();
      }
    );
  }

  private buildMenu(authState: AuthState) {
    this.menuLoaded$.next(false);
    this.http.get<any[]>('/api/auth/menu').subscribe(
      menuReferences => {
        this.currentMenuSections = (menuReferences || []).map(ref => referenceToMenuSection(authState, ref)).filter(section => !!section);
        this.updateOpenedMenuSections();
        this.menuSections$.next(this.currentMenuSections);
        const availableMenuSections = this.allMenuSections(this.currentMenuSections);
        this.availableMenuSections$.next(availableMenuSections);
        const homeSections = buildUserHome(authState, availableMenuSections);
        this.homeSections$.next(homeSections);
        // 检查是否有菜单
        const hasMenu = this.currentMenuSections && this.currentMenuSections.length > 0;
        this.hasMenu$.next(hasMenu);
        this.menuLoaded$.next(true);
      },
      error => {
        // 如果菜单加载失败，也标记为已加载（但菜单为空）
        this.currentMenuSections = [];
        this.menuSections$.next([]);
        this.hasMenu$.next(false);
        this.menuLoaded$.next(true);
      }
    );
  }

  private updateOpenedMenuSections() {
    const url = this.router.url;
    const openedMenuSections = getCurrentOpenedMenuSections(this.store);
    if (this.currentMenuSections?.length) {
      this.currentMenuSections.filter(section => section.type === 'toggle' &&
        (url.startsWith(section.path) || openedMenuSections.includes(section.path))).forEach(
        section => section.opened = true
      );
    }
  }

  private allMenuLinks(sections: Array<MenuSection>): Array<MenuSection> {
    const result: Array<MenuSection> = [];
    for (const section of sections) {
      if (section.type === 'link') {
        result.push(section);
      }
      if (section.pages && section.pages.length) {
        result.push(...this.allMenuLinks(section.pages));
      }
    }
    return result;
  }

  private allMenuSections(sections: Array<MenuSection>): Array<MenuSection> {
    const result: Array<MenuSection> = [];
    for (const section of sections) {
      result.push(section);
      if (section.pages && section.pages.length) {
        result.push(...this.allMenuSections(section.pages));
      }
    }
    return result;
  }

  public menuSections(): Observable<Array<MenuSection>> {
    return this.menuSections$;
  }

  public homeSections(): Observable<Array<HomeSection>> {
    return this.homeSections$;
  }

  public availableMenuLinks(): Observable<Array<MenuSection>> {
    return this.availableMenuLinks$;
  }

  public availableMenuSections(): Observable<Array<MenuSection>> {
    return this.availableMenuSections$;
  }

  public menuLinkById(id: MenuId | string): Observable<MenuSection | undefined> {
    return this.availableMenuLinks$.pipe(
      map((links) => links.find(link => link.id === id))
    );
  }

  public menuLinksByIds(ids: string[]): Observable<Array<MenuSection>> {
    return this.availableMenuLinks$.pipe(
      map((links) => links.filter(link => ids.includes(link.id)).sort((a, b) => {
        const i1 = ids.indexOf(a.id);
        const i2 = ids.indexOf(b.id);
        return i1 - i2;
      }))
    );
  }

  public hasMenu(): Observable<boolean> {
    return this.hasMenu$;
  }

  /**
   * 获取第一个可用的菜单链接路径
   * 如果是一级菜单（link类型），返回一级菜单的路径
   * 如果是二级菜单（toggle类型），返回第一个可用的子菜单的路径
   */
  public getFirstAvailableMenuPath(): Observable<string | null> {
    // 等待菜单加载完成，然后获取第一个菜单路径
    return this.menuLoaded$.pipe(
      filter(loaded => loaded),
      take(1),
      switchMap(() => this.menuSections$.pipe(
        take(1),
        map((sections) => {
          if (!sections || sections.length === 0) {
            return null;
          }

          const findFirstLink = (items: MenuSection[]): string | null => {
            for (const item of items) {
              if (item.type === 'link') {
                return item.path;
              }
              if (item.pages && item.pages.length > 0) {
                const subPath = findFirstLink(item.pages);
                if (subPath) {
                  return subPath;
                }
              }
            }
            return null;
          };

          return findFirstLink(sections);
        })
      )),
      catchError(() => of(null))
    );
  }

  public isPathAllowed(path: string): Observable<boolean> {
    return this.menuSections$.pipe(
      take(1),
      map((sections) => {
        const checkPath = (items: MenuSection[]): boolean => {
          for (const item of items) {
            if (item.type === 'link') {
              if (path.startsWith(item.path)) {
                return true;
              }
              if (item.path.startsWith(path + '/')) {
                return true;
              }
              // 特殊处理 dashboard 路径，因为它在菜单中是 /dashboards，但 URL 可能是 /dashboard/xxx
              if (path.startsWith('/dashboard/') && item.path === '/dashboards') {
                return true;
              }
            }
            // 允许 toggle 类型的菜单路径（例如一级菜单文件夹），但必须是精确匹配
            // 这样可以防止访问已撤销的子菜单（例如 /entities/devices），同时允许访问父菜单（/entities）
            if (item.type === 'toggle' && (path === item.path || path.startsWith(item.path + '?'))) {
              return true;
            }
            if (item.pages && item.pages.length > 0) {
              if (checkPath(item.pages)) {
                return true;
              }
            }
          }
          return false;
        };
        return checkPath(sections);
      })
    );
  }

}
