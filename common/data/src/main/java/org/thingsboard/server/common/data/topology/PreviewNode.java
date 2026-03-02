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
package org.thingsboard.server.common.data.topology;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class PreviewNode {
    private String id;
    private String name;
    private String type;
    private String label;
    private String credentialsId;
    private String credentialsValue;
    private String path;
    private List<PreviewNode> children;

    public PreviewNode() {
        this.children = new ArrayList<>();
    }

    public void addChild(PreviewNode child) {
        this.children.add(child);
    }
}
