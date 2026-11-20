# 交大智行

从西南交通大学犀浦校区到成都市内的公交、地铁出行规划。网页和微信小程序共用 Java 后端，支持城市地点搜索、多轮问路和分段路线地图。当前可从图书馆、天佑斋 1 号楼出发，六座校门参与路线比较。东门接驳已确认，其余校门按试用规则纳入。

[接口](docs/api.md) · [数据来源](source-data/校园数据/README.md) · [部署与发布](docs/release.md)

## 克隆仓库

```bash
git clone https://github.com/J-UN666/llm-route.git
cd llm-route
```

除特别注明，下列命令都在仓库根目录执行。首次构建需要联网下载依赖。

## Windows 本机运行真实 API

安装 **JDK 21** 并配置 `JAVA_HOME`，确认 `java -version` 可用。运行小程序另需 **Node.js 24** 和微信开发者工具。自然语言问路另需 MySQL 8.4 和模型 API Key。普通路线和网页端无需为高德申请 JS API Key。 [JDK 安装](https://docs.oracle.com/en/java/javase/21/install/installation-jdk-microsoft-windows-platforms.html) · [Node.js 24](https://nodejs.org/en/download/archive/v24.22.2) · [微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html) · [MySQL 8.4 安装](https://dev.mysql.com/doc/refman/8.4/en/windows-installation.html)

### 1. 配置后端

在 PowerShell 复制并打开本机配置文件，只需创建一次。

```powershell
Copy-Item .\backend\application-local.example.properties .\backend\application-local.properties
notepad .\backend\application-local.properties
```

填入自己的配置。只查普通路线时，填第一项即可。

```properties
amap.web-service-key=你的高德Web服务Key
assistant.llm.base-url=https://api.deepseek.com
assistant.llm.api-key=你的DeepSeek_API_Key
assistant.llm.model=deepseek-flash
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/llmroute?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true
spring.datasource.username=llmroute_app
spring.datasource.password=你的运行账号密码
```

`application-local.properties` 已被 Git 忽略。真实 Key 只保存在这个本机文件或服务端环境里。上面的 JDBC 地址只用于本机回环连接。 [DeepSeek API 配置](https://api-docs.deepseek.com/)

### 2. 初始化聊天数据库

只查普通路线可跳过本节。启动 MySQL 8.4 服务，用管理员账号登录 MySQL。在默认 MSI 安装位置，可以在 PowerShell 执行下面的命令，并按提示输入管理员密码。

```powershell
& "$env:ProgramFiles\MySQL\MySQL Server 8.4\bin\mysql.exe" -u root -p
```

在 MySQL 提示符下，执行 [建库和授权 SQL](docs/release.md#数据库)，然后输入 `exit` 回到 PowerShell。运行账号的密码与上面的配置一致。在 PowerShell 用迁移账号初始化库。

```powershell
$migration = Get-Credential -UserName 'llmroute_migration' -Message '输入迁移账号密码'
$env:MIGRATION_DB_URL = 'jdbc:mysql://127.0.0.1:3306/llmroute?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true'
$env:MIGRATION_DB_USER = $migration.UserName
$env:MIGRATION_DB_PASSWORD = $migration.GetNetworkCredential().Password
.\backend\mvnw.cmd -f backend/pom.xml flyway:migrate
Remove-Item Env:MIGRATION_DB_PASSWORD
Remove-Variable migration
```

首次运行和有新数据库迁移时执行。平时启动聊天前让 MySQL 服务保持运行。

### 3. 启动后端和网页

```powershell
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

保持这个窗口运行。服务启动后打开 [网页](http://127.0.0.1:8080/web/index.html)，也可双击 `启动预览.bat` 打开同一页面。网页使用「默认服务」读取上面的本机 Key，能搜索成都博物馆并查询真实公交地铁路线。配置模型与数据库后可直接在网页上多轮问路。结束时在后端窗口按 `Ctrl+C`。

### 4. 构建并打开小程序

保持同一个后端运行。另开一个 PowerShell 窗口，进入仓库根目录后执行。

```powershell
$env:VITE_API_BASE_URL = 'http://127.0.0.1:8080'
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build:mp-weixin
```

在微信开发者工具导入 `frontend/dist/build/mp-weixin`，使用自己的 AppID 或工具提供的测试号，选择「不使用云服务」。在本机模拟器调试 HTTP 时，在「详情 → 本地设置」允许本地调试请求，再点「编译」。小程序同样使用「默认服务」，和网页共用一个后端。

### 在应用内输入个人 Key

在网页或小程序的服务设置里输入 Key 时，后端须使用受信 HTTPS。Windows [安装 mkcert](https://github.com/FiloSottile/mkcert#windows) 后，首次运行以下命令生成并信任本机证书。

```powershell
mkcert -install
New-Item -ItemType Directory -Force .local-https | Out-Null
mkcert -pkcs12 -p12-file .local-https/windows-localhost.p12 127.0.0.1 localhost
```

停止原来的 HTTP 后端，在仓库根目录启动 HTTPS 后端。这里的 `changeit` 是 mkcert 生成的本机证书文件密码。要使用多轮问路，仍需前面的 MySQL 初始化。

```powershell
$env:SERVER_PORT = '8443'
$env:SERVER_SSL_ENABLED = 'true'
$env:SERVER_SSL_KEY_STORE = 'file:./.local-https/windows-localhost.p12'
$env:SERVER_SSL_KEY_STORE_TYPE = 'PKCS12'
$env:SERVER_SSL_KEY_STORE_PASSWORD = 'changeit'
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

访问 [HTTPS 网页](https://127.0.0.1:8443/web/index.html)，在「服务设置」验证高德和 DeepSeek Key。小程序在另一个 PowerShell 窗口把 `VITE_API_BASE_URL` 改成 `https://127.0.0.1:8443` 后重新构建，再在「设置 → 服务配置 → 个人配置」验证自己的 Key。

## macOS / Linux

- **真实 API 默认配置**沿用上面的配置文件和建库步骤，终端使用 `./backend/mvnw -f backend/pom.xml flyway:migrate` 初始化数据库，再运行 `./backend/mvnw -f backend/pom.xml spring-boot:run`。访问 [网页](http://127.0.0.1:8080/web/index.html)。构建小程序使用 `npm --prefix frontend ci` 和 `npm --prefix frontend run build:mp-weixin`。
- **macOS 本机 HTTPS**可运行 `python3 tools/run_personal_demo.py`，脚本启动独立演示数据库并构建小程序。加 `--web` 可只运行网页。首次设置证书及依赖见 [本机演示](docs/release.md#本机演示个人-key)。
- **离线演示**可用 `python3 tools/run_demo.py`，需要 MySQL 8.4.13 的 `mysqld`，可用 `DEMO_MYSQLD` 指定路径。它启动独立数据库并使用固定的示例路线和模型。小程序产物在 `release/demo/mp-weixin`。

## 测试与发布

```bash
./backend/mvnw -f backend/pom.xml verify
npm --prefix frontend run type-check
node tools/test.js
```

普通路线就绪检查用 `/actuator/health/journeys`，默认聊天配置检查用 `/actuator/health/chat`。使用默认服务进行真实 API 冒烟时，运行 `python3 tools/smoke_delivery.py --live`。局域网真机调试和体验发布见 [部署与发布](docs/release.md#制品)。
