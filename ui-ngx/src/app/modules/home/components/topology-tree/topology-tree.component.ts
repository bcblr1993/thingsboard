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

import { Component, EventEmitter, Input, OnChanges, AfterViewInit, Output, SimpleChanges, ChangeDetectorRef } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { MatTreeNestedDataSource } from '@angular/material/tree';
import { NestedTreeControl } from '@angular/cdk/tree';
import { AssetNodeConfig, AssetNodeType } from '@shared/models/topology.models';


@Component({
    selector: 'tb-topology-tree',
    templateUrl: './topology-tree.component.html',
    styleUrls: ['./topology-tree.component.scss']
})
export class TopologyTreeComponent implements OnChanges, AfterViewInit {

    @Input() topology: AssetNodeConfig;
    @Input() isReadOnly = false;
    @Input() defaultExpandAll = false;
    @Output() topologyChange = new EventEmitter<AssetNodeConfig>();
    @Output() nodeSelected = new EventEmitter<AssetNodeConfig>();

    treeControl = new NestedTreeControl<AssetNodeConfig>(node => node.subNodes || []);
    dataSource = new MatTreeNestedDataSource<AssetNodeConfig>();

    selectedNode: AssetNodeConfig | null = null;
    isTreeVisible = true;
    private viewInitialized = false;


    constructor(private translate: TranslateService,
        private dialogService: DialogService,
        private cd: ChangeDetectorRef) {
    }

    assetNodeTypes = Object.values(AssetNodeType);

    ngAfterViewInit(): void {
        this.viewInitialized = true;
        // 当组件在 mat-tab 切换后首次渲染时，数据可能已通过绑定传入但树未展开
        if (this.defaultExpandAll && this.topology) {
            setTimeout(() => this.expandAll(), 200);
        }
    }



    ngOnChanges(changes: SimpleChanges): void {
        if (changes.topology && this.topology) {
            // 在数据源更新前，记录当前展开的节点ID和选中的节点ID
            const expandedIds = new Set<string>();
            if (this.treeControl?.expansionModel) {
                this.treeControl.expansionModel.selected.forEach(node => {
                    if (node && node.id) {
                        expandedIds.add(node.id);
                    }
                });
            }
            const previouslySelectedId = this.selectedNode?.id;

            this.ensureIds(this.topology);
            this.dataSource.data = [this.topology];

            setTimeout(() => {
                const isFirstChange = changes.topology.isFirstChange();

                // 恢复展开状态
                if (this.defaultExpandAll) {
                    // defaultExpandAll 模式：切换实体或首次加载时始终全部展开
                    this.expandAll();
                } else if (expandedIds.size > 0) {
                    this.restoreExpandedNodes(this.topology, expandedIds);
                } else if (this.treeControl) {
                    this.treeControl.expand(this.topology);
                }

                // 恢复选中状态
                if (previouslySelectedId) {
                    const newSelectedNode = this.findNodeById(this.topology, previouslySelectedId);
                    if (newSelectedNode) {
                        this.selectedNode = newSelectedNode;
                        this.nodeSelected.emit(this.selectedNode);
                    }
                }
            }, 0);
        }
    }

    private restoreExpandedNodes(node: AssetNodeConfig, expandedIds: Set<string>) {
        if (expandedIds.has(node.id)) {
            this.treeControl.expand(node);
        }
        if (node.subNodes) {
            node.subNodes.forEach(child => this.restoreExpandedNodes(child, expandedIds));
        }
    }

    private findNodeById(node: AssetNodeConfig, id: string): AssetNodeConfig | null {
        if (node.id === id) return node;
        if (node.subNodes) {
            for (const child of node.subNodes) {
                const found = this.findNodeById(child, id);
                if (found) return found;
            }
        }
        return null; // Not found
    }

