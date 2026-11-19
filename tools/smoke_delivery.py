#!/usr/bin/env python3
"""检查地点、路线和聊天会话。"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import ssl
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

LIBRARY = "osm_way_1098786977"
DORM = "osm_way_1099340057"
BASE_PATH = "/api/assistant/conversations"


def check(args):
    context = ssl.create_default_context(cafile=args.ca) if args.ca else None
    base = args.base.rstrip("/")

    def request(path, body=None, *, method=None, token=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-Conversation-Token"] = token
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        query = Request(base + path, data, headers, method=method)
        try:
            response = urlopen(query, context=context, timeout=65)
        except HTTPError as error:
            response = error
        with response:
            content = response.read()
            if response.status != expected:
                raise AssertionError(f"{path} returned {response.status}, expected {expected}: {content[:400]!r}")
            return json.loads(content) if content else None

    assert request("/actuator/health/liveness")["status"] == "UP"
    assert request("/actuator/health/journeys")["status"] == "UP"
    places = request("/api/places?scope=CAMPUS")["items"]
    assert any(place["id"] == LIBRARY and place["routingReady"] for place in places)
    departure = (datetime.now(timezone(timedelta(hours=8))) + timedelta(minutes=10)).replace(second=0, microsecond=0)
    form = request("/api/journeys", {
        "originId": LIBRARY, "destinationId": "C01", "departureTime": departure.isoformat(), "preference": "LESS_WALKING"
    })
    provider = "AMAP" if args.live else "DEMO"
    assert form["status"] == "SUCCEEDED" and form["provider"] == provider and form["plans"]
    for plan in form["plans"]:
        assert plan["gateId"] == "campus_gate_east"
        segments = plan["segments"]
        assert segments and segments[0]["mode"] == "CAMPUS_WALK"
        assert segments[0]["toPoint"]["pointId"] == plan["gateExitPoint"]["pointId"]
        campus_seconds = segments[0]["durationSeconds"]
        assert datetime.fromisoformat(plan["cityDepartureTime"]) - datetime.fromisoformat(plan["departureTime"]) == timedelta(seconds=campus_seconds)
    print(f"普通路线通过 · {provider} · {len(form['plans'])} 条 · {form['requestId']}")
    if args.form_only:
        return

    assert request("/actuator/health/chat")["status"] == "UP"
    if not args.live:
        assert request("/api/assistant/tool-check", {"message":"check"}, expected=404) is not None
    owner = request(BASE_PATH, method="POST", expected=201)
    token = owner["ownerToken"]
    path = f"{BASE_PATH}/{owner['conversationId']}"
    version = 0

    def send(**body):
        nonlocal version
        data = dict(body, conversationId=owner["conversationId"], stateVersion=version, clientRequestId=str(uuid4()))
        answer = request("/api/assistant/query", data, token=token)
        version += 1
        assert answer["stateVersion"] == version
        return answer, data

    if args.live:
        first, _ = send(message=f"从图书馆去天府广场，少走路，出发时间为 {departure.isoformat()}（北京时间）。")
    else:
        missing, _ = send(message="去天府广场，少走路")
        assert missing["status"] == "NEEDS_CLARIFICATION" and missing["clarifications"][0]["reason"] == "MISSING_ORIGIN"
        ambiguous, _ = send(message="从天佑斋出发")
        choice = next(item for item in ambiguous["clarifications"] if item["reason"] == "AMBIGUOUS")
        assert any(candidate["id"] == DORM and candidate["routingReady"] for candidate in choice["candidates"])
        first, _ = send(confirmation={"confirmationId": choice["confirmationId"], "placeId": DORM})

    assert first["status"] == "SUCCEEDED" and first["journey"]["provider"] == provider
    assert first["conditions"]["destinationId"] == "C01"
    if args.live:
        assert datetime.fromisoformat(first["conditions"]["departureTime"]) == departure, "首轮应采用明确指定的出发时间"
    else:
        equivalent = request("/api/journeys", first["conditions"])
        fields = ("planId", "totalDurationSeconds", "totalWalkingMeters", "costYuan")
        def summaries(journey):
            return [[plan[field] for field in fields] for plan in journey["plans"]]

        assert summaries(equivalent) == summaries(first["journey"])
    changed, _ = send(message="改去春熙路")
    assert changed["status"] == "SUCCEEDED" and changed["journey"]["provider"] == provider
    assert changed["conditions"]["destinationId"] == "C02"
    assert changed["conditions"]["originId"] == first["conditions"]["originId"]
    assert changed["conditions"]["departureTime"] == first["conditions"]["departureTime"]
    assert changed["conditions"]["preference"] == first["conditions"]["preference"] == "LESS_WALKING"
    assert changed["journeyRequestId"] != first["journeyRequestId"]
    preferred, last_body = send(message="改为少换乘")
    assert preferred["status"] == "SUCCEEDED" and preferred["conditions"]["preference"] == "FEWER_TRANSFERS"
    assert preferred["conditions"]["departureTime"] == first["conditions"]["departureTime"]

    replay = request("/api/assistant/query", last_body, token=token)
    assert replay["journeyRequestId"] == preferred["journeyRequestId"] and replay["stateVersion"] == version
    persisted = request(path, token=token)
    assert persisted["stateVersion"] == version and len(persisted["messages"]) == 2 * version
    history = request(f"{path}/journeys/{first['journeyRequestId']}", token=token)
    assert history["requestId"] == first["journeyRequestId"]
    assert request(path, token="x" * 43, expected=404)["code"] == "CONVERSATION_NOT_FOUND"
    request(f"{path}/close", method="POST", token=token, expected=204)
    print(f"聊天通过 · 恢复 {len(persisted['messages'])} 条消息 · {preferred['journeyRequestId']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", nargs="?", default="http://127.0.0.1:8080")
    parser.add_argument("--ca", help="测试实例使用的受信 CA 证书路径")
    parser.add_argument("--live", action="store_true", help="校验真实高德/模型流程")
    parser.add_argument("--form-only", action="store_true")
    check(parser.parse_args())
