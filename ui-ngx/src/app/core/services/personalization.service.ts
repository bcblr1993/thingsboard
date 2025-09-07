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
import { AdminService } from '@core/http/admin.service';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { PersonalizationSettings } from '@home/pages/admin/personalization-settings.component';
import { map } from 'rxjs/operators';

import { Observable, of } from 'rxjs';



@Injectable({
  providedIn: 'root'
})
export class PersonalizationService {

  private personalizationSettings: PersonalizationSettings = null;

  constructor(
    private store: Store<AppState>,
    private adminService: AdminService
  ) { }

  loadPersonalization(): Observable<PersonalizationSettings> {
    if (this.personalizationSettings) {
      return of(this.personalizationSettings);
    }
    return this.adminService.getPublicAdminSettings<PersonalizationSettings>('personalization').pipe(
      map(settings => {
        if (settings && settings.jsonValue) {
          this.personalizationSettings = settings.jsonValue;
          this.applyPersonalization(this.personalizationSettings);
          return this.personalizationSettings;
        }
        return null;
      })
    );
  }

  getPersonalization(): PersonalizationSettings {
    return this.personalizationSettings;
  }

  applyPersonalization(settings: PersonalizationSettings) {
    
    if (settings.favicon) {
      const link: HTMLLinkElement = document.querySelector("link[rel*='icon']") || document.createElement('link');
      link.type = 'image/x-icon';
      link.rel = 'shortcut icon';
      link.href = settings.favicon;
      document.getElementsByTagName('head')[0].appendChild(link);
    }
  }
}
