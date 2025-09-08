import { Component, OnInit } from '@angular/core';
import { PageComponent } from '@shared/components/page.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { FormBuilder, FormGroup } from '@angular/forms';
import { defaultUserMenuMap, menuSectionMap, MenuSection } from '@core/services/menu.models';
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
  styleUrls: []
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

  save() {
    this.isLoading$.next(true);
    const menuConfig = this.buildMenuConfig(this.fullMenuSections);
    const menuSetting: MenuSetting = {
      authority: this.authority,
      menuConfig
    };
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
    this.menuSettingsForm.markAsDirty();
    this.checklistSelection.toggle(node.id);
    const descendants = this.treeControl.getDescendants(node);
    this.checklistSelection.isSelected(node.id)
      ? this.checklistSelection.select(...descendants.map(d => d.id))
      : this.checklistSelection.deselect(...descendants.map(d => d.id));
    this.checkAllParentsSelection(node);
  }

  leafItemSelectionToggle(node: MenuFlatNode): void {
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
    if (nodeSelected && !descAllSelected) {
      this.checklistSelection.deselect(node.id);
    } else if (!nodeSelected && descAllSelected) {
      this.checklistSelection.select(node.id);
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