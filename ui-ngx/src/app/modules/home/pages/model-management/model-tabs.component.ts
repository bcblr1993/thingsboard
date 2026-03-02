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

import { Component, ChangeDetectorRef, OnInit } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityTabsComponent } from '../../components/entity/entity-tabs.component';
import { AssetNodeConfig, TopologyTemplate } from '@shared/models/topology.models';
import { TranslateService } from '@ngx-translate/core';

@Component({
    selector: 'tb-model-tabs',
    templateUrl: './model-tabs.component.html',
    styleUrls: ['./model-details-panel/model-details-panel.component.scss']
})
export class ModelTabsComponent extends EntityTabsComponent<TopologyTemplate> implements OnInit {

    selectedNode: AssetNodeConfig | null = null;

    constructor(protected store: Store<AppState>,
        private cd: ChangeDetectorRef,
        private translate: TranslateService) {
        super(store);
    }

    ngOnInit() {
        super.ngOnInit();
    }

    // Override setEntity to clear selected node when switching between different models
    protected setEntity(entity: TopologyTemplate) {
        if (this.entityValue?.id?.id !== entity?.id?.id) {
            this.selectedNode = null;
        }
        super.setEntity(entity);
    }
    onNodeSelected(node: any) {
        // This could be used if we need to sync selection across tabs, 
        // but typically each tab maintains its own state or uses a shared service.
    }

    onTopologyChange(topology: any) {
        this.entity.configuration = topology;
        this.updateFormConfiguration();
    }

    onNodeDetailsChange(node: any) {
        this.cd.detectChanges();
        this.updateFormConfiguration();
    }

    private updateFormConfiguration() {
        if (this.detailsForm && this.detailsForm.get('configuration')) {
            // 通过 patchValue 并 markAsDirty 确保整个表单被标记为修改状态
            this.detailsForm.patchValue({
                configuration: this.entity.configuration
            });
            this.detailsForm.markAsDirty();
        }
    }

}
