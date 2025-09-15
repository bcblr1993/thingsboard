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
import { AbstractControl, FormBuilder, FormGroup, ValidatorFn, Validators } from '@angular/forms';
import { AdminService } from '@core/http/admin.service';
import { HasConfirmForm } from '@core/guards/confirm-on-exit.guard';
import { Subject } from 'rxjs';
import { AdminSettings } from '@shared/models/settings.models';
import { PersonalizationService } from '@core/services/personalization.service';
import { TitleService } from '@core/services/title.service';
import { Router } from '@angular/router';

export interface PersonalizationSettings {
  title: string;
  favicon: string;
  logo: string;
  logoHeight: number;
  loginLogo: string;
  loginLogoHeight: number;
}

export function maxImageSizeValidator(maxSizeKB: number): ValidatorFn {
  return (control: AbstractControl): {[key: string]: any} | null => {
    const value = control.value;
    if (!value || !value.startsWith('data:')) {
      return null;
    }

    const base64Data = value.substring(value.indexOf(',') + 1);
    let padding = 0;
    if (base64Data.endsWith('==')) {
      padding = 2;
    } else if (base64Data.endsWith('=')) {
      padding = 1;
    }
    const sizeInBytes = (base64Data.length * 3 / 4) - padding;

    if (sizeInBytes > maxSizeKB * 1024) {
      return { maxSizeExceeded: { maxSize: maxSizeKB } };
    }

    return null;
  };
}

export function svgBase64Validator(maxSizeKB: number): ValidatorFn {
  return (control: AbstractControl): {[key: string]: any} | null => {
    const value = control.value;
    if (!value || !value.startsWith('data:')) {
      return null;
    }

    if (!value.startsWith('data:image/svg+xml')) {
      return { invalidSvgFormat: true };
    }

    const base64Data = value.substring(value.indexOf(',') + 1);
    let padding = 0;
    if (base64Data.endsWith('==')) {
      padding = 2;
    } else if (base64Data.endsWith('=')) {
      padding = 1;
    }
    const sizeInBytes = (base64Data.length * 3 / 4) - padding;

    if (sizeInBytes > maxSizeKB * 1024) {
      return { maxSizeExceeded: { maxSize: maxSizeKB } };
    }

    return null;
  };
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
    public fb: FormBuilder,
    private titleService: TitleService,
    private router: Router
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
      favicon: ['', [maxImageSizeValidator(20)]],
      logo: ['', [svgBase64Validator(100)]],
      logoHeight: [null, [Validators.min(10)]],
      loginLogoHeight: [null, [Validators.min(10)]]
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
      loginLogo: formValue.logo
    };
    this.adminService.saveAdminSettings(this.adminSettings)
      .subscribe(adminSettings => {
        this.adminSettings = adminSettings;
        this.personalizationSettingsForm.reset(this.adminSettings.jsonValue);
        this.personalizationService.applyPersonalization(this.adminSettings.jsonValue);
        this.titleService.setTitle(this.router.routerState.snapshot.root);
      });
  }

  confirmForm(): FormGroup {
    return this.personalizationSettingsForm;
  }
}
