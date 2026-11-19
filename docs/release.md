# 交付与部署

本机运行见 [README](../README.md)。发布包中的 `release.json` 记录提交、数据版本和文件 SHA-256。

## 制品

```bash
python3 tools/package_release.py demo --output release/demo-v2
python3 tools/package_release.py backend --output release/backend-v1
python3 tools/package_release.py lan --api-base http://192.168.1.100:8080 --output release/lan-v1
python3 tools/package_release.py wechat --api-base https://api.example.com --app-id "$WECHAT_APP_ID" --output release/wechat-v1
```

构建新版离线包后用 `python3 tools/run_demo.py --bundle release/demo-v2` 启动。只拿到压缩包时先解包，再运行包内的 `run_demo.py`。用 `--state` 指定权限为 0700 的数据库状态目录。要更改演示端口，构建时指定相同端口的 `--api-base`，使小程序和后端地址一致。

局域网包填写后端电脑的 IPv4。体验包填写团队的 AppID 和公开 HTTPS 域名。小程序后台登记同一合法请求域名，由有权限的开发者上传体验版，并给测试成员开通权限。

小程序可在「设置 → 服务配置」选择默认托管或个人服务。个人 Key 需要 HTTPS，匿名配置会话创建后有效 30 分钟，服务端重启后失效。只验证高德 Web 服务 Key 即可查普通路线。模型由应用列出的 DeepSeek 官方模型中选择。应用关闭后需要重新验证。局域网 HTTP 和离线演示继续使用各自的默认服务。

体验环境使用随包 Nginx 模板在 HTTPS 入口终止 TLS，并由本机反代设置 `X-Forwarded-Proto`。Java 服务监听回环地址。个人 Key 与网页原型的高德 JS API 配置各自独立。

## 本机演示个人 Key

以下命令在 macOS 的仓库根目录执行，用微信开发者工具模拟器访问本机 HTTPS。已安装 `mkcert` 时可跳过安装命令。`mkcert -install` 会在本机安装开发 CA，证书文件保存在已忽略的 `.local-https/`。

```bash
brew install mkcert
mkcert -install
mkdir -p .local-https
mkcert -cert-file .local-https/localhost.pem -key-file .local-https/localhost-key.pem localhost 127.0.0.1
openssl pkcs12 -export -in .local-https/localhost.pem -inkey .local-https/localhost-key.pem \
  -out .local-https/localhost.p12 -passout pass:local-demo
```

启动后端。普通路线可直接在应用内填高德 Key。演示聊天前按下节初始化 MySQL，并为运行服务提供数据库连接。

```bash
SERVER_PORT=8443 SERVER_ADDRESS=127.0.0.1 SERVER_SSL_ENABLED=true \
SERVER_SSL_KEY_STORE="file:$PWD/.local-https/localhost.p12" \
SERVER_SSL_KEY_STORE_TYPE=PKCS12 SERVER_SSL_KEY_STORE_PASSWORD=local-demo \
./backend/mvnw -f backend/pom.xml spring-boot:run
```

另开终端构建并在微信开发者工具导入 `frontend/dist/build/mp-weixin`，使用测试号和「不使用云服务」。进入首页「设置 → 服务配置 → 个人配置」，填高德 Web 服务 Key 并点「验证并启用」，返回首页查询「图书馆 → 天府广场」。如果要问路，再选择支持的 DeepSeek 模型并验证模型 Key。

```bash
npm --prefix frontend ci
VITE_DEMO_MODE=0 VITE_API_BASE_URL=https://127.0.0.1:8443 npm --prefix frontend run build:mp-weixin
```

## 数据库

由 MySQL 管理员创建专用库和两个账号。示例使用本机 TCP，远端数据库应按实际来源地址调整账号的主机部分。更换下面两处密码后再执行。

```sql
CREATE DATABASE llmroute CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE USER 'llmroute_migration'@'127.0.0.1' IDENTIFIED BY '替换为迁移账号密码';
CREATE USER 'llmroute_app'@'127.0.0.1' IDENTIFIED BY '替换为运行账号密码';
GRANT ALL ON llmroute.* TO 'llmroute_migration'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON llmroute.* TO 'llmroute_app'@'127.0.0.1';
```

通过环境变量 `MIGRATION_DB_URL`、`MIGRATION_DB_USER` 和 `MIGRATION_DB_PASSWORD` 指定迁移连接。JDBC 地址示例为 `jdbc:mysql://127.0.0.1:3306/llmroute?connectionTimeZone=UTC`。源码用 `./backend/mvnw -f backend/pom.xml flyway:migrate`，发布包用 `java -jar backend.jar --migrate`。运行服务时使用 `llmroute_app`。生产和测试环境分别使用独立库。

## 测试部署

1. 把后端包解压到 `/srv/zhixing/releases/<版本>`。备份测试库并验证备份可恢复，再迁移到 V2。
2. 安装包内的 `deploy/runtime.env.example` 为 `/etc/zhixing/runtime.env`，权限 0600。填入运行账号、高德 Web 服务 Key 和模型配置。
3. 将 `/srv/zhixing/current` 指向该制品。用 `zhixing` 服务账号运行包内的 systemd 模板，执行 `systemctl daemon-reload` 和 `systemctl enable --now zhixing`。
4. 配置 Nginx 模板中的域名和受信证书。执行 `nginx -t` 后重载，依次访问 `/actuator/health/liveness`、`/actuator/health/journeys` 和 `/actuator/health/chat`。
5. 运行 `python3 smoke_delivery.py https://实际域名 --live`。普通行程可用 `--live --form-only` 单独验证。真机复测时记录构建版本和请求 ID。

探针检查配置和数据库结构，上游服务通过实际请求验证。更新失败时将 `current` 切回上一版制品并重启。V2 只新增会话表，回滚到旧后端时可保留该表。涉及不兼容迁移时在独立库验证备份恢复，再切换连接。

会话、消息和行程快照保留 30 天。维护账号按批在同一事务中清理，依次删除 `conversation_turn`、`journey_query`、`conversation_message` 和 `conversation`。

## 数据验收

`2026-11-19.2` 已核对图书馆、天佑斋 1 号楼经东门的接驳。两条高德步行复核分别为 894 米和 1,830 米，普通行程各查得 3 条城市段方案（`jr-534c98a2b8a34d06a077`、`jr-bbbee25ea61c47c59d6d`）。应用的校内步行仍按 OSM 接驳估算。已定位 D2 口，高德步行至西门为 318 米，西门暂未启用出校接驳。其余匹配依据和限制随 [地点数据](../backend/src/main/resources/data/application-places.json)及[接驳数据](../backend/src/main/resources/data/campus-connectors.json)保存。

东门出校点到公交规划的首段步行接入点经团队确认可通行。2026-11-19 高德步行接口返回约 30 米的轨迹，首末点约为 GCJ-02 `103.993568,30.769141`、`103.993264,30.769058`。应用只在首段轨迹命中该接入点时补这一段几何，原公交方案汇总沿用供应方数值。
