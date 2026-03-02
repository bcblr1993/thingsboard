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

import { Component, Inject, OnInit, SkipSelf } from '@angular/core';
import { ErrorStateMatcher } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { FormGroup, UntypedFormGroup } from '@angular/forms';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MediaBreakpoints } from '@shared/models/constants';
import { StepperOrientation } from '@angular/material/stepper';
import { ViewChild } from '@angular/core';
import { ProjectDeploymentWizardComponent } from '@home/pages/project-deployment/deployment-wizard/project-deployment-wizard.component';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { StepperSelectionEvent } from '@angular/cdk/stepper';

@Component({
    selector: 'tb-project-deployment-wizard-dialog',
    templateUrl: './project-deployment-wizard-dialog.component.html',
    styleUrls: ['./project-deployment-wizard-dialog.component.scss']
})
export class ProjectDeploymentWizardDialogComponent extends DialogComponent<ProjectDeploymentWizardDialogComponent, boolean> {

    @ViewChild('wizard', { static: false }) wizard: ProjectDeploymentWizardComponent;

    stepperOrientation: Observable<StepperOrientation>;
    stepperLabelPosition: Observable<'bottom' | 'end'>;
    selectedIndex = 0;
    showNext = true;

    constructor(protected store: Store<AppState>,
        protected router: Router,
        @Inject(MAT_DIALOG_DATA) public data: any,
        @SkipSelf() @Inject(ErrorStateMatcher) public errorStateMatcher: ErrorStateMatcher,
        public dialogRef: MatDialogRef<ProjectDeploymentWizardDialogComponent, boolean>,
        private breakpointObserver: BreakpointObserver) {
        super(store, router, dialogRef);

        this.stepperOrientation = this.breakpointObserver.observe(MediaBreakpoints['gt-sm'])
            .pipe(map(({ matches }) => matches ? 'horizontal' : 'vertical'));

        this.stepperLabelPosition = this.breakpointObserver.observe(MediaBreakpoints['gt-sm'])
            .pipe(map(({ matches }) => matches ? 'end' : 'bottom'));
    }

    get maxStepperIndex(): number {
        return this.wizard?.stepper?._steps?.length - 1;
    }

    get isPreviewing(): boolean {
        return this.wizard?.isPreviewing ?? false;
    }

    get isDeploying(): boolean {
        return this.wizard?.isDeploying ?? false;
    }

    get showDeploy(): boolean {
        return this.selectedIndex === 3;
    }

    get nextDisabled(): boolean {
        if (this.selectedIndex === 0) {
            return this.wizard?.selectTemplateForm?.invalid ?? true;
        } else if (this.selectedIndex === 1) {
            return this.wizard?.basicInfoForm?.invalid ?? true;
        }
        return false;
    }

    previousStep(): void {
        this.wizard.previousStep();
    }

    nextStep(): void {
        if (this.selectedIndex === 2) {
            this.wizard.preview();
        } else {
            this.wizard.nextStep();
        }
    }

    deploy(): void {
        this.wizard.checkCroppingAndNavigate('deploy');
    }

    changeStep($event: StepperSelectionEvent): void {
        this.selectedIndex = $event.selectedIndex;
        this.showNext = this.selectedIndex < 4 && this.selectedIndex !== 3; // Hide "Next" on preview and final steps
    }

    onDeploymentFinished(res: any) {
        // Do not close dialog, let stepper go to the final result step
    }

    close(): void {
        this.dialogRef.close(this.wizard?.deployResult ? true : false);
    }

}
