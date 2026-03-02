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

import { Component, OnInit } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { TopologyTemplateService } from '@core/http/topology-template.service';
import { TopologyTemplate, AssetNodeType } from '@shared/models/topology.models';

@Component({
    selector: 'tb-model-add-dialog',
    templateUrl: './model-add-dialog.component.html',
    styleUrls: ['./model-add-dialog.component.scss']
})
export class ModelAddDialogComponent extends DialogComponent<ModelAddDialogComponent, TopologyTemplate> implements OnInit {

    modelForm: FormGroup;
    submitted = false;

    constructor(protected store: Store<AppState>,
        protected router: Router,
        public dialogRef: MatDialogRef<ModelAddDialogComponent, TopologyTemplate>,
        public fb: FormBuilder,
        private topologyTemplateService: TopologyTemplateService) {
        super(store, router, dialogRef);
    }

    ngOnInit(): void {
        this.modelForm = this.fb.group({
            name: ['', [Validators.required]],
            version: ['1.0.0', [Validators.required]],
            description: ['']
        });
    }

    cancel(): void {
        this.dialogRef.close(null);
    }

    save(): void {
        this.submitted = true;
        if (this.modelForm.valid) {
            const formValue = this.modelForm.value;
            const newTemplate: TopologyTemplate = {
                id: null,
                name: formValue.name,
                type: 'DEFAULT',
                description: formValue.description,
                modelVersion: formValue.version,
                configuration: {
                    structure: {
                        type: AssetNodeType.ASSET,
                        entityTypeLabel: 'New Asset',
                        namePattern: '${Name}',
                        defaultCount: 1,
                        isRemovable: false,
                        subNodes: []
                    }
                }
            };

            this.topologyTemplateService.saveTopologyTemplate(newTemplate).subscribe(
                (saved) => {
                    this.dialogRef.close(saved);
                }
            );
        }
    }
}
