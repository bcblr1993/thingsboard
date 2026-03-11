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

export enum AssetNodeType {
    ASSET = 'ASSET',
    DEVICE = 'DEVICE'
}

export interface AssetNodeConfig {
    id?: string; // Optional for new nodes
    type: AssetNodeType;
    entityTypeLabel: string; // e.g., "Station", "PCS"
    namePattern: string;     // e.g., "${StationName}-PCS-${Index}"
    defaultCount: number;    // 默认数量
    subNodes?: AssetNodeConfig[]; // 子节点定义
    attributes?: Record<string, any>; // 默认属性
    isRemovable: boolean;    // 是否允许用户删除
    profileType?: 'default' | 'custom'; // 配置类型
    customProfileName?: string; // 自定义配置名称
    credentialStrategy?: 'name' | 'pinyin' | 'custom';
    customCredentialName?: string;
    relationAdditionalInfo?: any;
    _isInvalid?: boolean;
}

import { EntityId } from '@shared/models/id/entity-id';
import { BaseData } from '@shared/models/base-data';

export interface TopologyTemplate extends BaseData<EntityId> {
    tenantId?: EntityId;
    name: string;
    type: string;
    configuration: any;
    description?: string;
    modelVersion?: string;
    additionalInfo?: any;
    externalId?: EntityId;
}

export interface DeploymentRequest {
    templateId: string;
    globalParams: {
        companyName: string;
        stationName: string;
        stationSn: string;
    };
    finalTopology: AssetNodeConfig; // 用户微调后的最终树
    excludedPaths?: string[]; // 被排除的节点路径
}

export interface PreviewNode {
    id?: string;
    path: string; // Hierarchical Index Path
    name: string;
    type: string;
    label: string;
    credentialsId?: string;
    credentialsValue?: string;
    expanded?: boolean;
    included?: boolean;
    disabled?: boolean;
    level?: number; // Added for flattened tree rendering
    children: PreviewNode[];
}
