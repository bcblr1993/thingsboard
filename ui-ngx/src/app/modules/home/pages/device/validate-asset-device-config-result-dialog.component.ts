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
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { Router } from '@angular/router';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatTreeNestedDataSource } from '@angular/material/tree';
import { animate, state, style, transition, trigger } from '@angular/animations';

interface TreeNode {
  name: string;
  children?: TreeNode[];
}

export interface ValidationResultDialogData {
  nodes: TreeNode[];
  noRelation: string[];
  onlyRelation: string[];
}

@Component({
  selector: 'tb-validation-result-dialog',
  templateUrl: './validate-asset-device-config-result-dialog.component.html',
  styleUrls: ['./validate-asset-device-config-result-dialog.component.scss'],
  animations: [
    trigger('slideVertical', [
      state('collapsed', style({ height: '0px', minHeight: '0', display: 'none' })),
      state('expanded', style({ height: '*' })),
      transition('expanded <=> collapsed', animate('225ms cubic-bezier(0.4, 0.0, 0.2, 1)')),
    ]),
  ],
})
export class ValidationResultDialogComponent extends DialogComponent<ValidationResultDialogComponent> {

  treeControl = new NestedTreeControl<TreeNode>(node => node.children);
  dataSource = new MatTreeNestedDataSource<TreeNode>();
  private originalData: TreeNode[];
  private filter = '';

  constructor(
    protected store: Store<AppState>,
    protected router: Router,
    public dialogRef: MatDialogRef<ValidationResultDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: ValidationResultDialogData
  ) {
    super(store, router, dialogRef);
    this.originalData = JSON.parse(JSON.stringify(data.nodes));
    this.dataSource.data = data.nodes;
  }

  hasChild = (_: number, node: TreeNode) => !!node.children && node.children.length > 0;

  close(): void {
    this.dialogRef.close();
  }

  applyFilter(event: Event) {
    this.filter = (event.target as HTMLInputElement).value.trim().toLowerCase();
    this.dataSource.data = this.filterNodes(this.originalData);
    if (this.filter) {
      this.treeControl.expandAll();
    } else {
      this.treeControl.collapseAll();
    }
  }

  private filterNodes(nodes: TreeNode[]): TreeNode[] {
    if (!this.filter) {
      return nodes;
    }
    return nodes.reduce<TreeNode[]>((acc, node) => {
      if (node.name.toLowerCase().includes(this.filter)) {
        acc.push(node);
        return acc;
      }
      if (node.children) {
        const filteredChildren = this.filterNodes(node.children);
        if (filteredChildren.length > 0) {
          acc.push({ ...node, children: filteredChildren });
        }
      }
      return acc;
    }, []);
  }

  highlight(text: string): string {
    if (!this.filter) {
      return text;
    }
    const re = new RegExp(this.filter, 'gi');
    return text.replace(re, `<span class="highlight">$&</span>`);
  }
}
