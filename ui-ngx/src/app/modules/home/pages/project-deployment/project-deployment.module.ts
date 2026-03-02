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
import { ProjectDeploymentRoutingModule } from './project-deployment-routing.module';
import { ProjectDeploymentWizardComponent } from './deployment-wizard/project-deployment-wizard.component';
import { MatStepperModule } from '@angular/material/stepper';
import { MatTreeModule } from '@angular/material/tree';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatInputModule } from '@angular/material/input';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { ModelComponentsModule } from '../model-management/model-components.module';
import { ProjectDeploymentWizardDialogComponent } from './deployment-wizard/project-deployment-wizard-dialog.component';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatDialogModule } from '@angular/material/dialog';

@NgModule({
    declarations: [
        ProjectDeploymentWizardComponent,
        ProjectDeploymentWizardDialogComponent
    ],
    imports: [
        CommonModule,
        SharedModule,
        HomeComponentsModule,
        ProjectDeploymentRoutingModule,
        MatStepperModule,
        MatTreeModule,
        ScrollingModule,
        MatIconModule,
        MatButtonModule,
        MatInputModule,
        ReactiveFormsModule,
        MatFormFieldModule,
        ModelComponentsModule,
        MatToolbarModule,
        MatDialogModule
    ]
})
export class ProjectDeploymentModule { }
