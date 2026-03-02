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
import { HttpClient } from '@angular/common/http';
import { PageLink } from '@shared/models/page/page-link';
import { Observable } from 'rxjs';
import { PageData } from '@shared/models/page/page-data';
import { defaultHttpOptionsFromConfig, RequestConfig } from './http-utils';
import { EntityId } from '@shared/models/id/entity-id';
import { DeploymentRequest, TopologyTemplate, PreviewNode } from '@shared/models/topology.models';

@Injectable({
    providedIn: 'root'
})
export class TopologyTemplateService {

    constructor(
        private http: HttpClient
    ) { }

    public getTopologyTemplates(pageLink: PageLink, includeSystem: boolean = true, config?: RequestConfig): Observable<PageData<TopologyTemplate>> {
        const queryParams = pageLink.toQuery();
        return this.http.get<PageData<TopologyTemplate>>(`/api/topology-templates${queryParams}&includeSystem=${includeSystem}`,
            defaultHttpOptionsFromConfig(config));
    }

    public getTopologyTemplate(topologyTemplateId: string, config?: RequestConfig): Observable<TopologyTemplate> {
        return this.http.get<TopologyTemplate>(`/api/topology-template/${topologyTemplateId}`,
            defaultHttpOptionsFromConfig(config));
    }

    public saveTopologyTemplate(topologyTemplate: TopologyTemplate, config?: RequestConfig): Observable<TopologyTemplate> {
        return this.http.post<TopologyTemplate>('/api/topology-template', topologyTemplate,
            defaultHttpOptionsFromConfig(config));
    }

    public deleteTopologyTemplate(topologyTemplateId: string, config?: RequestConfig) {
        return this.http.delete(`/api/topology-template/${topologyTemplateId}`,
            defaultHttpOptionsFromConfig(config));
    }

    public setDefaultTopologyTemplate(topologyTemplate: TopologyTemplate, config?: RequestConfig): Observable<TopologyTemplate> {
        // 1. Get all templates to find current default(s)
        const pageLink = new PageLink(1000); // Assume < 1000 templates
        return new Observable(observer => {
            this.getTopologyTemplates(pageLink, true, config).subscribe(pageData => {
                const templates = pageData.data;
                const updateTasks: Observable<TopologyTemplate>[] = [];

                // 2. Unset default for others
                templates.forEach(t => {
                    if (t.id.id !== topologyTemplate.id.id && t.additionalInfo && t.additionalInfo.default) {
                        t.additionalInfo.default = false;
                        updateTasks.push(this.saveTopologyTemplate(t, config));
                    }
                });

                // 3. Set default for target
                if (!topologyTemplate.additionalInfo) {
                    topologyTemplate.additionalInfo = {};
                }
                topologyTemplate.additionalInfo.default = true;
                updateTasks.push(this.saveTopologyTemplate(topologyTemplate, config));

                // 4. Execute all updates
                if (updateTasks.length > 0) {
                    import('rxjs').then(rxjs => {
                        const { forkJoin } = rxjs;
                        forkJoin(updateTasks).subscribe(() => {
                            observer.next(topologyTemplate);
                            observer.complete();
                        }, error => {
                            observer.error(error);
                        });
                    });
                } else {
                    observer.next(topologyTemplate);
                    observer.complete();
                }
            }, error => {
                observer.error(error);
            });
        });
    }

    public deployTopology(request: DeploymentRequest, config?: RequestConfig): Observable<PreviewNode> {
        return this.http.post<PreviewNode>('/api/topology-template/deploy', request,
            defaultHttpOptionsFromConfig(config));
    }

    public previewTopology(request: DeploymentRequest, config?: RequestConfig): Observable<PreviewNode> {
        return this.http.post<PreviewNode>('/api/topology-template/preview', request,
            defaultHttpOptionsFromConfig(config));
    }

}
