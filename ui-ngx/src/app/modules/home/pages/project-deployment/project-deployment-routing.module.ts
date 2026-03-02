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
import { RouterModule, Routes } from '@angular/router';
import { Authority } from '@shared/models/authority.enum';
import { MenuId } from '@core/services/menu.models';
import { ProjectDeploymentWizardComponent } from './deployment-wizard/project-deployment-wizard.component';
import { EntitiesTableComponent } from '@home/components/entity/entities-table.component';
import { ProjectDeploymentTableConfigResolver } from './project-deployment-table-config.resolver';

const routes: Routes = [
    {
        path: 'projects',
        data: {
            breadcrumb: {
                menuId: MenuId.projects
            }
        },
        children: [
            {
                path: '',
                component: EntitiesTableComponent,
                data: {
                    auth: [Authority.TENANT_ADMIN],
                    title: 'item.projects'
                },
                resolve: {
                    entitiesTableConfig: ProjectDeploymentTableConfigResolver
                }
            }
        ]
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: [RouterModule],
    providers: [
        ProjectDeploymentTableConfigResolver
    ]
})
export class ProjectDeploymentRoutingModule { }
