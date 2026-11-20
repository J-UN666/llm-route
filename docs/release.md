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

体验环境使用随包 Nginx 模板在 HTTPS 入口终止 TLS，并由本机反代设置 `X-Forwarded-Proto`。Java 服务监听回环地址。浏览器访问同一 HTTPS 域名下的 `/web/index.html`，网页、地点搜索、行程和个人 Key 配置共用后端。

## 本机演示个人 Key

在 macOS 的仓库根目录执行：

```bash
python3 tools/run_personal_demo.py
```

首次按提示确认安装或使用 `mkcert`，并在系统提示中确认信任本机开发证书。证书保存在已忽略的 `.local-https/`，后续可复用。首次构建需要 Maven 和 npm 依赖。启动器会构建小程序，初始化独立演示库及 V2 迁移，然后启动 `https://127.0.0.1:8443` 并打开微信开发者工具。

小程序导入 `release/personal-key/mp-weixin`，首次可选择「测试号」和「不使用云服务」。网页打开 [本机演示](https://127.0.0.1:8443/web/index.html)。两端都可在服务设置中验证个人 Key。命令加 `--web` 可只启动网页。按 `Ctrl+C` 停止演示，下次启动会复用演示数据库。

Windows 的本机 HTTPS 步骤见 [应用内输入个人 Key](../README.md#在应用内输入个人-key)。

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

## 路线数据

当前接驳版本 `2026-11-20.1` 覆盖两个校园起点和六座校门。东门接驳及校外首段步道经团队确认。其余五门按项目试用规则纳入全天算路，西南门保留历史门锁提示。地点、坐标与通行依据见 [地点数据](../backend/src/main/resources/data/application-places.json)和[接驳数据](../backend/src/main/resources/data/campus-connectors.json)。
