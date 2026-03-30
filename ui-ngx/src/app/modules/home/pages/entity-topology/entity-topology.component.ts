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

import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { PageComponent } from '@shared/components/page.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityRelationService } from '@core/http/entity-relation.service';
import { EntityType } from '@shared/models/entity-type.models';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatTreeNestedDataSource } from '@angular/material/tree';
import { HierarchyNode } from '@shared/models/relation.models';
import { TranslateService } from '@ngx-translate/core';
import { DeviceComponent } from '@modules/home/pages/device/device.component';
import { DeviceTabsComponent } from '@modules/home/pages/device/device-tabs.component';
import { DeviceService } from '@core/http/device.service';
import { DeviceInfo } from '@shared/models/device.models';
import { AssetService } from '@core/http/asset.service';
import { AssetComponent } from '@modules/home/pages/asset/asset.component';
import { AssetTabsComponent } from '@modules/home/pages/asset/asset-tabs.component';
import { AssetInfo } from '@shared/models/asset.models';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { EntityAction } from '@home/models/entity/entity-component.models';
import { entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { EntityId } from '@shared/models/id/entity-id';
import { Subject } from 'rxjs';
import { mergeMap } from 'rxjs/operators';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { DialogService } from '@core/services/dialog.service';
import {
  AssignToCustomerDialogComponent,
  AssignToCustomerDialogData
} from '@modules/home/dialogs/assign-to-customer-dialog.component';
import {
  DeviceCheckConnectivityDialogComponent,
  DeviceCheckConnectivityDialogData
} from '@modules/home/pages/device/device-check-connectivity-dialog.component';
import {
  DeviceCredentialsDialogComponent,
  DeviceCredentialsDialogData
} from '@modules/home/pages/device/device-credentials-dialog.component';
import { EntityDetailsPanelComponent } from '@home/components/entity/entity-details-panel.component';
import { isDefinedAndNotNull } from '@core/utils';
import { DeviceId } from '@shared/models/id/device-id';
import { AssetId } from '@shared/models/id/asset-id';
import { Device, DeviceCredentials } from '@shared/models/device.models';
import { Asset } from '@shared/models/asset.models';

@Component({
  selector: 'tb-entity-topology',
  templateUrl: './entity-topology.component.html',
  styleUrls: ['./entity-topology.component.scss']
})
export class EntityTopologyComponent extends PageComponent implements OnInit {

  @ViewChild('searchInput') searchInput: ElementRef;
  @ViewChild('scrollContainer') scrollContainer: ElementRef;
  @ViewChild('entityDetailsPanel') entityDetailsPanel: EntityDetailsPanelComponent;

  treeControl = new NestedTreeControl<HierarchyNode>(node => node.children);
  dataSource = new MatTreeNestedDataSource<HierarchyNode>();

  searchQuery = '';
  textSearchMode = false;
  previewMode = false;
  loading = false;
  private allNodes: HierarchyNode[] = [];

  // Drag variables
  isDragging = false;
  startX = 0;
  startY = 0;
  scrollLeftStart = 0;
  scrollTopStart = 0;
  zoom = 1;

  // Details Panel variables
  currentConfig: EntityTableConfig<any>;
  currentEntityId: EntityId;
  isDetailsOpen = false;

  private deviceConfig: EntityTableConfig<DeviceInfo>;
  private assetConfig: EntityTableConfig<AssetInfo>;

  hasChild = (_: number, node: HierarchyNode) => !!node.children && node.children.length > 0;

  constructor(
    protected store: Store<AppState>,
    private entityRelationService: EntityRelationService,
    private deviceService: DeviceService,
    private assetService: AssetService,
    private translate: TranslateService,
    private router: Router,
    private dialog: MatDialog,
    private dialogService: DialogService
  ) {
    super(store);
  }

  ngOnInit() {
    this.loadHierarchy();
  }

  loadHierarchy() {
    this.loading = true;
    this.entityRelationService.getEntityTopology().subscribe(backendTree => {
      const transformNode = (node: any): HierarchyNode => {
        let icon = 'domain';
        switch (node.entityType) {
          case EntityType.DEVICE:
            icon = 'devices_other';
            break;
          case EntityType.TENANT:
            icon = 'supervisor_account';
            break;
          case EntityType.ASSET:
            icon = 'domain';
            break;
        }
        return {
          id: node.id,
          entityType: node.entityType as EntityType,
          name: node.name,
          relationType: node.relationType,
          additionalInfo: node.additionalInfo,
          icon: icon,
          children: (node.children || []).map(c => transformNode(c))
        };
      };

      const rootNode = transformNode(backendTree);
      this.allNodes = [rootNode];
      this.dataSource.data = this.allNodes;

      // Ensure root connects are visibly expanded by default (shows up to level 2)
      this.treeControl.expand(rootNode);
      this.loading = false;
    });
  }

  togglePreviewMode() {
    this.previewMode = !this.previewMode;
    if (!this.previewMode) {
      this.resetZoom();
    }
  }

  zoomIn() {
    if (this.zoom < 2) {
      this.zoom = Math.round((this.zoom + 0.2) * 10) / 10;
    }
  }

  zoomOut() {
    if (this.zoom > 0.2) {
      this.zoom = Math.round((this.zoom - 0.2) * 10) / 10;
    }
  }

  resetZoom() {
    this.zoom = 1;
  }

  enterSearchMode() {
    this.textSearchMode = true;
    setTimeout(() => {
      this.searchInput.nativeElement.focus();
    }, 0);
  }

  exitSearchMode() {
    this.textSearchMode = false;
    this.searchQuery = '';
    this.onSearchChange();
  }

  onSearchChange() {
    if (!this.searchQuery) {
      this.dataSource.data = this.allNodes;
      return;
    }
    const query = this.searchQuery.toLowerCase();
    this.dataSource.data = this.filterNodes(this.allNodes, query);
    // Auto expand results
    this.expandAll();
  }

  private filterNodes(nodes: HierarchyNode[], query: string): HierarchyNode[] {
    return nodes.map(node => {
      const match = node.name.toLowerCase().includes(query);
      const filteredChildren = node.children ? this.filterNodes(node.children, query) : [];
      if (match || filteredChildren.length > 0) {
        return { ...node, children: filteredChildren };
      }
      return null;
    }).filter(n => n !== null);
  }

  expandAll() {
    const expandRecursive = (nodes: HierarchyNode[]) => {
      nodes.forEach(node => {
        this.treeControl.expand(node);
        if (node.children) {
          expandRecursive(node.children);
        }
      });
    };
    if (this.dataSource.data) {
      expandRecursive(this.dataSource.data);
    }
  }

  collapseAll() {
    this.treeControl.collapseAll();
    // Re-expand root
    if (this.dataSource.data.length > 0) {
      this.treeControl.expand(this.dataSource.data[0]);
    }
  }

  getEdgeLabelText(node: HierarchyNode): string {
    if (node.additionalInfo !== undefined && node.additionalInfo !== null) {
      if (typeof node.additionalInfo === 'object') {
        if (Object.keys(node.additionalInfo).length > 0) {
          try {
            return JSON.stringify(node.additionalInfo);
          } catch (e) {
            return String(node.additionalInfo);
          }
        }
      } else {
         return String(node.additionalInfo);
      }
    }
    return '';
  }

  startDrag(e: MouseEvent) {
    if (!this.scrollContainer || (e.target as HTMLElement).closest('.org-toggle-btn')) {
      return;
    }
    this.isDragging = true;
    this.startX = e.pageX - this.scrollContainer.nativeElement.offsetLeft;
    this.startY = e.pageY - this.scrollContainer.nativeElement.offsetTop;
    this.scrollLeftStart = this.scrollContainer.nativeElement.scrollLeft;
    this.scrollTopStart = this.scrollContainer.nativeElement.scrollTop;
  }

  stopDrag() {
    this.isDragging = false;
  }

  onDrag(e: MouseEvent) {
    if (!this.isDragging || !this.scrollContainer) return;
    e.preventDefault();
    const x = e.pageX - this.scrollContainer.nativeElement.offsetLeft;
    const y = e.pageY - this.scrollContainer.nativeElement.offsetTop;
    const walkX = x - this.startX;
    const walkY = y - this.startY;
    this.scrollContainer.nativeElement.scrollLeft = this.scrollLeftStart - walkX;
    this.scrollContainer.nativeElement.scrollTop = this.scrollTopStart - walkY;
  }

  openDetails(node: HierarchyNode) {
    if (this.isDragging) return; // ignore clicks during drags

    if (node.entityType === EntityType.DEVICE) {
      if (!this.deviceConfig) {
        this.deviceConfig = new EntityTableConfig<DeviceInfo>();
        this.deviceConfig.entityType = EntityType.DEVICE;
        this.deviceConfig.entityComponent = DeviceComponent;
        this.deviceConfig.entityTabsComponent = DeviceTabsComponent;
        this.deviceConfig.entityTranslations = entityTypeTranslations.get(EntityType.DEVICE);
        this.deviceConfig.entityResources = entityTypeResources.get(EntityType.DEVICE);
        this.deviceConfig.loadEntity = id => this.deviceService.getDeviceInfo(id.id);
        this.deviceConfig.saveEntity = device => this.deviceService.saveDevice(device).pipe(
          mergeMap((savedDevice) => this.deviceService.getDeviceInfo(savedDevice.id.id))
        );
        this.deviceConfig.detailsReadonly = () => false;
        this.deviceConfig.deleteEnabled = () => true;
        this.deviceConfig.deleteEntity = id => this.deviceService.deleteDevice(id.id);
        this.deviceConfig.onEntityAction = action => this.onDeviceAction(action);
        this.deviceConfig.componentsData = { deviceScope: 'tenant', deviceInfoFilter: {}, deviceCredentials$: new Subject() };
      }
      this.currentConfig = this.deviceConfig;
      this.currentEntityId = { id: node.id, entityType: EntityType.DEVICE };
      this.isDetailsOpen = true;
    } else if (node.entityType === EntityType.ASSET) {
      if (!this.assetConfig) {
        this.assetConfig = new EntityTableConfig<AssetInfo>();
        this.assetConfig.entityType = EntityType.ASSET;
        this.assetConfig.entityComponent = AssetComponent;
        this.assetConfig.entityTabsComponent = AssetTabsComponent;
        this.assetConfig.entityTranslations = entityTypeTranslations.get(EntityType.ASSET);
        this.assetConfig.entityResources = entityTypeResources.get(EntityType.ASSET);
        this.assetConfig.loadEntity = id => this.assetService.getAssetInfo(id.id);
        this.assetConfig.saveEntity = asset => this.assetService.saveAsset(asset).pipe(
          mergeMap((savedAsset) => this.assetService.getAssetInfo(savedAsset.id.id))
        );
        this.assetConfig.detailsReadonly = () => false;
        this.assetConfig.deleteEnabled = () => true;
        this.assetConfig.deleteEntity = id => this.assetService.deleteAsset(id.id);
        this.assetConfig.onEntityAction = action => this.onAssetAction(action);
        this.assetConfig.componentsData = { assetScope: 'tenant', assetInfoFilter: {} };
      }
      this.currentConfig = this.assetConfig;
      this.currentEntityId = { id: node.id, entityType: EntityType.ASSET };
      this.isDetailsOpen = true;
    } else {
      // Unhandled entities (Tenant) ignored for now
      this.isDetailsOpen = false;
    }
  }

  onEntityUpdated(entity: any) {
    this.loadHierarchy();
  }

  // Device Actions
  private onDeviceAction(action: EntityAction<DeviceInfo>): boolean {
    switch (action.action) {
      case 'open':
        this.router.navigate(['/devices', action.entity.id.id]);
        return true;
      case 'makePublic':
        this.makeDevicePublic(action.event, action.entity);
        return true;
      case 'assignToCustomer':
        this.assignDeviceToCustomer(action.event, [action.entity.id]);
        return true;
      case 'unassignFromCustomer':
        this.unassignDeviceFromCustomer(action.event, action.entity);
        return true;
      case 'delete':
        this.deleteDevice(action.event, action.entity);
        return true;
      case 'manageCredentials':
        this.manageDeviceCredentials(action.event, action.entity);
        return true;
      case 'checkConnectivity':
        this.checkConnectivity(action.event, action.entity.id);
        return true;
    }
    return false;
  }

  private makeDevicePublic($event: Event, device: Device) {
    if ($event) $event.stopPropagation();
    this.dialogService.confirm(
      this.translate.instant('device.make-public-device-title', { deviceName: device.name }),
      this.translate.instant('device.make-public-device-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe((res) => {
      if (res) {
        this.deviceService.makeDevicePublic(device.id.id).subscribe(() => {
          this.loadHierarchy();
          if (this.isDetailsOpen && this.entityDetailsPanel) {
            this.entityDetailsPanel.reloadEntity();
          }
        });
      }
    });
  }

  private assignDeviceToCustomer($event: Event, deviceIds: Array<DeviceId>) {
    if ($event) $event.stopPropagation();
    this.dialog.open<AssignToCustomerDialogComponent, AssignToCustomerDialogData, boolean>(AssignToCustomerDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {
        entityIds: deviceIds,
        entityType: EntityType.DEVICE
      }
    }).afterClosed().subscribe((res) => {
      if (res) {
        this.loadHierarchy();
        if (this.isDetailsOpen && this.entityDetailsPanel) {
          this.entityDetailsPanel.reloadEntity();
        }
      }
    });
  }

  private unassignDeviceFromCustomer($event: Event, device: DeviceInfo) {
    if ($event) $event.stopPropagation();
    const isPublic = device.customerIsPublic;
    const title = isPublic ? this.translate.instant('device.make-public-device-title', { deviceName: device.name }) :
                             this.translate.instant('device.unassign-device-title', { deviceName: device.name });
    const content = isPublic ? this.translate.instant('device.make-private-device-text') :
                               this.translate.instant('device.unassign-device-text');
    this.dialogService.confirm(title, content, this.translate.instant('action.no'), this.translate.instant('action.yes'), true).subscribe((res) => {
      if (res) {
        this.deviceService.unassignDeviceFromCustomer(device.id.id).subscribe(() => {
          this.loadHierarchy();
          if (this.isDetailsOpen && this.entityDetailsPanel) {
            this.entityDetailsPanel.reloadEntity();
          }
        });
      }
    });
  }

  private checkConnectivity($event: Event, deviceId: EntityId) {
    if ($event) $event.stopPropagation();
    this.dialog.open<DeviceCheckConnectivityDialogComponent, DeviceCheckConnectivityDialogData>(
      DeviceCheckConnectivityDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          deviceId,
          afterAdd: false
        }
      }
    ).afterClosed().subscribe(() => {
      if (this.isDetailsOpen && this.entityDetailsPanel) {
        this.entityDetailsPanel.reloadEntity();
      }
    });
  }

  private manageDeviceCredentials($event: Event, device: DeviceInfo) {
    if ($event) $event.stopPropagation();
    this.dialog.open<DeviceCredentialsDialogComponent, DeviceCredentialsDialogData, DeviceCredentials>(
      DeviceCredentialsDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          deviceId: device.id.id,
          deviceProfileId: device.deviceProfileId.id,
          isReadOnly: false
        }
      }
    ).afterClosed().subscribe(deviceCredentials => {
      if (isDefinedAndNotNull(deviceCredentials)) {
        this.deviceConfig.componentsData.deviceCredentials$.next(deviceCredentials);
      }
    });
  }

  private deleteDevice($event: Event, device: DeviceInfo) {
    if ($event) $event.stopPropagation();
    this.dialogService.confirm(
      this.translate.instant('device.delete-device-title', { deviceName: device.name }),
      this.translate.instant('device.delete-device-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe((res) => {
      if (res) {
        this.deviceService.deleteDevice(device.id.id).subscribe(() => {
          this.isDetailsOpen = false;
          this.loadHierarchy();
        });
      }
    });
  }

  // Asset Actions
  private onAssetAction(action: EntityAction<AssetInfo>): boolean {
    switch (action.action) {
      case 'open':
        this.router.navigate(['/assets', action.entity.id.id]);
        return true;
      case 'makePublic':
        this.makeAssetPublic(action.event, action.entity);
        return true;
      case 'assignToCustomer':
        this.assignAssetToCustomer(action.event, [action.entity.id]);
        return true;
      case 'unassignFromCustomer':
        this.unassignAssetFromCustomer(action.event, action.entity);
        return true;
      case 'delete':
        this.deleteAsset(action.event, action.entity);
        return true;
    }
    return false;
  }

  private makeAssetPublic($event: Event, asset: Asset) {
    if ($event) $event.stopPropagation();
    this.dialogService.confirm(
      this.translate.instant('asset.make-public-asset-title', { assetName: asset.name }),
      this.translate.instant('asset.make-public-asset-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe((res) => {
      if (res) {
        this.assetService.makeAssetPublic(asset.id.id).subscribe(() => {
          this.loadHierarchy();
          if (this.isDetailsOpen && this.entityDetailsPanel) {
            this.entityDetailsPanel.reloadEntity();
          }
        });
      }
    });
  }

  private assignAssetToCustomer($event: Event, assetIds: Array<AssetId>) {
    if ($event) $event.stopPropagation();
    this.dialog.open<AssignToCustomerDialogComponent, AssignToCustomerDialogData, boolean>(AssignToCustomerDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {
        entityIds: assetIds,
        entityType: EntityType.ASSET
      }
    }).afterClosed().subscribe((res) => {
      if (res) {
        this.loadHierarchy();
        if (this.isDetailsOpen && this.entityDetailsPanel) {
          this.entityDetailsPanel.reloadEntity();
        }
      }
    });
  }

  private unassignAssetFromCustomer($event: Event, asset: AssetInfo) {
    if ($event) $event.stopPropagation();
    const isPublic = asset.customerIsPublic;
    const title = isPublic ? this.translate.instant('asset.make-private-asset-title', { assetName: asset.name }) :
                             this.translate.instant('asset.unassign-asset-title', { assetName: asset.name });
    const content = isPublic ? this.translate.instant('asset.make-private-asset-text') :
                               this.translate.instant('asset.unassign-asset-text');
    this.dialogService.confirm(title, content, this.translate.instant('action.no'), this.translate.instant('action.yes'), true).subscribe((res) => {
      if (res) {
        this.assetService.unassignAssetFromCustomer(asset.id.id).subscribe(() => {
          this.loadHierarchy();
          if (this.isDetailsOpen && this.entityDetailsPanel) {
            this.entityDetailsPanel.reloadEntity();
          }
        });
      }
    });
  }

  private deleteAsset($event: Event, asset: AssetInfo) {
    if ($event) $event.stopPropagation();
    this.dialogService.confirm(
      this.translate.instant('asset.delete-asset-title', { assetName: asset.name }),
      this.translate.instant('asset.delete-asset-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes'),
      true
    ).subscribe((res) => {
      if (res) {
        this.assetService.deleteAsset(asset.id.id).subscribe(() => {
          this.isDetailsOpen = false;
          this.loadHierarchy();
        });
      }
    });
  }
}