    private ensureIds(node: AssetNodeConfig) {
        if (!node.id) {
            node.id = this.generateGuid();
        }
        if (node.subNodes) {
            node.subNodes.forEach(child => this.ensureIds(child));
        }
    }

    // 判断是否为根节点（第一层）
    isRootNode(node: AssetNodeConfig | null): boolean {
        if (!node) return false;
        const root = this.dataSource.data?.length > 0 ? this.dataSource.data[0] : null;
        return !!root && (node === root || (!!node.id && node.id === root.id));
    }

    // 判断是否为第二层节点（根的直接子节点）
    isSecondLevelNode(node: AssetNodeConfig | null): boolean {
        if (!node) return false;
        const root = this.dataSource.data?.[0];
        if (!root || !root.subNodes) return false;
        return root.subNodes.some(n => n === node || (!!node.id && !!n.id && n.id === node.id));
    }

    hasChild = (_: number, node: AssetNodeConfig) => !!node.subNodes && node.subNodes.length > 0;

    selectNode(node: AssetNodeConfig) {
        this.selectedNode = node;
        this.nodeSelected.emit(node);
    }

    addNode(parentNode: AssetNodeConfig) {
        if (!parentNode.subNodes) {
            parentNode.subNodes = [];
        }

        const newNode: AssetNodeConfig = {
            id: this.generateGuid(),
            type: AssetNodeType.ASSET,
            entityTypeLabel: '',
            defaultCount: 1,
            subNodes: [],
            isRemovable: true,
            namePattern: parentNode.namePattern || '${StationSn}-${Type}${HierarchicalIndex}'
        };

        // Ensure we don't accidentally copy internal state if any (though Config should be pure data)

        // Immutable push
        parentNode.subNodes = [...parentNode.subNodes, newNode];

        // 1. Update data source to render the new node
        this.refreshDataSource();

        // 2. Expand parent *after* data source update ensures tree knows about children
        if (!this.treeControl.isExpanded(parentNode)) {
            this.treeControl.expand(parentNode);
        }

        // 3. Select the new node
        this.selectNode(newNode);

        // 4. Emit change
        this.topologyChange.emit(this.topology);
    }

    deleteNode(node: AssetNodeConfig) {
        if (!this.topology || node === this.topology || (!!node.id && node.id === this.topology.id)) {
            return; // Cannot delete root
        }

        this.dialogService.confirm(
            this.translate.instant('model.node.delete-confirmation-title'),
            this.translate.instant('model.node.delete-confirmation', { name: node.entityTypeLabel }),
            undefined,
            this.translate.instant('action.delete'),
            true
        ).subscribe((result) => {
            if (result) {
                const success = this.removeNodeFromTree(this.topology, node);
                if (success) {
                    this.refreshDataSource();

                    // If deleted node was selected, deselect
                    if (this.selectedNode && (this.selectedNode === node || this.selectedNode.id === node.id)) {
                        this.selectedNode = null;
                        this.nodeSelected.emit(null);
                    }
                    this.topologyChange.emit(this.topology);
                } else {
                    console.warn('Failed to find node to delete', node);
                }
            }
        });
    }

    public getSiblingNames(node: AssetNodeConfig | null): string[] {
        if (!node || !this.topology) return [];
        const parent = this.findParent(this.topology, node);
        if (!parent || !parent.subNodes) {
          // If no parent, it's the root node. Root node has no siblings.
          return [];
        }
        return parent.subNodes
          .filter(child => child !== node && child.id !== node.id)
          .map(child => child.entityTypeLabel)
          .filter(name => !!name);
    }

    private findParent(current: AssetNodeConfig, target: AssetNodeConfig): AssetNodeConfig | null {
        if (!current.subNodes) return null;
        if (current.subNodes.some(child => child === target || (!!child.id && !!target.id && child.id === target.id))) {
            return current;
        }
        for (const child of current.subNodes) {
            const parent = this.findParent(child, target);
            if (parent) return parent;
        }
        return null;
    }

