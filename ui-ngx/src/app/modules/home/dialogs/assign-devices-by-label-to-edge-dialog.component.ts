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

import { Component, Inject, OnDestroy, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DeviceService } from '@core/http/device.service';
import { Observable, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, startWith, switchMap, takeUntil } from 'rxjs/operators';

export interface AssignDevicesByLabelToEdgeDialogData {
  edgeId: string;
}

@Component({
  selector: 'tb-assign-devices-by-label-to-edge-dialog',
  templateUrl: './assign-devices-by-label-to-edge-dialog.component.html',
  styleUrls: []
})
export class AssignDevicesByLabelToEdgeDialogComponent implements OnInit, OnDestroy {

  assignDevicesByLabelFormGroup: FormGroup;
  private destroy$ = new Subject<void>();
  filteredLabels: Observable<Array<string>>;

  constructor(
    private store: Store<AppState>,
    private fb: FormBuilder,
    public dialogRef: MatDialogRef<AssignDevicesByLabelToEdgeDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: AssignDevicesByLabelToEdgeDialogData,
    private deviceService: DeviceService
  ) {
    this.assignDevicesByLabelFormGroup = this.fb.group({
      deviceLabel: ['', [Validators.required]]
    });
  }

  ngOnInit(): void {
    this.filteredLabels = this.assignDevicesByLabelFormGroup.get('deviceLabel').valueChanges
      .pipe(
        startWith(''),
        debounceTime(400),
        distinctUntilChanged(),
        switchMap(searchText => {
          return this.deviceService.getTenantDeviceLabels(searchText);
        })
      );
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  assign(): void {
    const deviceLabel = this.assignDevicesByLabelFormGroup.get('deviceLabel').value;
    this.deviceService.assignDevicesToEdgeByLabel(this.data.edgeId, deviceLabel).pipe(takeUntil(this.destroy$)).subscribe(
      () => {
        this.dialogRef.close(true);
      }
    );
  }
}
