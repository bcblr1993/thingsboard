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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Authority } from '@shared/models/authority.enum';

// Note: We need to create this interface based on the backend DTO
export interface MenuSetting {
  id?: any;
  authority: Authority;
  menuConfig: any;
}

@Injectable({
  providedIn: 'root'
})
export class MenuSettingService {

  constructor(private http: HttpClient) { }

  getMenuSetting(authority: Authority): Observable<MenuSetting> {
    return this.http.get<MenuSetting>(`/api/menu/${authority}`);
  }

  saveMenuSetting(menuSetting: MenuSetting): Observable<MenuSetting> {
    return this.http.post<MenuSetting>('/api/menu', menuSetting);
  }

  // We will also need a way to get ALL menu items to display in the tree.
  // For now, we can get this from the existing MenuService, but a dedicated API might be better later.
}
