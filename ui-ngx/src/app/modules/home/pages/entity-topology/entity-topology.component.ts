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

import { ChangeDetectorRef, Component, OnDestroy, OnInit, ViewChild, ElementRef } from '@angular/core';
import { PageComponent } from '@shared/components/page.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityRelationService } from '@core/http/entity-relation.service';
import { EntityType } from '@shared/models/entity-type.models';
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

interface VisibleHierarchyNode {
  node: HierarchyNode;
  level: number;
  path: string;
  expandable: boolean;
  displayName: string;
  tooltipText: string;
}

@Component({
  selector: 'tb-entity-topology',
  templateUrl: './entity-topology.component.html',
  styleUrls: ['./entity-topology.component.scss']
})
export class EntityTopologyComponent extends PageComponent implements OnInit, OnDestroy {

  @ViewChild('searchInput') searchInput: ElementRef;
  @ViewChild('scrollContainer') scrollContainer: ElementRef;
  @ViewChild('orgTreeWrapper') orgTreeWrapper: ElementRef<HTMLDivElement>;
  @ViewChild('entityDetailsPanel') entityDetailsPanel: EntityDetailsPanelComponent;

  dataSource = new MatTreeNestedDataSource<HierarchyNode>();

  searchQuery = '';
  textSearchMode = false;
  previewMode = false;
  loading = false;
  visibleNodes: VisibleHierarchyNode[] = [];
  previewNodes: HierarchyNode[] = [];
  previewRenderLimit = 1000;
  previewRenderedNodeCount = 0;
  previewCompactMode = false;
  previewLabelsVisible = true;
  previewRelationLabelsVisible = true;
  readonly previewLabelsHidden = false;
  readonly initialPreviewNodeLimit = 1000;
  readonly previewNodeStep = 100;
  readonly treeIndentPx = 32;
  private allNodes: HierarchyNode[] = [];
  private dataGeneration = 0;
  private expandedNodeIds = new Set<string>();
  private searchDebounceHandle: ReturnType<typeof setTimeout> | null = null;

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

  get hasMorePreviewNodes(): boolean {
    return this.previewRenderedNodeCount < this.visibleNodes.length;
  }

  trackByVisibleNode = (_: number, item: VisibleHierarchyNode) => `${item.path}_${this.dataGeneration}`;
  trackByOrgNode = (index: number, node: HierarchyNode) => `${this.getNodeKey(node)}_${index}`;

  constructor(
    protected store: Store<AppState>,
    private entityRelationService: EntityRelationService,
    private deviceService: DeviceService,
    private assetService: AssetService,
    private translate: TranslateService,
    private router: Router,
    private dialog: MatDialog,
    private dialogService: DialogService,
    private cd: ChangeDetectorRef
  ) {
    super(store);
  }

  ngOnInit() {
    this.loadHierarchy();
  }

  ngOnDestroy() {
    if (this.searchDebounceHandle) {
      clearTimeout(this.searchDebounceHandle);
    }
  }

  loadHierarchy() {
    this.loading = true;
    this.entityRelationService.getEntityTopology().subscribe({
      next: backendTree => {
        const transformNode = (node: any, path: string): HierarchyNode => {
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
          const result: any = {
            id: node.id,
            entityType: node.entityType as EntityType,
            name: node.name,
            relationType: node.relationType,
            additionalInfo: node.additionalInfo,
            icon: icon,
            uiPath: path,
            children: (node.children || []).map((c, index) => transformNode(c, `${path}/${c.id}_${index}`))
          };
          result.edgeLabel = this.getEdgeLabelText(result);
          return result as HierarchyNode;
        };

        const rootNode = transformNode(backendTree, `${backendTree.id}_0`);
        this.sortHierarchyChildren(rootNode);
        this.allNodes = [rootNode];
        this.dataSource.data = this.allNodes;
        this.expandedNodeIds.clear();
        this.expandRootNodes();
        this.loading = false;
      },
      error: () => {
        this.allNodes = [];
        this.dataSource.data = [];
        this.visibleNodes = [];
        this.expandedNodeIds.clear();
        this.loading = false;
      }
    });
  }

  togglePreviewMode() {
    this.previewMode = !this.previewMode;
    if (this.previewMode) {
      this.resetPreviewLimit();
      this.refreshPreviewNodes();
      this.scheduleFitPreviewToView();
    } else {
      this.resetZoom();
    }
  }

  zoomIn() {
    this.setPreviewZoom(this.zoom + 0.2);
  }

  zoomOut() {
    this.setPreviewZoom(this.zoom - 0.2);
  }

  resetZoom() {
    this.zoom = 1;
  }

  centerPreview() {
    const container = this.scrollContainer?.nativeElement;
    if (!container) {
      return;
    }
    container.scrollLeft = Math.max(0, (container.scrollWidth - container.clientWidth) / 2);
    container.scrollTop = Math.max(0, (container.scrollHeight - container.clientHeight) / 2);
  }

  fitPreviewToView() {
    const container = this.scrollContainer?.nativeElement;
    const wrapper = this.orgTreeWrapper?.nativeElement;
    if (!container || !wrapper) {
      return;
    }
    const bounds = wrapper.getBoundingClientRect();
    const unscaledWidth = bounds.width / this.zoom;
    const unscaledHeight = bounds.height / this.zoom;
    if (!unscaledWidth || !unscaledHeight) {
      return;
    }
    const availableWidth = Math.max(120, container.clientWidth - 96);
    const availableHeight = Math.max(120, container.clientHeight - 140);
    this.setPreviewZoom(Math.min(1.4, availableWidth / unscaledWidth, availableHeight / unscaledHeight));
    setTimeout(() => this.centerPreview(), 0);
  }

