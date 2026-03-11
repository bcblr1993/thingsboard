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

import { ChangeDetectorRef, Component, EventEmitter, Inject, OnInit, Output, ViewChild } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { AssetNodeConfig, AssetNodeType, TopologyTemplate } from '@shared/models/topology.models';
import { TopologyTemplateService } from '@core/http/topology-template.service';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { Authority } from '@shared/models/authority.enum';
import { EntityComponent } from '@home/components/entity/entity.component';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { TranslateService } from '@ngx-translate/core';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';

@Component({
    selector: 'tb-model-details-panel',
    templateUrl: './model-details-panel.component.html',
    styleUrls: ['./model-details-panel.component.scss']
})
export class ModelDetailsPanelComponent extends EntityComponent<TopologyTemplate> implements OnInit {

    isSysAdmin = false;
    isTenantAdmin = false;
    isPublicTemplate = false;

    constructor(protected store: Store<AppState>,
        protected translate: TranslateService,
        @Inject('entity') protected entityValue: TopologyTemplate,
        @Inject('entitiesTableConfig') protected entitiesTableConfigValue: EntityTableConfig<TopologyTemplate>,
        public fb: UntypedFormBuilder,
        public cd: ChangeDetectorRef,
        private topologyTemplateService: TopologyTemplateService) {
        super(store, fb, entityValue, entitiesTableConfigValue, cd);
    }

    ngOnInit() {
        super.ngOnInit();
        this.checkPermissions();
    }

    buildForm(entity: TopologyTemplate): UntypedFormGroup {
        return this.fb.group({
            name: [entity ? entity.name : '', [Validators.required, Validators.maxLength(255), Validators.pattern(/.*\S.*/)]],
            description: [entity ? entity.description : '', [Validators.maxLength(512)]],
            modelVersion: [entity ? entity.modelVersion : '', [Validators.required, Validators.maxLength(255), Validators.pattern(/.*\S.*/)]],
            configuration: [entity ? entity.configuration : null, [this.topologyValidator.bind(this)]]
        });
    }

    private topologyValidator(control: any): { [key: string]: any } | null {
        const configuration = control.value;
        if (!configuration) {
            return null;
        }
        const invalidNode = this.findInvalidNode(configuration);
        if (invalidNode) {
            return { invalidTopology: true };
        }
        return null;
    }

    private findInvalidNode(node: AssetNodeConfig): AssetNodeConfig | null {
        if (!node) return null;

        if (node._isInvalid) {
            return node;
        }

        if (!node.entityTypeLabel || node.entityTypeLabel.trim() === '' || node.entityTypeLabel.length > 255 ||
            !node.namePattern || node.namePattern.trim() === '' || node.namePattern.length > 255 ||
            node.defaultCount === null || node.defaultCount === undefined || isNaN(node.defaultCount) ||
            node.defaultCount < 1 || !Number.isInteger(node.defaultCount)) {
            return node;
        }

        if (node.profileType === 'custom' && (!node.customProfileName || !node.customProfileName.trim() || node.customProfileName.length > 255)) {
            return node;
        }

        if (node.type === AssetNodeType.DEVICE && node.credentialStrategy === 'custom' && (!node.customCredentialName || !node.customCredentialName.trim() || node.customCredentialName.length > 255)) {
            return node;
        }

        if (node.attributes && Object.keys(node.attributes).length > 0) {
            for (const key of Object.keys(node.attributes)) {
                const value = node.attributes[key];
                if (key.trim() === '' || key.startsWith('__empty_key_')) {
                    return node;
                }
                if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) {
                    return node;
                }
            }
        }

        if (node.subNodes && node.subNodes.length > 0) {
            const names = new Set<string>();
            for (const subNode of node.subNodes) {
                const name = subNode.entityTypeLabel?.trim();
                if (name) {
                    if (names.has(name)) {
                        return subNode;
                    }
                    names.add(name);
                }

                const invalidSubNode = this.findInvalidNode(subNode);
                if (invalidSubNode) {
                    return invalidSubNode;
                }
            }
        }

        return null;
    }

    updateForm(entity: TopologyTemplate) {
        this.entityForm.patchValue({
            name: entity.name,
            description: entity.description || '',
            modelVersion: entity.modelVersion || ''
        });
        this.entityForm.patchValue({
            configuration: entity.configuration
        });
        this.entityForm.get('configuration').updateValueAndValidity();
        this.checkPermissions();
    }


    checkPermissions() {
        const authUser = getCurrentAuthUser(this.store);
        this.isSysAdmin = authUser.authority === Authority.SYS_ADMIN;
        this.isTenantAdmin = authUser.authority === Authority.TENANT_ADMIN;
        if (this.entity && this.entity.tenantId) {
            this.isPublicTemplate = this.entity.tenantId.id === '13814000-1dd2-11b2-8080-808080808080';
        }
    }
}
