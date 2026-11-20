#!/usr/bin/env python3
"""启动本机 HTTPS 小程序演示。"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import ssl
import subprocess
import tempfile
from urllib.error import URLError
from urllib.request import build_opener, HTTPSHandler, ProxyHandler

from package_release import ROOT, MYSQL_VERSION, build_backend, build_miniprogram, check_platform
from run_demo import child, demo_database, executables, listening, state_lock, stop, wait_for, wait_for_exit

DEVTOOLS = Path("/Applications/wechatwebdevtools.app")


def prepare_tls():
    directory = ROOT / ".local-https"
    cert = directory / "localhost.pem"
    private_key = directory / "localhost-key.pem"
    store = directory / "localhost.p12"
    password = "local-demo"
    valid = cert.is_file() and private_key.is_file() and subprocess.run(
        ["openssl", "x509", "-in", str(cert), "-noout", "-checkend", "86400"],
        capture_output=True).returncode == 0
    if not valid:
        notice = "需要创建并信任本机 HTTPS 开发证书"
        if not shutil.which("mkcert"):
            if not shutil.which("brew"):
                raise ValueError("请先安装 Homebrew 或 mkcert")
            notice += "，并通过 Homebrew 安装 mkcert"
        if input(f"{notice}。继续请输入 y：").strip().lower() != "y":
            raise ValueError("首次 HTTPS 设置已取消")
        if not shutil.which("mkcert"):
            subprocess.run(["brew", "install", "mkcert"], check=True)
        directory.mkdir(mode=0o700, exist_ok=True)
        subprocess.run(["mkcert", "-install"], check=True)
        subprocess.run(["mkcert", "-cert-file", str(cert), "-key-file", str(private_key),
                        "localhost", "127.0.0.1"], check=True)

    if not store.is_file() or store.stat().st_mtime_ns < max(cert.stat().st_mtime_ns, private_key.stat().st_mtime_ns):
        with tempfile.TemporaryDirectory(dir=directory) as stage:
            new_store = Path(stage) / store.name
            subprocess.run(["openssl", "pkcs12", "-export", "-in", str(cert), "-inkey", str(private_key),
                            "-out", str(new_store), "-passout", f"pass:{password}"], check=True)
            new_store.replace(store)
    context = ssl.create_default_context(cafile=str(cert))
    context.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
    return context, {
        "SERVER_SSL_ENABLED": "true", "SERVER_SSL_KEY_STORE": store.as_uri(),
        "SERVER_SSL_KEY_STORE_TYPE": "PKCS12", "SERVER_SSL_KEY_STORE_PASSWORD": password
    }


def build_project(workdir, base, offline_build):
    config_path = workdir / "mp-weixin/project.config.json"
    previous = json.loads(config_path.read_text()) if config_path.is_file() else {}
    app_id = previous.get("appid") or ""
    project = build_miniprogram(workdir, base, channel="personal", offline_build=offline_build)
    if re.fullmatch(r"wx[0-9a-f]{16}", app_id):
        config = json.loads(config_path.read_text())
        config["appid"] = app_id
        config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    return project


def open_devtools(project):
    cli = DEVTOOLS / "Contents/MacOS/cli"
    if not cli.is_file():
        print("请在微信开发者工具中导入上述目录。", flush=True)
        return
    app_id = json.loads((project / "project.config.json").read_text()).get("appid") or ""
    if re.fullmatch(r"wx[0-9a-f]{16}", app_id):
        try:
            result = subprocess.run([cli, "open", "--project", project], capture_output=True, timeout=15)
            if result.returncode == 0 and b"[error]" not in result.stdout + result.stderr:
                return
        except (OSError, subprocess.TimeoutExpired):
            pass
    subprocess.run(["open", "-a", DEVTOOLS], check=False)
    subprocess.run(["open", "-R", project], check=False)
    print("首次在微信开发者工具导入该目录，选择「测试号」和「不使用云服务」。", flush=True)


def ready_at(base, context):
    opener = build_opener(ProxyHandler({}), HTTPSHandler(context=context))

    def check():
        try:
            with opener.open(f"{base}/actuator/health/readiness", timeout=1) as response:
                return json.load(response)["status"] == "UP"
        except (URLError, TimeoutError, ConnectionError):
            return False

    return check


def start(args):
    if args.port == args.db_port or any(listening(port) for port in (args.port, args.db_port)):
        raise ValueError("本机端口已占用或重复，请停止旧的演示，或更改 --port / --db-port")
    check_platform("backend" if args.web else "personal")
    java, mysqld = executables({"java": (ROOT / ".java-version").read_text().strip(), "mysql": MYSQL_VERSION})
    workdir = ROOT / "release/personal-key"
    with state_lock(workdir):
        launch(args, workdir, java, mysqld)


def launch(args, workdir, java, mysqld):
    context, tls = prepare_tls()
    base = f"https://127.0.0.1:{args.port}"
    state = workdir / ".state"
    jar = build_backend(args.offline_build)
    project = None if args.web else build_project(workdir, base, args.offline_build)
    runner = [java, "-jar", str(jar)]
    with demo_database(mysqld, args.db_port, state, workdir, runner) as (database, db_env):
        environment = {key: os.environ[key] for key in ("PATH", "LANG", "JAVA_HOME") if key in os.environ}
        environment.update(db_env, **tls, CAMPUS_DATA_ROOT=str(ROOT),
                           SERVER_PORT=str(args.port), SERVER_ADDRESS="127.0.0.1",
                           ASSISTANT_TOOL_CHECK_ENABLED="false")
        with child([*runner, "--spring.profiles.active=production"], state / "backend.log",
                   cwd=workdir, env=environment) as app:
            wait_for(ready_at(base, context), app, "HTTPS 服务")
            web_url = f"{base}/web/index.html"
            print(f"个人 Key 演示已启动\n网页 {web_url}，点击「服务设置」。", flush=True)
            if project is not None:
                print(f"小程序目录 {project}\n打开首页「设置 → 服务配置 → 个人配置」。", flush=True)
            print("按 Ctrl+C 停止。", flush=True)
            if not args.no_open:
                if args.web:
                    subprocess.run(["open", web_url], check=False)
                else:
                    open_devtools(project)
            wait_for_exit(database, app)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--db-port", type=int, default=13387)
    parser.add_argument("--offline-build", action="store_true")
    parser.add_argument("--web", action="store_true", help="只启动网页演示")
    parser.add_argument("--no-open", action="store_true")
    args = parser.parse_args()
    if any(not 1024 <= port <= 65535 for port in (args.port, args.db_port)):
        parser.error("端口必须在 1024 至 65535 之间")
    os.umask(0o077)
    signal.signal(signal.SIGTERM, stop)
    try:
        start(args)
    except KeyboardInterrupt:
        print("个人 Key 演示已停止")
    except (OSError, EOFError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"启动失败：{error}\n日志目录 {ROOT / 'release/personal-key/.state'}\n")


if __name__ == "__main__":
    main()
