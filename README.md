# 交大智行

从西南交通大学犀浦校区出发，经校门接入成都公交、地铁的出行查询项目。主程序为 Java 后端和微信小程序，`web/` 保留独立的规则演示原型。

## 当前进度

截至 2026-11-19：

| 模块 | 状态 |
|---|---|
| 地点搜索 | 已实现名称、别名搜索和可用性区分，共 220 个候选；候选不等于可导航地点 |
| 校园接驳 | 可用起点为图书馆、天佑斋1号楼，均经东门出校；距离、耗时采用地图估算，通行依据为团队的演示范围确认 |
| 城市公共交通 | 已接入真实高德服务，支持推荐、少步行、少换乘；合并校园段，去重后最多返回 3 条方案 |
| 小程序 | 普通表单和自然语言问路均可显示方案卡片、分段和 Canvas 路线，聊天支持会话恢复和重试 |
| LLM | 已接入地点搜索、校园接驳、行程工具和聊天问路。支持补条件、选候选、改终点和改偏好 |
| 数据库 | 匿名会话、消息、行程快照和幂等重试保存在 MySQL。地点和接驳从本地 JSON 读取 |
| 交付 | 真机、正式部署、演示视频、OPC 报告和参赛材料待完成 |

应用层有坐标的城市目的地为天府广场、春熙路、成都东客站、成都站和宽窄巷子。已有真实高德验收覆盖图书馆→前三处、天佑斋1号楼→前两处，共五组查询；并非所有地点组合均已实测。历史校车和施工通知仅作参考，不参与实时算路。

## 克隆后运行

准备 JDK 21、Node.js 24 和微信开发者工具。Node 精确版本见 `frontend/.nvmrc`，依赖版本由 `package-lock.json` 锁定；Maven Wrapper 已包含，不必单独安装 Maven。

```bash
git clone https://github.com/J-UN666/llm-route.git
cd llm-route
```

后续命令均在仓库根目录执行。首次构建需要联网下载 Maven 和 npm 依赖。

### 1. 后端

macOS / Linux：

```bash
cp backend/application-local.example.properties backend/application-local.properties
```

Windows PowerShell：

```powershell
Copy-Item backend/application-local.example.properties backend/application-local.properties
```

编辑 `backend/application-local.properties`，将自己的高德 **Web 服务 Key** 填入 `amap.web-service-key`。这不是给浏览器地图使用的 JavaScript API Key。LLM 配置可暂时留空，普通表单查询可以独立运行。本地配置被 Git 忽略，不会随仓库分享。

启动：

```bash
./backend/mvnw -f backend/pom.xml spring-boot:run
```

Windows 使用：

```powershell
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

打开以下地址检查：

- `http://127.0.0.1:8080/actuator/health/liveness`：应返回 `UP`。
- `http://127.0.0.1:8080/api/places?scope=CAMPUS`：应返回地点列表。

缺少高德 Key 时服务和地点搜索仍可启动，但无法获得真实城市路线。没有配置数据库时 readiness 可能返回 503；当前普通查询不依赖数据库，检查启动应使用 liveness。

### 2. 小程序

另开一个终端，仍进入仓库根目录：

```bash
npm --prefix frontend ci
```

复制前端配置，macOS / Linux：

```bash
cp frontend/.env.example frontend/.env.local
```

Windows PowerShell：

```powershell
Copy-Item frontend/.env.example frontend/.env.local
```

同一台电脑的开发者工具使用默认 `VITE_API_BASE_URL=http://127.0.0.1:8080`。构建：

```bash
npm --prefix frontend run build:mp-weixin
```

在微信开发者工具中导入 `frontend/dist/build/mp-weixin`，使用自己的小程序 AppID 或工具允许的测试方式。修改 API 地址后需要重新构建；持续开发可运行 `npm --prefix frontend run dev:mp-weixin`，导入 `frontend/dist/dev/mp-weixin`。

首次演示可选图书馆→天府广场，点击“现在”填写未来出发时间，再查询路线。起点和终点都要从候选列表确认。

### 3. 手机调试

手机与后端电脑连接同一局域网：

1. 后端本地配置改为 `server.address=0.0.0.0`，重启。
2. 前端 `.env.local` 改为电脑的局域网地址，例如 `VITE_API_BASE_URL=http://192.168.1.100:8080`，重新构建。
3. 确认防火墙允许该端口，并按微信开发者工具的调试要求配置网络访问。手机上的 `127.0.0.1` 指向手机自身。

