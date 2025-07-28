/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.relations;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * 节点树VO
 *
 * @author junhao.feng
 */
@Slf4j
public class TreeViewVO implements Serializable {
    /**
     * 树形结构
     */
    private List<TreeNode> nodes;
    /**
     * 未完成关联的设备和资产
     */
    private Set<String> noRelation;
    /**
     * 只在关联关系中出现的设备和资产
     */
    private Set<String> onlyRelation;

    public List<TreeNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<TreeNode> nodes) {
        this.nodes = nodes;
    }

    public Set<String> getNoRelation() {
        return noRelation;
    }

    public void setNoRelation(Set<String> noRelation) {
        this.noRelation = noRelation;
    }

    public Set<String> getOnlyRelation() {
        return onlyRelation;
    }

    public void setOnlyRelation(Set<String> onlyRelation) {
        this.onlyRelation = onlyRelation;
    }

    @Override
    public String toString() {
        return "TreeViewVO{" +
                "nodes=" + nodes +
                ", noRelation=" + noRelation +
                ", onlyRelation=" + onlyRelation +
                '}';
    }
}
