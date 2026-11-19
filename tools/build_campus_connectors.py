#!/usr/bin/env python3
"""构建 W1-05 校园入口到实际出校点的固定步行接驳应用数据。

输入（只读来源，不修改）：
  source-data/校园数据/cleaned/graph_nodes_candidates.json
  source-data/校园数据/cleaned/graph_edges_candidates.json

输出：
  backend/src/main/resources/data/campus-connectors.json

方法：
  1. 在 OSM WGS84 路网上做 Dijkstra 最短路；排除 walk_permission=prohibited 与
     crosses_campus_boundary 的边（校园接驳止于校门出校点，不进入城市道路）。
  2. 起点取图书馆、天佑斋1号楼的楼外路网接入点（演示不要求精确楼门），
     终点取东门出校方向门节点 osm_node_13469658219（W1-04 campus_gate_east 的
     routingPosition）。
  3. 连续且同一 OSM way 的边合并为一个 segment，按顺序保存说明、米数、估算秒数、
     通行状态与 WGS84 几何；总米数/总秒数由 segment 加总，供验收核对。
  4. 估算速度 1.33 m/s（约 4.8 km/h），每段秒数向上取整，总秒数=各段之和。
  5. 团队确认东门可步行通过；按 2026-11-19 的项目口径，已启用接驳不设门禁时段。
     地图用于坐标、几何和距离估算，明确禁行的边仍排除。

脚本只使用 Python 标准库，可重复执行得到完全相同的 JSON（无时间戳、无随机量）。
"""

from __future__ import annotations

import heapq
import json
import math
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
CLEANED_DIR = REPO_ROOT / "source-data" / "校园数据" / "cleaned"
NODES_PATH = CLEANED_DIR / "graph_nodes_candidates.json"
EDGES_PATH = CLEANED_DIR / "graph_edges_candidates.json"
OUTPUT_PATH = REPO_ROOT / "backend" / "src" / "main" / "resources" / "data" / "campus-connectors.json"

DATA_VERSION = "2026-11-19.2"
REVIEWED_AT = "2026-11-15"
TEAM_SOURCE_REF = "TEAM_CONFIRMATION_2026_11_15"
DEMO_RULE_REF = "DEMO_WALKWAY_DEFAULT_OPEN"
GATE_HOURS_RULE_REF = "PROJECT_GATE_HOURS_UNRESTRICTED_2026_11_19"
AMAP_WALK_REF = "AMAP_WALKING_2026_11_19"
WEST_GATE_POI_URL = "https://ditu.amap.com/place/B001C80PYA"
WALKING_SPEED_MPS = 1.33  # 约 4.8 km/h；仅用于固定口径估算，不是现场实测
EARTH_RADIUS_M = 6371008.8

LIBRARY_ORIGIN_ID = "osm_way_1098786977"
LIBRARY_ORIGIN_NAME = "西南交通大学犀浦校区图书馆"
LIBRARY_ACCESS_NODE = "osm_node_5167087319"

DORM1_ORIGIN_ID = "osm_way_1099340057"
DORM1_ORIGIN_NAME = "天佑斋1号楼"
DORM1_ACCESS_NODE = "osm_node_9893299669"

EAST_GATE_ID = "campus_gate_east"
EAST_GATE_NAME = "东门"
EAST_GATE_INBOUND_NODE = "osm_node_4150911420"
EAST_GATE_OUTBOUND_NODE = "osm_node_13469658219"

WEST_GATE_ID = "campus_gate_west"
WEST_GATE_NAME = "西门"
WEST_GATE_NODE_A = "osm_node_11668769187"
WEST_GATE_NODE_B = "osm_node_11668769188"

SOUTHWEST_GATE_NODE = "osm_node_9893305424"
SOUTHWEST_GATE_NAME = "西南门"

DATA_VERSION_ISSUE_NOTE = "数据版本问题记录（2026-11-15）："

