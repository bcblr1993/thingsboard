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

import { ChangeDetectorRef, Component, EventEmitter, OnInit, Output, ViewChild } from '@angular/core';
import { PageComponent } from '@shared/components/page.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { TopologyTemplateService } from '@core/http/topology-template.service';
import { AssetNodeConfig, DeploymentRequest, TopologyTemplate, PreviewNode } from '@shared/models/topology.models';
import { NULL_UUID } from '@shared/models/id/has-uuid';
import { PageLink } from '@shared/models/page/page-link';
import { Observable, of, Subject } from 'rxjs';
import { map, catchError, startWith, switchMap, debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { MatStepper, StepperOrientation } from '@angular/material/stepper';
import { Input } from '@angular/core';
import { StepperSelectionEvent } from '@angular/cdk/stepper';
// @ts-ignore
import Pinyin from 'tiny-pinyin';

@Component({
    selector: 'tb-project-deployment-wizard',
    templateUrl: './project-deployment-wizard.component.html',
    styleUrls: ['./project-deployment-wizard.component.scss']
})
export class ProjectDeploymentWizardComponent extends PageComponent implements OnInit {

    @Output()
    deploymentFinished = new EventEmitter<any>();

    @Output()
    selectionChange = new EventEmitter<StepperSelectionEvent>();

    @Input()
    stepperOrientation: Observable<StepperOrientation>;

    @Input()
    stepperLabelPosition: Observable<'bottom' | 'end'>;

    templates$: Observable<TopologyTemplate[]>;
    filteredTemplates$: Observable<TopologyTemplate[]>;
    allTemplates: TopologyTemplate[] = [];
    selectedTemplate: TopologyTemplate | null = null;
    finalTopology: AssetNodeConfig | null = null;

    // Forms
    selectTemplateForm: FormGroup;
    basicInfoForm: FormGroup;

    isDeploying = false;
    deployResult: PreviewNode | null = null;
    selectedNode: AssetNodeConfig | null = null;

    // Preview
    previewNode: PreviewNode | null = null;
    visibleNodes: PreviewNode[] = []; // For virtual scroll
    isPreviewing = false;
    excludedPaths = new Set<string>();

    searchQuery: string = '';
    private searchSubject = new Subject<string>();

    createdAssetsCount = 0;
    createdDevicesCount = 0;

    @ViewChild('stepper', { static: false }) stepper: MatStepper;

    constructor(protected store: Store<AppState>,
        private fb: FormBuilder,
        private topologyTemplateService: TopologyTemplateService,
        private dialogService: DialogService,
        private translate: TranslateService,
        private cd: ChangeDetectorRef) {
        super(store);
        this.selectTemplateForm = this.fb.group({
            templateId: [null, [Validators.required, (control) => {
                const value = control.value;
                if (!value || typeof value === 'string' || !value.id || !value.id.id) {
                    return { invalidTemplate: true };
                }
                return null;
            }]]
        });
        this.basicInfoForm = this.fb.group({
            companyName: ['', Validators.required],
            stationName: ['', Validators.required],
            stationSn: ['', Validators.required],
            useStationNameAsPrefix: [false]
        });
    }

    ngOnInit() {
        console.log('ProjectDeploymentWizardComponent initialized');

        // Auto-generate Station SN from Station Name
        this.basicInfoForm.get('stationName').valueChanges.subscribe(name => {
            const snControl = this.basicInfoForm.get('stationSn');
            // Only auto-generate if SN is empty or untouched (pristine)
            if (name && (snControl.pristine || !snControl.value)) {
                const generatedSn = this.generateSn(name);
                snControl.setValue(generatedSn);
                snControl.markAsPristine(); // Keep it pristine so further edits to name continue to update SN?
                // Actually, usually if user edits Name, we update SN. If user edits SN, we stop updating.
                // So we should check if SN is pristine. But setValue makes it dirty?
                // Angular forms: setValue makes as dirty? No, it depends.
                // Let's just check if it was pristine result.
                // A better UX: If user manually edits SN, stop auto-generating.
                // If I use setValue, it doesn't change pristine status by default I think?
                // Wait, setValue(value, {emitEvent: ...})
                // Let's just check `snControl.pristine`.
            }
        });

        // includeSystem=true 确保加载公共模板 + 租户模板
        this.templates$ = this.topologyTemplateService.getTopologyTemplates(new PageLink(100), true).pipe(
            map(pageData => {
                console.log('Templates loaded:', pageData.data);
                this.allTemplates = pageData.data;
                return pageData.data;
            }),
            catchError(err => {
                console.error('Failed to load templates', err);
                this.store.dispatch(new ActionNotificationShow({
                    message: 'Failed to load templates',
                    type: 'error'
                }));
                return of([]);
            })
        );

        this.searchSubject.pipe(
            debounceTime(300),
            distinctUntilChanged()
        ).subscribe(query => {
            this.searchQuery = query.toLowerCase();
            this.updateVisibleNodes();
            this.cd.markForCheck();
        });

        this.filteredTemplates$ = this.selectTemplateForm.get('templateId').valueChanges.pipe(
            startWith(''),
            switchMap(value => {
                const search = typeof value === 'string' ? value : (value ? value.name : '');
                return this.templates$.pipe(
                    map(templates => {
                        if (!search) {
                            return templates;
                        }
                        const lowercaseSearch = search.toLowerCase();
                        return templates.filter(t => t.name.toLowerCase().includes(lowercaseSearch));
                    })
                );
            })
        );
    }

    displayTemplateFn(template?: TopologyTemplate): string {
        return template ? template.name : '';
    }

    onTemplateSelected(template: TopologyTemplate) {
        // Use setTimeout to allow the mat-select dropdown to close before potential UI re-rendering
        setTimeout(() => {
            if (template) {
                this.selectedTemplate = template;
                this.finalTopology = template.configuration;
                this.hydrateTopology(this.finalTopology);
                this.selectedNode = this.finalTopology;
                this.cd.markForCheck();
            }
        }, 0);
    }

    /**
     * 判断模板是否为系统公共模板
     */
    isSystemTemplate(tpl: TopologyTemplate): boolean {
        return !tpl.tenantId || tpl.tenantId?.id === NULL_UUID;
    }

    private hydrateTopology(node: AssetNodeConfig) {
        if (!node) return;
        if (!node.namePattern) {
            node.namePattern = '${StationSn}-${Type}${HierarchicalIndex}';
        }
        if (node.defaultCount === undefined || node.defaultCount === null) {
            node.defaultCount = 1;
        }
        if (!node.attributes) {
            node.attributes = {};
        }
        if (node.subNodes && node.subNodes.length > 0) {
            node.subNodes.forEach(child => this.hydrateTopology(child));
        }
    }

    private setTopologyFromTemplate(template: TopologyTemplate) {
        let topology = null;
        if (template.configuration) {
            if (template.configuration.structure) {
                topology = template.configuration.structure;
            } else if (template.configuration.subNodes || template.configuration.type) {
                topology = template.configuration;
            }
        }

        if (topology) {
            try {
                this.finalTopology = JSON.parse(JSON.stringify(topology));
            } catch (e) {
                console.error('Failed to parse topology structure', e);
            }
        } else {
            this.finalTopology = null;
        }
    }

    private generateSn(name: string): string {
        if (!name) return '';
        try {
            if (Pinyin.isSupported()) {
                // Convert to pinyin with separator, e.g. "Ri Xin"
                const pinyinStr = Pinyin.convertToPinyin(name, ' ', true);
                // Split and take first char
                return pinyinStr.split(' ').map(s => s.charAt(0)).join('').toLowerCase();
            }
        } catch (e) {
            console.warn('Pinyin conversion failed', e);
        }
        return name;
    }


    // Placeholder for tree manipulation
    updateTopology(newTopology: AssetNodeConfig) {
        this.finalTopology = newTopology;
    }

    changeStep($event: StepperSelectionEvent): void {
        this.selectionChange.emit($event);
    }

    previousStep() {
        this.stepper.previous();
    }

    preview() {
        if (this.basicInfoForm.invalid || !this.finalTopology) {
            return;
        }

        const invalidNode = this.findInvalidNode(this.finalTopology);
        if (invalidNode) {
            this.selectedNode = invalidNode;
            this.store.dispatch(new ActionNotificationShow({
                message: this.translate.instant('model.wizard.preview-error-invalid-node', { nodeLabel: invalidNode.entityTypeLabel }),
                type: 'error'
            }));
            return;
        }

        this.isPreviewing = true;
        this.excludedPaths.clear();
        const request: DeploymentRequest = {
            templateId: this.selectedTemplate.id.id,
            globalParams: this.basicInfoForm.value,
            finalTopology: this.finalTopology
        };

        this.topologyTemplateService.previewTopology(request).subscribe(
            (node) => {
                this.isPreviewing = false;
                this.previewNode = node;
                this.initPreviewNodeStates(this.previewNode, 0);
                this.updateVisibleNodes();
                console.log('Preview loaded:', this.previewNode);
                this.stepper.next();
            },
            err => {
                this.isPreviewing = false;
                this.store.dispatch(new ActionNotificationShow({
                    message: 'Preview failed: ' + (err.error?.message || err.message),
                    type: 'error'
                }));
            }
        );
    }

    toggleExclusion(node: PreviewNode, checked: boolean) {
        if (!node.path) return;

        if (node.disabled) {
            if (!checked) { // Attempting to exclude
                this.store.dispatch(new ActionNotificationShow({
                    message: this.translate.instant('project.deployment-wizard.cropping-level-restricted'),
                    type: 'warn'
                }));
                return;
            }
        }

        if (checked) {
            this.excludedPaths.delete(node.path);
        } else {
            this.excludedPaths.add(node.path);
        }

        this.calculateIncludedStates(this.previewNode);
    }

    public isLevelRestricted(node: any): boolean {
        if (!node || !node.path) {
            return false;
        }
        // Path format examples: "0", "0-1", "0-2-1"
        // Root is "" or index, so segments <= 1 are first two levels
        return node.path.split('-').length < 2;
    }

    isNodeIncluded(node: any): boolean {
        if (!node || !node.path) {
            return true;
        }
        // Check if the node itself is excluded
        if (this.excludedPaths.has(node.path)) {
            return false;
        }

        // Check if any parent path is excluded
        // Path format: "1-0-1" -> Check "1-0", "1"
        const parts = node.path.split('-');
        let currentPath = '';
        for (let i = 0; i < parts.length - 1; i++) {
            currentPath = currentPath ? currentPath + '-' + parts[i] : parts[i];
            if (this.excludedPaths.has(currentPath)) {
                return false;
            }
        }
        return true;
    }

    checkCroppingAndNavigate(direction: 'back' | 'deploy') {
        if (this.excludedPaths.size > 0) {
            this.dialogService.confirm(
                this.translate.instant('project.deployment-wizard.cropping-confirm-title', 'Confirm Navigation'),
                this.translate.instant('project.deployment-wizard.cropping-confirm-message', 'You have excluded some nodes. Navigating away might lose these changes. Are you sure?'),
                this.translate.instant('action.cancel'),
                this.translate.instant('action.continue')
            ).subscribe((result) => {
                if (result) {
                    if (direction === 'back') {
                        this.stepper.previous();
                    } else {
                        this.deploy();
                    }
                }
            });
        } else {
            if (direction === 'back') {
                this.stepper.previous();
            } else {
                this.deploy();
            }
        }
    }

    private countStats(node: PreviewNode) {
        if (!node) return;
        if (node.type === 'DEVICE') {
            this.createdDevicesCount++;
        } else {
            this.createdAssetsCount++;
        }

        if (node.children?.length > 0) {
            node.children.forEach(child => this.countStats(child));
        }
    }

    deploy() {
        if (this.basicInfoForm.invalid || !this.finalTopology) {
            return;
        }

        const invalidNode = this.findInvalidNode(this.finalTopology);
        if (invalidNode) {
            this.selectedNode = invalidNode;
            this.store.dispatch(new ActionNotificationShow({
                message: this.translate.instant('model.wizard.deploy-error-invalid-node', { nodeLabel: invalidNode.entityTypeLabel }),
                type: 'error'
            }));
            return;
        }

        this.isDeploying = true;
        const request: DeploymentRequest = {
            templateId: this.selectedTemplate.id.id,
            globalParams: this.basicInfoForm.value,
            finalTopology: this.finalTopology,
            excludedPaths: Array.from(this.excludedPaths)
        };

        this.topologyTemplateService.deployTopology(request).subscribe(
            (result) => {
                this.isDeploying = false;
                this.deployResult = result;
                this.deploymentFinished.emit(result);
                this.cd.markForCheck();
                // Calculate Stats
                this.createdAssetsCount = 0;
                this.createdDevicesCount = 0;
                this.countStats(this.deployResult);

                this.stepper.next();
            },
            err => {
                this.isDeploying = false;
                // Handle error (alert or toast)
                this.store.dispatch(new ActionNotificationShow({
                    message: 'Deployment failed: ' + (err.error?.message || err.message),
                    type: 'error'
                }));
            }
        );
    }

    onNodeSelected(node: AssetNodeConfig) {
        this.selectedNode = node;
    }

    onNodeDetailsChange(node: AssetNodeConfig) {
        // Since objects are passed by reference, finalTopology is updated
        // We just need to ensure the UI/Stepper state re-evaluates
        this.cd.markForCheck();
    }

    public findInvalidNode(node: AssetNodeConfig): AssetNodeConfig | null {
        if (!node) return null;

        // Required fields
        // namePattern is kept optional here because hydrateTopology ensures it exists or it can be empty for root
        if (!node.entityTypeLabel?.trim() ||
            node.defaultCount === null || node.defaultCount === undefined || isNaN(node.defaultCount) || node.defaultCount < 1) {
            console.warn('[ProjectDeploymentWizard] Validation Failed on Fields (Label/Count):', node);
            return node;
        }

        if (node.profileType === 'custom' && !node.customProfileName?.trim()) {
            return node;
        }
        if (node.credentialStrategy === 'custom' && !node.customCredentialName?.trim()) {
            return node;
        }

        if (node.attributes && Object.keys(node.attributes).length > 0) {
            for (const key of Object.keys(node.attributes)) {
                const value = node.attributes[key];
                if (key.startsWith('__empty_key_')) {
                    return node;
                }
                if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) {
                    return node;
                }
            }
        }

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

    nextStep() {
        if (this.stepper.selectedIndex === 2) { // Topology Edit Step
            const invalidNode = this.findInvalidNode(this.finalTopology);
            if (invalidNode) {
                this.selectedNode = invalidNode;
                this.store.dispatch(new ActionNotificationShow({
                    message: this.translate.instant('model.wizard.node-configuration-invalid', { nodeLabel: invalidNode.entityTypeLabel }),
                    type: 'error'
                }));
                return;
            }
        }
        this.stepper.next();
    }

    exportCredentials() {
        if (!this.deployResult) {
            return;
        }
        const rows: string[] = [];
        // Header
        rows.push('Device Name,Client ID,Username,Password');

        // Traverse
        this.collectCredentials(this.deployResult, rows);

        if (rows.length <= 1) {
            this.store.dispatch(new ActionNotificationShow({
                message: 'No devices found in deployment result',
                type: 'warn'
            }));
            return;
        }

        const csvContent = rows.join('\n');
        const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
        const link = document.createElement('a');
        const url = URL.createObjectURL(blob);

        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
        link.setAttribute('href', url);
        link.setAttribute('download', `deployment_credentials_${timestamp}.csv`);
        link.style.visibility = 'hidden';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    }

    private collectCredentials(node: PreviewNode, rows: string[]) {
        if (node.type === 'DEVICE' && node.credentialsValue) {
            try {
                const creds = JSON.parse(node.credentialsValue);
                // Handle comma in fields? Simplified for now as names usually don't have commas
                // Or use quotes
                const line = [
                    node.name,
                    creds.clientId || '',
                    creds.userName || '',
                    creds.password || ''
                ].map(s => `"${s.replace(/"/g, '""')}"`).join(','); // Escape quotes
                rows.push(line);
            } catch (e) {
                console.warn('Failed to parse credentials for node', node.name, e);
            }
        }
        if (node.children && node.children.length > 0) {
            node.children.forEach(child => this.collectCredentials(child, rows));
        }
    }

    private initPreviewNodeStates(node: PreviewNode, level: number = 0) {
        if (!node) return;
        node.expanded = true;
        node.disabled = this.isLevelRestricted(node);
        node.included = true;
        node.level = level;
        if (node.children?.length > 0) {
            node.children.forEach(child => this.initPreviewNodeStates(child, level + 1));
        }
    }

    private updateVisibleNodes() {
        this.visibleNodes = [];
        this.flattenNode(this.previewNode, false);
    }

    private isNodeMatchesSearch(node: PreviewNode): boolean {
        if (!this.searchQuery) return true;

        // 1. 直系名称匹配
        if (node.name.toLowerCase().includes(this.searchQuery)) return true;

        // 2. 如果包含子节点，且子节点中存在匹配的，则当前父节点也该算作匹配（以保证展开层级可见）
        if (node.children?.length > 0) {
            for (const child of node.children) {
                if (this.isNodeMatchesSearch(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private flattenNode(node: PreviewNode, forceInclude: boolean) {
        if (!node) return;

        const matchesSelf = this.searchQuery ? node.name.toLowerCase().includes(this.searchQuery) : true;
        const matchesSubTree = this.searchQuery ? this.isNodeMatchesSearch(node) : true;

        // 如果既不匹配本身其子树中也没有匹配的，并且没有被父级（匹配项）强制带入，则隐藏
        if (this.searchQuery && !matchesSubTree && !forceInclude) {
            return;
        }

        this.visibleNodes.push(node);

        // 如果是在进行有效搜索（有关键字输入）那么默认把能够显示的节点分支强制全部设为展开（以防折叠导致看不见内部结构）
        if (this.searchQuery) {
            node.expanded = true;
        }

        // 如果自身命中了关键字搜索（或者是被强制带入的子集节点），所有的子节点应该强制带出
        const shouldForceIncludeChild = forceInclude || matchesSelf;

        if (node.expanded && node.children?.length > 0) {
            node.children.forEach(child => this.flattenNode(child, shouldForceIncludeChild));
        }
    }

    onSearch(query: string) {
        this.searchSubject.next(query);
    }

    private calculateIncludedStates(node: PreviewNode, parentExcluded: boolean = false) {
        if (!node) return;
        const explicitlyExcluded = this.excludedPaths.has(node.path);
        const isExcluded = parentExcluded || explicitlyExcluded;
        node.included = !isExcluded;

        if (node.children?.length > 0) {
            node.children.forEach(child => this.calculateIncludedStates(child, isExcluded));
        }
    }

    trackByNodePath(index: number, node: PreviewNode): string {
        return node.path || node.id;
    }

    toggleExpanded(node: PreviewNode, event: Event) {
        if (event) {
            event.stopPropagation();
        }
        node.expanded = !node.expanded;
        this.updateVisibleNodes();
    }
}
