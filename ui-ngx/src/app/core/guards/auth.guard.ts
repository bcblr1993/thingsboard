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
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { select, Store } from '@ngrx/store';
import { AppState } from '../core.state';
import { selectAuth } from '../auth/auth.selectors';
import { catchError, map, mergeMap, skipWhile, take } from 'rxjs/operators';
import { AuthState } from '../auth/auth.models';
import { forkJoin, Observable, of } from 'rxjs';
import { enterZone } from '@core/operator/enterZone';
import { Authority } from '@shared/models/authority.enum';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { UtilsService } from '@core/services/utils.service';
import { isObject } from '@core/utils';
import { MobileService } from '@core/services/mobile.service';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptions } from '../http/http-utils';

@Injectable({
  providedIn: 'root'
})
export class AuthGuard  {

  constructor(private store: Store<AppState>,
              private router: Router,
              private authService: AuthService,
              private dialogService: DialogService,
              private utils: UtilsService,
              private translate: TranslateService,
              private mobileService: MobileService,
              private http: HttpClient,
              private zone: NgZone) {}

  getAuthState(): Observable<AuthState> {
    return this.store.pipe(
      select(selectAuth),
      skipWhile((authState) => !authState || !authState.isUserLoaded),
      take(1),
      enterZone(this.zone)
    );
  }

  canActivate(next: ActivatedRouteSnapshot,
              state: RouterStateSnapshot) {

    return this.getAuthState().pipe(
      mergeMap((authState) => {
        const url: string = state.url;

        let lastChild = state.root;
        const urlSegments: string[] = [];
        if (lastChild.url) {
          urlSegments.push(...lastChild.url.map(segment => segment.path));
        }
        while (lastChild.children.length) {
          lastChild = lastChild.children[0];
          if (lastChild.url) {
            urlSegments.push(...lastChild.url.map(segment => segment.path));
          }
        }
        const path = urlSegments.join('.');
        const publicId = this.utils.getQueryParam('publicId');
        const data = lastChild.data || {};
        const params = lastChild.params || {};
        const isPublic = data.module === 'public';

        if (!authState.isAuthenticated || isPublic) {
          if (publicId && publicId.length > 0) {
            this.authService.setUserFromJwtToken(null, null, false);
            this.authService.reloadUser();
            return of(false);
          } else if (!isPublic) {
            this.authService.redirectUrl = url;
            // this.authService.gotoDefaultPlace(false);
            return of(this.authService.defaultUrl(false));
          } else {
            if (path === 'login') {
              return forkJoin([this.authService.loadOAuth2Clients()]).pipe(
                map(() => {
                  return true;
                })
              );
            } else if (path === 'login.mfa') {
              if (authState.authUser?.authority === Authority.PRE_VERIFICATION_TOKEN) {
                return this.authService.getAvailableTwoFaLoginProviders().pipe(
                  map(() => {
                    return true;
                  })
                );
              }
              this.authService.logout();
              return of(this.authService.defaultUrl(false));
            } else {
              return of(true);
            }
          }
        } else {
          if (authState.authUser.isPublic) {
            if (this.authService.parsePublicId() !== publicId) {
              if (publicId && publicId.length > 0) {
                this.authService.setUserFromJwtToken(null, null, false);
                this.authService.reloadUser();
              } else {
                this.authService.logout();
              }
              return of(false);
            }
          }
          if (this.mobileService.isMobileApp() && !path.startsWith('dashboard.')) {
            this.mobileService.handleMobileNavigation(path, params);
            return of(false);
          }
          if (authState.authUser.authority === Authority.PRE_VERIFICATION_TOKEN) {
            this.authService.logout();
            return of(false);
          }
          const defaultUrl = this.authService.defaultUrl(true, authState, path, params);
          if (defaultUrl) {
            // this.authService.gotoDefaultPlace(true);
            return of(defaultUrl);
          } else {
            const authority = Authority[authState.authUser.authority];
            if (data.auth && data.auth.indexOf(authority) === -1) {
              this.dialogService.forbidden();
              return of(false);
            } else if (data.redirectTo) {
              let redirect;
              if (isObject(data.redirectTo)) {
                redirect = data.redirectTo[authority];
              } else {
                redirect = data.redirectTo;
              }
              return of(this.router.parseUrl(redirect));
            } else {
              // 如果是账号页面、通知页面或首页，始终允许访问
              if (url.startsWith('/account') || url.startsWith('/notification/inbox') || url === '/home') {
                return of(true);
              }
              
              // 检查当前访问的页面是否需要验证菜单权限
              const menuIdForPath = this.getMenuIdByPath(url);
              
              if (menuIdForPath) {
                // 如果当前路径对应一个菜单项,需要验证菜单权限
                return this.http.get<any[]>('/api/auth/menu', defaultHttpOptions()).pipe(
                  mergeMap((menuReferences) => {
                    // 检查菜单权限中是否包含当前菜单
                    const hasPermission = this.checkMenuPermission(menuReferences, menuIdForPath);
                    
                    if (!hasPermission) {
                      // 如果没有当前页面的权限,获取第一个有权限的菜单路径并重定向
                      const firstAvailableMenu = this.getFirstAvailableMenuPath(menuReferences);
                      if (firstAvailableMenu) {
                        return of(this.router.parseUrl(firstAvailableMenu));
                      } else {
                        // 如果没有任何可用菜单,重定向到首页显示提示信息
                        return of(this.router.parseUrl('/home'));
                      }
                    } else {
                      return of(true);
                    }
                  }),
                  catchError(() => {
                    // 如果获取菜单失败,仍然允许访问
                    return of(true);
                  })
                );
              } else {
                return of(true);
              }
            }
          }
        }
      }),
      catchError((err => { console.error(err); return of(false); } ))
    );
  }

