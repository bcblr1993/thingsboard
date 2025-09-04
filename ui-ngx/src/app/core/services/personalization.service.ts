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
import { map, tap } from 'rxjs/operators';
import { Title } from '@angular/platform-browser';
import { Observable, of } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class PersonalizationService {

  private personalizationSettings: PersonalizationSettings = null;

  constructor(
    private store: Store<AppState>,
    private adminService: AdminService,
    private titleService: Title
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
    if (settings.title) {
      this.titleService.setTitle(settings.title);
    }
    if (settings.favicon) {
      const link: HTMLLinkElement = document.querySelector("link[rel*='icon']") || document.createElement('link');
      link.type = 'image/x-icon';
      link.rel = 'shortcut icon';
      link.href = settings.favicon;
      document.getElementsByTagName('head')[0].appendChild(link);
    }
    if (settings.primaryColor) {
      this.applyColors(settings.primaryColor, settings.secondaryColor, settings.hue3Color);
    }
  }

  applyColors(primaryColor: string, secondaryColor: string, hue3Color: string) {
    const css = `
      .mat-mdc-raised-button.mat-primary, .mat-mdc-fab.mat-primary, .mat-mdc-mini-fab.mat-primary {
        background-color: ${primaryColor};
      }
      .mat-toolbar.mat-primary {
        background-color: ${primaryColor};
      }
      .mat-mdc-slider.mat-primary {
        --mdc-slider-handle-color: ${primaryColor};
        --mdc-slider-focus-handle-color: ${primaryColor};
        --mdc-slider-hover-handle-color: ${primaryColor};
        --mdc-slider-active-track-color: ${primaryColor};
        --mdc-slider-inactive-track-color: ${primaryColor};
        --mdc-slider-with-tick-marks-active-container-color: ${primaryColor};
        --mdc-slider-with-tick-marks-inactive-container-color: ${primaryColor};
      }
      .mat-mdc-checkbox-checked.mat-primary {
        --mdc-checkbox-selected-icon-color: ${primaryColor};
        --mdc-checkbox-selected-hover-icon-color: ${primaryColor};
        --mdc-checkbox-selected-focus-icon-color: ${primaryColor};
        --mdc-checkbox-selected-pressed-icon-color: ${primaryColor};
      }
      .mat-mdc-radio-button.mat-primary.mat-mdc-radio-checked {
        --mdc-radio-selected-icon-color: ${primaryColor};
        --mdc-radio-selected-hover-icon-color: ${primaryColor};
        --mdc-radio-selected-focus-icon-color: ${primaryColor};
        --mdc-radio-selected-pressed-icon-color: ${primaryColor};
      }
      .mat-tab-label-active.mat-primary {
        color: ${primaryColor};
      }
      .mat-ink-bar.mat-primary {
        background-color: ${primaryColor};
      }
      a.mat-primary, .mat-icon.mat-primary {
        color: ${primaryColor};
      }
      .mat-toolbar.mat-hue-3 {
        background-color: ${hue3Color};
      }
    `;
    const head = document.getElementsByTagName('head')[0];
    const style = document.createElement('style');
    style.type = 'text/css';
    style.appendChild(document.createTextNode(css));
    head.appendChild(style);
  }
}
