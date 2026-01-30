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

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.filter.TbCheckValuesNodeConfiguration.TbCheckValuesOperator;
import org.thingsboard.rule.engine.filter.TbCheckValuesNodeConfiguration.TbKeyValueCheck;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.List;
import java.util.Map;

@Slf4j
@RuleNode(
        type = ComponentType.FILTER,
        name = "check values presence",
        relationTypes = {TbNodeConnectionType.TRUE, TbNodeConnectionType.FALSE},
        configClazz = TbCheckValuesNodeConfiguration.class,
        nodeDescription = "Checks top-level key-value pairs in the message and/or metadata using simple comparison operators.",
        nodeDetails = "By default, the rule node checks that all specified top-level key-value pairs are present and match the selected comparison operator. " +
                "Equality comparison prefers numeric comparison when both values are numeric, otherwise string comparison is used; greater-than and less-than are numeric. " +
                "Uncheck the 'Check that all selected fields are present' if a match of at least one pair is sufficient.<br><br>" +
                "Output connections: <code>True</code>, <code>False</code>, <code>Failure</code>",
        configDirective = "tbFilterNodeCheckValuesConfig")
public class TbCheckValuesNode implements TbNode {

    private static final Gson gson = new Gson();

    private TbCheckValuesNodeConfiguration config;
    private List<TbKeyValueCheck> messageKeyValue;
    private List<TbKeyValueCheck> metadataKeyValue;

    /*
    * 配置解析并从配置里读取 messageKeyValue 与 metadataKeyValue
     * */
    @Override
    public void init(TbContext tbContext, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbCheckValuesNodeConfiguration.class);
        messageKeyValue = config.getMessageKeyValue();
        metadataKeyValue = config.getMetadataKeyValue();
    }

    /*
    * 根据配置判断消息应走 True 还是 False 链路，并在异常时走 Failure
    * */
    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            // - checkAllKeys = true: 需要消息体全部匹配且元数据全部匹配
            // - checkAllKeys = false: 只要消息体至少一个匹配 atLeastOnePairData(msg)或元数据至少一个匹配
            String relationType = config.isCheckAllKeys() ?
                    allPairsData(msg) && allPairsMetadata(msg) ? TbNodeConnectionType.TRUE : TbNodeConnectionType.FALSE :
                    atLeastOnePairData(msg) || atLeastOnePairMetadata(msg) ? TbNodeConnectionType.TRUE : TbNodeConnectionType.FALSE;
            ctx.tellNext(msg, relationType);
        } catch (Exception e) {
            // 任意解析/比较异常都进入 Failure 分支
            ctx.tellFailure(msg, e);
        }
    }

    private boolean allPairsData(TbMsg msg) {
        if (messageKeyValue != null && !messageKeyValue.isEmpty()) {
            Map<String, Object> dataMap = dataToMap(msg);
            return processAllPairs(messageKeyValue, dataMap);
        }
        return true;
    }

    private boolean allPairsMetadata(TbMsg msg) {
        if (metadataKeyValue != null && !metadataKeyValue.isEmpty()) {
            Map<String, String> metadataMap = metadataToMap(msg);
            return processAllPairs(metadataKeyValue, metadataMap);
        }
        return true;
    }

    private boolean atLeastOnePairData(TbMsg msg) {
        if (messageKeyValue != null && !messageKeyValue.isEmpty()) {
            Map<String, Object> dataMap = dataToMap(msg);
            return processAtLeastOnePair(messageKeyValue, dataMap);
        }
        return false;
    }

    private boolean atLeastOnePairMetadata(TbMsg msg) {
        if (metadataKeyValue != null && !metadataKeyValue.isEmpty()) {
            Map<String, String> metadataMap = metadataToMap(msg);
            return processAtLeastOnePair(metadataKeyValue, metadataMap);
        }
        return false;
    }

    private boolean processAllPairs(List<TbKeyValueCheck> expected, Map<String, ?> actual) {
        for (TbKeyValueCheck check : expected) {
            if (!valueMatches(actual, check)) {
                return false;
            }
        }
        return true;
    }

    private boolean processAtLeastOnePair(List<TbKeyValueCheck> expected, Map<String, ?> actual) {
        for (TbKeyValueCheck check : expected) {
            if (valueMatches(actual, check)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> metadataToMap(TbMsg msg) {
        return msg.getMetaData().getData();
    }

    private boolean valueMatches(Map<String, ?> actual, TbKeyValueCheck check) {
        if (check == null || check.getKey() == null) {
            return false;
        }
        if (!actual.containsKey(check.getKey())) {
            return false;
        }
        Object value = actual.get(check.getKey());
        if (value == null || check.getValue() == null) {
            return false;
        }
        TbCheckValuesOperator operation = check.getOperation() != null ? check.getOperation() : TbCheckValuesOperator.EQ;
        String actualValue = String.valueOf(value);
        switch (operation) {
            case EQ:
            case NEQ:
                // EQ/NEQ 数值优先，无法解析为数值时回退到字符串比较
                try {
                    double actualNumber = Double.parseDouble(actualValue);
                    double expectedNumber = Double.parseDouble(check.getValue());
                    boolean equals = Double.compare(actualNumber, expectedNumber) == 0;
                    return operation == TbCheckValuesOperator.EQ ? equals : !equals;
                } catch (NumberFormatException e) {
                    boolean equals = actualValue.equals(check.getValue());
                    return operation == TbCheckValuesOperator.EQ ? equals : !equals;
                }
            case GT:
            case LT:
                // GT/LT 为数值比较，非数值将抛出 NumberFormatException
                double actualNumber = Double.parseDouble(actualValue);
                double expectedNumber = Double.parseDouble(check.getValue());
                return operation == TbCheckValuesOperator.GT ? actualNumber > expectedNumber : actualNumber < expectedNumber;
                /*try {
                    double actualNumber = Double.parseDouble(actualValue);
                    double expectedNumber = Double.parseDouble(check.getValue());
                    return operation == TbCheckValuesOperator.GT ? actualNumber > expectedNumber : actualNumber < expectedNumber;
                } catch (NumberFormatException e) {
                    return false;
                }*/

            default:
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataToMap(TbMsg msg) {
        // msg.getData() 期望为 JSON object，否则可能抛出转换异常
        return (Map<String, Object>) gson.fromJson(msg.getData(), Map.class);
    }

}
