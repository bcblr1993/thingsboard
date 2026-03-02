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
import { Observable, of, timer } from 'rxjs';
import { delay, mapTo } from 'rxjs/operators';
import { AssetNodeConfig, AssetNodeType, DeploymentRequest, TopologyTemplate } from '@shared/models/topology.models';
import { EntityType } from '@shared/models/entity-type.models';

const CHINESE_TEMPLATE: AssetNodeConfig = {
    type: AssetNodeType.ASSET,
    entityTypeLabel: '公司',
    namePattern: '${CompanyName}',
    defaultCount: 1,
    isRemovable: false,
    subNodes: [
        {
            type: AssetNodeType.ASSET,
            entityTypeLabel: '电站',
            namePattern: '${StationName}',
            defaultCount: 1,
            isRemovable: false,
            subNodes: [
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '策略',
                    namePattern: '${StationSn}-Strategy',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '电表',
                    namePattern: '${StationSn}-Ammeter-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '箱变',
                    namePattern: '${StationSn}-Box-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.ASSET,
                    entityTypeLabel: '气象站',
                    namePattern: '${StationSn}-Weather',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '逆变器',
                    namePattern: '${StationSn}-Inverter-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '风机',
                    namePattern: '${StationSn}-WindFan-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '充电桩',
                    namePattern: '${StationSn}-ChargingPile-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '母线',
                    namePattern: '${StationSn}-Bus-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.DEVICE,
                    entityTypeLabel: '控制器',
                    namePattern: '${StationSn}-Controller-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true
                },
                {
                    type: AssetNodeType.ASSET,
                    entityTypeLabel: '储能单元',
                    namePattern: '${StationSn}-Container-${HierarchicalIndex}',
                    defaultCount: 1,
                    isRemovable: true,
                    subNodes: [
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '能量链',
                            namePattern: '${StationSn}-EnergyChain-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '液冷机组',
                            namePattern: '${StationSn}-CoolWater-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '空调',
                            namePattern: '${StationSn}-Air-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '消防',
                            namePattern: '${StationSn}-Fire-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '仓门',
                            namePattern: '${StationSn}-Door-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '烟感',
                            namePattern: '${StationSn}-Smoke-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '水浸',
                            namePattern: '${StationSn}-Water-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: 'EPO',
                            namePattern: '${StationSn}-EPO-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '变流器',
                            namePattern: '${StationSn}-PCS-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true
                        },
                        {
                            type: AssetNodeType.DEVICE,
                            entityTypeLabel: '电池堆',
                            namePattern: '${StationSn}-BMS-${HierarchicalIndex}',
                            defaultCount: 1,
                            isRemovable: true,
                            subNodes: [
                                {
                                    type: AssetNodeType.DEVICE,
                                    entityTypeLabel: '电池簇',
                                    namePattern: '${StationSn}-Cluster-${HierarchicalIndex}',
                                    defaultCount: 8,
                                    isRemovable: true,
                                    subNodes: [
                                        {
                                            type: AssetNodeType.DEVICE,
                                            entityTypeLabel: '单体',
                                            namePattern: '${StationSn}-Cell-${HierarchicalIndex}',
                                            defaultCount: 14,
                                            isRemovable: false
                                        }
                                    ]
                                }
                            ]
                        }
                    ]
                }
            ]
        }
    ]
};

@Injectable({
    providedIn: 'root'
})
export class AssetImportMockService {

    private mockTemplates: TopologyTemplate[] = [
        {
            id: { id: 'tpl-001', entityType: EntityType.TOPOLOGY_TEMPLATE },
            name: '全量标准中文模板 V1.0',
            type: 'STANDARD',
            configuration: {
                ...CHINESE_TEMPLATE,
                description: '包含常用的电站、逆变器、储能单元、BMS、电池簇及单体的完整层级结构。',
                version: '1.0.0'
            },
            createdTime: Date.now()
        }
    ];

    constructor() { }

    public getTemplates(): Observable<TopologyTemplate[]> {
        return of(this.mockTemplates).pipe(delay(500));
    }

    public getTemplateById(id: string): Observable<TopologyTemplate> {
        const tpl = this.mockTemplates.find(t => t.id.id === id);
        return of(tpl).pipe(delay(300));
    }

    public saveTemplate(template: TopologyTemplate): Observable<TopologyTemplate> {
        const idx = this.mockTemplates.findIndex(t => t.id?.id === template.id?.id);
        if (idx > -1) {
            this.mockTemplates[idx] = template;
        } else {
            template.id = { id: 'tpl-' + Date.now(), entityType: EntityType.TOPOLOGY_TEMPLATE };
            template.createdTime = Date.now();
            this.mockTemplates.push(template);
        }
        return of(template).pipe(delay(600));
    }

    public deleteTemplate(id: string): Observable<void> {
        this.mockTemplates = this.mockTemplates.filter(t => t.id.id !== id);
        return timer(400).pipe(mapTo(null));
    }

    public deployProject(request: DeploymentRequest): Observable<any> {
        console.log('[Mock Backend] Received Deployment Request:', request);
        // Simulate long running task
        return timer(2000).pipe(mapTo({ success: true, jobId: 'job-' + Date.now() }));
    }

}
