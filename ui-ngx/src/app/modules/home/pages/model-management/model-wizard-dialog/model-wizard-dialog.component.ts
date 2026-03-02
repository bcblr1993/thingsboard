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

import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { FormBuilder, FormGroup, Validators, FormControl } from '@angular/forms';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { Observable, Subscription, of } from 'rxjs';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { TranslateService } from '@ngx-translate/core';
import { map, startWith } from 'rxjs/operators';
import { TopologyTemplateService } from '@core/http/topology-template.service';
import { AssetNodeConfig, AssetNodeType, TopologyTemplate } from '@shared/models/topology.models';
import { NULL_UUID } from '@shared/models/id/has-uuid';
import { MatStepper, StepperOrientation } from '@angular/material/stepper';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MediaBreakpoints } from '@shared/models/constants';
import { StepperSelectionEvent } from '@angular/cdk/stepper';
import { TopologyTreeComponent } from '@home/components/topology-tree/topology-tree.component';
import { PageLink } from '@shared/models/page/page-link';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';

// 模板来源枚举
export enum TemplateSource {
    EMPTY = 'EMPTY',
    FROM_TEMPLATE = 'FROM_TEMPLATE'
}

@Component({
    selector: 'tb-model-wizard-dialog',
    templateUrl: './model-wizard-dialog.component.html',
    styleUrls: ['./model-wizard-dialog.component.scss']
})
export class ModelWizardDialogComponent extends DialogComponent<ModelWizardDialogComponent, TopologyTemplate> implements OnInit, OnDestroy {

    @ViewChild('addModelWizardStepper', { static: true }) addModelWizardStepper: MatStepper;
    @ViewChild('topologyTree') topologyTree: TopologyTreeComponent;

    stepperOrientation: Observable<StepperOrientation>;
    stepperLabelPosition: Observable<'bottom' | 'end'>;

    selectedIndex = 0;
    showNext = true;

    // 模板来源选择
    templateSource: TemplateSource = TemplateSource.EMPTY;
    TemplateSource = TemplateSource;

    // 可用模板列表
    availableTemplates: TopologyTemplate[] = [];
    filteredTemplates$: Observable<TopologyTemplate[]>;
    templateControl = new FormControl<any>('');
    selectedTemplateId: string | null = null;
    templatesLoading = false;

    modelWizardFormGroup: FormGroup;
    customizeFormGroup: FormGroup;

    defaultTopology: AssetNodeConfig;
    selectedNode: AssetNodeConfig | null = null;

    private subscriptions: Subscription[] = [];

    constructor(protected store: Store<AppState>,
        protected router: Router,
        public dialogRef: MatDialogRef<ModelWizardDialogComponent, TopologyTemplate>,
        private topologyTemplateService: TopologyTemplateService,
        private fb: FormBuilder,
        private translate: TranslateService,
        private breakpointObserver: BreakpointObserver) {
        super(store, router, dialogRef);

        this.stepperOrientation = this.breakpointObserver.observe(MediaBreakpoints['gt-sm'])
            .pipe(map(({ matches }) => matches ? 'horizontal' : 'vertical'));

        this.stepperLabelPosition = this.breakpointObserver.observe(MediaBreakpoints['gt-sm'])
            .pipe(map(({ matches }) => matches ? 'end' : 'bottom'));

        this.modelWizardFormGroup = this.fb.group({
            name: ['', [Validators.required, Validators.maxLength(255)]],
            version: ['', [Validators.required, Validators.maxLength(255)]],
            description: ['']
        });

        this.customizeFormGroup = this.fb.group({
            configuration: [null, (control) => {
                return this.findInvalidNode(control.value) === null ? null : { topologyInvalid: true };
            }]
        });
    }

    ngOnInit(): void {
        // 初始化空白拓扑结构
        this.applyEmptyTopology();
        // 加载可用模板列表
        this.loadAvailableTemplates();
    }

    ngOnDestroy(): void {
        this.subscriptions.forEach(s => s.unsubscribe());
    }

