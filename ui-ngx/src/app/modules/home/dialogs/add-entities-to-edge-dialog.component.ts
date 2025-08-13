import { Component, Inject, OnInit, SkipSelf } from '@angular/core';
import { ErrorStateMatcher } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { UntypedFormBuilder, UntypedFormControl, FormGroupDirective, NgForm } from '@angular/forms';
import { DeviceService } from '@core/http/device.service';
import { EdgeService } from '@core/http/edge.service';
import { EntityType, entityTypeTranslations } from '@shared/models/entity-type.models';
import { forkJoin, Observable, BehaviorSubject } from 'rxjs';
import { AssetService } from '@core/http/asset.service';
import { EntityViewService } from '@core/http/entity-view.service';
import { DashboardService } from '@core/http/dashboard.service';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { RuleChainService } from '@core/http/rule-chain.service';
import { RuleChainType } from '@shared/models/rule-chain.models';
import { PageLink } from '@shared/models/page/page-link';
import { Direction } from '@shared/models/page/sort-order';
import { SelectionModel } from '@angular/cdk/collections';
import { debounceTime, distinctUntilChanged, map, startWith } from 'rxjs/operators';
import { DeviceInfo } from '@app/shared/models/device.models';
import { TranslateService } from '@ngx-translate/core';

export interface AddEntitiesToEdgeDialogData {
  edgeId: string;
  entityType: EntityType;
}

@Component({
  selector: 'tb-add-entities-to-edge-dialog',
  templateUrl: './add-entities-to-edge-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: AddEntitiesToEdgeDialogComponent}],
  styleUrls: []
})
export class AddEntitiesToEdgeDialogComponent extends
  DialogComponent<AddEntitiesToEdgeDialogComponent, boolean> implements OnInit, ErrorStateMatcher {

  entityType: EntityType;
  assignToEdgeTitle: string;
  entityTypeTranslation: string;

  selection = new SelectionModel<DeviceInfo>(true, []);

  searchControl = new UntypedFormControl();
  isLoading = false;

  private allEntities: DeviceInfo[] = [];
  private filteredEntities: DeviceInfo[] = [];
  entities: Observable<DeviceInfo[]>;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: AddEntitiesToEdgeDialogData,
              private deviceService: DeviceService,
              private edgeService: EdgeService,
              private assetService: AssetService,
              private entityViewService: EntityViewService,
              private dashboardService: DashboardService,
              private ruleChainService: RuleChainService,
              private translate: TranslateService,
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              public dialogRef: MatDialogRef<AddEntitiesToEdgeDialogComponent, boolean>,
              public fb: UntypedFormBuilder) {
    super(store, router, dialogRef);
    this.entityType = this.data.entityType;
  }

  ngOnInit(): void {
    this.entityTypeTranslation = this.translate.instant(entityTypeTranslations.get(this.entityType).typePlural);
    this.assignToEdgeTitle = this.translate.instant('edge.assign-entity-title', {entityType: this.entityTypeTranslation});

    this.fetchEntities();

    this.entities = this.searchControl.valueChanges.pipe(
      startWith(''),
      debounceTime(400),
      distinctUntilChanged(),
      map(value => {
        this.filteredEntities = this._filter(value);
        return this.filteredEntities;
      })
    );
  }

  private _filter(value: string): DeviceInfo[] {
    const filterValue = value.toLowerCase();
    return this.allEntities.filter(entity => entity.name.toLowerCase().includes(filterValue));
  }

  fetchEntities() {
    this.isLoading = true;
    const pageLink = new PageLink(50, 0, null, {property: 'name', direction: Direction.ASC});
    this.getEntities(pageLink).subscribe(
      (data) => {
        this.allEntities = data.data;
        this.searchControl.setValue('');
        this.isLoading = false;
      },
      () => {
        this.isLoading = false;
      }
    );
  }

  getEntities(pageLink: PageLink): Observable<any> {
    switch (this.entityType) {
      case EntityType.DEVICE:
        return this.deviceService.getTenantDeviceInfos(pageLink);
      case EntityType.ASSET:
        return this.assetService.getTenantAssetInfos(pageLink);
      case EntityType.ENTITY_VIEW:
        return this.entityViewService.getTenantEntityViewInfos(pageLink);
      case EntityType.DASHBOARD:
        return this.dashboardService.getTenantDashboards(pageLink);
      case EntityType.RULE_CHAIN:
        return this.ruleChainService.getRuleChains(pageLink, RuleChainType.CORE);
    }
  }

  isAllSelected() {
    if (!this.filteredEntities || this.filteredEntities.length === 0) {
      return false;
    }
    return this.filteredEntities.every(entity => this.selection.isSelected(entity));
  }

  masterToggle() {
    if (this.isAllSelected()) {
      this.filteredEntities.forEach(row => this.selection.deselect(row));
    } else {
      this.filteredEntities.forEach(row => this.selection.select(row));
    }
  }

  isErrorState(control: UntypedFormControl | null, form: FormGroupDirective | NgForm | null): boolean {
    const originalErrorState = this.errorStateMatcher.isErrorState(control, form);
    const customErrorState = !!(control && control.invalid);
    return originalErrorState || customErrorState;
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  assign(): void {
    const tasks: Observable<any>[] = [];
    this.selection.selected.forEach(
      (entity) => {
        tasks.push(this.assignEntityToEdge(this.data.edgeId, entity.id.id, this.entityType));
      }
    );
    forkJoin(tasks).subscribe(
      () => {
        this.dialogRef.close(true);
      }
    );
  }

  private assignEntityToEdge(edgeId: string, entityId: string, entityType: EntityType): Observable<any> {
    switch (entityType) {
      case EntityType.DEVICE:
        return this.deviceService.assignDeviceToEdge(edgeId, entityId);
      case EntityType.ASSET:
        return this.assetService.assignAssetToEdge(edgeId, entityId);
      case EntityType.ENTITY_VIEW:
        return this.entityViewService.assignEntityViewToEdge(edgeId, entityId);
      case EntityType.DASHBOARD:
        return this.dashboardService.assignDashboardToEdge(edgeId, entityId);
      case EntityType.RULE_CHAIN:
        return this.ruleChainService.assignRuleChainToEdge(edgeId, entityId);
    }
  }
}
