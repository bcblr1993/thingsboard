import { Component, Inject } from '@angular/core';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { RelationService } from '@core/http/relation.service';

export interface ImportAssetConfigDialogData {
}

@Component({
  selector: 'tb-import-asset-config-dialog',
  templateUrl: './import-asset-config-dialog.component.html',
  styleUrls: ['./import-asset-config-dialog.component.scss']
})
export class ImportAssetConfigDialogComponent extends DialogComponent<ImportAssetConfigDialogComponent, File> {

  form: UntypedFormGroup;

  constructor(
    protected store: Store<AppState>,
    protected router: Router,
    @Inject(MAT_DIALOG_DATA) public data: ImportAssetConfigDialogData,
    public dialogRef: MatDialogRef<ImportAssetConfigDialogComponent, File>,
    private fb: UntypedFormBuilder,
    private relationService: RelationService
  ) {
    super(store, router, dialogRef);
    this.form = this.fb.group({
      file: [null, [Validators.required]]
    });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  import(): void {
    if (this.form.valid) {
      const fileContent = this.form.get('file').value;
      const BOM = '\uFEFF';
      const file = new File([BOM + fileContent], 'asset_config.csv', {type: 'text/csv;charset=utf-8'});

      this.relationService.bulkImport(file).subscribe(() => {
        this.dialogRef.close(file);
      });
    }
  }
}