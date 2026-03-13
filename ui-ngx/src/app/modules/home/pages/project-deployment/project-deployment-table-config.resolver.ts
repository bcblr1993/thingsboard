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
import {
    DateEntityTableColumn,
    EntityTableColumn,
    EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { AuditLog, ActionType } from '@shared/models/audit-log.models';
import { EntityType, entityTypeTranslations } from '@shared/models/entity-type.models';
import { AuditLogService } from '@core/http/audit-log.service';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { MatDialog } from '@angular/material/dialog';
import { ProjectDeploymentWizardDialogComponent } from './deployment-wizard/project-deployment-wizard-dialog.component';
import { TimePageLink } from '@shared/models/page/page-link';
import { Observable } from 'rxjs';
import { PageData } from '@shared/models/page/page-data';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { ActionNotificationShow } from '@core/notification/notification.actions';

@Injectable()
export class ProjectDeploymentTableConfigResolver implements Resolve<EntityTableConfig<AuditLog, TimePageLink>> {

    private readonly config: EntityTableConfig<AuditLog, TimePageLink> = new EntityTableConfig<AuditLog, TimePageLink>();

    constructor(private auditLogService: AuditLogService,
        private translate: TranslateService,
        private datePipe: DatePipe,
        private dialog: MatDialog,
        private store: Store<AppState>) {

        this.config.useTimePageLink = true;
        this.config.entityType = null;
        this.config.entityTranslations = {
            noEntities: 'audit-log.no-audit-logs-prompt',
            search: 'audit-log.search'
        };

        // Note: Audit Log doesn't have a standard details panel we want to show here
        this.config.detailsPanelEnabled = false;
        this.config.selectionEnabled = false;
        this.config.searchEnabled = true;
        this.config.entitiesDeleteEnabled = false;
        this.config.deleteEnabled = () => false;

        this.config.columns.push(
            new DateEntityTableColumn<AuditLog>('createdTime', 'audit-log.created-time', this.datePipe, '150px'),
            new EntityTableColumn<AuditLog>('entityName', 'audit-log.entity-name', '25%'),
            new EntityTableColumn<AuditLog>('userName', 'audit-log.user', '20%'),
            new EntityTableColumn<AuditLog>('actionStatus', 'audit-log.status', '150px',
                (entity) => this.translate.instant('audit-log.status-' + entity.actionStatus.toLowerCase())),
            new EntityTableColumn<AuditLog>('actionFailureDetails', 'audit-log.failure-details', '30%')
        );

        this.config.rowPointer = true;

        this.config.addActionDescriptors.push({
            name: this.translate.instant('project.deployment-wizard.title'),
            icon: 'add',
            isEnabled: () => true,
            onAction: ($event) => this.addDeployment($event)
        });

        this.config.cellActionDescriptors.push({
            name: this.translate.instant('project.export-credentials'),
            icon: 'file_download',
            isEnabled: () => true,
            onAction: ($event, entity) => this.exportHistory($event, entity)
        });

        this.config.entitiesFetchFunction = pageLink => this.fetchDeployments(pageLink);
    }

    resolve(): EntityTableConfig<AuditLog, TimePageLink> {
        this.config.tableTitle = this.translate.instant('project.projects');
        return this.config;
    }

    private fetchDeployments(pageLink: TimePageLink): Observable<PageData<AuditLog>> {
        return this.auditLogService.getAuditLogs(pageLink, [ActionType.DEPLOY_TOPOLOGY], { ignoreLoading: true });
    }

    private addDeployment($event: Event) {
        if ($event) {
            $event.stopPropagation();
        }
        this.dialog.open(ProjectDeploymentWizardDialogComponent, {
            disableClose: true,
            panelClass: ['tb-dialog', 'tb-fullscreen-dialog']
        }).afterClosed().subscribe(res => {
            if (res) {
                this.config.updateData();
            }
        });
    }

    private exportHistory($event: Event, log: AuditLog) {
        if ($event) {
            $event.stopPropagation();
        }
        if (!log.actionData) {
            this.store.dispatch(new ActionNotificationShow({
                message: this.translate.instant('project.deployment-history.no-data-to-export'),
                type: 'error'
            }));
            return;
        }

        const rows: string[] = [];
        rows.push('Device Name,Client ID,Username,Password');
        this.collectCredentials(log.actionData, rows);

        if (rows.length <= 1) {
            this.store.dispatch(new ActionNotificationShow({
                message: this.translate.instant('project.deployment-wizard.no-devices-found'),
                type: 'error'
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

    private collectCredentials(node: any, rows: string[]) {
        if (node.type === 'DEVICE' && node.credentialsValue) {
            try {
                const creds = typeof node.credentialsValue === 'string' ? JSON.parse(node.credentialsValue) : node.credentialsValue;
                const line = [
                    node.name,
                    creds.clientId || '',
                    creds.userName || '',
                    creds.password || ''
                ].map(s => `"${String(s).replace(/"/g, '""')}"`).join(',');
                rows.push(line);
            } catch (e) {
                console.warn('Failed to parse credentials', e);
            }
        }
        if (node.children) {
            node.children.forEach(child => this.collectCredentials(child, rows));
        }
    }
}
