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

import { Component, Inject } from '@angular/core';
import { MatDialog, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { RelationService } from '@core/http/relation.service';
import { ValidationResultDialogComponent, ValidationResultDialogData } from './validate-asset-device-config-result-dialog.component';

export interface ValidateAssetDeviceConfigDialogData {
}

@Component({
  selector: 'tb-validate-asset-device-config-dialog',
  templateUrl: './validate-asset-device-config-dialog.component.html',
  styleUrls: ['./validate-asset-device-config-dialog.component.scss']
})
export class ValidateAssetDeviceConfigDialogComponent extends DialogComponent<ValidateAssetDeviceConfigDialogComponent, boolean> {

  form: UntypedFormGroup;

  constructor(
    protected store: Store<AppState>,
    protected router: Router,
    @Inject(MAT_DIALOG_DATA) public data: ValidateAssetDeviceConfigDialogData,
    public dialogRef: MatDialogRef<ValidateAssetDeviceConfigDialogComponent, boolean>,
    private fb: UntypedFormBuilder,
    private relationService: RelationService,
    private dialog: MatDialog
  ) {
    super(store, router, dialogRef);
    this.form = this.fb.group({
      assetFile: [null, [Validators.required]],
      deviceFile: [null, [Validators.required]],
      relationFile: [null, [Validators.required]]
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  validate(): void {
    if (this.form.invalid) {
      return;
    }

    const assetFileContent = this.form.get('assetFile').value;
    const deviceFileContent = this.form.get('deviceFile').value;
    const relationFileContent = this.form.get('relationFile').value;

    const BOM = '\uFEFF';
    const assetFile = new File([BOM + assetFileContent], 'asset.csv', {type: 'text/csv;charset=utf-8'});
    const deviceFile = new File([BOM + deviceFileContent], 'device.csv', {type: 'text/csv;charset=utf-8'});
    const relationFile = new File([BOM + relationFileContent], 'relation.csv', {type: 'text/csv;charset=utf-8'});

    this.relationService.validateAssetsDevices(deviceFile, assetFile, relationFile).subscribe(response => {
      this.dialogRef.close(true);
      this.dialog.open<ValidationResultDialogComponent, ValidationResultDialogData>(ValidationResultDialogComponent, {
          disableClose: true,
          data: response.data,
          width: '800px',
          panelClass: ['tb-dialog', 'tb-fullscreen-dialog']
        });
    });
  }
}

