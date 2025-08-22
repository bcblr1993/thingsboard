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
import { UntypedFormBuilder, UntypedFormControl, FormGroupDirective, NgForm } from '@angular/forms';
import { DeviceService } from '@core/http/device.service';
import { EntityType, entityTypeTranslations } from '@shared/models/entity-type.models';
import { forkJoin, Observable } from 'rxjs';
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
import { debounceTime, distinctUntilChanged, map, startWith, switchMap, tap } from 'rxjs/operators';
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
  subType: string;
  assignToEdgeTitle: string;
  assignToEdgeText: string;
  entityTypeTranslation: string;

  selection = new SelectionModel<DeviceInfo>(true, []);

  searchControl = new UntypedFormControl();
  isLoading = false;

  private filteredEntities: DeviceInfo[] = [];
  entities: Observable<DeviceInfo[]>;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: AddEntitiesToEdgeDialogData,
              private deviceService: DeviceService,
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
    this.subType = '';
    switch (this.entityType) {
      case EntityType.DEVICE:
        this.assignToEdgeTitle = 'device.assign-device-to-edge-title';
        this.assignToEdgeText = 'device.assign-device-to-edge-text';
        break;
      case EntityType.RULE_CHAIN:
        this.assignToEdgeTitle = 'rulechain.assign-rulechain-to-edge-title';
        this.assignToEdgeText = 'rulechain.assign-rulechain-to-edge-text';
        this.subType = RuleChainType.EDGE;
        break;
      case EntityType.ASSET:
        this.assignToEdgeTitle = 'asset.assign-asset-to-edge-title';
        this.assignToEdgeText = 'asset.assign-asset-to-edge-text';
        break;
      case EntityType.ENTITY_VIEW:
        this.assignToEdgeTitle = 'entity-view.assign-entity-view-to-edge-title';
        this.assignToEdgeText = 'entity-view.assign-entity-view-to-edge-text';
        break;
      case EntityType.DASHBOARD:
        this.assignToEdgeTitle = 'dashboard.assign-dashboard-to-edge-title';
        this.assignToEdgeText = 'dashboard.assign-dashboard-to-edge-text';
        break;
    }

    const assignedEntitiesPageLink = new PageLink(1000, 0);
    this.getAssignedEntitiesToEdge(assignedEntitiesPageLink).subscribe(
      (assignedEntities) => {
        if (assignedEntities.data) {
          this.selection.select(...assignedEntities.data);
        }
      }
    );

    this.entities = this.searchControl.valueChanges.pipe(
      startWith(''),
      debounceTime(400),
      distinctUntilChanged(),
      tap(() => {
        this.isLoading = true;
      }),
      switchMap((value: string) => {
        const pageLink = new PageLink(50, 0, value, {property: 'createdTime', direction: Direction.ASC});
        return this.getEntities(pageLink);
      }),
      map((pageData) => {
        this.filteredEntities = pageData.data;
        this.isLoading = false;
        return this.filteredEntities;
      })
    );
  }

  getAssignedEntitiesToEdge(pageLink: PageLink): Observable<any> {
    switch (this.entityType) {
      case EntityType.DEVICE:
        return this.deviceService.getEdgeDevices(this.data.edgeId, pageLink);
      case EntityType.ASSET:
        return this.assetService.getEdgeAssets(this.data.edgeId, pageLink);
      case EntityType.ENTITY_VIEW:
        return this.entityViewService.getEdgeEntityViews(this.data.edgeId, pageLink);
      case EntityType.DASHBOARD:
        return this.dashboardService.getEdgeDashboards(this.data.edgeId, pageLink);
      case EntityType.RULE_CHAIN:
        return this.ruleChainService.getEdgeRuleChains(this.data.edgeId, pageLink);
    }
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
        return this.ruleChainService.getRuleChains(pageLink, this.subType as RuleChainType);
    }
  }

  isEntitySelected(entity: DeviceInfo): boolean {
    return this.selection.selected.some(e => e.id.id === entity.id.id);
  }

  toggleSelection(entity: DeviceInfo): void {
    if (this.isEntitySelected(entity)) {
      const existing = this.selection.selected.find(e => e.id.id === entity.id.id);
      if (existing) {
        this.selection.deselect(existing);
      }
    } else {
      this.selection.select(entity);
    }
  }

  isAllSelected() {
    if (!this.filteredEntities || this.filteredEntities.length === 0) {
      return false;
    }
    return this.filteredEntities.every(entity => this.isEntitySelected(entity));
  }

  masterToggle() {
    if (this.isAllSelected()) {
      this.filteredEntities.forEach(entity => {
        const existing = this.selection.selected.find(e => e.id.id === entity.id.id);
        if (existing) {
          this.selection.deselect(existing);
        }
      });
    } else {
      this.selection.select(...this.filteredEntities);
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