LIBRARY_SEGMENT_DESCRIPTIONS = [
    "从图书馆东北侧楼外步道接入点出发，沿步行区向东北至图书馆北侧主轴线",
    "沿图书馆北侧主轴线步行区向东偏南至东侧通道",
    "沿图书馆东侧自行车/步行通道向东北至校园内部道路",
    "沿校园内部道路向东北至知行路",
    "沿知行路向东南至东门内路口",
    "沿东门出校单向车道向东北步行至东门出校点",
]

DORM1_SEGMENT_DESCRIPTIONS = [
    "从天佑斋1号楼北侧楼外接入点沿人行步道向西南",
    "沿天佑斋1号、2号楼东侧人行步道向北",
    "沿人行步道向东南接入宿舍区内部道路",
    "沿宿舍区生活性道路向东北",
    "沿宿舍区东侧自行车/步行通道向东北",
    "过玻璃桥/蓝桥（自行车/步行通道）向东北",
    "沿湖东侧自行车/步行通道向东北",
    "沿宿舍区生活性道路向西北",
    "沿自行车/步行通道向东北",
    "沿自行车/步行通道向西北",
    "沿生活性道路向东北",
    "沿人行步道向北",
    "沿生活性道路向东北",
    "沿生活性道路向东北",
    "过生活性道路交叉口",
    "沿北区生活性道路向东北",
    "沿知行路向西北至东门内路口",
    "沿东门出校单向车道向东北步行至东门出校点",
]