  canActivateChild(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot) {
    return this.canActivate(route, state);
  }

  private hasAnyAvailableMenu(menuReferences: any[]): boolean {
    if (!menuReferences || menuReferences.length === 0) {
      return false;
    }
    
    for (const menu of menuReferences) {
      if (menu.selected !== false) {
        // 如果是link类型的菜单项，直接返回true
        if (menu.type === 'link') {
          return true;
        }
        // 如果是toggle类型，检查是否有子菜单
        if (menu.pages && menu.pages.length > 0) {
          if (this.hasAnyAvailableMenu(menu.pages)) {
            return true;
          }
        }
      }
    }
    
    return false;
  }

  private checkMenuPermission(menuReferences: any[], menuId: string): boolean {
    if (!menuReferences || menuReferences.length === 0) {
      return false;
    }
    
    for (const menu of menuReferences) {
      if (menu.id === menuId && menu.selected !== false) {
        return true;
      }
      // 递归检查子菜单
      if (menu.pages && menu.pages.length > 0) {
        if (this.checkMenuPermission(menu.pages, menuId)) {
          return true;
        }
      }
    }
    
    return false;
  }

  private getFirstAvailableMenuPath(menuReferences: any[]): string | null {
    if (!menuReferences || menuReferences.length === 0) {
      return null;
    }
    
    for (const menu of menuReferences) {
      if (menu.selected !== false) {
        // 获取菜单对应的路径
        const path = this.getMenuPath(menu.id);
        if (path) {
          return path;
        }
        
        // 如果当前菜单是toggle类型,检查子菜单
        if (menu.pages && menu.pages.length > 0) {
          const childPath = this.getFirstAvailableMenuPath(menu.pages);
          if (childPath) {
            return childPath;
          }
        }
      }
    }
    
    return null;
  }