    /**
     * 应用空白拓扑配置（内置默认结构）
     */
    private applyEmptyTopology(): void {
        this.defaultTopology = {
            type: AssetNodeType.ASSET,
            entityTypeLabel: 'Company',
            namePattern: '${StationSn}',
            defaultCount: 1,
            subNodes: [
                {
                    type: AssetNodeType.ASSET,
                    entityTypeLabel: 'Station',
                    namePattern: '${StationSn}-${Type}${HierarchicalIndex}',
                    defaultCount: 1,
                    subNodes: [],
                    isRemovable: false
                } as unknown as AssetNodeConfig
            ],
            isRemovable: false
        } as unknown as AssetNodeConfig;
        this.customizeFormGroup.get('configuration').setValue(this.defaultTopology);
    }

    /**
     * 加载可用模板列表（根据用户权限过滤）
     * SysAdmin: 仅系统模板（includeSystem=true, 后端自动隔离）
     * TenantAdmin: 公共模板 + 租户模板（includeSystem=true）
     */
    loadAvailableTemplates(): void {
        this.templatesLoading = true;
        const authUser = getCurrentAuthUser(this.store);
        // TenantAdmin 传 includeSystem=true 以看到公共模板+租户模板
        // SysAdmin 传 includeSystem=true（后端自动仅返回系统模板）
        const includeSystem = authUser.authority === Authority.TENANT_ADMIN;
        const pageLink = new PageLink(1000);
        this.topologyTemplateService.getTopologyTemplates(pageLink, includeSystem).subscribe(
            (pageData) => {
                this.availableTemplates = pageData.data;
                this.templatesLoading = false;

                this.filteredTemplates$ = this.templateControl.valueChanges.pipe(
                    startWith(''),
                    map(value => {
                        const search = typeof value === 'string' ? value : (value ? value.name : '');
                        if (!search) {
                            return this.availableTemplates;
                        }
                        const lowercaseSearch = search.toLowerCase();
                        return this.availableTemplates.filter(t => t.name.toLowerCase().includes(lowercaseSearch));
                    })
                );
            },
            () => {
                this.availableTemplates = [];
                this.templatesLoading = false;
            }
        );
    }

    displayTemplateFn(template?: TopologyTemplate): string {
        return template ? template.name : '';
    }

    /**
     * 切换模板来源模式
     */
    onTemplateSourceChange(source: TemplateSource): void {
        this.templateSource = source;
        if (source === TemplateSource.EMPTY) {
            this.selectedTemplateId = null;
            this.applyEmptyTopology();
        }
    }

    /**
     * 选中一个模板后应用其拓扑配置
     */
    onTemplateSelected(template: TopologyTemplate): void {
        this.selectedTemplateId = template?.id?.id;
        if (template && template.configuration) {
            // 深拷贝以避免修改原始模板
            this.defaultTopology = JSON.parse(JSON.stringify(template.configuration));
            this.customizeFormGroup.get('configuration').setValue(this.defaultTopology);
        }
    }

    /**
     * 判断模板是否为系统公共模板
     */
    isSystemTemplate(tpl: TopologyTemplate): boolean {
        return !tpl.tenantId || tpl.tenantId?.id === NULL_UUID;
    }

    cancel(): void {
        this.dialogRef.close(null);
    }

    previousStep(): void {
        this.addModelWizardStepper.previous();
    }

    nextStep(): void {
        // 检查"选择来源"步骤（Step 0）
        if (this.selectedIndex === 0) {
            if (this.templateSource === TemplateSource.FROM_TEMPLATE && !this.selectedTemplateId) {
                this.store.dispatch(new ActionNotificationShow({
                    message: this.translate.instant('model.wizard.select-template-required'),
                    type: 'warn'
                }));
                return;
            }
        }

        // 检查"自定义模型"步骤（Step 2）
        if (this.selectedIndex === 2) {
            const config = this.customizeFormGroup.get('configuration').value;
            const invalidNode = this.findInvalidNode(config);
            if (invalidNode) {
                this.selectedNode = invalidNode;
                this.store.dispatch(new ActionNotificationShow({
                    message: this.translate.instant('model.wizard.node-configuration-invalid', { nodeLabel: invalidNode.entityTypeLabel }),
                    type: 'error'
                }));
                return;
            }
        }
        this.addModelWizardStepper.next();
    }

