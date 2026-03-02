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

import { Injectable } from '@angular/core';
import { Resolve } from '@angular/router';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import {
    CellActionDescriptor,
    DateEntityTableColumn,
    EntityTableColumn,
    EntityTableConfig,
    HeaderActionDescriptor
} from '@home/models/entity/entities-table-config.models';
import { TopologyTemplate } from '@shared/models/topology.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { TopologyTemplateService } from '@core/http/topology-template.service';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { MatDialog } from '@angular/material/dialog';
import { ModelWizardDialogComponent } from './model-wizard-dialog/model-wizard-dialog.component';
import { ModelPreviewDialogComponent } from './model-preview-dialog/model-preview-dialog.component';
import { DialogService } from '@core/services/dialog.service';
import { of } from 'rxjs';
import { ModelDetailsPanelComponent } from './model-details-panel/model-details-panel.component';
import { ModelTabsComponent } from './model-tabs.component';
import { ImportExportService } from '@shared/import-export/import-export.service';

@Injectable()
export class TopologyTemplatesTableConfigResolver implements Resolve<EntityTableConfig<TopologyTemplate>> {

    private readonly config: EntityTableConfig<TopologyTemplate> = new EntityTableConfig<TopologyTemplate>();

    constructor(private topologyTemplateService: TopologyTemplateService,
        private translate: TranslateService,
        private datePipe: DatePipe,
        private dialogService: DialogService,
        private dialog: MatDialog,
        private importExport: ImportExportService,
        private store: Store<AppState>) {

        this.config.entityType = EntityType.TOPOLOGY_TEMPLATE;
        this.config.entityComponent = ModelDetailsPanelComponent;
        this.config.entityTabsComponent = ModelTabsComponent;
        this.config.entityTranslations = entityTypeTranslations.get(EntityType.TOPOLOGY_TEMPLATE);
        this.config.entityResources = entityTypeResources.get(EntityType.TOPOLOGY_TEMPLATE);
        this.config.hideDetailsTabsOnEdit = false;
        this.config.addEnabled = true;
        this.config.entitiesDeleteEnabled = true;
        this.config.entitySelectionEnabled = () => true;

        this.config.columns.push(
            new DateEntityTableColumn<TopologyTemplate>('createdTime', 'model.created-time', this.datePipe, '150px'),
            new EntityTableColumn<TopologyTemplate>('name', 'model.name', '30%'),
            new EntityTableColumn<TopologyTemplate>('modelVersion', 'feature.version-control', '120px'),
            new EntityTableColumn<TopologyTemplate>('description', 'model.description', '50%')
        );

        this.config.cellActionDescriptors = this.configureCellActions();

        this.config.addActionDescriptors.push({
            name: this.translate.instant('model.add'),
            icon: 'add',
            isEnabled: () => true,
            onAction: ($event) => this.addModel($event)
        });

        this.config.addActionDescriptors.push({
            name: this.translate.instant('model.import'),
            icon: 'file_upload',
            isEnabled: () => true,
            onAction: ($event) => this.importModel($event)
        });

        this.config.addEntity = () => {
            this.addModel(null);
            return of(null);
        };

        this.config.entitiesFetchFunction = pageLink => {
            const authUser = getCurrentAuthUser(this.store);
            const includeSystem = authUser.authority !== Authority.TENANT_ADMIN;
            return this.topologyTemplateService.getTopologyTemplates(pageLink, includeSystem);
        };
        this.config.loadEntity = id => this.topologyTemplateService.getTopologyTemplate(id.id);
        this.config.saveEntity = template => this.topologyTemplateService.saveTopologyTemplate(template);
        this.config.deleteEntity = id => this.topologyTemplateService.deleteTopologyTemplate(id.id);

        this.config.deleteEntityTitle = template => this.translate.instant('model.delete-title', { modelName: template.name });
        this.config.deleteEntityContent = () => this.translate.instant('model.delete-text');
        this.config.deleteEntitiesTitle = count => this.translate.instant('model.delete-models-title', { count });
        this.config.deleteEntitiesContent = () => this.translate.instant('model.delete-models-text');

        this.config.deleteEnabled = () => true;

        this.config.onEntityAction = action => {
            if (action.action === 'open') {
                // Handled by standard details panel if needed, but we might want custom toggleDetails
                return false;
            }
            return false;
        };
    }

    resolve(): EntityTableConfig<TopologyTemplate> {
        this.config.tableTitle = this.translate.instant('model.management');
        return this.config;
    }

    private configureAddActions(): Array<HeaderActionDescriptor> {
        return [
            {
                name: this.translate.instant('model.add'),
                icon: 'add',
                isEnabled: () => true,
                onAction: ($event) => this.addModel($event)
            },
            {
                name: this.translate.instant('model.import'),
                icon: 'file_upload',
                isEnabled: () => true,
                onAction: ($event) => this.importModel($event)
            }
        ];
    }

    private configureCellActions(): Array<CellActionDescriptor<TopologyTemplate>> {
        return [
            {
                name: this.translate.instant('model.preview'),
                icon: 'visibility',
                isEnabled: () => true,
                onAction: ($event, entity) => this.previewModel($event, entity)
            },
            {
                name: this.translate.instant('model.export'),
                icon: 'file_download',
                isEnabled: () => true,
                onAction: ($event, entity) => this.exportModel($event, entity)
            }
        ];
    }

    private addModel(_event: Event) {
        this.dialog.open(ModelWizardDialogComponent, {
            disableClose: true,
            panelClass: ['tb-dialog', 'tb-fullscreen-dialog']
        }).afterClosed().subscribe(res => {
            if (res) {
                this.config.updateData();
            }
        });
    }

    private previewModel($event: Event, template: TopologyTemplate) {
        if ($event) {
            $event.stopPropagation();
        }
        this.dialog.open(ModelPreviewDialogComponent, {
            disableClose: true,
            panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
            data: {
                template: template
            }
        });
    }

    private exportModel($event: Event, template: TopologyTemplate) {
        if ($event) {
            $event.stopPropagation();
        }
        this.importExport.exportTopologyTemplate(template.id.id);
    }

    private importModel(_event: Event) {
        this.importExport.importTopologyTemplate().subscribe(res => {
            if (res) {
                this.config.updateData();
            }
        });
    }

}
