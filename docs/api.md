# 接口说明

基址默认 `http://127.0.0.1:8080`。距离用米、耗时用秒、金额用元；时间为带偏移的 ISO-8601，业务按 `Asia/Shanghai` 解释。未知数字保留 `null`。完整类型以 Java record 和 `frontend/src/types/journey.ts` 为准。

## 地点与校园接驳

- `GET /api/places?keyword=图书馆&scope=CAMPUS` → `{ "items": [...] }`。
- `GET /api/places/{id}` → 单个地点；未知 ID 返回 404。
- `GET /api/campus/connectors?originId=...&departureTime=...` → `{originId,dataVersion,connectors}`。

`scope` 支持 `CAMPUS/GATE/CITY`，省略则不限类别。空关键词列出相应范围，未命中返回空数组。查询参数中的 `+08:00` 应编码为 `%2B08:00`。

地点字段：`id/name/kind/category/aliases/routingReady/unavailableReason/verificationStatus/displayPosition/routingPosition/dataVersion/sourceRefs`。位置包含 `pointId/longitude/latitude/coordinateSystem/positionRole`。展示位置可能只是建筑代表点；`routingReady=true` 不代表该点到所有校门都有接驳。

接驳保留起点、校门、出校点、轨迹、坐标系、米/秒指标、估算方法与核实依据。当前仅两个起点经东门可用。无审核连接返回 422 `NO_APPROVED_CONNECTOR` 并附原因；西门和西南方向记录保留不可用状态。

| 名称 | 稳定 ID |
|---|---|
| 图书馆 | `osm_way_1098786977` |
| 天佑斋1号楼 | `osm_way_1099340057` |
| 东门 | `campus_gate_east` |
| 天府广场 / 春熙路 / 成都东客站 | `C01` / `C02` / `C03` |
| 成都站 / 宽窄巷子 | `C04` / `C08` |

## 普通行程

`POST /api/journeys`，JSON 请求：

```json
{
  "originId": "osm_way_1098786977",
  "destinationId": "C01",
  "departureTime": "2026-12-01T09:00:00+08:00",
  "preference": "RECOMMENDED"
}
```

演示时替换为未来出发时间。偏好支持 `RECOMMENDED/LESS_WALKING/FEWER_TRANSFERS`。

响应字段为 `requestId/status/plans/queriedAt/campusDataVersion/provider`，部分校门失败时可带 `warnings`。成功 `status=SUCCEEDED`；无线路为 HTTP 200、`status=NO_ROUTE`、空 `plans`。去重、排序后最多返回三条实际方案。

每条方案包含：

- 身份：`planId/originId/destinationId/gateId/gateExitPoint`。
- 时间：`departureTime/cityDepartureTime/providerDepartureTime/roundingWaitSeconds/arrivalTime/totalDurationSeconds`。
- 汇总：`totalWalkingMeters/transferCount/costYuan`。
- 分段与来源：`segments/sources/queriedAt/campusDataVersion`。

分段字段为 `sequence/mode/description/fromPoint/toPoint/lineId/lineName/direction/boardingStation/alightingStation/distanceMeters/durationSeconds/geometry/coordinateSystem`。轨迹按经度、纬度排列。校园段为 WGS84，城市段通常为 GCJ-02；前端统一转换后绘制。轨迹缺失时保留文字，不画猜测路线。

组合口径：校园出发时刻 + 接驳耗时 = 城市段出发时刻；供应方需要分钟精度时向上取整，等待计入总耗时。城市段已包含的到站步行只累计一次。校园段止于所选出校点，城市段从同一点开始。未知费用、步行距离保留 `null`。

`TransitProvider` 隔离高德 DTO、坐标转换、超时与错误解析。后续模型工具应复用 `JourneyService`，不要另写组合算法。

## 模型工具自检

`POST /api/assistant/tool-check`

```json
{"message":"请调用工具查看当前服务信息"}
```

返回 `requestId/model/toolCalls/answer`。模型请求 `get_service_info`，Java 校验工具名和严格 JSON 参数、执行工具，再按 callId 将结果回传模型。工具只接受空对象；尾随非空文本、第二个 JSON 对象、未知工具和多余参数均拒绝。最大轮数由配置限制。

此接口不支持业务问路或多轮会话，目前没有 `/api/assistant/query`。模型配置缺失 503、工具非法 422、上游/协议异常 502、超时 504；模型失败不影响普通查询。

## 错误与健康检查

错误通常含 `requestId/code/message/retryable`，部分模块附带详情。前端显示中文提示，排查时保留 `requestId`。具体错误码以异常处理器为准。

| HTTP | 场景 |
|---|---|
| 400 | 缺失字段、非法范围、偏好或格式 |
| 404 | 地点 ID 不存在 |
| 422 | 地点/接驳不可用、出发时间不适用、模型工具非法 |
| 502 | 地图或模型上游故障、协议解析失败 |
| 503 | 模型未配置；数据库未就绪的 readiness |
| 504 | 地图或模型超时 |

地图 Key 缺失按城市供应方错误处理，不能回退到静态示意路线。

- `/actuator/health/liveness`：进程存活，不包含数据库。
- `/actuator/health/readiness`：包含数据库检查；数据库不可用时 503。

## 数据库边界

唯一迁移入口为 `backend/src/main/resources/db/migration/`。`conversation` 保存匿名会话条件、确认状态和版本；`conversation_message` 保存有序消息；`journey_query` 保存输入、结果和故障快照。

后续实现以 owner token 哈希隔离会话，以 `state_version` 防止旧请求覆盖新条件，按会话分配消息序号，数据库时间列统一 UTC。迁移 SQL 已提供，Repository、事务和持久化调用尚未实现。字段和约束以 SQL 为准。
