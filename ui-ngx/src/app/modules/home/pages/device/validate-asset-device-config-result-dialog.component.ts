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

import { Component, Inject, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { DialogComponent } from '@shared/components/dialog.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { Router } from '@angular/router';
import { FlatTreeControl } from '@angular/cdk/tree';
import { MatTreeFlatDataSource, MatTreeFlattener } from '@angular/material/tree';

interface AppTreeNode {
  name: string;
  children?: AppTreeNode[];
}

export interface ValidationResultDialogData {
  nodes: AppTreeNode[];
  noRelation: string[];
  onlyRelation: string[];
}

interface FlatNode {
  expandable: boolean;
  name: string;
  level: number;
}

@Component({
  selector: 'tb-validation-result-dialog',
  templateUrl: './validate-asset-device-config-result-dialog.component.html',
  styleUrls: ['./validate-asset-device-config-result-dialog.component.scss']
})
export class ValidationResultDialogComponent extends DialogComponent<ValidationResultDialogComponent> implements OnInit {

  private originalData: AppTreeNode[];
  filter = '';

  private transformer = (node: AppTreeNode, level: number): FlatNode => ({
    expandable: !!node.children && node.children.length > 0,
    name: node.name,
    level
  });

  treeControl = new FlatTreeControl<FlatNode>(
    node => node.level,
    node => node.expandable
  );

  treeFlattener = new MatTreeFlattener(
    this.transformer,
    node => node.level,
    node => node.expandable,
    node => node.children
  );

  dataSource = new MatTreeFlatDataSource(this.treeControl, this.treeFlattener);

  hasChild = (_: number, node: FlatNode) => node.expandable;

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

  ngOnInit(): void {
  }

  applyFilter(filterText: string) {
    this.filter = filterText.trim().toLowerCase();
    const filteredData = this.filterNodes(this.originalData, this.filter);
    this.dataSource.data = filteredData;
    if (this.filter) {
      this.treeControl.expandAll();
    } else {
      this.treeControl.collapseAll();
    }
  }

  private filterNodes(nodes: AppTreeNode[], filter: string): AppTreeNode[] {
    if (!filter) {
      return nodes;
    }
    return nodes.reduce<AppTreeNode[]>((acc, node) => {
      const children = node.children ? this.filterNodes(node.children, filter) : null;
      if (node.name.toLowerCase().includes(filter) || (children && children.length > 0)) {
        acc.push({ ...node, children });
      }
      return acc;
    }, []);
  }

  clearFilter() {
    this.applyFilter('');
  }

  highlight(text: string): string {
    if (!this.filter) {
      return text;
    }
    const re = new RegExp(this.filter, 'gi');
    return text.replace(re, `<span class="highlight">$&</span>`);
  }

  close(): void {
    this.dialogRef.close();
  }
}
