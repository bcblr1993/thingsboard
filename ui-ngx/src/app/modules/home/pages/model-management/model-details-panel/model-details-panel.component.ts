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
import { AssetNodeConfig, TopologyTemplate } from '@shared/models/topology.models';
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
            name: [entity ? entity.name : '', [Validators.required, Validators.maxLength(255)]],
            description: [entity ? entity.description : ''],
            modelVersion: [{ value: entity ? entity.modelVersion : '', disabled: true }],
            configuration: [entity ? entity.configuration : null]
        });
    }

    updateForm(entity: TopologyTemplate) {
        this.entityForm.patchValue({
            name: entity.name,
            description: entity.description || '',
            modelVersion: entity.modelVersion || '',
            configuration: entity.configuration
        });
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
