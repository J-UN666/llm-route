# 接口说明

基址默认 `http://127.0.0.1:8080`。距离用米、耗时用秒、金额用元；时间为带偏移的 ISO-8601，业务按 `Asia/Shanghai` 解释。未知数字保留 `null`。完整类型以 Java record 和 `frontend/src/types/journey.ts` 为准。

## 地点与校园接驳

- `GET /api/places?keyword=图书馆&scope=CAMPUS` → `{ "items": [...] }`。
- `GET /api/places/{id}` → 单个地点；未知 ID 返回 404。
- `GET /api/campus/connectors?originId=...&departureTime=...` → `{originId,dataVersion,connectors}`。

`scope` 支持 `CAMPUS/GATE/CITY`，省略时搜索本地地点库的所有类别。空关键词列出相应范围，未命中返回空数组。查询参数中的 `+08:00` 应编码为 `%2B08:00`。

城市搜索优先匹配可算路的本地正式名或别名，其余至少两字的关键词通过高德搜索成都市内的地点，保留名称或地址匹配的结果。在线候选使用 `amap:<POI ID>`，同名地点按 ID 区分。普通表单和问路对话都可以将该 ID 作为 `destinationId`。个人服务模式的城市搜索、在线地点详情和行程带 `X-Service-Token`。离线演示使用本地地点库。搜索成功且无匹配时返回空数组，未配置 Key、超时、限额和上游失败分别返回 `PLACE_SEARCH_*` 错误。

地点字段包括 `id/name/kind/category/address/aliases/routingReady/unavailableReason/verificationStatus/displayPosition/routingPosition/dataVersion/sourceRefs`。位置包含 `pointId/longitude/latitude/coordinateSystem/positionRole`。高德位置使用 `GCJ-02`。在线终点优先使用高德入口（`AMAP_POI_ENTRANCE`），缺入口时使用有效的地点标注点（`AMAP_POI_LOCATION`）。东门的 `amap_walk_endpoint` 是高德返回的步行末端，出校衔接使用 `routingPosition` 中的 WGS84 出校方向点。是否可规划路线以 `routingReady` 和具体接驳为准。

接驳保留起点、校门、出校点、轨迹、坐标系、米/秒指标、估算方法与核实依据。图书馆、天佑斋 1 号楼各有六座校门可选。`APPLICATION_VERIFIED` 为已确认接驳，`PROJECT_ASSUMED_OPEN` 为按项目规则启用的试用接驳。试用校门在行程 `warnings` 中提示以现场通行为准。`gateOpenHoursStatus=PROJECT_ASSUMED_UNRESTRICTED` 表示项目按无门禁时段计算。无可用接驳返回 422 `NO_APPROVED_CONNECTOR` 并附原因。

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

- 身份与点位包括 `planId/originId/destinationId/gateId/gateExitPoint/destinationPoint`。终点标记使用 `destinationPoint`，旧行程可用最后一段的 `toPoint`。
- 时间：`departureTime/cityDepartureTime/providerDepartureTime/roundingWaitSeconds/arrivalTime/totalDurationSeconds`。
- 汇总：`totalWalkingMeters/transferCount/costYuan`。
- 分段与来源：`segments/sources/queriedAt/campusDataVersion`。

分段字段为 `sequence/mode/description/fromPoint/toPoint/lineId/lineName/direction/boardingStation/alightingStation/distanceMeters/durationSeconds/geometry/coordinateSystem`。轨迹按经度、纬度排列。校园段为 WGS84，城市段通常为 GCJ-02；前端统一转换后绘制。轨迹缺失时保留文字，不画猜测路线。

组合口径：校园出发时刻 + 接驳耗时 = 城市段出发时刻；供应方需要分钟精度时向上取整，等待计入总耗时。城市段已包含的到站步行只累计一次。校园段止于所选出校点，城市段从同一点开始。未知费用、步行距离保留 `null`。

从东门出校，高德首段步行轨迹命中已复核的接入点时，前面补上可通行的高德步行折线，首段 `fromPoint` 与 `gateExitPoint` 相同，`sources` 记录 `AMAP_WALKING_EAST_GATE_2026_11_19`。出发时刻、距离和耗时沿用原公交规划口径。

`TransitProvider` 隔离高德 DTO、坐标转换、超时与错误解析。后续模型工具应复用 `JourneyService`，不要另写组合算法。

## 模型工具自检

`POST /api/assistant/tool-check`

```json
{"message":"请调用工具查看当前服务信息"}
```

返回 `requestId/model/toolCalls/answer`。模型请求 `get_service_info`，Java 校验工具名和严格 JSON 参数、执行工具，再按 callId 将结果回传模型。工具只接受空对象；尾随非空文本、第二个 JSON 对象、未知工具和多余参数均拒绝。最大轮数由配置限制。

自检独立于问路和会话。模型配置缺失 503、工具非法 422、上游/协议异常 502、超时 504；模型失败不影响普通查询。

## 自然语言问路

`POST /api/assistant/query` 接受以下两种请求。

- 单轮：`{"message":"从图书馆去天府广场，少走路"}`。单轮无需会话数据库，不保存历史。
- 会话：加 `conversationId`、`clientRequestId`（UUID v4）、`stateVersion`（非负整数），并在 `X-Conversation-Token` 请求头传 owner token。输入为非空 `message`（最多 500 字符）或 `confirmation: {"confirmationId":"…","placeId":"…"}`，两者选一。点击确认需要已创建的会话。候选 ID 只对当前待确认项有效，有效期 10 分钟。

