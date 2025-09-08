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
