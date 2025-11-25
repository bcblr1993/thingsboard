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
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { DialogComponent } from '@shared/components/dialog.component';
import { AppState } from '@core/core.state';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AuthService } from '@core/auth/auth.service';

export interface NoMenuPermissionDialogData {
  title: string;
  message: string;
  logoutText: string;
}

@Component({
  selector: 'tb-no-menu-permission-dialog',
  templateUrl: './no-menu-permission-dialog.component.html',
  styleUrls: ['./no-menu-permission-dialog.component.scss']
})
export class NoMenuPermissionDialogComponent extends DialogComponent<NoMenuPermissionDialogComponent, boolean> {
  constructor(protected store: Store<AppState>,
              protected router: Router,
              public dialogRef: MatDialogRef<NoMenuPermissionDialogComponent, boolean>,
              @Inject(MAT_DIALOG_DATA) public data: NoMenuPermissionDialogData,
              private authService: AuthService) {
    super(store, router, dialogRef);
  }

  logout(): void {
    this.authService.logout();
    this.dialogRef.close(true);
  }
}

