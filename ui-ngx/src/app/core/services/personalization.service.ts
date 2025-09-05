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

import tinycolor from 'tinycolor2';

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
    const contrastColor = tinycolor.mostReadable(primaryColor, ['#fff', '#000']).toHexString();
    const css = `
      /* --- General --- */
      a {
        color: ${secondaryColor};
      }

      .tb-primary-color {
        color: ${primaryColor};
      }

      .tb-primary-fill, .tb-no-data-bg:before, .tb-primary-fill:before {
        background: ${primaryColor};
      }

      /* === Buttons === */

      .mat-mdc-raised-button:not(.mat-accent):not(.mat-warn),
      .mat-mdc-fab:not(.mat-accent):not(.mat-warn),
      .mat-mdc-mini-fab:not(.mat-accent):not(.mat-warn) {
        background-color: ${primaryColor};
        color: ${contrastColor};
      }

      /* .mat-mdc-button:not(.mat-accent):not(.mat-warn) {
        color: ${primaryColor};
      } */

      .mat-mdc-outlined-button:not(.mat-accent):not(.mat-warn) {
        color: ${primaryColor};
        border-color: ${primaryColor};
      }

      .tb-toggle-header .mat-button-toggle-checked .mat-button-toggle-button {
        color: ${primaryColor};
        border: 1px solid ${primaryColor};
      }

      .tb-toggle-header.tb-fill .mat-button-toggle-checked .mat-button-toggle-button {
        background: ${primaryColor};
        color: ${contrastColor};
      }

      .tb-toggle-header.tb-fill.tb-invert .mat-button-toggle-checked .mat-button-toggle-button {
        background: ${contrastColor};
        color: ${primaryColor};
      }

      .tb-copy-button:hover .mat-icon {
          color: ${primaryColor} !important;
      }

      /* === Toolbar === */
      .mat-toolbar.mat-primary {
        background-color: ${primaryColor};
        color: ${contrastColor};
      }
      .mat-toolbar.mat-hue-3 {
        background-color: ${hue3Color};
      }

      /* === Tabs === */
      .tb-default .mat-mdc-tab-group, .tb-default .mat-mdc-tab-nav-bar {
        --mdc-tab-indicator-active-indicator-color: ${primaryColor};
        --mat-tab-header-active-label-text-color: ${primaryColor};
        --mat-tab-header-active-ripple-color: ${primaryColor};
        --mat-tab-header-inactive-ripple-color: ${primaryColor};
        --mat-tab-header-active-focus-label-text-color: ${primaryColor};
        --mat-tab-header-active-hover-label-text-color: ${primaryColor};
        --mat-tab-header-active-focus-indicator-color: ${primaryColor};
        --mat-tab-header-active-hover-indicator-color: ${primaryColor};
      }

      /* === Forms & Inputs === */
      .tb-datasource-index, .tb-dynamic-form-item-index {
        color: ${primaryColor};
      }
      .tb-datasource-index:before, .tb-dynamic-form-item-index:before {
        background-color: ${primaryColor};
      }
      .rule-node-config .help-icon:hover {
        color: ${primaryColor};
      }
      .tb-mobile-page-item-info .mdc-text-field:not(.mdc-text-field--disabled) input.mdc-text-field__input::placeholder,
      .tb-mobile-page-item-info .mdc-text-field:not(.mdc-text-field--disabled) input.mdc-text-field__input:-ms-input-placeholder {
        color: ${primaryColor};
      }
      .tb-image-references a.tb-reference {
        color: ${primaryColor};
      }
      .tb-image-references table.tb-entities-list-table:before {
        border: 1px solid ${primaryColor};
      }
      .tb-rule-chain-select-panel .tb-selected-option mat-icon, .tb-rule-chain-select-panel .tb-selected-option .mdc-list-item__primary-text {
        color: ${primaryColor};
      }

      /* === Map Controls === */
      .tb-map-layout .leaflet-bar a:not(.leaflet-disabled):hover, .tb-map-layout .leaflet-bar a:not(.leaflet-disabled).active {
        color: ${primaryColor};
      }
      .tb-map-layout .leaflet-bar a.tb-control-button:not(.leaflet-disabled).active > div:not(.tb-control-text):not(.tb-close),
      .tb-map-layout .leaflet-bar a.tb-control-button:not(.leaflet-disabled):hover > div:not(.tb-control-text):not(.tb-close) {
        background-color: ${primaryColor};
      }
      .tb-map-layout .leaflet-bar a.tb-control-button:not(.leaflet-disabled).active > div:not(.tb-control-text):not(.tb-close) svg,
      .tb-map-layout .leaflet-bar a.tb-control-button:not(.leaflet-disabled):hover > div:not(.tb-control-text):not(.tb-close) svg {
        fill: ${primaryColor};
      }
      .tb-map-layout .tb-map-sidebar .tb-layer-card input.tb-layer-button:checked+label.tb-layer-label, .tb-map-layout .tb-map-sidebar .tb-layer-card label.tb-layer-label:hover {
        border: 3px solid ${primaryColor};
      }

      /* === Dialogs & Popups === */
      .tb-edge-instructions-dialog .tb-markdown-view div.code-wrapper button.clipboard-btn p {
        color: ${primaryColor} !important;
      }
      .tb-edge-instructions-dialog .tb-markdown-view div.code-wrapper button.clipboard-btn div:after,
      .tb-mobile-app-configuration-dialog .tb-command-code button.clipboard-btn div:after {
        background: ${primaryColor};
      }
      .tb-edge-instructions-dialog .tb-markdown-view pre[class*="language-"] {
        border: 1px solid ${primaryColor} !important;
      }
      .tb-mobile-app-configuration-dialog .tb-command-code .code-wrapper pre[class*=language-] {
        border-color: ${primaryColor};
      }
      .tb-mobile-app-configuration-dialog .tb-command-code button.clipboard-btn p {
        color: ${primaryColor};
      }
      .tb-sent-notification-dialog .preview-group .web-preview tb-notification {
        border: 1px groove ${primaryColor};
      }

      /* === Other Specific Components === */
      .tb-notification-popover .tb-no-notification-svg-color, .unread-notification-widget .tb-no-notification-svg-color {
        color: ${primaryColor};
      }
      .tb-widget-container .tb-widget-reference-panel {
        color: ${primaryColor};
      }
      .widgets-bundle-widgets .tb-add-widget-button-text {
        color: ${primaryColor};
      }
      .tb-mobile-layout-button .tb-add-mobile-label-container:after {
        border: 1px solid ${primaryColor};
      }

      /* === Material Components (from previous attempts) === */
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
      a.mat-primary, .mat-icon.mat-primary {
        color: ${primaryColor};
      }
      .tb-default {
        --mat-option-selected-state-label-text-color: ${primaryColor};
      }
      .tb-default .mat-primary {
        --mat-full-pseudo-checkbox-selected-icon-color: ${primaryColor};
        --mat-full-pseudo-checkbox-selected-checkmark-color: ${contrastColor};
        --mat-minimal-pseudo-checkbox-selected-checkmark-color: ${primaryColor};
      }
    `;
    const head = document.getElementsByTagName('head')[0];
    const style = document.createElement('style');
    style.type = 'text/css';
    style.appendChild(document.createTextNode(css));
    head.appendChild(style);
  }
}