HIGHWAY_NAMES = {
    "footway": "人行步道",
    "pedestrian": "步行区步道",
    "cycleway": "自行车/步行通道",
    "living_street": "生活性道路",
    "residential": "校园道路",
    "path": "小径",
    "steps": "台阶",
}


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def haversine_m(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def build_graph(nodes, edges):
    adjacency = {}
    for edge in edges:
        if edge["walk_permission"] == "prohibited":
            continue  # 禁行路段不进入任何接驳路线
        if edge["crosses_campus_boundary"]:
            continue  # 校园接驳止于校门出校点
        start = edge["from_node_id"]
        end = edge["to_node_id"]
        adjacency.setdefault(start, []).append((end, edge))
        adjacency.setdefault(end, []).append((start, edge))
    return adjacency


def shortest_path(adjacency, nodes, source, target):
    distances = {source: 0.0}
    previous = {}
    queue = [(0.0, source)]
    visited = set()
    while queue:
        distance, node_id = heapq.heappop(queue)
        if node_id in visited:
            continue
        visited.add(node_id)
        if node_id == target:
            break
        for neighbour, edge in adjacency.get(node_id, []):
            candidate = distance + edge["length_m"]
            if candidate < distances.get(neighbour, math.inf):
                distances[neighbour] = candidate
                previous[neighbour] = (node_id, edge)
                heapq.heappush(queue, (candidate, neighbour))
    if target not in distances:
        raise RuntimeError(f"路网不可达：{source} -> {target}")
    edges_on_path = []
    current = target
    while current != source:
        parent, edge = previous[current]
        edges_on_path.append((parent, current, edge))
        current = parent
    edges_on_path.reverse()
    return distances[target], edges_on_path


def group_segments(edges_on_path):
    groups = []
    for start, end, edge in edges_on_path:
        key = (edge["osm_way_id"], edge["highway"], edge["walk_permission"], edge["access"], edge["name"])
        if groups and groups[-1]["key"] == key:
            group = groups[-1]
            group["edge_ids"].append(edge["edge_id"])
            group["osm_way_ids"].add(edge["osm_way_id"])
            group["node_ids"].append(end)
            group["length_m"] += edge["length_m"]
        else:
            groups.append({
                "key": key,
                "edge_ids": [edge["edge_id"]],
                "osm_way_ids": {edge["osm_way_id"]},
                "node_ids": [start, end],
                "length_m": edge["length_m"],
                "highway": edge["highway"],
                "walk_permission": edge["walk_permission"],
                "access": edge["access"],
                "name": edge["name"],
            })
    return groups


def passability(group, is_gate_lane: bool):
    if group["walk_permission"] == "prohibited":
        return "PROHIBITED", "来源明确禁止步行，不适用演示默认开放规则。"
    basis = "用户确认东门可步行通过" if is_gate_lane else "演示规则：校内人行道默认开放"
    return (
        "OPEN",
        f"{basis}（2026-11-15 团队确认）；OSM 来源属性保留：highway={group['highway']}、"
        f"walk_permission={group['walk_permission']}、access={group['access']}。距离与耗时为地图估算。",
    )


def make_geometry(nodes, node_ids):
    points = [nodes[node_id] for node_id in node_ids]
    return {
        "coordinateSystem": "WGS84",
        "points": [[round(node["longitude"], 7), round(node["latitude"], 7)] for node in points],
    }


def make_segments(nodes, groups, descriptions, gate_lane_last: bool):
    segments = []
    duration_sum = 0
    for index, group in enumerate(groups):
        order = index + 1
        description = descriptions[index] if index < len(descriptions) else (
            f"沿{HIGHWAY_NAMES.get(group['highway'], group['highway'])}步行"
        )
        is_gate_lane = gate_lane_last and index == len(groups) - 1
        status, note = passability(group, is_gate_lane)
        seconds = math.ceil(group["length_m"] / WALKING_SPEED_MPS)
        duration_sum += seconds
        geometry = make_geometry(nodes, group["node_ids"])
        # 几何折线长度必须与分段米数一致（同源 WGS84 Haversine）。
        geometry_length = sum(
            haversine_m(*geometry["points"][i], *geometry["points"][i + 1])
            for i in range(len(geometry["points"]) - 1)
        )
        if abs(geometry_length - group["length_m"]) > 0.05:
            raise RuntimeError(
                f"分段 {order} 几何长度 {geometry_length:.3f} 与 edge length {group['length_m']:.3f} 不一致"
            )
        segments.append({
            "segmentId": None,  # 由调用方按连接 ID 填充
            "order": order,
            "description": description,
            "fromPointId": group["node_ids"][0],
            "toPointId": group["node_ids"][-1],
            "lengthMeters": round(group["length_m"], 2),
            "durationSeconds": seconds,
            "highway": group["highway"],
            "roadName": group["name"],
            "passabilityStatus": status,
            "passabilityNote": note,
            "geometry": geometry,
            "osmWayIds": sorted(str(way) for way in group["osm_way_ids"]),
            "sourceRefs": ["S04", TEAM_SOURCE_REF, DEMO_RULE_REF],
        })
    return segments, duration_sum


def make_point(nodes, node_id, position_role, evidence=None):
    node = nodes[node_id]
    point = {
        "pointId": node_id,
        "longitude": node["longitude"],
        "latitude": node["latitude"],
        "coordinateSystem": "WGS84",
        "positionRole": position_role,
    }
    if evidence is not None:
        point["evidence"] = evidence
    return point


def approved_demo_connector(nodes, edges_on_path, connector_id, origin_id, origin_name, origin_node,
                            segment_descriptions, verification_basis):
    groups = group_segments(edges_on_path)
    if len(groups) != len(segment_descriptions):
        raise RuntimeError(
            f"{connector_id} 分段数 {len(groups)} 与说明数 {len(segment_descriptions)} 不一致"
        )
    segments, duration_sum = make_segments(nodes, groups, segment_descriptions, gate_lane_last=True)
    for segment in segments:
        segment["segmentId"] = f"{connector_id}_{segment['order']:02d}"
    # 总量必须等于写入 JSON 的分段米数之和（分段各自保留 2 位小数），而不是未取整的原始长度，
    # 这样“总米数由分段加总”在文件层面严格成立、可被外部逐段核对。
    walk_meters = round(sum(segment["lengthMeters"] for segment in segments), 2)
    if abs(walk_meters - sum(segment["lengthMeters"] for segment in segments)) > 1e-9:
        raise RuntimeError(f"{connector_id} 总米数与分段和不一致")
    if segments[0]["fromPointId"] != origin_node:
        raise RuntimeError(f"{connector_id} 首段起点与入口接入点不一致")
    if segments[-1]["toPointId"] != EAST_GATE_OUTBOUND_NODE:
        raise RuntimeError(f"{connector_id} 末段终点与东门出校点不一致")
    connector = {
        "id": connector_id,
        "originId": origin_id,
        "originName": origin_name,
        "originPoint": make_point(nodes, origin_node, "building_external_access_point", [
            "演示采用现有楼外路网接入点；用户同意不要求精确到楼门。坐标取自 S04 OSM WGS84 路网节点。",
            "S01/S02 官方总平面图用于建筑与周边路径的定性对照；通行依据为团队确认。",
        ]),
        "gateId": EAST_GATE_ID,
        "gateName": EAST_GATE_NAME,
        "gatePoints": [
            {
                "pointId": EAST_GATE_INBOUND_NODE,
                "longitude": nodes[EAST_GATE_INBOUND_NODE]["longitude"],
                "latitude": nodes[EAST_GATE_INBOUND_NODE]["latitude"],
                "coordinateSystem": "WGS84",
                "direction": "INBOUND",
                "positionRole": "gate_inbound_lane",
                "evidence": [
                    "S04 OSM way 532472151：进校方向单向车道（oneway=yes、access=permit），"
                    "节点顺序为校外→osm_node_4150911420→校内",
                    "W1-04 campus_gate_east 合并的进校侧节点",
                ],
            },
            {
                "pointId": EAST_GATE_OUTBOUND_NODE,
                "longitude": nodes[EAST_GATE_OUTBOUND_NODE]["longitude"],
                "latitude": nodes[EAST_GATE_OUTBOUND_NODE]["latitude"],
                "coordinateSystem": "WGS84",
                "direction": "OUTBOUND",
                "positionRole": "gate_exit",
                "evidence": [
                    "S04 OSM way 532472150：出校方向单向车道（oneway=yes、access=permit），"
                    "节点顺序为校内→osm_node_12113987436→osm_node_13469658219→校外",
                    "W1-04 application-places.json campus_gate_east routingPosition 指向该节点",
                ],
            },
        ],
        "gateExitPoint": make_point(nodes, EAST_GATE_OUTBOUND_NODE, "gate_exit", [
            "S04 OSM 东门出校方向门节点（barrier=gate），位于校园边界外侧出校车道上",
            "W1-04 应用地点已把 campus_gate_east 的 routingPosition 定为该点",
            "2026-11-15 用户确认该东门方向可步行通过，作为演示城市段衔接点。",
        ]),
        "segments": segments,
        "walkMeters": walk_meters,
        "durationSeconds": duration_sum,
        "durationBasis": (
            f"按 {WALKING_SPEED_MPS} m/s（约 4.8 km/h）平地步行速度估算；"
            "每段 durationSeconds=ceil(段长度/1.33)，总秒数=各段之和；"
            "未计入楼梯、坡道、闸机等待、校门排队与现场绕行，现场实测前为估算值。"
        ),
        "verificationStatus": "APPLICATION_VERIFIED",
        "verifiedAt": REVIEWED_AT,
        "verificationMethod": (
            "团队确认通行＋地图路网估算。2026-11-15 用户确认东门可步行通过，"
            "指定人行道默认开放。2026-11-19 用户确认已启用接驳按无门禁时段处理；"
            "起点为楼外接入点，距离与耗时为地图估算。"
        ),
        "verificationBasis": verification_basis + [
            "TEAM_CONFIRMATION_2026_11_15：用户在项目会话中明确确认“可步行”，适用于东门出校方向。",
            "DEMO_WALKWAY_DEFAULT_OPEN：用户指定演示人行道默认开放，楼外接入点即可，不要求精确门位。",
            f"{GATE_HOURS_RULE_REF}：用户指定已启用的东门接驳不设门禁时段，作为本版项目规则。",
        ],
        "missingEvidence": [],
        "accessRules": {
            "direction": "OUTBOUND",
            "pedestrianAccess": "PERMITTED",
            "pedestrianAccessBasis": (
                "2026-11-15 团队确认东门可步行通过，演示沿现有东门出校点衔接城市段；"
                "OSM 出校方向及几何保留，来源机动车道标签不作为团队确认的人行禁行依据。"
            ),
            "gateOpenHours": "项目按全天可通行处理",
            "gateOpenHoursStatus": "PROJECT_ASSUMED_UNRESTRICTED",
            "timeRestrictions": [],
            "timeRestrictionBasis": "2026-11-19 用户指定已启用的东门接驳不设门禁时段，作为项目算路规则。",
        },
        "sourceRefs": ["S01", "S02", "S04", TEAM_SOURCE_REF, DEMO_RULE_REF, GATE_HOURS_RULE_REF, AMAP_WALK_REF],
        "unavailableReasonCode": None,
        "unavailableReason": None,
    }
    return connector


def pending_connector(nodes):
    return {
        "id": "connector_library_west_gate",
        "originId": LIBRARY_ORIGIN_ID,
        "originName": LIBRARY_ORIGIN_NAME,
        "originPoint": make_point(nodes, LIBRARY_ACCESS_NODE, "building_access_point", [
            "S04 OSM 建筑轮廓与相邻步道顶点核对；入口门位与开放规则待现场核实",
        ]),
        "gateId": WEST_GATE_ID,
        "gateName": WEST_GATE_NAME,
        "gatePoints": [
            {
                "pointId": WEST_GATE_NODE_A,
                "longitude": nodes[WEST_GATE_NODE_A]["longitude"],
                "latitude": nodes[WEST_GATE_NODE_A]["latitude"],
                "coordinateSystem": "WGS84",
                "direction": "UNKNOWN",
                "positionRole": "gate_candidate_node",
                "evidence": [
                    "S04 OSM 无名门节点（barrier=gate、motor_vehicle=permit）；"
                    "对应官方图标签“西门”（LABEL_016，S01），仅有名称线索",
                ],
            },
            {
                "pointId": WEST_GATE_NODE_B,
                "longitude": nodes[WEST_GATE_NODE_B]["longitude"],
                "latitude": nodes[WEST_GATE_NODE_B]["latitude"],
                "coordinateSystem": "WGS84",
                "direction": "UNKNOWN",
                "positionRole": "gate_candidate_node",
                "evidence": [
                    "S04 OSM 无名门节点（barrier=gate、motor_vehicle=permit），"
                    "邻接跨校界 residential 边 osm_edge_207572443_8 与 footway 过街边；进出方向未确认",
                ],
            },
        ],
        "gateExitPoint": None,
        "segments": [],
        "walkMeters": None,
        "durationSeconds": None,
        "durationBasis": None,
        "verificationStatus": "PENDING_VERIFICATION",
        "verifiedAt": None,
        "verificationMethod": "高德西门 POI 与官方图和 OSM 门节点位置相符；城市接驳所用出校方向与步行权限待核对。",
        "verificationBasis": [
            "S04 OSM 西侧门两个候选节点 osm_node_11668769187 / osm_node_11668769188（barrier=gate、motor_vehicle=permit）",
            "S01 官方图标签“西门”（LABEL_016）仅作名称线索，官方图无门洞与通行方向符号",
            f"{WEST_GATE_POI_URL}：2026-11-19 核对西门高德展示点；该点距两个 OSM 候选节点均很近，方向信息由接驳另行核对。",
            f"{AMAP_WALK_REF}：交大兴业北街站 D2 口至西门展示点的高德步行规划为 318 米、254 秒。",
        ],
        "missingEvidence": [
            "西门出校方向的物理点位（两个 OSM 门节点中哪一个是出校侧）",
            "西门是否允许日常城市出行（W1-04 应用地点将 campus_gate_west 标为 campusOnly 且 routingPosition 为空）",
            "西门行人可用的出校路径",
        ],
        "accessRules": {
            "direction": "UNVERIFIED",
            "pedestrianAccess": "UNVERIFIED",
            "pedestrianAccessBasis": "无西门人行通行证据；官方图仅名称标签，OSM 节点无 foot/access 规则。",
            "gateOpenHours": None,
            "gateOpenHoursStatus": "EVIDENCE_UNAVAILABLE",
            "timeRestrictions": [],
            "timeRestrictionBasis": "西门尚未启用为城市接驳，项目已启用接驳的时段规则不改变西门的待核对状态。",
        },
        "sourceRefs": ["S01", "S04", WEST_GATE_POI_URL, AMAP_WALK_REF],
        "unavailableReasonCode": "GATE_EXIT_POINT_UNVERIFIED",
        "unavailableReason": (
            "西门两个 OSM 门节点（osm_node_11668769187 / osm_node_11668769188）的进出方向未确认，"
            "应用地点将 campus_gate_west 标为 campusOnly 且 routingPosition 为空；"
            "高德已补充展示位置，城市接驳等待出校点和步行路径核对。"
        ),
    }


def rejected_connector(nodes):
    return {
        "id": "connector_dorm1_southwest_gate",
        "originId": DORM1_ORIGIN_ID,
        "originName": DORM1_ORIGIN_NAME,
        "originPoint": make_point(nodes, DORM1_ACCESS_NODE, "building_access_point", [
            "S04 OSM 建筑轮廓与相邻人行步道顶点核对；入口门位与开放规则待现场核实",
        ]),
        "gateId": SOUTHWEST_GATE_NODE,
        "gateName": SOUTHWEST_GATE_NAME,
        "gatePoints": [
            {
                "pointId": SOUTHWEST_GATE_NODE,
                "longitude": nodes[SOUTHWEST_GATE_NODE]["longitude"],
                "latitude": nodes[SOUTHWEST_GATE_NODE]["latitude"],
                "coordinateSystem": "WGS84",
                "direction": "UNKNOWN",
                "positionRole": "gate_candidate_node",
                "evidence": [
                    "S04 OSM 西南门节点 barrier=gate、locked=yes；邻接跨校界 residential 边 osm_edge_413859301_9（精勤路）",
                ],
            },
        ],
        "gateExitPoint": None,
        "segments": [],
        "walkMeters": None,
        "durationSeconds": None,
        "durationBasis": None,
        "verificationStatus": "REJECTED",
        "verifiedAt": REVIEWED_AT,
        "verificationMethod": "OSM 标签审核：西南门标注 locked=yes，视为当前不开放。",
        "verificationBasis": [
            "S04 OSM 西南门节点 osm_node_9893305424（barrier=gate、locked=yes，source_updated_at 2022-11-19）",
            "该门不在 W1-04 首批应用地点校门范围内；无开放时段、人行规则与现场通行证据",
        ],
        "missingEvidence": [
            "西南门当前是否仍 locked=yes（OSM 标签更新时间 2022-11-19，需现场核实）",
            "若开放，出校方向点位、开放时段与人行规则",
        ],
        "accessRules": {
            "direction": "UNVERIFIED",
            "pedestrianAccess": "PROHIBITED",
            "pedestrianAccessBasis": "S04 OSM 标签 barrier=gate、locked=yes；未取得现场开放证据，按不开放审核。",
            "gateOpenHours": None,
            "gateOpenHoursStatus": "EVIDENCE_UNAVAILABLE",
            "timeRestrictions": [],
            "timeRestrictionBasis": "无开放时段证据；locked=yes 表示当前关闭。",
        },
        "sourceRefs": ["S04"],
        "unavailableReasonCode": "GATE_LOCKED",
        "unavailableReason": (
            "S04 OSM 西南门节点 osm_node_9893305424 标注 barrier=gate、locked=yes，"
            "无开放时段与出校方向证据，审核不通过，不生成接驳路线。"
        ),
    }


def validate_connectors(connectors):
    ids = [connector["id"] for connector in connectors]
    if len(ids) != len(set(ids)):
        raise RuntimeError("接驳 ID 重复")
    approved = [connector for connector in connectors
                if connector["verificationStatus"] == "APPLICATION_VERIFIED"]
    if len(approved) < 2 or len({connector["originId"] for connector in approved}) < 2:
        raise RuntimeError("验收要求至少两个起点各有一条团队确认可用的演示接驳")
    for connector in approved:
        if TEAM_SOURCE_REF not in connector["sourceRefs"] or DEMO_RULE_REF not in connector["sourceRefs"]:
            raise RuntimeError(f"{connector['id']} 缺少团队确认与演示规则来源")
        if connector["missingEvidence"]:
            raise RuntimeError(
                f"{connector['id']} 仍列有 {len(connector['missingEvidence'])} 项缺失证据，"
                "不能标记 APPLICATION_VERIFIED"
            )
    with_route = [connector for connector in connectors if connector["segments"]]
    origins = {connector["originId"] for connector in with_route}
    if len(with_route) < 2 or len(origins) < 2:
        raise RuntimeError("验收要求至少两个起点各有一条可核对的接驳（候选或已审核）")
    for connector in with_route:
        status = connector["verificationStatus"]
        if connector["gateExitPoint"] is None:
            raise RuntimeError(f"{connector['id']} 缺少 gateExitPoint")
        outbound = [
            point for point in connector["gatePoints"]
            if point["direction"] == "OUTBOUND"
        ]
        if len(outbound) != 1 or outbound[0]["pointId"] != connector["gateExitPoint"]["pointId"]:
            raise RuntimeError(f"{connector['id']} 的 gateExitPoint 不属于该物理出校点")
        if connector["gateId"] != EAST_GATE_ID:
            raise RuntimeError(f"{connector['id']} 校门 ID 与出校物理点不匹配")
        segment_sum = sum(segment["lengthMeters"] for segment in connector["segments"])
        if round(segment_sum, 2) != connector["walkMeters"]:
            raise RuntimeError(f"{connector['id']} 总米数与分段和不一致")
        duration_sum = sum(segment["durationSeconds"] for segment in connector["segments"])
        if duration_sum != connector["durationSeconds"]:
            raise RuntimeError(f"{connector['id']} 总秒数与分段和不一致")
        expected_durations = [
            math.ceil(segment["lengthMeters"] / WALKING_SPEED_MPS) for segment in connector["segments"]
        ]
        if expected_durations != [segment["durationSeconds"] for segment in connector["segments"]]:
            raise RuntimeError(f"{connector['id']} 分段秒数与 1.33 m/s 口径不一致")
        first = connector["segments"][0]
        last = connector["segments"][-1]
        if first["geometry"]["points"][0] != [connector["originPoint"]["longitude"], connector["originPoint"]["latitude"]]:
            raise RuntimeError(f"{connector['id']} 几何起点与 originPoint 不一致")
        if last["geometry"]["points"][-1] != [
            connector["gateExitPoint"]["longitude"], connector["gateExitPoint"]["latitude"]
        ]:
            raise RuntimeError(f"{connector['id']} 几何终点与 gateExitPoint 不一致")
        if first["fromPointId"] != connector["originPoint"]["pointId"]:
            raise RuntimeError(f"{connector['id']} 首段起点与 originPoint.pointId 不一致")
        if last["toPointId"] != connector["gateExitPoint"]["pointId"]:
            raise RuntimeError(f"{connector['id']} 末段终点与 gateExitPoint.pointId 不一致")
        for segment in connector["segments"]:
            if segment["passabilityStatus"] == "PROHIBITED":
                raise RuntimeError(f"{connector['id']} 含禁行分段 {segment['segmentId']}")
            if segment["geometry"]["coordinateSystem"] != "WGS84":
                raise RuntimeError(f"{connector['id']} 分段坐标系不是 WGS84")
        if status == "PENDING_VERIFICATION" and not connector["missingEvidence"]:
            raise RuntimeError(f"{connector['id']} 待核实候选接驳必须列出缺失证据")
    for connector in connectors:
        status = connector["verificationStatus"]
        if status != "APPLICATION_VERIFIED":
            if not connector["unavailableReasonCode"] or not connector["unavailableReason"]:
                raise RuntimeError(f"{connector['id']} 待核实/不通过连接缺少不可用原因")
        if status == "REJECTED":
            if (connector["segments"] or connector["walkMeters"] is not None
                    or connector["durationSeconds"] is not None):
                raise RuntimeError(f"{connector['id']} 不通过连接不应带接驳数字")
        if status == "APPLICATION_VERIFIED" and not connector["segments"]:
            raise RuntimeError(f"{connector['id']} 已审核连接必须带接驳路线")


def main():
    nodes_list = load_json(NODES_PATH)
    edges = load_json(EDGES_PATH)
    nodes = {node["node_id"]: node for node in nodes_list}
    adjacency = build_graph(nodes, edges)

    library_distance, library_path = shortest_path(adjacency, nodes, LIBRARY_ACCESS_NODE, EAST_GATE_OUTBOUND_NODE)
    dorm1_distance, dorm1_path = shortest_path(adjacency, nodes, DORM1_ACCESS_NODE, EAST_GATE_OUTBOUND_NODE)

    library_connector = approved_demo_connector(
        nodes, library_path, "connector_library_east_gate",
        LIBRARY_ORIGIN_ID, LIBRARY_ORIGIN_NAME, LIBRARY_ACCESS_NODE,
        LIBRARY_SEGMENT_DESCRIPTIONS,
        verification_basis=[
            "S04 OSM WGS84：图书馆楼外接入点 osm_node_5167087319，经步行区、校园道路与知行路连接东门。",
            "S01/S02 总平面图对照建筑与周边路径；演示起点使用楼外路网点，不要求精确门位。",
            "东门出校点与 W1-04 campus_gate_east 对齐：osm_node_13469658219。",
            f"{AMAP_WALK_REF}：对转换后的楼外接入点和东门出校点复查，返回 894 米、715 秒，终点接近传入出校点。",
        ],
    )
    dorm1_connector = approved_demo_connector(
        nodes, dorm1_path, "connector_dorm1_east_gate",
        DORM1_ORIGIN_ID, DORM1_ORIGIN_NAME, DORM1_ACCESS_NODE,
        DORM1_SEGMENT_DESCRIPTIONS,
        verification_basis=[
            "S04 OSM WGS84：天佑斋1号楼外接入点 osm_node_9893299669，经宿舍区通道与知行路连接东门。",
            "S01/S02 总平面图对照建筑与周边路径；演示起点使用楼外路网点，不要求精确门位。",
            "东门出校点与 W1-04 campus_gate_east 对齐：osm_node_13469658219。",
            f"{AMAP_WALK_REF}：对转换后的楼外接入点和东门出校点复查，返回 1830 米、1464 秒，终点接近传入出校点。",
        ],
    )

    connectors = [
        library_connector,
        dorm1_connector,
        pending_connector(nodes),
        rejected_connector(nodes),
    ]
    validate_connectors(connectors)

    payload = {
        "dataVersion": DATA_VERSION,
        "description": (
            "校园接驳：图书馆、天佑斋1号楼使用楼外路网接入点，经东门衔接城市段。"
            "已启用的东门接驳按2026-11-19项目规则不设门禁时段。"
            "几何与距离来自S04 OSM WGS84路网，耗时按1.33 m/s估算。"
            "西门有高德展示点，城市接驳待核实；西南门仍为拒绝，明确禁行边继续排除。"
        ),
        "connectors": connectors,
    }
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_PATH.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"已写入 {OUTPUT_PATH}")
    print(f"dataVersion={DATA_VERSION}, connectors={len(connectors)}")
    for connector in connectors:
        status = connector["verificationStatus"]
        if connector["segments"]:
            print(
                f"- {connector['id']}: status={status} origin={connector['originId']} gate={connector['gateId']} "
                f"walkMeters={connector['walkMeters']} durationSeconds={connector['durationSeconds']} "
                f"segments={len(connector['segments'])} reason={connector['unavailableReasonCode']}"
            )
            for segment in connector["segments"]:
                print(
                    f"    {segment['segmentId']} order={segment['order']:>2} "
                    f"len={segment['lengthMeters']:>7.2f} sec={segment['durationSeconds']:>4} "
                    f"status={segment['passabilityStatus']:<18} highway={segment['highway']:<13} "
                    f"way={segment['osmWayIds'][0]}"
                )
        else:
            print(f"- {connector['id']}: status={status} reason={connector['unavailableReasonCode']}")


if __name__ == "__main__":
    main()