    public expandAll() {
        // 安全检查：如果 treeControl.dataNodes 已就绪则直接使用原生方法
        if (this.treeControl.dataNodes && this.treeControl.dataNodes.length > 0) {
            this.treeControl.expandAll();
        } else if (this.topology) {
            // 回退方案：递归展开 dataSource 中的所有节点
            this.expandNodeRecursively(this.topology);
        }
    }

    private expandNodeRecursively(node: AssetNodeConfig) {
        this.treeControl.expand(node);
        if (node.subNodes) {
            node.subNodes.forEach(child => this.expandNodeRecursively(child));
        }
    }

    /**
     * 在树中揭示并选中指定节点。
     * 会自动展开该节点的所有父节点。
     */
    public revealNode(targetNode: AssetNodeConfig) {
        if (!targetNode || !this.topology) return;

        // 查找从根节点到目标节点的路径
        const path: AssetNodeConfig[] = [];
        if (this.findPath(this.topology, targetNode, path)) {
            // 展开路径上的所有节点（除了最后一个目标节点）
            for (let i = 0; i < path.length - 1; i++) {
                this.treeControl.expand(path[i]);
            }
            // 选中目标节点并发出事件
            this.selectNode(targetNode);

            // 触发变更检查以确保 UI 更新（高亮选中）
            this.cd.markForCheck();
        }
    }

    private findPath(current: AssetNodeConfig, target: AssetNodeConfig, path: AssetNodeConfig[]): boolean {
        path.push(current);
        if (current === target || (!!current.id && !!target.id && current.id === target.id)) {
            return true;
        }
        if (current.subNodes) {
            for (const child of current.subNodes) {
                if (this.findPath(child, target, path)) {
                    return true;
                }
            }
        }
        path.pop();
        return false;
    }

    public collapseAll() {
        this.treeControl.collapseAll();
    }

    public refresh() {
        // Prevent complete destruction of the mat-tree structure, which could cause a loss of expanded context
        // Instead, just mark it for check and refresh the data source array reference.
        this.refreshDataSource();
        this.cd.detectChanges();
    }

    public refreshDataSource() {
        // Re-assign data array to trigger CD
        // Do not set to null as it causes MatTree to crash with "nodes is not iterable"
        // Use spread operator to create new array reference
        const currentData = this.dataSource.data;
        this.dataSource.data = [];
        if (currentData) {
            this.dataSource.data = [...currentData];
        } else {
            this.dataSource.data = [this.topology];
        }
    }

    private removeNodeFromTree(parent: AssetNodeConfig, target: AssetNodeConfig): boolean {
        // console.log('Searching in parent:', parent.entityTypeLabel, 'for target:', target.entityTypeLabel);
        if (parent.subNodes && Array.isArray(parent.subNodes)) {
            // Check by ID or reference
            const index = parent.subNodes.findIndex(child =>
                (child.id && target.id && child.id === target.id) || child === target
            );

            if (index > -1) {
                parent.subNodes.splice(index, 1);
                // Maintain structural integrity
                // Maintain as empty array instead of undefined to prevent tree errors if expanded
                if (parent.subNodes.length === 0) {
                    parent.subNodes = [];
                } else {
                    parent.subNodes = [...parent.subNodes]; // Update reference
                }
                return true;
            }
            // Recursive check
            for (const child of parent.subNodes) {
                if (this.removeNodeFromTree(child, target)) {
                    // Bubble up the change: update parent's subNodes reference
                    parent.subNodes = [...parent.subNodes];
                    return true;
                }
            }
        }
        return false;
    }
    private generateGuid() {
        function s4() {
            return Math.floor((1 + Math.random()) * 0x10000)
                .toString(16)
                .substring(1);
        }
        return s4() + s4() + '-' + s4() + '-' + s4() + '-' +
            s4() + '-' + s4() + s4() + s4();
    }
}

