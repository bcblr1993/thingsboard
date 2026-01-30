# TbCheckValuesNode 设计文档

## 概述
`TbCheckValuesNode` 是规则引擎中的过滤节点，用于校验消息体（data）与元数据（metadata）中的键值对是否满足指定条件，并将消息路由到 `True` / `False` 连接。

- **节点类型**：Filter
- **组件名称**：check values presence
- **后端实现**：`rule-engine/rule-engine-components/src/main/java/org/thingsboard/rule/engine/filter/TbCheckValuesNode.java`
- **配置类**：`rule-engine/rule-engine-components/src/main/java/org/thingsboard/rule/engine/filter/TbCheckValuesNodeConfiguration.java`
- **UI 配置组件**：`ui-ngx/src/app/modules/home/components/rule-node/filter/check-values-config.component.ts`

## 输入输出

### 输入
- **消息体**：`TbMsg.getData()`，JSON 字符串；后端使用 Gson 反序列化为 `Map<String, Object>`。
- **元数据**：`TbMsg.getMetaData().getData()`，`Map<String, String>`。

### 输出连接
- `True`：满足配置的匹配条件。
- `False`：未满足配置的匹配条件。
- `Failure`：处理异常（如消息体 JSON 反序列化失败）。

## 配置模型

### 后端配置字段
`TbCheckValuesNodeConfiguration`：

| 字段 | 类型 | 说明 | 默认值 |
| --- | --- | --- | --- |
| messageKeyValue | List<TbKeyValueCheck> | 消息体键值检查列表 | [] |
| metadataKeyValue | List<TbKeyValueCheck> | 元数据键值检查列表 | [] |
| checkAllKeys | boolean | 是否要求全部匹配 | true |

`TbKeyValueCheck`（`TbCheckValuesNodeConfiguration` 内部类）：

| 字段 | 类型 | 说明 | 默认值 |
| --- | --- | --- | --- |
| key | String | 要检查的字段名 | - |
| value | String | 期望值 | - |
| operation | TbCheckValuesOperator | 比较操作符（EQ/NEQ/GT/LT） | EQ |

`TbCheckValuesOperator`（`TbCheckValuesNodeConfiguration` 内部枚举）：
- `EQ`：数值优先相等（可解析为数字时按数值比较，否则按字符串相等）
- `NEQ`：数值优先不等（可解析为数字时按数值比较，否则按字符串不等）
- `GT`：数值大于
- `LT`：数值小于

### 配置反序列化规则
`TbCheckValuesPairsDeserializer`（`TbCheckValuesNodeConfiguration` 内部类）支持两种配置形态：

1) **数组形式**（推荐）
```json
[
  {"key": "temperature", "value": "30", "operation": "GT"}
]
```

2) **对象形式**（简写，默认 EQ）
```json
{
  "status": "active",
  "type": "sensor"
}
```

对象形式会被转换为 `operation = EQ`。

## 核心逻辑

### 处理流程
- `init`：读取配置并缓存 `messageKeyValue` / `metadataKeyValue`。
- `onMsg`：根据 `checkAllKeys` 决定匹配策略：
  - `checkAllKeys = true`：
    - 消息体检查 **全部匹配** AND 元数据检查 **全部匹配** → `True`
  - `checkAllKeys = false`：
    - 消息体检查 **至少一个匹配** OR 元数据检查 **至少一个匹配** → `True`
- 未满足条件 → `False`
- 异常 → `Failure`

### 匹配规则
`valueMatches(actual, check)` 的规则如下：
1. `check` 或 `check.key` 为空 → 不匹配
2. `actual` 中不存在该 key → 不匹配
3. 实际值或期望值为空 → 不匹配
4. 操作符为空时默认 `EQ`
5. `EQ`：数值优先比较；若无法解析为数值则按字符串相等
6. `NEQ`：数值优先比较；若无法解析为数值则按字符串不等
7. `GT` / `LT`：将实际值和期望值转换为 `Double` 比较；若解析失败 → 不匹配

## UI 配置说明

### 组件结构
- 组件：`CheckValuesConfigComponent`
- 表单字段：
  - `messageKeyValue`（表格形式）
  - `metadataKeyValue`（表格形式）
  - `checkAllKeys`（滑动开关）

### 交互行为
- 用户可添加多行键值检查，每行包含：`key`、`operation`、`value`。
- 操作符下拉项：`= (EQ)`、`!= (NEQ)`、`> (GT)`、`< (LT)`。
- `checkAllKeys` 开启时需全部匹配，关闭时至少一条匹配即可。
- 校验规则：
  - `messageKeyValue` 与 `metadataKeyValue` 至少填写一个列表项
  - `key` 与 `value` 必填

### 输入归一化
`normalizeKeyValueChecks` 支持数组/对象输入：
- 数组：按条目读取 `key/value/operation`，缺省 `operation` 默认为 `EQ`
- 对象：将 `key/value` 转为条目，`operation` 固定为 `EQ`

## 行为边界与注意事项

- **空配置 + checkAllKeys=true**：两个列表均空时，默认返回 `True`（全部满足）。
- **空配置 + checkAllKeys=false**：两个列表均空时，返回 `False`（没有任何匹配）。
- **数值比较**：`GT/LT` 仅适用于能转换为数字的值；否则不匹配。
- **消息体解析失败**：会进入 `Failure` 连接。

## 配置示例

### 示例 1：全部满足
```json
{
  "messageKeyValue": [
    {"key": "temperature", "value": "30", "operation": "GT"},
    {"key": "humidity", "value": "80", "operation": "LT"}
  ],
  "metadataKeyValue": [
    {"key": "source", "value": "sensor", "operation": "EQ"}
  ],
  "checkAllKeys": true
}
```

### 示例 2：满足任意一项
```json
{
  "messageKeyValue": {
    "status": "active",
    "mode": "auto"
  },
  "metadataKeyValue": [],
  "checkAllKeys": false
}
```

## 与设计理念的对齐

- **KISS**：匹配逻辑集中在 `valueMatches`，流程清晰、分支可控。
- **YAGNI**：仅支持基础操作符，避免过度扩展。
- **SRP**：配置解析与匹配逻辑分离，职责明确。
- **DRY**：消息体与元数据共享 `processAllPairs/processAtLeastOnePair` 与 `valueMatches`。

## 后续改进方向（可选）

- 扩展操作符：如 `GTE/LTE`。
- 增加类型感知：避免 `String` 与 `Double` 强转导致的比较歧义。
- 提供更细粒度的错误提示（例如数值解析失败的 key 列表）。
