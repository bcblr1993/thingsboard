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

import { Injectable, NgZone } from '@angular/core';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { select, Store } from '@ngrx/store';
import { AppState } from '../core.state';
import { selectAuth } from '../auth/auth.selectors';
import { map, skipWhile, switchMap, take } from 'rxjs/operators';
import { Observable, of } from 'rxjs';
import { enterZone } from '@core/operator/enterZone';
import { HttpClient } from '@angular/common/http';
import { MenuId, MenuReference, menuSectionMap, referenceToMenuSection } from '@core/services/menu.models';

/**
 * 动态菜单重定向守卫
 * 用于处理toggle类型菜单的默认重定向，根据用户的实际菜单权限动态重定向到第一个可用的子菜单
 */
@Injectable({
  providedIn: 'root'
})
export class DynamicMenuRedirectGuard {

  constructor(
    private store: Store<AppState>,
    private router: Router,
    private http: HttpClient,
    private zone: NgZone
  ) {}

  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot
  ): Observable<boolean | UrlTree> {
    // 从路由数据中获取父级菜单ID
    const parentMenuId = route.data?.parentMenuId as MenuId;
    
    if (!parentMenuId) {
      console.error('DynamicMenuRedirectGuard: parentMenuId not found in route data');
      return of(this.router.parseUrl('/home'));
    }

    return this.store.pipe(
      select(selectAuth),
      skipWhile((authState) => !authState || !authState.isUserLoaded),
      take(1),
      enterZone(this.zone),
      switchMap((authState) => {
        // 从后端API获取用户的菜单权限
        return this.http.get<MenuReference[]>('/api/auth/menu').pipe(
          map(menuReferences => {
            // 找到对应的父级菜单
            const parentMenuRef = this.findMenuReference(menuReferences, parentMenuId);
            
            if (!parentMenuRef || !parentMenuRef.pages || parentMenuRef.pages.length === 0) {
              // 如果没有子菜单权限，重定向到首页
              return this.router.parseUrl('/home');
            }

            // 查找第一个可用的子菜单
            const firstAvailablePage = this.findFirstAvailablePage(authState, parentMenuRef.pages);
            
            if (!firstAvailablePage) {
              // 如果没有可用的子菜单，重定向到首页
              return this.router.parseUrl('/home');
            }

            // 获取第一个可用子菜单的路径
            const menuSection = menuSectionMap.get(firstAvailablePage.id);
            if (menuSection && menuSection.path) {
              return this.router.parseUrl(menuSection.path);
            }

            // 如果找不到路径，重定向到首页
            return this.router.parseUrl('/home');
          })
        );
      })
    );
  }

  /**
   * 递归查找指定ID的菜单引用
   */
  private findMenuReference(menuReferences: MenuReference[], targetId: MenuId): MenuReference | undefined {
    for (const ref of menuReferences) {
      if (ref.id === targetId) {
        return ref;
      }
      if (ref.pages && ref.pages.length > 0) {
        const found = this.findMenuReference(ref.pages, targetId);
        if (found) {
          return found;
        }
      }
    }
    return undefined;
  }

  /**
   * 查找第一个可用的子页面
   * 会递归查找，直到找到一个type为'link'的菜单项
   */
  private findFirstAvailablePage(authState: any, pages: MenuReference[]): MenuReference | undefined {
    for (const page of pages) {
      // 检查菜单项是否被选中
      if ((page as any).selected === false) {
        continue;
      }

      // 尝试转换为菜单节点，检查是否有权限
      const menuSection = referenceToMenuSection(authState, page);
      if (!menuSection) {
        continue;
      }

      // 如果是link类型，直接返回
      if (menuSection.type === 'link') {
        return page;
      }

      // 如果是toggle类型，递归查找子菜单
      if (menuSection.type === 'toggle' && page.pages && page.pages.length > 0) {
        const found = this.findFirstAvailablePage(authState, page.pages);
        if (found) {
          return found;
        }
      }
    }
    return undefined;
  }
}

