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

import { Component, OnInit } from '@angular/core';
import { PageComponent } from '@shared/components/page.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { FormBuilder, FormGroup } from '@angular/forms';
import { defaultUserMenuMap, menuSectionMap, MenuSection, MenuId } from '@core/services/menu.models';
import { FlatTreeControl } from '@angular/cdk/tree';
import { MatTreeFlatDataSource, MatTreeFlattener } from '@angular/material/tree';
import { MatSelectChange } from '@angular/material/select';
import { SelectionModel } from '@angular/cdk/collections';
import { Authority } from '@shared/models/authority.enum';
import { MenuSetting, MenuSettingService } from '@core/http/menu-setting.service';
import { deepClone } from '@core/utils';
import { BehaviorSubject } from 'rxjs';

/** Flat node with expandable and level information */
interface MenuFlatNode {
  expandable: boolean;
  name: string;
  level: number;
  id: string;
}

@Component({
  selector: 'tb-menu-settings',
  templateUrl: './menu-settings.component.html',
  styleUrls: ['./menu-settings.component.scss']
})
export class MenuSettingsComponent extends PageComponent implements OnInit {

  menuSettingsForm: FormGroup;
  authorities = [Authority.SYS_ADMIN, Authority.TENANT_ADMIN, Authority.CUSTOMER_USER];
  authority = Authority.SYS_ADMIN;
  isLoading$ = new BehaviorSubject<boolean>(false);

  private fullMenuSections: MenuSection[] = [];

  private _transformer = (node: MenuSection, level: number) => {
    return {
      expandable: !!node.pages && node.pages.length > 0,
      name: node.name,
      level: level,
      id: node.id
    };
  }

  treeControl = new FlatTreeControl<MenuFlatNode>(
    node => node.level, node => node.expandable);

  treeFlattener = new MatTreeFlattener(
    this._transformer, node => node.level, node => node.expandable, node => node.pages);

  dataSource = new MatTreeFlatDataSource(this.treeControl, this.treeFlattener);

  /** The selection for checklist */
  checklistSelection = new SelectionModel<string>(true /* multiple */);

  constructor(protected store: Store<AppState>,
    private menuSettingService: MenuSettingService,
    private fb: FormBuilder) {
    super(store);
    this.menuSettingsForm = this.fb.group({});
  }

  ngOnInit() {
    this.buildDisplayTree();
    this.loadSettings();
  }

  private buildDisplayTree() {
    const menuReferences = defaultUserMenuMap.get(this.authority);

    const buildFullNode = (reference) => {
      const section = deepClone(menuSectionMap.get(reference.id));
      if (reference.pages) {
        section.pages = reference.pages.map(r => buildFullNode(r));
      }
      return section;
    };
    this.fullMenuSections = menuReferences.map(r => buildFullNode(r));

    const buildDisplayNode = (reference) => {
      const section = deepClone(menuSectionMap.get(reference.id));
      if (section.type === 'toggle' && reference.pages) {
        section.pages = reference.pages.map(r => buildDisplayNode(r));
      } else {
        section.pages = [];
      }
      return section;
    };
    this.dataSource.data = menuReferences.map(r => buildDisplayNode(r));
  }

  authorityChanged(event: MatSelectChange) {
    this.authority = event.value;
    this.isAllExpanded = false;
    this.buildDisplayTree();
    this.loadSettings();
    this.menuSettingsForm.markAsDirty();
  }

  loadSettings() {
    this.isLoading$.next(true);
    this.menuSettingService.getMenuSetting(this.authority).subscribe(settings => {
      this.checklistSelection.clear();
      if (settings && settings.menuConfig) {
        this.selectNodes(settings.menuConfig);
      } else {
        this.treeControl.dataNodes.forEach(node => this.checklistSelection.select(node.id));
      }
      // 确保设置菜单和权限菜单分配页面始终被选中（仅对系统管理员）
      if (this.authority === Authority.SYS_ADMIN) {
        this.checklistSelection.select(MenuId.settings);
        this.checklistSelection.select(MenuId.permission_menu_allocation);
      }
      this.isLoading$.next(false);
    });
  }

  selectNodes(nodes: any[]) {
    nodes.forEach(node => {
      if (node.selected !== false) {
        this.checklistSelection.select(node.id);
      }
      if (node.pages) {
        this.selectNodes(node.pages);
      }
    });
  }

  reset(): void {
    this.treeControl.dataNodes.forEach(node => this.checklistSelection.select(node.id));
    this.menuSettingsForm.markAsDirty();
  }

  isAllExpanded = false;

  toggleExpandAll(): void {
    if (this.isAllExpanded) {
      this.treeControl.collapseAll();
    } else {
      this.treeControl.expandAll();
    }
    this.isAllExpanded = !this.isAllExpanded;
  }

