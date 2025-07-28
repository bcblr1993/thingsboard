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

import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { SharedModule } from "@shared/shared.module";

// 变化点: 将导入路径修改为相对路径
import { TimeseriesFetchLatestRedisConfigComponent } from "./timeseries-fetch-latest-redis-config.component";

@NgModule({
  declarations: [
    TimeseriesFetchLatestRedisConfigComponent
  ],
  imports: [
    CommonModule,
    SharedModule
  ],
  exports: [
    TimeseriesFetchLatestRedisConfigComponent
  ]
})
export class TimeseriesFetchLatestRedisConfigModule {
}