  private getMenuPath(menuId: string): string | null {
    // 定义菜单ID到路径的映射
    const menuPathMap: { [key: string]: string } = {
      'home': '/home',
      'alarms': '/alarms',
      'dashboards': '/dashboards',
      'devices': '/entities/devices',
      'assets': '/entities/assets',
      'entity_views': '/entities/entityViews',
      'gateways': '/entities/gateways',
      'customers': '/customers',
      'rule_chains': '/ruleChains',
      'tenants': '/tenants',
      'tenant_profiles': '/tenantProfiles',
      'device_profiles': '/profiles/deviceProfiles',
      'asset_profiles': '/profiles/assetProfiles',
      'edges': '/edgeManagement/instances',
      'edge_instances': '/edgeInstances',
      'rulechain_templates': '/edgeManagement/ruleChains',
      'otaUpdates': '/otaUpdates',
      'version_control': '/version-control',
      'api_usage': '/usage',
      'audit_log': '/auditLogs',
      'widget_library': '/resources/widgets-library',
      'widget_types': '/resources/widgets-library/widget-types',
      'widgets_bundles': '/resources/widgets-library/widgets-bundles',
      'images': '/resources/images',
      'scada_symbols': '/resources/scada-symbols',
      'javascript_library': '/resources/javascript-library',
      'resources_library': '/resources/resources-library',
      'notification_inbox': '/notification/inbox',
      'notification_sent': '/notification/sent',
      'notification_recipients': '/notification/recipients',
      'notification_templates': '/notification/templates',
      'notification_rules': '/notification/rules',
      'mobile_apps': '/mobile-center/applications',
      'mobile_bundles': '/mobile-center/bundles'
    };
    
    return menuPathMap[menuId] || null;
  }

  private getMenuIdByPath(urlPath: string): string | null {
    // 定义路径到菜单ID的映射（反向映射）
    const pathToMenuIdMap: { [key: string]: string } = {
      '/home': 'home',
      '/alarms': 'alarms',
      '/dashboards': 'dashboards',
      '/entities/devices': 'devices',
      '/entities/assets': 'assets',
      '/entities/entityViews': 'entity_views',
      '/entities/gateways': 'gateways',
      '/customers': 'customers',
      '/ruleChains': 'rule_chains',
      '/tenants': 'tenants',
      '/tenantProfiles': 'tenant_profiles',
      '/profiles/deviceProfiles': 'device_profiles',
      '/profiles/assetProfiles': 'asset_profiles',
      '/edgeManagement/instances': 'edges',
      '/edgeInstances': 'edge_instances',
      '/edgeManagement/ruleChains': 'rulechain_templates',
      '/otaUpdates': 'otaUpdates',
      '/version-control': 'version_control',
      '/usage': 'api_usage',
      '/auditLogs': 'audit_log',
      '/resources/widgets-library': 'widget_library',
      '/resources/widgets-library/widget-types': 'widget_types',
      '/resources/widgets-library/widgets-bundles': 'widgets_bundles',
      '/resources/images': 'images',
      '/resources/scada-symbols': 'scada_symbols',
      '/resources/javascript-library': 'javascript_library',
      '/resources/resources-library': 'resources_library',
      '/notification/inbox': 'notification_inbox',
      '/notification/sent': 'notification_sent',
      '/notification/recipients': 'notification_recipients',
      '/notification/templates': 'notification_templates',
      '/notification/rules': 'notification_rules',
      '/mobile-center/applications': 'mobile_apps',
      '/mobile-center/bundles': 'mobile_bundles'
    };
    
    // 尝试精确匹配
    if (pathToMenuIdMap[urlPath]) {
      return pathToMenuIdMap[urlPath];
    }
    
    // 尝试前缀匹配（处理带参数的路由，如 /dashboards/xxx）
    for (const [pathPrefix, menuId] of Object.entries(pathToMenuIdMap)) {
      if (urlPath.startsWith(pathPrefix + '/') || urlPath.startsWith(pathPrefix + '?')) {
        return menuId;
      }
    }
    
    return null;
  }
}