  save() {
    this.isLoading$.next(true);
    console.log(this.fullMenuSections)
    const menuConfig = this.buildMenuConfig(this.fullMenuSections);
    const menuSetting: MenuSetting = {
      authority: this.authority,
      menuConfig
    };
    console.log(menuSetting)
    this.menuSettingService.saveMenuSetting(menuSetting).subscribe(() => {
      this.isLoading$.next(false);
      this.menuSettingsForm.markAsPristine();
    });
  }

  buildMenuConfig(nodes: MenuSection[]): any[] {
    return nodes.map(node => {
      const isSelected = this.checklistSelection.isSelected(node.id);
      const configNode: any = { id: node.id, selected: isSelected };
      if (node.pages && node.pages.length > 0) {
        configNode.pages = this.buildMenuConfig(node.pages);
      }
      return configNode;
    });
  }

  hasChild = (_: number, node: MenuFlatNode) => node.expandable;

  /**
   * 检查节点是否可以被取消勾选
   * 对于系统管理员：
   * 1. 设置菜单不能被取消勾选（包含权限菜单分配页面）
   * 2. 权限菜单分配页面不能被取消勾选
   * 否则用户将无法访问此页面来恢复其他菜单配置
   */
  isNodeDisabled(node: MenuFlatNode): boolean {
    // 只对系统管理员角色进行限制
    if (this.authority === Authority.SYS_ADMIN) {
      // 设置菜单和权限菜单分配页面都不能被取消勾选
      return node.id === MenuId.settings || node.id === MenuId.permission_menu_allocation;
    }
    return false;
  }

  descendantsAllSelected(node: MenuFlatNode): boolean {
    const descendants = this.treeControl.getDescendants(node);
    return descendants.length > 0 && descendants.every(child => {
      return this.checklistSelection.isSelected(child.id);
    });
  }

  descendantsPartiallySelected(node: MenuFlatNode): boolean {
    const descendants = this.treeControl.getDescendants(node);
    const result = descendants.some(child => this.checklistSelection.isSelected(child.id));
    return result && !this.descendantsAllSelected(node);
  }

  itemSelectionToggle(node: MenuFlatNode): void {
    // 禁止取消勾选被禁用的节点
    if (this.isNodeDisabled(node)) {
      return;
    }
    this.menuSettingsForm.markAsDirty();
    this.checklistSelection.toggle(node.id);
    const descendants = this.treeControl.getDescendants(node);
    if (this.checklistSelection.isSelected(node.id)) {
      // 选中所有子节点
      this.checklistSelection.select(...descendants.map(d => d.id));
    } else {
      // 取消选中子节点时，跳过被禁用的节点
      const descendantsToDeselect = descendants.filter(d => !this.isNodeDisabled(d));
      this.checklistSelection.deselect(...descendantsToDeselect.map(d => d.id));
    }
    this.checkAllParentsSelection(node);
  }

  leafItemSelectionToggle(node: MenuFlatNode): void {
    // 禁止取消勾选被禁用的节点
    if (this.isNodeDisabled(node)) {
      return;
    }
    this.menuSettingsForm.markAsDirty();
    this.checklistSelection.toggle(node.id);
    this.checkAllParentsSelection(node);
  }

  checkAllParentsSelection(node: MenuFlatNode): void {
    let parent: MenuFlatNode | null = this.getParentNode(node);
    while (parent) {
      this.checkRootNodeSelection(parent);
      parent = this.getParentNode(parent);
    }
  }

  checkRootNodeSelection(node: MenuFlatNode): void {
    const nodeSelected = this.checklistSelection.isSelected(node.id);
    const descendants = this.treeControl.getDescendants(node);
    const descAllSelected = descendants.length > 0 && descendants.every(child => {
      return this.checklistSelection.isSelected(child.id);
    });
    const descSomeSelected = descendants.length > 0 && descendants.some(child => {
      return this.checklistSelection.isSelected(child.id);
    });

    // 修复bug：改进父节点的选中逻辑
    // 1. 如果所有子节点都被选中，自动选中父节点
    // 2. 如果至少有一个子节点被选中，也自动选中父节点（确保父菜单可见）
    // 3. 不要自动取消选中父节点，除非所有子节点都未选中
    if (!nodeSelected && descSomeSelected) {
      // 如果父节点未选中，但至少有一个子节点被选中，自动选中父节点
      this.checklistSelection.select(node.id);
    } else if (nodeSelected && !descSomeSelected && descendants.length > 0) {
      // 如果父节点已选中，但所有子节点都未选中，且父节点有子节点，则取消选中父节点
      // 这样可以避免出现只有父节点选中但没有任何子节点选中的情况
      this.checklistSelection.deselect(node.id);
    }
  }

  getParentNode(node: MenuFlatNode): MenuFlatNode | null {
    const currentLevel = this.treeControl.getLevel(node);
    if (currentLevel < 1) {
      return null;
    }
    const startIndex = this.treeControl.dataNodes.indexOf(node) - 1;
    for (let i = startIndex; i >= 0; i--) {
      const currentNode = this.treeControl.dataNodes[i];
      if (this.treeControl.getLevel(currentNode) < currentLevel) {
        return currentNode;
      }
    }
    return null;
  }
}
