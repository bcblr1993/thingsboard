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
package org.thingsboard.rule.engine.filter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Data
public class TbCheckValuesNodeConfiguration implements NodeConfiguration<TbCheckValuesNodeConfiguration> {

    @JsonDeserialize(using = TbCheckValuesPairsDeserializer.class)
    private List<TbKeyValueCheck> messageKeyValue;

    @JsonDeserialize(using = TbCheckValuesPairsDeserializer.class)
    private List<TbKeyValueCheck> metadataKeyValue;

    private boolean checkAllKeys;


    @Override
    public TbCheckValuesNodeConfiguration defaultConfiguration() {
        TbCheckValuesNodeConfiguration configuration = new TbCheckValuesNodeConfiguration();
        configuration.setMessageKeyValue(Collections.emptyList());
        configuration.setMetadataKeyValue(Collections.emptyList());
        configuration.setCheckAllKeys(true);
        return configuration;
    }

    public enum TbCheckValuesOperator {
        EQ,
        NEQ,
        GT,
        LT
    }

    @Data
    public static class TbKeyValueCheck {
        private String key;
        private String value;
        private TbCheckValuesOperator operation;
    }

    public static class TbCheckValuesPairsDeserializer extends JsonDeserializer<List<TbKeyValueCheck>> {

        @Override
        public List<TbKeyValueCheck> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            JsonNode node = p.getCodec().readTree(p);
            List<TbKeyValueCheck> result = new ArrayList<>();
            if (node == null || node.isNull()) {
                return result;
            }
            if (node.isArray()) {
                ObjectMapper mapper = (ObjectMapper) p.getCodec();
                for (JsonNode entry : node) {
                    TbKeyValueCheck check = mapper.treeToValue(entry, TbKeyValueCheck.class);
                    if (check != null) {
                        result.add(check);
                    }
                }
                return result;
            }
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    TbKeyValueCheck check = new TbKeyValueCheck();
                    check.setKey(entry.getKey());
                    check.setValue(entry.getValue().asText(null));
                    check.setOperation(TbCheckValuesOperator.EQ);
                    result.add(check);
                }
            }
            return result;
        }
    }
}
