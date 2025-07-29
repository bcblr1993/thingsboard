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
    return nodes.map(node => {
      const filteredChildren = node.children ? this.filterNodes(node.children) : null;
      if (node.name.toLowerCase().includes(this.filter) || (filteredChildren && filteredChildren.length > 0)) {
        return { ...node, children: filteredChildren };
      }
      return null;
    }).filter(node => node !== null);
  }

  highlight(text: string): string {
    if (!this.filter) {
      return text;
    }
    const re = new RegExp(this.filter, 'gi');
    return text.replace(re, `<span class="highlight">$&</span>`);
  }
}