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

import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@modules/home/components/home-components.module';
import { ModelManagementRoutingModule } from './model-management-routing.module';
import { ModelAddDialogComponent } from './model-add-dialog/model-add-dialog.component';
import { ModelPreviewDialogComponent } from './model-preview-dialog/model-preview-dialog.component';
import { ModelDetailsPanelComponent } from './model-details-panel/model-details-panel.component';
import { ModelTabsComponent } from './model-tabs.component';


import { ModelWizardDialogComponent } from './model-wizard-dialog/model-wizard-dialog.component';

import { ModelComponentsModule } from './model-components.module';
import { TopologyTemplatesTableConfigResolver } from './topology-templates-table-config.resolver';

@NgModule({
    declarations: [
        ModelAddDialogComponent,
        ModelPreviewDialogComponent,
        ModelDetailsPanelComponent,
        ModelTabsComponent,
        ModelWizardDialogComponent
    ],
    imports: [
        CommonModule,
        SharedModule,
        HomeComponentsModule,
        ModelManagementRoutingModule,
        ModelComponentsModule
    ],
    providers: [
        TopologyTemplatesTableConfigResolver
    ]
})
export class ModelManagementModule { }
