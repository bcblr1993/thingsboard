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

import { Component, OnDestroy } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { PageComponent } from '@shared/components/page.component';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { AdminService } from '@core/http/admin.service';
import { HasConfirmForm } from '@core/guards/confirm-on-exit.guard';
import { Subject } from 'rxjs';
import { AdminSettings } from '@shared/models/settings.models';
import { takeUntil } from 'rxjs/operators';
import tinycolor from 'tinycolor2';
import { PersonalizationService } from '@core/services/personalization.service';

export interface PersonalizationSettings {
  title: string;
  favicon: string;
  logo: string;
  logoHeight: number;
  loginLogo: string;
  loginLogoHeight: number;
  primaryColor: string;
  secondaryColor: string;
  hue3Color: string;
}

@Component({
  selector: 'tb-personalization-settings',
  templateUrl: './personalization-settings.component.html',
  styleUrls: ['./settings-card.scss']
})
export class PersonalizationSettingsComponent extends PageComponent implements HasConfirmForm, OnDestroy {

  personalizationSettingsForm: FormGroup;

  private adminSettings: AdminSettings<PersonalizationSettings>;
  private readonly destroy$ = new Subject<void>();

  constructor(
    protected store: Store<AppState>,
    private adminService: AdminService,
    private personalizationService: PersonalizationService,
    public fb: FormBuilder
  ) {
    super(store);
    this.buildForm();
    this.adminService.getAdminSettings<PersonalizationSettings>('personalization')
      .subscribe(adminSettings => {
        this.adminSettings = adminSettings;
        if (this.adminSettings.jsonValue) {
          this.personalizationSettingsForm.reset(this.adminSettings.jsonValue);
        }
      });
  }

  private buildForm() {
    this.personalizationSettingsForm = this.fb.group({
      title: ['', [Validators.required]],
      favicon: ['', []],
      logo: ['', []],
      logoHeight: [null, [Validators.min(10)]],
      loginLogo: ['', []],
      loginLogoHeight: [null, [Validators.min(10)]],
      primaryColor: ['', []],
      secondaryColor: ['', []],
      hue3Color: ['', []]
    });
    this.personalizationSettingsForm.get('primaryColor').valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((color) => {
      if (tinycolor(color).isValid()) {
        const secondaryColor = tinycolor(color).darken(10).toString();
        const hue3Color = tinycolor(color).lighten(10).toString();
        this.personalizationSettingsForm.patchValue({ secondaryColor, hue3Color }, { emitEvent: false });
      }
    });
  }

  ngOnDestroy() {
    super.ngOnDestroy();
    this.destroy$.next();
    this.destroy$.complete();
  }

  save(): void {
    const formValue = this.personalizationSettingsForm.value;
    this.adminSettings.jsonValue = {
      ...this.adminSettings.jsonValue,
      ...formValue,
      secondaryColor: formValue.secondaryColor,
      hue3Color: formValue.hue3Color
    };
    this.adminService.saveAdminSettings(this.adminSettings)
      .subscribe(adminSettings => {
        this.adminSettings = adminSettings;
        this.personalizationSettingsForm.reset(this.adminSettings.jsonValue);
        this.personalizationService.applyPersonalization(this.adminSettings.jsonValue);
      });
  }

  discard(): void {
    this.personalizationSettingsForm.reset(this.adminSettings.jsonValue);
  }

  confirmForm(): FormGroup {
    return this.personalizationSettingsForm;
  }
}