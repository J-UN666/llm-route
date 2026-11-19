# 交大智行

西南交通大学犀浦校区到成都市内的公交、地铁出行查询。Java 后端为微信小程序的普通表单和自然语言问路提供同一套路由服务，会话存入 MySQL。

目前支持从图书馆、天佑斋 1 号楼经东门出校，目的地有天府广场、春熙路、成都东客站、成都站和宽窄巷子。东门按全天开放计算，校内步行使用 OSM 路网估算。高德 POI 用于地点展示，西门出校接驳尚未启用。[接口](docs/api.md) · [数据来源](source-data/校园数据/README.md) · [部署与发布](docs/release.md)

## 克隆仓库

```bash
git clone https://github.com/J-UN666/llm-route.git
cd llm-route
```

以下命令从仓库根目录执行。源码构建使用 JDK 21.0.13、Node.js 24.22.2、npm 11.19.1 和 Python 3.11+，首次构建需要联网。Maven Wrapper 已随仓库提供。

## 运行离线演示

macOS / Linux 需要另备 MySQL 8.4.13 的 `mysqld`。若系统找不到它，可将其绝对路径设为 `DEMO_MYSQLD`。脚本自行启动独立的本机演示数据库并执行迁移。

```bash
python3 tools/run_demo.py
```

运行后端口为 `127.0.0.1:8080`，在微信开发者工具导入 `release/demo/mp-weixin`。首次运行生成制品，之后复用已有目录或自动解包 `release/demo.tar.gz`。更新源码后若要重新构建，可输出到新目录，见 [部署与发布](docs/release.md#制品)。

普通表单可查「图书馆 → 天府广场」。在聊天中发送「去天府广场，少走路」，补充「从天佑斋出发」，选择 1 号楼，再发送「改去春熙路」。演示使用固定城市线路和本地示例模型，无需密钥。另开终端可执行：

```bash
python3 tools/smoke_delivery.py
```

按 Ctrl+C 停止，重启可恢复已有会话。若从 GitHub Actions 下载了演示包，解包后可直接运行 `python3 demo/run_demo.py`，并导入 `demo/mp-weixin`。预构建包仍需要本机 JDK、Python 和 `mysqld`。

## 接入真实高德与模型

### 1. 配置后端

```bash
cp backend/application-local.example.properties backend/application-local.properties
```

在本地配置中设置 `amap.web-service-key`，填入高德 **Web 服务 Key**。普通表单只需要高德 Key。完整聊天还需要支持 Chat Completions 工具调用的模型与 MySQL 8.4，填写下面这些字段：

| 配置 | 用途 |
| --- | --- |
| `assistant.llm.base-url`、`assistant.llm.api-key`、`assistant.llm.model` | 模型基址、密钥和模型名 |
| `spring.datasource.url`、`spring.datasource.username`、`spring.datasource.password` | MySQL JDBC 地址、运行账号和密码 |

本地配置已被 Git 忽略。密钥只放在后端。模板也支持从环境变量读取。

要在小程序界面演示个人 Key，在本机启动受信 HTTPS 后端，重新构建并导入，再打开「设置 → 服务配置 → 个人配置」。[本机操作步骤](docs/release.md#本机演示个人-key)

### 2. 为聊天初始化数据库

启动 MySQL，由管理员创建独立的 `llmroute` 库。建库及账号授权示例见 [数据库部署](docs/release.md#数据库)。通过当前终端的环境变量设置 `MIGRATION_DB_URL`、`MIGRATION_DB_USER`、`MIGRATION_DB_PASSWORD`，再执行：

```bash
./backend/mvnw -f backend/pom.xml flyway:migrate
```

迁移账号负责执行 V1、V2。运行账号只需业务表的读写权限。只体验真实普通路线时可以跳过数据库和模型配置。

### 3. 启动后端并构建小程序

```bash
./backend/mvnw -f backend/pom.xml spring-boot:run
```

另开终端执行：

```bash
npm --prefix frontend ci
npm --prefix frontend run build:mp-weixin
```

在微信开发者工具中导入 `frontend/dist/build/mp-weixin`，使用自己的 AppID 或开发工具支持的测试方式。默认 API 地址为 `http://127.0.0.1:8080`。三个健康端点分别为 `/actuator/health/liveness`、`/actuator/health/journeys`、`/actuator/health/chat`。真实冒烟通过 `python3 tools/smoke_delivery.py --live` 运行，只查普通表单时加 `--form-only`。

Windows PowerShell 用 `Copy-Item` 复制配置，将 Maven 命令中的 Wrapper 替换为 `.\backend\mvnw.cmd`。微信模拟器中使用相同的导入目录。

## 真机和测试

局域网调试时让手机和电脑连接同一网络，后端配置 `server.address=0.0.0.0`，复制 `frontend/.env.example` 为 `frontend/.env.local`，把 `VITE_API_BASE_URL` 改为电脑的内网地址并重新构建。核对防火墙和微信调试设置。体验版需要团队的 AppID、受信 HTTPS、微信合法请求域名和体验成员权限。

```bash
./backend/mvnw -f backend/pom.xml verify
npm --prefix frontend run type-check
node tools/test.js
```

`node tools/serve.js` 可启动独立网页原型，打开 `http://127.0.0.1:5178`。原型使用本地规则和静态数据，可选城市底图使用单独的浏览器地图配置。

## 下一步

1. 在测试服务器部署后端和独立库，配置受信 HTTPS、AppID、合法请求域名与体验权限。
2. 由另一位同学从干净目录复现，再在真机跑普通查询、候选确认、改终点、改偏好和重启恢复，记录版本与请求 ID。
3. 按题目整理 Claude／OpenClaw 开发证据、OPC 分工和运营复盘，落实全员参赛。校园起点和西门接驳按数据核实结果逐步扩展。
