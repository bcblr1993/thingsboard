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
import { RuleNodeConfigurationComponent } from '@shared/models/rule-node.models';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { TimeseriesFetchLatestRedisNodeConfiguration } from './timeseries-fetch-latest-redis-config.models';

@Component({
  selector: 'tb-action-node-timeseries-fetch-latest-redis-config',
  templateUrl: './timeseries-fetch-latest-redis-config.component.html',
  styleUrls: ['./timeseries-fetch-latest-redis-config.component.scss']
})
export class TimeseriesFetchLatestRedisConfigComponent extends RuleNodeConfigurationComponent {

  timeseriesFetchLatestRedisConfigForm: FormGroup;

  constructor(protected store: Store<AppState>,
              private fb: FormBuilder) {
    super(store);
  }

  protected configForm(): FormGroup {
    return this.timeseriesFetchLatestRedisConfigForm;
  }

  // 变化点 1: prepareInputConfig 现在严格遵守返回类型
  // 它确保 entityTypeKeys 始终是一个 string[]
  protected prepareInputConfig(config: TimeseriesFetchLatestRedisNodeConfiguration): TimeseriesFetchLatestRedisNodeConfiguration {
    return {
      entityId: config?.entityId ?? '',
      entityType: config?.entityType ?? 'DEVICE',
      fetchAllKeys: config?.fetchAllKeys ?? false,
      entityTypeKeys: config?.entityTypeKeys ?? [] // 确保这里返回的是数组
    };
  }

  // 变化点 2: onConfigurationSet 负责将 string[] 转换为 string 以适应表单输入框
  protected onConfigurationSet(configuration: TimeseriesFetchLatestRedisNodeConfiguration) {
    // 从配置对象中提取值，并将 entityTypeKeys 数组转换为逗号分隔的字符串
    const formValue = {
      ...configuration,
      entityTypeKeys: configuration.entityTypeKeys ? configuration.entityTypeKeys.join(',') : ''
    };

    // 使用转换后的 formValue 来构建表单
    this.timeseriesFetchLatestRedisConfigForm = this.fb.group({
      entityId: [formValue.entityId, [Validators.required]],
      entityType: [formValue.entityType, [Validators.required]],
      fetchAllKeys: [formValue.fetchAllKeys, []],
      entityTypeKeys: [formValue.entityTypeKeys, []]
    });
  }

  // 此方法保持不变，它的逻辑是正确的（将表单的 string 转回 string[]）
  protected prepareOutputConfig(config: any): TimeseriesFetchLatestRedisNodeConfiguration {
    const entityTypeKeys = config.entityTypeKeys;
    config.entityTypeKeys = (typeof entityTypeKeys === 'string' && entityTypeKeys.length > 0)
      ? entityTypeKeys.split(',').map(s => s.trim())
      : [];
    return config;
  }

  protected validatorTriggers(): string[] {
    return ['fetchAllKeys'];
  }

  protected updateValidators(emitEvent: boolean) {
    const fetchAllKeys = this.timeseriesFetchLatestRedisConfigForm.get('fetchAllKeys').value;
    const entityTypeKeysControl = this.timeseriesFetchLatestRedisConfigForm.get('entityTypeKeys');

    if (fetchAllKeys) {
      entityTypeKeysControl.clearValidators();
    } else {
      entityTypeKeysControl.setValidators(Validators.required);
    }
    entityTypeKeysControl.updateValueAndValidity({emitEvent});
  }
}
