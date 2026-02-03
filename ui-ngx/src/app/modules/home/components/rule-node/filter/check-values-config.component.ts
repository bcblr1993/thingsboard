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

import { Component } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { isDefinedAndNotNull } from '@core/public-api';
import {
  AbstractControl,
  FormArray,
  FormBuilder,
  FormGroup,
  ValidationErrors,
  ValidatorFn,
  Validators
} from '@angular/forms';
import { RuleNodeConfiguration, RuleNodeConfigurationComponent } from '@app/shared/models/rule-node.models';

interface KeyValueCheck {
  key: string;
  value: string;
  operation: string;
}

@Component({
  selector: 'tb-filter-node-check-values-config',
  templateUrl: './check-values-config.component.html',
  styleUrls: ['./check-values-config.component.scss']
})
export class CheckValuesConfigComponent extends RuleNodeConfigurationComponent {

  checkValuesConfigForm: FormGroup;
  readonly operationOptions = [
    { value: 'EQ', name: '=' },
    { value: 'NEQ', name: '!=' },
    { value: 'GT', name: '>' },
    { value: 'LT', name: '<' }
  ];
  private readonly defaultOperation = 'EQ';
  private readonly keyValuePattern = /(?:.|\s)*\S(&:.|\s)*/;

  constructor(private fb: FormBuilder) {
    super();
  }

  messageKeyValueArray(): FormArray {
    return this.checkValuesConfigForm.get('messageKeyValue') as FormArray;
  }

  metadataKeyValueArray(): FormArray {
    return this.checkValuesConfigForm.get('metadataKeyValue') as FormArray;
  }

  protected configForm(): FormGroup {
    return this.checkValuesConfigForm;
  }