  toggleCompactPreview() {
    this.previewCompactMode = !this.previewCompactMode;
    this.scheduleFitPreviewToView();
  }

  togglePreviewLabels() {
    this.previewLabelsVisible = !this.previewLabelsVisible;
    this.scheduleFitPreviewToView();
  }

  togglePreviewRelationLabels() {
    this.previewRelationLabelsVisible = !this.previewRelationLabelsVisible;
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

  scheduleSearchChange() {
    if (this.searchDebounceHandle) {
      clearTimeout(this.searchDebounceHandle);
    }
    this.searchDebounceHandle = setTimeout(() => this.onSearchChange(), 150);
  }

  onSearchChange() {
    // Increment generation to invalidate the virtual-scroll trackBy cache after filtering.
    this.dataGeneration++;
    this.resetPreviewLimit();
    if (!this.searchQuery) {
      this.dataSource.data = this.allNodes;
      this.expandedNodeIds.clear();
      this.expandRootNodes();
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
        this.expandedNodeIds.add(this.getNodeKey(node));
        if (node.children) {
          expandRecursive(node.children);
        }
      });
    };
    if (this.dataSource.data) {
      this.expandedNodeIds.clear();
      expandRecursive(this.dataSource.data);
      this.refreshVisibleNodes();
    }
  }

  collapseAll() {
    this.expandedNodeIds.clear();
    this.expandRootNodes();
  }

  isNodeExpanded(node: HierarchyNode): boolean {
    return this.expandedNodeIds.has(this.getNodeKey(node));
  }

  toggleNode(node: HierarchyNode, event?: Event) {
    if (event) {
      event.stopPropagation();
    }
    const hasChildren = !!(node.children?.length || (node as any).hasChildren);
    if (!hasChildren) {
      return;
    }
    const nodeKey = this.getNodeKey(node);
    if (this.isNodeExpanded(node)) {
      this.expandedNodeIds.delete(nodeKey);
    } else {
      this.expandedNodeIds.add(nodeKey);
    }
    this.refreshVisibleNodes();
  }

  showMorePreviewNodes() {
    this.previewRenderLimit += this.previewNodeStep;
    this.refreshPreviewNodes();
    this.cd.detectChanges();
  }

  private expandRootNodes() {
    (this.dataSource.data || []).forEach(node => this.expandedNodeIds.add(this.getNodeKey(node)));
    this.refreshVisibleNodes();
  }

  private refreshVisibleNodes() {
    const visibleNodes: VisibleHierarchyNode[] = [];
    const addVisibleNodes = (nodes: HierarchyNode[], level: number, parentPath: string) => {
      nodes.forEach((node, index) => {
        const path = this.getNodeKey(node) || (parentPath ? `${parentPath}/${node.id}_${index}` : `${node.id}_${index}`);
        const expandable = !!node.children && node.children.length > 0;
        visibleNodes.push({
          node,
          level,
          path,
          expandable,
          displayName: node.name,
          tooltipText: ''
        });
        if (expandable && this.isNodeExpanded(node)) {
          addVisibleNodes(node.children, level + 1, path);
        }
      });
    };
    addVisibleNodes(this.dataSource.data || [], 0, '');
    this.visibleNodes = visibleNodes;
    this.refreshPreviewNodes();
  }

  private refreshPreviewNodes() {
    let remaining = this.previewRenderLimit;
    let rendered = 0;
    const buildPreviewNodes = (nodes: HierarchyNode[]): HierarchyNode[] => {
      const result: HierarchyNode[] = [];
      for (const node of nodes) {
        if (remaining <= 0) {
          break;
        }
        remaining--;
        rendered++;
        const childCount = node.children?.length || 0;
        const children = childCount > 0 && this.isNodeExpanded(node) ? buildPreviewNodes(node.children) : [];
        result.push({
          ...(node as any),
          children,
          hasChildren: childCount > 0,
          childCount
        });
      }
      return result;
    };
    this.previewNodes = buildPreviewNodes(this.dataSource.data || []);
    this.previewRenderedNodeCount = rendered;
  }

  private resetPreviewLimit() {
    this.previewRenderLimit = this.initialPreviewNodeLimit;
  }

  private setPreviewZoom(value: number) {
    this.zoom = Math.round(Math.min(2, Math.max(0.2, value)) * 10) / 10;
  }

  private scheduleFitPreviewToView() {
    setTimeout(() => this.fitPreviewToView(), 0);
  }

  private getNodeKey(node: HierarchyNode): string {
    return (node as any).uiPath || node.id;
  }

  private sortHierarchyChildren(node: HierarchyNode) {
    if (!node.children || node.children.length === 0) {
      return;
    }
    node.children.sort((a, b) => {
      const aChildCount = a.children?.length || 0;
      const bChildCount = b.children?.length || 0;
      if (!!bChildCount !== !!aChildCount) {
        return bChildCount ? 1 : -1;
      }
      if (bChildCount !== aChildCount) {
        return bChildCount - aChildCount;
      }
      return (a.name || '').localeCompare(b.name || '');
    });
    node.children.forEach(child => this.sortHierarchyChildren(child));
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
    if (!this.scrollContainer || (e.target as HTMLElement).closest('.org-node-toggle, .preview-load-more')) {
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
