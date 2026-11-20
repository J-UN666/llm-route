#!/usr/bin/env python3
"""Run the offline bundle with a private MySQL instance and no service keys."""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.error import URLError
from urllib.request import build_opener, ProxyHandler

LOCAL_HTTP = build_opener(ProxyHandler({}))


def wait_for(probe, process, label):
    for _ in range(120):
        if process.poll() is not None:
            break
        if probe():
            return
        time.sleep(0.5)
    raise RuntimeError(f"{label}没有就绪，请查看本次演示的日志")


def listening(port):
    with socket.socket() as listener:
        listener.settimeout(0.25)
        return listener.connect_ex(("127.0.0.1", port)) == 0


def chat_ready(port):
    try:
        with LOCAL_HTTP.open(f"http://127.0.0.1:{port}/actuator/health/chat", timeout=1) as response:
            return json.load(response)["status"] == "UP"
    except (URLError, TimeoutError):
        return False


@contextmanager
def child(command, log_path, **options):
    with log_path.open("ab") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, **options)
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=37)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def read_bundle(bundle):
    meta = json.loads((bundle / "release.json").read_text())
    if meta["channel"] != "demo":
        raise ValueError("离线启动入口只接受 demo 制品")
    for name in meta["runtimeFiles"]:
        if not (bundle / name).is_file():
            raise ValueError(f"演示制品缺少文件：{name}")
    return bundle, meta


def unpack_bundle(archive, bundle):
    with tempfile.TemporaryDirectory(prefix=".demo-unpack-", dir=bundle.parent) as temporary:
        stage = Path(temporary)
        try:
            with tarfile.open(archive, "r:gz") as source:
                for member in source:
                    name = PurePosixPath(member.name)
                    if (not name.parts or name.parts[0] != bundle.name or ".." in name.parts
                            or not (member.isdir() or member.isfile())):
                        raise ValueError(f"演示压缩包中含有不支持的条目：{member.name}")
                    target = stage / name
                    if member.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with source.extractfile(member) as data, target.open("xb") as output:
                            shutil.copyfileobj(data, output)
        except (tarfile.TarError, EOFError) as error:
            raise ValueError(f"无法读取演示压缩包：{archive}") from error
        extracted = stage / bundle.name
        read_bundle(extracted)
        try:
            extracted.rename(bundle)
        except OSError:
            if not (bundle / "release.json").is_file():
                raise
    return read_bundle(bundle)


def open_bundle(path, offline_build):
    script = Path(__file__).resolve()
    if path:
        bundle = path.resolve()
    elif (script.parent / "release.json").is_file():
        bundle = script.parent
    else:
        bundle = script.parent.parent / "release/demo"
        if not (bundle.exists() or bundle.is_symlink()):
            archive = bundle.with_suffix(".tar.gz")
            if archive.is_file():
                return unpack_bundle(archive, bundle)
            command = [sys.executable, script.with_name("package_release.py"), "demo", "--output", bundle]
            if offline_build:
                command.append("--offline-build")
            subprocess.run(command, check=True)
    return read_bundle(bundle)


def executables(meta):
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else "java"
    mysqld = os.environ.get("DEMO_MYSQLD") or shutil.which("mysqld")
    if not mysqld and shutil.which("brew"):
        prefix = subprocess.check_output(["brew", "--prefix", "mysql@8.4"], text=True).strip()
        mysqld = str(Path(prefix) / "bin/mysqld")
    if not mysqld:
        raise ValueError("请安装 MySQL 8.4.13 并设定 DEMO_MYSQLD，或将 mysqld 放在 PATH")
    version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
    if not re.search(r'"' + re.escape(meta["java"]) + r'(?:[.+-][^"]*)?"', version):
        raise ValueError(f"演示需要 JDK {meta['java']}")
    version = subprocess.check_output([mysqld, "--no-defaults", "--version"], text=True)
    if not re.search(r"\b" + re.escape(meta["mysql"]) + r"\b", version):
        raise ValueError(f"演示需要 MySQL {meta['mysql']}")
    return java, mysqld


def provision(mysqld, state, log):
    data = state / "mysql"
    passwords = state / "local-accounts.json"
    if passwords.exists():
        credentials = json.loads(passwords.read_text())
    else:
        if data.exists():
            raise ValueError("演示目录已有数据但缺少账号文件，请改用新的 --state 目录")
        credentials = {role: secrets.token_hex(24) for role in ("root", "migration", "app")}
        passwords.write_text(json.dumps(credentials))
    if not data.exists():
        with log.open("ab") as output:
            subprocess.run([mysqld, "--no-defaults", "--initialize-insecure", f"--datadir={data}"],
                           stdout=output, stderr=subprocess.STDOUT, check=True)
    return data, credentials