问路响应固定包含 `schemaVersion: 1`、`requestId`、`status`、`answer`、`conditions`、`clarifications`、`toolCalls`、`journey`、`journeyRequestId`、`error`、`conversationId`、`stateVersion`。后两个会话字段在单轮时为 null。`journey` 是普通行程接口的原始结果，包含原始排序、空值与轨迹。工具轨迹只保留调用 ID、工具、结果状态、行程请求 ID 和用于核验的稳定 ID、时间、偏好。转发给模型的结果省略轨迹。

- `SUCCEEDED` / `NO_ROUTE`：行程已查，后者的 `plans` 为空。
- `NEEDS_CLARIFICATION`：`clarifications` 列出 `field`、`reason`、提示、候选和确认编号/有效期。地点原因包括 `MISSING_ORIGIN`、`MISSING_DESTINATION`、`LOCATION_UNKNOWN`、`NO_MATCH`、`AMBIGUOUS`、`PLACE_UNAVAILABLE`。候选带稳定 ID、名称、类别、算路可用性和原因。确认过期返回 409，候选越界或不可用返回 422。
- `JOURNEY_FAILED`：`error` 保留行程请求 ID、代码、提示和可重试标志。高德未配置为 `PROVIDER_NOT_CONFIGURED`，高德超时为 `PROVIDER_TIMEOUT`。此类结果作为一次已接受的业务答复保存。
- 模型未配置、超时、协议/轮次失败和数据库不可用按 HTTP 错误返回 `requestId/code/message/retryable`，不接受该轮。整体问路执行预算 55 秒，工具轮次默认 3 轮，最多 8 次调用，每轮用户提交最多一次行程执行。小程序超时为 60 秒。

未指定时间时按北京时间下一整分钟出发，未指定偏好时按推荐。答复展示实际出发时间和偏好。会话保留已确认的四项条件，仅覆盖本轮明确修改项。已保存出发时间过期时先要求新时间。新地点经过服务端查找与可用性检查，同名实体保留独立 ID。图书馆等已登记的实体别名优先匹配算路地点，官方组织目录条目仍可在地点 API 中按原 ID 查找。无法获取用户当前位置时要求指定地点。

## 匿名会话

- `POST /api/assistant/conversations` → HTTP 201，返回 `conversationId/ownerToken/stateVersion`。owner token 只在创建时返回。
- `GET /api/assistant/conversations/{id}` → 当前版本、状态、条件、待确认项、最近 40 条有序用户/助手消息及 `latest` 答复。消息带提交时间和历史行程请求 ID。
- `POST /api/assistant/conversations/{id}/close` → HTTP 204，幂等结束会话。
- `GET /api/assistant/conversations/{id}/journeys/{requestId}` → 此会话已接受的历史行程原始快照。

创建以外的会话请求均发送 `X-Conversation-Token`。凭据错误与会话不存在均返回 404。凭据在数据库中只保存 SHA-256。相同 `clientRequestId` 和相同请求体返回已接受的原答复，省略重复写入；重复标识换请求体或请求版本已过时返回 409。客户端先恢复最新状态，再发起新提交。新字段按 `schemaVersion=1` 向后兼容地增加，客户端按已知状态渲染并可显示通用错误；破坏性改动提升契约版本。

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
- `/actuator/health/journeys`：检查校园数据和地图配置，可独立于 MySQL 和模型验收普通查询。
- `/actuator/health/chat`：检查校园数据、地图与模型配置，以及 V2 会话表。上游连通性另用冒烟命令验证。

显式启用 Spring `demo` profile 时，行程响应中的 `provider` 为 `DEMO`，路线来源标记「示例路线」。演示城市段仅覆盖东门至天府广场和春熙路。实际服务部署使用独立运行配置。

## 个人服务配置

以下端点要求 HTTPS。新建会话返回 `ownerToken` 和 `expiresAt`，随后通过 `X-Service-Token` 授权。状态只返回到期时间、高德与模型是否验证通过、模型名。

| 方法与端点 | 用途 |
|---|---|
| `GET /api/service-config/options` | 获取支持的提供方和模型列表 |
| `POST /api/service-config/sessions` | 创建匿名配置会话（201） |
| `GET /api/service-config` | 查询当前服务状态 |
| `PUT /api/service-config/amap` | 验证并更新高德 Web 服务 `key` |
| `PUT /api/service-config/model` | 验证并更新 `key` 和 `model` |
| `DELETE /api/service-config/amap` 或 `/model` | 撤销对应 Key |
| `DELETE /api/service-config` | 撤销整个配置会话（204） |

`POST /api/journeys` 和 `POST /api/assistant/query` 可带同一个 `X-Service-Token`。聊天会话仍由原来的 `X-Conversation-Token` 授权。省略服务配置请求头会使用默认托管服务。个人配置失效时返回 `SERVICE_CONFIG_EXPIRED`，应重新验证或显式切换到默认服务。

## 数据库边界

唯一迁移入口为 `backend/src/main/resources/db/migration/`。`conversation` 保存匿名会话条件、确认状态和版本；`conversation_message` 保存有序消息；`journey_query` 保存输入、结果和故障快照。V2 新增的 `conversation_turn` 保存请求指纹、重试标识与关联的答复。

模型和高德在事务外执行。提交时以 `state_version` 条件更新，并在同一短事务内提交状态、消息和路线快照。过时结果丢弃，最新界面只显示已接受版本。会话按 30 天未更新到期。查询接口不再返回到期会话。管理员按 README 的清理步骤定期删除到期会话与关联消息、重试记录及快照。普通行程独立于会话数据库。