    getFormLabel(index: number): string {
        switch (index) {
            case 0:
                return 'model.wizard.select-source';
            case 1:
                return 'model.wizard.model-details';
            case 2:
                return 'model.wizard.customize-model';
            case 3:
                return 'model.wizard.preview';
        }
    }

    get maxStepperIndex(): number {
        return this.addModelWizardStepper?._steps?.length - 1;
    }

    onTopologyChange(topology: AssetNodeConfig) {
        this.customizeFormGroup.get('configuration').setValue(topology);
        this.customizeFormGroup.markAsDirty();
    }

    onNodeSelected(node: AssetNodeConfig) {
        this.selectedNode = node;
    }

    onNodeDetailsChange(node: AssetNodeConfig) {
        this.customizeFormGroup.markAsDirty();
        this.customizeFormGroup.get('configuration').updateValueAndValidity();
        if (this.topologyTree) {
            this.topologyTree.refresh();
        }
    }

    public findInvalidNode(node: AssetNodeConfig): AssetNodeConfig | null {
        if (!node) return null;

        // 1. 验证必填字段：节点名称和节点数量
        if (!node.entityTypeLabel?.trim() ||
            node.defaultCount === null || node.defaultCount === undefined || isNaN(node.defaultCount) || node.defaultCount < 1) {
            console.warn('[ModelWizard] Validation Failed on Fields (Label/Count):', node);
            return node;
        }

        // 2. 验证设备/资产档案设置：仅当明确选择了 'custom' 时，才强制需要填写自定义名称
        if (node.profileType === 'custom' && (!node.customProfileName || !node.customProfileName.trim())) {
            return node;
        }

        // 3. 验证设备凭证设置：仅当（若是设备类型）且明确选择了 'custom' 时，才强制需要填写自定义凭证前缀
        if (node.type === AssetNodeType.DEVICE && node.credentialStrategy === 'custom' && (!node.customCredentialName || !node.customCredentialName.trim())) {
            return node;
        }

        // 4. 验证服务器端属性：不允许含有未命名（空键名）或者空值的属性条目
        if (node.attributes && Object.keys(node.attributes).length > 0) {
            for (const key of Object.keys(node.attributes)) {
                const value = node.attributes[key];
                // 如果用户添加了属性行但没填入合法的键名（UI 上带有 __empty_key_ 等前缀表示还没起名字）
                if (key.trim() === '' || key.startsWith('__empty_key_')) {
                    return node;
                }
                // 或者填了键名但没填值
                if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) {
                    return node;
                }
            }
        }

        // 5. 递归验证子节点
        if (node.subNodes && node.subNodes.length > 0) {
            for (const subNode of node.subNodes) {
                const invalidSubNode = this.findInvalidNode(subNode);
                if (invalidSubNode) {
                    return invalidSubNode;
                }
            }
        }

        return null;
    }

    public isTopologyValid(node: AssetNodeConfig): boolean {
        return this.findInvalidNode(node) === null;
    }

    add(): void {
        if (this.allValid()) {
            const config = this.customizeFormGroup.get('configuration').value;
            const invalidNode = this.findInvalidNode(config);
            if (invalidNode) {
                this.selectedNode = invalidNode;
                this.store.dispatch(new ActionNotificationShow({
                    message: this.translate.instant('model.wizard.save-error-invalid-node', { nodeLabel: invalidNode.entityTypeLabel }),
                    type: 'error'
                }));
                return;
            }

            const formValue = this.modelWizardFormGroup.value;
            const template: TopologyTemplate = {
                name: formValue.name,
                type: 'default',
                description: formValue.description,
                modelVersion: formValue.version,
                configuration: {
                    ...config
                }
            };

            this.topologyTemplateService.saveTopologyTemplate(template).subscribe(
                (saved) => this.dialogRef.close(saved)
            );
        }
    }

    allValid(): boolean {
        return !this.addModelWizardStepper.steps.find((item, index) => {
            if (item.stepControl && item.stepControl.invalid) {
                item.interacted = true;
                this.addModelWizardStepper.selectedIndex = index;
                return true;
            } else {
                return false;
            }
        });
    }

    changeStep($event: StepperSelectionEvent): void {
        this.selectedIndex = $event.selectedIndex;
        this.showNext = this.selectedIndex !== this.maxStepperIndex;
    }
}