@contextmanager
def state_lock(state):
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    if state.stat().st_mode & 0o077:
        raise ValueError("演示状态目录需要权限 0700，请提供独立私有目录")
    with (state / "lock").open("a") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("该演示目录正在使用，请回到已打开的终端或停止原进程") from error
        yield


@contextmanager
def demo_database(mysqld, port, state, workdir, runner):
    with state_lock(state), tempfile.TemporaryDirectory(prefix="zhixing-", dir="/tmp") as temporary:
        mysql_log = state / "mysql.log"
        data, credentials = provision(mysqld, state, mysql_log)
        config = Path(temporary) / "initialize.sql"
        config.write_text(f"""
ALTER USER 'root'@'localhost' IDENTIFIED BY '{credentials['root']}';
CREATE DATABASE IF NOT EXISTS zhixingdemo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE USER IF NOT EXISTS 'demo_migration'@'127.0.0.1' IDENTIFIED BY '{credentials['migration']}';
CREATE USER IF NOT EXISTS 'demo_app'@'127.0.0.1' IDENTIFIED BY '{credentials['app']}';
GRANT ALL ON zhixingdemo.* TO 'demo_migration'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON zhixingdemo.* TO 'demo_app'@'127.0.0.1';
""")
        command = [mysqld, "--no-defaults", f"--datadir={data}", "--bind-address=127.0.0.1",
                   f"--port={port}", f"--socket={Path(temporary) / 'mysql.sock'}",
                   f"--pid-file={Path(temporary) / 'mysqld.pid'}", "--mysqlx=OFF", "--skip-log-bin",
                   "--skip-name-resolve", f"--init-file={config}", f"--log-error={mysql_log}"]
        with child(command, state / "mysql-console.log", cwd=workdir) as database:
            wait_for(lambda: listening(port), database, "MySQL")
            url = f"jdbc:mysql://127.0.0.1:{port}/zhixingdemo?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true"
            migration = dict(os.environ, MIGRATION_DB_URL=url, MIGRATION_DB_USER="demo_migration",
                             MIGRATION_DB_PASSWORD=credentials["migration"])
            with (state / "migration.log").open("ab") as log:
                subprocess.run([*runner, "--migrate"], cwd=workdir, env=migration, check=True,
                               stdout=log, stderr=subprocess.STDOUT)
            yield database, {"DB_URL": url, "DB_USER": "demo_app", "DB_PASSWORD": credentials["app"]}


def wait_for_exit(database, app):
    while database.poll() is None and app.poll() is None:
        time.sleep(0.5)
    raise RuntimeError("演示进程已退出，请查看日志")


def start(args):
    bundle, meta = open_bundle(args.bundle, args.offline_build)
    java, mysqld = executables(meta)
    api_port = args.port or int(meta["apiBaseUrl"].rsplit(":", 1)[-1])
    if api_port == args.db_port or any(listening(port) for port in (api_port, args.db_port)):
        raise ValueError("本机端口已占用或重复，请检查 --port 和 --db-port")

    state = (args.state or bundle / ".state").resolve()
    runner = [java, "-jar", str(bundle / "backend.jar")]
    with demo_database(mysqld, args.db_port, state, bundle, runner) as (database, db_env):
        api_environment = {key: os.environ[key] for key in ("PATH", "LANG", "JAVA_HOME") if key in os.environ}
        api_environment.update(db_env, CAMPUS_DATA_ROOT=str(bundle / "data"), SERVER_PORT=str(api_port))
        with child([*runner, "--spring.profiles.active=demo"], state / "backend.log",
                   cwd=bundle, env=api_environment) as app:
            wait_for(lambda: chat_ready(api_port), app, "聊天服务")
            print(f"离线演示已启动 http://127.0.0.1:{api_port}，日志位于 {state}。Ctrl+C 停止。", flush=True)
            wait_for_exit(database, app)


def stop(_signal, _frame):
    raise KeyboardInterrupt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--state", type=Path)
    parser.add_argument("--port", type=int)
    parser.add_argument("--db-port", type=int, default=13386)
    parser.add_argument("--offline-build", action="store_true")
    args = parser.parse_args()
    if any(port is not None and not 1024 <= port <= 65535 for port in (args.port, args.db_port)):
        parser.error("端口必须在 1024 至 65535 之间")
    signal.signal(signal.SIGTERM, stop)
    os.umask(0o077)
    try:
        start(args)
    except KeyboardInterrupt:
        print("离线演示已停止")
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"离线演示失败：{error}\n")


if __name__ == "__main__":
    main()