  protected prepareInputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    return {
      messageKeyValue: this.normalizeKeyValueChecks(configuration?.messageKeyValue),
      metadataKeyValue: this.normalizeKeyValueChecks(configuration?.metadataKeyValue),
      checkAllKeys: isDefinedAndNotNull(configuration?.checkAllKeys) ? configuration.checkAllKeys : false
    };
  }

  protected prepareOutputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    return {
      messageKeyValue: this.filterEmptyKeyValueChecks(this.normalizeKeyValueChecks(configuration?.messageKeyValue)),
      metadataKeyValue: this.filterEmptyKeyValueChecks(this.normalizeKeyValueChecks(configuration?.metadataKeyValue)),
      checkAllKeys: configuration.checkAllKeys
    };
  }

  private atLeastOneList(controls: string[] = null) {
    return (group: FormGroup): ValidationErrors | null => {
      if (!controls) {
        controls = Object.keys(group.controls);
      }
      const hasAtLeastOne = group?.controls && controls.some((key) => {
        const formArray = group.controls[key] as FormArray;
        return this.hasAnyCompleteKeyValue(formArray);
      });

      return hasAtLeastOne ? null : {atLeastOne: true};
    };
  }

  protected onConfigurationSet(configuration: RuleNodeConfiguration) {
    this.checkValuesConfigForm = this.fb.group({
      messageKeyValue: this.buildKeyValueChecksArray(configuration.messageKeyValue),
      metadataKeyValue: this.buildKeyValueChecksArray(configuration.metadataKeyValue),
      checkAllKeys: [configuration.checkAllKeys, []]
    }, {validators: this.atLeastOneList(['messageKeyValue', 'metadataKeyValue'])});
    this.updateCheckAllKeysAvailability();
    this.checkValuesConfigForm.valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => {
      this.updateCheckAllKeysAvailability();
    });
  }

  protected updateConfiguration(configuration: RuleNodeConfiguration) {
    const prepared = this.prepareInputConfig(configuration);
    this.checkValuesConfigForm.setControl('messageKeyValue', this.buildKeyValueChecksArray(prepared.messageKeyValue));
    this.checkValuesConfigForm.setControl('metadataKeyValue', this.buildKeyValueChecksArray(prepared.metadataKeyValue));
    this.checkValuesConfigForm.patchValue({checkAllKeys: prepared.checkAllKeys}, {emitEvent: false});
    this.updateCheckAllKeysAvailability();
    this.updateValidators(false);
  }

  get touchedValidationControl(): boolean {
    return ['messageKeyValue', 'metadataKeyValue'].some(name => this.checkValuesConfigForm.get(name).touched);
  }

  get canToggleCheckAllKeys(): boolean {
    return this.hasAllKeyValuesProvided(this.messageKeyValueArray()) &&
      this.hasAllKeyValuesProvided(this.metadataKeyValueArray());
  }

  private buildKeyValueCheckGroup(check?: KeyValueCheck): AbstractControl {
    return this.fb.group({
      key: [check?.key ?? '', [this.optionalPatternValidator()]],
      operation: [check?.operation ?? this.defaultOperation, [Validators.required]],
      value: [check?.value ?? '', [this.optionalPatternValidator()]]
    }, {validators: this.keyValuePairValidator()});
  }

  private buildKeyValueChecksArray(checks: KeyValueCheck[]): FormArray {
    const items = (checks || []).map((check) => this.buildKeyValueCheckGroup(check));
    if (!items.length) {
      items.push(this.buildKeyValueCheckGroup());
    }
    return this.fb.array(items);
  }

  private hasAnyCompleteKeyValue(formArray: FormArray): boolean {
    const controls = formArray?.controls || [];
    return controls.some((control) => this.isCompleteKeyValueRow(control));
  }

  private hasAllKeyValuesProvided(formArray: FormArray): boolean {
    const controls = formArray?.controls || [];
    if (!controls.length) {
      return false;
    }
    return controls.every((control) => this.isCompleteKeyValueRow(control));
  }

  private updateCheckAllKeysAvailability(): void {
    const control = this.checkValuesConfigForm.get('checkAllKeys');
    if (!control) {
      return;
    }
    if (this.canToggleCheckAllKeys) {
      if (control.disabled) {
        control.enable({emitEvent: false});
      }
    } else if (control.enabled) {
      control.disable({emitEvent: false});
    }
  }

  private normalizeKeyValueChecks(input: any): KeyValueCheck[] {
    if (!isDefinedAndNotNull(input)) {
      return [];
    }
    if (Array.isArray(input)) {
      return input.map((entry) => ({
        key: entry?.key ?? '',
        value: entry?.value ?? '',
        operation: entry?.operation ?? this.defaultOperation
      }));
    }
    if (typeof input === 'object') {
      return Object.keys(input).map((key) => ({
        key,
        value: input[key],
        operation: this.defaultOperation
      }));
    }
    return [];
  }

  private filterEmptyKeyValueChecks(checks: KeyValueCheck[]): KeyValueCheck[] {
    return (checks || []).filter((check) => {
      const key = this.normalizeText(check?.key);
      const value = this.normalizeText(check?.value);
      return key.length > 0 && value.length > 0;
    });
  }

  private optionalPatternValidator(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
      const value = control?.value;
      if (value === null || value === undefined || value === '') {
        return null;
      }
      return Validators.pattern(this.keyValuePattern)(control);
    };
  }

  private keyValuePairValidator(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
      const key = this.normalizeText(control?.get('key')?.value);
      const value = this.normalizeText(control?.get('value')?.value);
      if (!key && !value) {
        return null;
      }
      return key && value ? null : {keyValuePairRequired: true};
    };
  }

  private normalizeText(value: unknown): string {
    return String(value ?? '').trim();
  }

  private isEmptyKeyValueRow(control: AbstractControl): boolean {
    const key = this.normalizeText(control?.get('key')?.value);
    const value = this.normalizeText(control?.get('value')?.value);
    return !key && !value;
  }

  private isCompleteKeyValueRow(control: AbstractControl): boolean {
    const key = this.normalizeText(control?.get('key')?.value);
    const value = this.normalizeText(control?.get('value')?.value);
    return key.length > 0 && value.length > 0;
  }
}
