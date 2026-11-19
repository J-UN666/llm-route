#!/usr/bin/env python3
"""Build a relocatable backend and the selected WeChat build from a checkout."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FRONTEND = ROOT / "frontend"
MYSQL_VERSION = "8.4.13"
CAMPUS = Path("source-data/校园数据")
RUNTIME_DATA = [
    CAMPUS / "cleaned" / name
    for name in ("official_places.json", "place_aliases.json", "mvp_seed_candidates.json")
] + [Path("web/data/campus.json")]
SOURCE_DATA = [CAMPUS / "sources.json", CAMPUS / "README.md"]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_platform(args):
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else "java"
    actual = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0]
    expected = (ROOT / ".java-version").read_text().strip()
    if not re.search(r'"' + re.escape(expected) + r'(?:[.+-][^"]*)?"', actual):
        raise ValueError(f"需要 JDK {expected}，当前为 {actual}")
    if args.channel == "backend":
        return
    node_version = (FRONTEND / ".nvmrc").read_text().strip()
    if subprocess.check_output(["node", "-v"], text=True).strip() != "v" + node_version:
        raise ValueError(f"需要 Node.js {node_version}，见 frontend/.nvmrc")
    npm_version = json.loads((FRONTEND / "package.json").read_text())["packageManager"].split("@")[1]
    if subprocess.check_output(["npm", "-v"], text=True).strip() != npm_version:
        raise ValueError(f"需要 npm {npm_version}，见 frontend/package.json")


def network_config(args):
    if args.channel == "backend":
        return None
    fallback = "http://127.0.0.1:8080" if args.channel == "demo" else ""
    url = (args.api_base or fallback).rstrip("/")
    endpoint = urlsplit(url)
    if (not endpoint.hostname or endpoint.path or endpoint.query or endpoint.fragment
            or endpoint.username or endpoint.password or any(c.isspace() for c in url)
            or endpoint.scheme not in ("http", "https")):
        raise ValueError("需要完整的 API 基址，基址只能包含协议、主机和端口")
    port = endpoint.port
    if args.channel == "wechat":
        host = endpoint.hostname
        if (endpoint.scheme != "https" or port not in (None, 443)
                or not re.fullmatch(r"(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*", host)
                or host.endswith((".local", ".localhost", ".internal", ".invalid", ".test"))):
            raise ValueError("体验版需使用公开 HTTPS 域名与默认端口 443")
        if not re.fullmatch(r"wx[0-9a-f]{16}", args.app_id):
            raise ValueError("体验版需显式提供有效格式的真实微信 AppID")
    elif args.channel == "demo":
        if endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or not port or port < 1024:
            raise ValueError("演示包使用 http://127.0.0.1 和显式的回环端口（1024 至 65535）")
    elif args.channel == "lan":
        try:
            ip = ipaddress.IPv4Address(endpoint.hostname)
            is_lan = ip.is_private and not (ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_reserved)
        except ipaddress.AddressValueError:
            is_lan = False
        if not is_lan:
            raise ValueError("局域网构建需显式提供可由手机访问的内网 IPv4 地址")
    return url


def build_miniprogram(args, stage, url):
    npm_options = ["--offline"] if args.offline_build else ["--prefer-offline"]
    subprocess.run(["npm", "ci", *npm_options], cwd=FRONTEND, check=True)
    output = stage / "mp-weixin"
    env = dict(os.environ, UNI_OUTPUT_DIR=str(output), VITE_API_BASE_URL=url, VITE_RELEASE_CHANNEL=args.channel,
               VITE_DEMO_MODE="1" if args.channel == "demo" else "0")
    subprocess.run(["npm", "run", "build:mp-weixin"], cwd=FRONTEND, env=env, check=True)
    config_path = output / "project.config.json"
    config = json.loads(config_path.read_text())
    if args.channel == "wechat":
        config["appid"] = args.app_id
        config.setdefault("setting", {})["urlCheck"] = True
        config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
        compiled_api = (output / "services/api.js").read_text()
        if url not in compiled_api or "http://127.0.0.1:8080" in compiled_api:
            raise ValueError("体验版制品未正确注入 API 基址")
    if not (output / "app.json").is_file():
        raise ValueError("缺少已构建的小程序入口")


def package(args, output, url):
    check_platform(args)
    for path in RUNTIME_DATA + SOURCE_DATA:
        if not (ROOT / path).is_file():
            raise ValueError(f"缺少校园数据：{path}")
    if args.jar:
        source_jar = Path(args.jar).resolve()
    else:
        command = ["./backend/mvnw", "-B", "-f", "backend/pom.xml", "-DskipTests"]
        if args.offline_build:
            command.append("-o")
        subprocess.run([*command, "package"], cwd=ROOT, check=True)
        source_jar = ROOT / "backend/target/llm-route-backend.jar"

    with zipfile.ZipFile(source_jar) as jar:
        data_version = {
            name: json.loads(jar.read(f"BOOT-INF/classes/data/{name}.json"))["dataVersion"]
            for name in ("application-places", "campus-connectors")
        }
        if "BOOT-INF/classes/db/migration/V2__create_assistant_turns.sql" not in jar.namelist():
            raise ValueError("制品缺少 V2 会话迁移")
    source_ref = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".pack-", dir=output.parent) as temporary:
        stage = Path(temporary) / output.name
        stage.mkdir()
        shutil.copyfile(source_jar, stage / "backend.jar")
        for path in RUNTIME_DATA + SOURCE_DATA:
            target = stage / "data" / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, target)
        shutil.copyfile(ROOT / "docs/release.md", stage / "README.md")
        shutil.copyfile(ROOT / "tools/smoke_delivery.py", stage / "smoke_delivery.py")
        if args.channel == "demo":
            shutil.copyfile(ROOT / "tools/run_demo.py", stage / "run_demo.py")
        else:
            target = stage / "deploy"
            target.mkdir()
            for name in ("zhixing.service", "zhixing.nginx.conf", "runtime.env.example"):
                shutil.copyfile(ROOT / "deploy" / name, target / name)
        if url:
            build_miniprogram(args, stage, url)
        checksums = {path.relative_to(stage).as_posix(): digest(path)
                     for path in sorted(stage.rglob("*")) if path.is_file()}
        manifest = dict(channel=args.channel, commit=source_ref, dirty=dirty,
                        java=(ROOT / ".java-version").read_text().strip(), mysql=MYSQL_VERSION,
                        node=(FRONTEND / ".nvmrc").read_text().strip(), dataVersions=data_version,
                        runtimeFiles=["backend.jar", *(("data" / path).as_posix() for path in RUNTIME_DATA)],
                        apiBaseUrl=url, backendSha256=checksums["backend.jar"], sha256=checksums)
        (stage / "release.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        archive = Path(temporary) / (output.name + ".tar.gz")
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(stage, arcname=output.name)
        stage.rename(output)
        archive.rename(output.parent / archive.name)
    print(f"制品 {output.parent / archive.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("channel", choices=("demo", "backend", "lan", "wechat"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--api-base", default=os.getenv("VITE_API_BASE_URL"))
    parser.add_argument("--app-id", default=os.getenv("WECHAT_APP_ID", ""))
    parser.add_argument("--offline-build", action="store_true", help="只使用已缓存的 Maven/npm 依赖")
    parser.add_argument("--check-config", action="store_true", help="仅验证渠道和基址参数")
    parser.add_argument("--jar", help="使用本轮已验证的后端 JAR")
    args = parser.parse_args()
    try:
        url = network_config(args)
        if args.check_config:
            print(f"{args.channel} 渠道参数校验通过")
            return
        output = (args.output or ROOT / "release" / args.channel).resolve()
        if output.exists() or output.with_name(output.name + ".tar.gz").exists():
            raise ValueError(f"请选择新的输出路径：{output}")
        package(args, output, url)
    except (OSError, ValueError, subprocess.CalledProcessError, KeyError) as error:
        parser.exit(1, f"制品构建失败：{error}\n")


if __name__ == "__main__":
    main()