正式发布还需要 HTTPS 服务、微信合法请求域名及实际真机验收；本仓库当前没有完成这一步。

## 可选配置

### 模型

在后端本地配置中设置 `assistant.llm.base-url`、`assistant.llm.api-key`、`assistant.llm.model`，使用支持 Chat Completions 工具调用的服务。基址以 `/v1` 为例，具体取值以提供方为准。例如 DeepSeek 可使用 `https://api.deepseek.com` 与 `deepseek-flash`。聊天入口位于小程序主页，也可以调用 `POST /api/assistant/query`。见 [接口说明](docs/api.md)和 [DeepSeek API](https://api-docs.deepseek.com/api/create-chat-completion/)。

也可以通过环境变量配置 `AMAP_WEB_SERVICE_KEY`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`DB_URL`、`DB_USER`、`DB_PASSWORD`、`SERVER_ADDRESS`、`SERVER_PORT`、`CAMPUS_DATA_ROOT`。模板保留环境变量占位符；也可在本地文件中直接填写。

### MySQL

地点、普通行程和单轮问路可独立运行。小程序聊天使用 MySQL 8.4 保存会话，需先迁移到 V2。迁移入口为 `backend/src/main/resources/db/migration/`。四张业务表为 `conversation`、`conversation_message`、`journey_query`、`conversation_turn`。服务启动不会自动建表。

先由数据库管理员创建 `llmroute`，给运行服务的账号授予四张业务表的 `SELECT/INSERT/UPDATE` 权限。运行账号配置到本地文件，迁移账号通过环境变量 `MIGRATION_DB_URL`、`MIGRATION_DB_USER`、`MIGRATION_DB_PASSWORD` 提供，然后执行：

```bash
./backend/mvnw -f backend/pom.xml flyway:migrate
```

Windows 将命令中的 Wrapper 换成 `.\backend\mvnw.cmd`。迁移环境变量不会从 `application-local.properties` 读取。不要将别人的 localhost、数据库账号或密钥直接复制到自己的设备。

### 会话保留

会话、消息和行程快照在最后一次提交或结束后保留 30 天。到期记录在 API 中返回找不到，由数据库维护账号定期分批清理。下面的语句每次处理至多 500 个到期会话，并在同一事务内按外键顺序删除。清理账号需要四张业务表的查询和删除权限以及创建临时表权限。

```sql
CREATE TEMPORARY TABLE expired_conversations (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY
);
START TRANSACTION;
INSERT INTO expired_conversations
    SELECT conversation_id FROM conversation
    WHERE updated_at_utc < UTC_TIMESTAMP(3) - INTERVAL 30 DAY
    ORDER BY updated_at_utc, conversation_id LIMIT 500 FOR UPDATE;
DELETE t FROM conversation_turn t JOIN expired_conversations e USING (conversation_id);
DELETE q FROM journey_query q JOIN expired_conversations e USING (conversation_id);
DELETE m FROM conversation_message m JOIN expired_conversations e USING (conversation_id);
DELETE c FROM conversation c JOIN expired_conversations e USING (conversation_id);
COMMIT;
DROP TEMPORARY TABLE expired_conversations;
```

### 网页原型

```bash
node tools/serve.js
```

打开 `http://127.0.0.1:5178`；Windows 也可双击 `启动预览.bat`。此页使用本地规则和静态参考数据，与小程序和 Java API 独立。可选的高德城市地图另需浏览器 JS API 配置，未配置时不影响本地校园原型。

## 检查

```bash
./backend/mvnw -f backend/pom.xml verify
npm --prefix frontend run type-check
npm --prefix frontend run build:mp-weixin
node tools/test.js
```

## 目录

- `backend/`：地点、校园接驳、行程组合、模型问路和匿名会话。
- `frontend/`：uni-app / Vue 3 微信小程序。
- `web/`：独立网页原型及生成的地图数据；后端也会读取其中的校门实体。
- `source-data/校园数据/`：公开来源数据、地图与字段说明，保留来源候选状态。
- `tools/`：数据生成脚本、原型预览与断言检查。
- `docs/api.md`：当前接口与字段约定。

应用层核实结论位于 `backend/src/main/resources/data/`，不覆盖来源数据来改变其核实状态。OSM 数据保留 © OpenStreetMap contributors 署名；其他来源和使用限制见 [数据说明](source-data/校园数据/README.md)。
