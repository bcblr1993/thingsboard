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
import { isDefinedAndNotNull } from '@core/public-api';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ValidationErrors, Validators } from '@angular/forms';
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
      checkAllKeys: isDefinedAndNotNull(configuration?.checkAllKeys) ? configuration.checkAllKeys : true
    };
  }

  protected prepareOutputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    return {
      messageKeyValue: this.normalizeKeyValueChecks(configuration?.messageKeyValue),
      metadataKeyValue: this.normalizeKeyValueChecks(configuration?.metadataKeyValue),
      checkAllKeys: configuration.checkAllKeys
    };
  }

  private atLeastOneList(controls: string[] = null) {
    return (group: FormGroup): ValidationErrors | null => {
      if (!controls) {
        controls = Object.keys(group.controls);
      }
      const hasAtLeastOne = group?.controls && controls.some(k =>
        (group.controls[k] as FormArray).length > 0);

      return hasAtLeastOne ? null : {atLeastOne: true};
    };
  }

  protected onConfigurationSet(configuration: RuleNodeConfiguration) {
    this.checkValuesConfigForm = this.fb.group({
      messageKeyValue: this.buildKeyValueChecksArray(configuration.messageKeyValue),
      metadataKeyValue: this.buildKeyValueChecksArray(configuration.metadataKeyValue),
      checkAllKeys: [configuration.checkAllKeys, []]
    }, {validators: this.atLeastOneList(['messageKeyValue', 'metadataKeyValue'])});
  }

  protected updateConfiguration(configuration: RuleNodeConfiguration) {
    const prepared = this.prepareInputConfig(configuration);
    this.checkValuesConfigForm.setControl('messageKeyValue', this.buildKeyValueChecksArray(prepared.messageKeyValue));
    this.checkValuesConfigForm.setControl('metadataKeyValue', this.buildKeyValueChecksArray(prepared.metadataKeyValue));
    this.checkValuesConfigForm.patchValue({checkAllKeys: prepared.checkAllKeys}, {emitEvent: false});
    this.updateValidators(false);
  }

  get touchedValidationControl(): boolean {
    return ['messageKeyValue', 'metadataKeyValue'].some(name => this.checkValuesConfigForm.get(name).touched);
  }

  addMessageKeyValue() {
    this.messageKeyValueArray().push(this.buildKeyValueCheckGroup());
  }

  removeMessageKeyValue(index: number) {
    this.messageKeyValueArray().removeAt(index);
  }

  addMetadataKeyValue() {
    this.metadataKeyValueArray().push(this.buildKeyValueCheckGroup());
  }

  removeMetadataKeyValue(index: number) {
    this.metadataKeyValueArray().removeAt(index);
  }

  private buildKeyValueCheckGroup(check?: KeyValueCheck): AbstractControl {
    return this.fb.group({
      key: [check?.key ?? '', [Validators.required, Validators.pattern(/(?:.|\s)*\S(&:.|\s)*/)]],
      operation: [check?.operation ?? this.defaultOperation, [Validators.required]],
      value: [check?.value ?? '', [Validators.required, Validators.pattern(/(?:.|\s)*\S(&:.|\s)*/)]]
    });
  }

  private buildKeyValueChecksArray(checks: KeyValueCheck[]): FormArray {
    const items = (checks || []).map((check) => this.buildKeyValueCheckGroup(check));
    return this.fb.array(items);
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
}
