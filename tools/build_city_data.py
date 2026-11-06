# -*- coding: utf-8 -*-
"""
城市段接驳数据（人工整理的公开常识数据，用于演示与算法占位）。
注意：城市段不是本数据包核验过的数据，页面中会显式标注 data_status。
"""
import json, os, sys, io, math, heapq

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'web', 'data')
os.makedirs(OUT, exist_ok=True)


def hav(a, b):
    R = 6371008.8
    lng1, lat1 = math.radians(a[0]), math.radians(a[1])
    lng2, lat2 = math.radians(b[0]), math.radians(b[1])
    dl = lng2 - lng1
    dp = lat2 - lat1
    h = math.sin(dp / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


# 犀浦校区西南门 / 地铁6号线兴业北街站
HUB = {'name': '犀浦校区', 'lng': 103.9825419, 'lat': 30.759961}

# 成都地铁线路（站点顺序，坐标为近似值，用于演示可视化）
LINES = [
    {
        'id': 'L2', 'name': '地铁2号线', 'color': '#0f9d58',
        'stations': [
            ['犀浦', 103.9730, 30.7605],
            ['天河路', 103.9886, 30.7412],
            ['百草路', 104.0018, 30.7293],
            ['金周路', 104.0148, 30.7215],
            ['金科北路', 104.0272, 30.7183],
            ['迎宾大道', 104.0408, 30.7127],
            ['茶店子客运站', 104.0522, 30.7071],
            ['羊犀立交', 104.0625, 30.7032],
            ['一品天下', 104.0722, 30.6991],
            ['蜀汉路东', 104.0808, 30.6958],
            ['白果林', 104.0896, 30.6925],
            ['中医大省医院', 104.0980, 30.6893],
            ['通惠门', 104.1060, 30.6866],
            ['人民公园', 104.1128, 30.6842],
            ['天府广场', 104.1187, 30.6819],
            ['春熙路', 104.1260, 30.6797],
            ['东门大桥', 104.1330, 30.6773],
            ['牛市口', 104.1430, 30.6748],
            ['东大路', 104.1523, 30.6723],
            ['塔子山公园', 104.1613, 30.6697],
            ['成都东客站', 104.1713, 30.6669],
        ],
    },
    {
        'id': 'L6', 'name': '地铁6号线', 'color': '#c75b12',
        'stations': [
            ['望丛祠', 103.9210, 30.7823],
            ['犀浦', 103.9730, 30.7605],
            ['兴业北街', 103.9800, 30.7631],
            ['梓潼宫', 103.9900, 30.7533],
            ['侯家桥', 104.0033, 30.7432],
            ['兴盛', 104.0158, 30.7350],
            ['金石路', 104.0290, 30.7282],
            ['西南交大', 104.0418, 30.7220],
            ['沙湾', 104.0560, 30.7150],
            ['梁家巷', 104.0702, 30.7082],
            ['前锋路', 104.0838, 30.7018],
            ['建设北路', 104.0972, 30.6958],
            ['玉双路', 104.1105, 30.6893],
            ['牛王庙', 104.1232, 30.6828],
            ['东光', 104.1360, 30.6762],
            ['琉璃场', 104.1488, 30.6697],
        ],
    },
    {
        'id': 'L7', 'name': '地铁7号线（环线）', 'color': '#1a73e8',
        'stations': [
            ['火车北站', 104.0740, 30.6990],
            ['驷马桥', 104.0832, 30.7072],
            ['花牌坊', 104.0920, 30.7152],
            ['西南交大', 104.0418, 30.7220],
            ['九里堤', 104.0510, 30.7280],
            ['火车南站', 104.0690, 30.6062],
            ['神仙树', 104.0620, 30.6155],
            ['武侯大道', 104.0300, 30.6280],
            ['太平园', 104.0180, 30.6380],
        ],
    },
]

# 城市目的地（教学/生活/出行常用）
DESTINATIONS = [
    {'id': 'C01', 'name': '天府广场', 'lng': 104.1187, 'lat': 30.6819, 'cat': '城市中心',
     'line': 'L2', 'station': '天府广场', 'note': '地铁2号线直达'},
    {'id': 'C02', 'name': '春熙路（太古里）', 'lng': 104.1260, 'lat': 30.6797, 'cat': '商圈',
     'line': 'L2', 'station': '春熙路', 'note': '地铁2号线直达'},
    {'id': 'C03', 'name': '成都东客站', 'lng': 104.1713, 'lat': 30.6669, 'cat': '交通枢纽',
     'line': 'L2', 'station': '成都东客站', 'note': '地铁2号线直达'},
    {'id': 'C04', 'name': '成都站（火车北站）', 'lng': 104.0740, 'lat': 30.6990, 'cat': '交通枢纽',
     'line': 'L7', 'station': '火车北站', 'note': '需换乘7号线'},
    {'id': 'C05', 'name': '成都南站', 'lng': 104.0690, 'lat': 30.6062, 'cat': '交通枢纽',
     'line': 'L7', 'station': '火车南站', 'note': '需换乘7号线'},
    {'id': 'C06', 'name': '双流国际机场 T2', 'lng': 103.9526, 'lat': 30.5785, 'cat': '机场',
     'line': 'L2', 'station': '犀浦', 'note': '地铁+机场专线，城市段以地图服务为准'},
    {'id': 'C07', 'name': '西南交大九里校区', 'lng': 104.0510, 'lat': 30.6990, 'cat': '校区',
     'line': 'L7', 'station': '西南交大', 'note': '地铁6/7号线可达'},
    {'id': 'C08', 'name': '宽窄巷子', 'lng': 104.0615, 'lat': 30.6698, 'cat': '景区',
     'line': 'L2', 'station': '通惠门', 'note': '地铁2号线转步行'},
    {'id': 'C09', 'name': '武侯祠／锦里', 'lng': 104.0480, 'lat': 30.6458, 'cat': '景区',
     'line': 'L7', 'station': '武侯大道', 'note': '需换乘+步行'},
    {'id': 'C10', 'name': '成都博物馆', 'lng': 104.1140, 'lat': 30.6780, 'cat': '文化',
     'line': 'L2', 'station': '天府广场', 'note': '地铁2号线直达'},
]

# 校门 → 城市接驳点
# 距离优先采用可追溯的 OSM 步行路线查询；无可用路线时才保留 null，
# 不以固定数值或几何平均替代。原始文字记录保留在 evidence 中。
ROUTE_SERVICE = 'routing.openstreetmap.de'
ROUTE_PROFILE = 'routed-foot'
ROUTE_QUERY_DATE = '2026-11-06'

CON01 = {
    'evidence_id': 'CON01',
    'from_name': '兴业北街站D2出口',
    'to_name': '犀浦校区西门',
    'mode': 'walking',
    'distance_m': 500,
    'distance_precision': 'source_approximate',
    'source_id': 'S07',
    'source_published_at': '2023-12-19',
    'context': '2024年硕士研究生考试到校说明；不延用其考试通行限制作为日常规则',
    'current_verified': False,
    'geometry': None,
}
CON03 = {
    'evidence_id': 'CON03',
    'from_name': '交大兴业北街站D2口',
    'to_name': '犀浦校区西门',
    'mode': 'walking',
    'distance_m': 300,
    'distance_precision': 'source_approximate',
    'source_id': 'S11',
    'source_published_at': '2025-08-08',
    'context': '2025级新生报到指南；与CON01的500米描述不同，保留两条证据等待核实',
    'current_verified': False,
    'geometry': None,
}


def ep(node_id, name, lng, lat, role):
    return {'node_id': node_id, 'name': name, 'lng': lng, 'lat': lat,
            'crs': 'WGS84', 'role': role}


def route_meta(a, b, distance_m, duration_s, snap_from, snap_to):
    return {
        'service': ROUTE_SERVICE,
        'api': 'OSRM',
        'profile': ROUTE_PROFILE,
        'query_date': ROUTE_QUERY_DATE,
        'distance_m': distance_m,
        'duration_s': duration_s,
        'waypoint_snap_m': [snap_from, snap_to],
        'request': ('https://routing.openstreetmap.de/routed-foot/route/v1/foot/'
                    '%s,%s;%s,%s?overview=full&geometries=geojson&steps=true&annotations=false'
                    % (a['lng'], a['lat'], b['lng'], b['lat'])),
    }


def connection(gate, gate_id, hub, hub_id, from_ep, to_ep, gate_match, hub_match,
               walk_m=None, duration_s=None, snap=None, evidence=None,
               endpoint_note=None, confidence='medium', metro=None):
    """生成一条接驳记录。

    校门对应关系未确认时，不输出任何步行/直线距离（walk_m、straight_m 及
    route 全部置空），页面只能显示“接驳待核实”。接驳点站名未确认时，
    不输出步行路线，仅在两端点坐标可用时保留直线距离。
    """
    gate_ok = gate_match.get('status') == 'confirmed'
    hub_ok = hub_match.get('status') == 'confirmed'

    straight = None
    if gate_ok and to_ep:
        straight = round(hav((from_ep['lng'], from_ep['lat']),
                             (to_ep['lng'], to_ep['lat'])), 1)

    if not gate_ok:
        walk_m = None
        duration_s = None
        snap = None
        straight = None
    elif walk_m is not None and not hub_ok:
        # 站名/入口对应未确认时，不把路线距离当作到该接驳点的步行距离。
        walk_m = None
        duration_s = None
        snap = None

    if walk_m is not None:
        walk_status = 'route'
        walk_source = '地图估算'
    elif straight is not None:
        walk_status = 'straight'
        walk_source = None
    else:
        walk_status = 'pending'
        walk_source = None

    rec = {
        'gate': gate,
        'gate_id': gate_id,
        'hub': hub,
        'hub_id': hub_id,
        'walk_m': walk_m,
        'walk_display_m': int(round(walk_m)) if walk_m is not None else None,
        'straight_m': straight,
        'walk_status': walk_status,
        'walk_source': walk_source,
        'gate_match': gate_match,
        'hub_match': hub_match,
        'endpoints': {'from': from_ep, 'to': to_ep},
        'route': route_meta(from_ep, to_ep, walk_m, duration_s, snap[0], snap[1])
                 if walk_m is not None else None,
        'metro': metro,
        'evidence': evidence or [],
        'confidence': confidence,
    }
    if endpoint_note:
        rec['endpoint_note'] = endpoint_note
    return rec


def metro_itinerary(origin_line_id, origin_station, dest_line_id, dest_station):
    """按城市段线路站序生成换乘 itinerary。

    只使用数据中已有的线路/站点名称，不按目的地线路反推“直达”。
    返回 {text, legs, transfers, destination_station}；无路径时返回 None。
    """
    lines = {l['id']: l for l in LINES}
    if origin_line_id not in lines:
        return None
    start = (origin_line_id, origin_station)
    if origin_station == dest_station:
        line = lines[origin_line_id]
        leg = {'line_id': origin_line_id, 'line_name': line['name'],
               'line_color': line['color'], 'from': origin_station,
               'to': dest_station, 'stops': 0}
        return {'text': line['name'] + ' ' + origin_station, 'legs': [leg],
                'transfers': [], 'destination_station': dest_station}

    best = {start: 0}
    prev = {}
    pq = [(0, start)]
    goal = None

    while pq:
        cost, state = heapq.heappop(pq)
        if cost != best.get(state):
            continue
        line_id, station = state
        if station == dest_station:
            goal = state
            break

        line = lines[line_id]
        names = [s[0] for s in line['stations']]
        if station in names:
            i = names.index(station)
            for j in (i - 1, i + 1):
                if 0 <= j < len(names):
                    nxt = (line_id, names[j])
                    nc = cost + 1
                    if nc < best.get(nxt, 10 ** 9):
                        best[nxt] = nc
                        prev[nxt] = (state, ('ride', line_id, station, names[j]))
                        heapq.heappush(pq, (nc, nxt))

        # 换乘惩罚高于乘车，优先选择少换乘方案。
        for other_id, other in lines.items():
            if other_id == line_id:
                continue
            if any(s[0] == station for s in other['stations']):
                nxt = (other_id, station)
                nc = cost + 6
                if nc < best.get(nxt, 10 ** 9):
                    best[nxt] = nc
                    prev[nxt] = (state, ('transfer', station, line_id, other_id))
                    heapq.heappush(pq, (nc, nxt))

    if goal is None:
        return None

    edges = []
    cur = goal
    while cur != start:
        parent, edge = prev[cur]
        edges.append(edge)
        cur = parent
    edges.reverse()

    legs, transfers = [], []
    current = None
    for edge in edges:
        if edge[0] == 'ride':
            _, lid, a, b = edge
            if current is None or current['line_id'] != lid:
                if current:
                    legs.append(current)
                current = {'line_id': lid, 'line_name': lines[lid]['name'],
                           'line_color': lines[lid]['color'],
                           'from': a, 'to': b, 'stops': 1}
            else:
                current['to'] = b
                current['stops'] += 1
        else:
            _, station, _, _ = edge
            transfers.append(station)
            if current:
                legs.append(current)
                current = None
    if current:
        legs.append(current)
    if not legs:
        return None

    parts = []
    for i, leg in enumerate(legs):
        if i == 0:
            parts.append(leg['line_name'] + ' ' + leg['from'] + ' → ' + leg['to'])
        else:
            parts.append('换乘 ' + leg['line_name'] + ' → ' + leg['to'])
    return {
        'text': '；'.join(parts),
        'legs': legs,
        'transfers': transfers,
        'destination_station': dest_station,
    }


GATE_CONNECTIONS = [
    connection(
        '西门', 'campus_gate_west', '兴业北街站 D2口', 'osm_node_8547581358',
        ep('osm_node_11668769188', '西门（应用层校门实体）', 103.9792051, 30.7644976, 'gate_exit'),
        ep('osm_node_8547581358', '兴业北街站 D2 地面入口', 103.9806476, 30.7620674, 'transit_entrance'),
        {'status': 'confirmed', 'level': 'application_entity',
         'basis': '应用层 campus_gate_west：官方图标签“西门”（S01，LABEL_016，仅名称线索）对应 OSM 西侧门节点 osm_node_11668769188（S04）；未现场核实校门开放状态。'},
        {'status': 'confirmed',
         'basis': 'OSM 节点 ref=D2，railway=subway_entrance；position_role=mapped_point，routing_ready=false。'},
        walk_m=328.9, duration_s=263.4, snap=(0, 3.847615835),
        evidence=[CON01, CON03],
        endpoint_note='起点为西侧门内路网接入点，终点为 D2 地面入口；OSRM 终点 snap 3.85m，原始入口坐标仍保留在 endpoints。',
        confidence='medium',
        metro={'line_id': 'L6', 'station': '兴业北街'},
    ),
    connection(
        '东门', 'campus_gate_east', '交大犀浦校区有轨电车站', 'osm_node_7093110874',
        ep('osm_node_13469658219', '东门出校节点', 103.991294, 30.771698, 'gate_exit'),
        ep('osm_node_7093110874', '交大犀浦校区有轨电车站', 103.9794495, 30.7633205, 'transit_stop'),
        {'status': 'confirmed', 'level': 'application_entity',
         'basis': 'campus_gate_east 应用层实体；OSM 东门进/出车道已合并，通行状态未核实。'},
        {'status': 'confirmed',
         'basis': 'OSM railway=tram_stop 名称与资料一致；position_role=mapped_point，routing_ready=false。'},
        walk_m=1824.2, duration_s=1459.1, snap=(0.11, 1.69),
        confidence='medium',
    ),
    connection(
        '南门', 'osm_node_2178309445', '西南交大犀浦校区公交站', 'osm_node_2690053659',
        ep('osm_node_2178309445', '南门', 103.98979, 30.762968, 'gate_exit'),
        ep('osm_node_2690053659', '南门旁未命名公交站', 103.9909539, 30.7630853, 'transit_stop'),
        {'status': 'confirmed', 'level': 'campus_entity',
         'basis': 'campus.json 校门实体“南门”，OSM 节点 osm_node_2178309445。'},
        {'status': 'unconfirmed',
         'basis': 'OSM 公交站无名称（name=null），不能确认其站名即“西南交大犀浦校区公交站”。'},
        walk_m=None,
        endpoint_note='仅有端点坐标（OSM mapped_point，未现场验收）：直线距离由两端点 Haversine 计算；步行距离留空，公交站名对应未核实。',
        confidence='low',
    ),
    connection(
        '西北一门', 'osm_node_2178323767', '犀方路有轨电车站', 'osm_node_7093170783',
        ep('osm_node_2178323767', '西北一门', 103.975188, 30.769938, 'gate_exit'),
        ep('osm_node_7093170783', '犀方路有轨电车站', 103.9748279, 30.7692228, 'transit_stop'),
        {'status': 'confirmed', 'level': 'campus_entity',
         'basis': 'campus.json 校门实体“西北一门”，OSM 节点 osm_node_2178323767。'},
        {'status': 'confirmed',
         'basis': 'OSM railway=tram_stop 名称与资料一致；position_role=mapped_point，routing_ready=false。'},
        walk_m=274.3, duration_s=219.5, snap=(0, 1.97),
        confidence='medium',
    ),
    connection(
        '风洞门', 'osm_node_8386702126', '犀方路有轨电车站', 'osm_node_7093170783',
        ep('osm_node_8386702126', '风洞门', 103.980138, 30.773106, 'gate_exit'),
        ep('osm_node_7093170783', '犀方路有轨电车站', 103.9748279, 30.7692228, 'transit_stop'),
        {'status': 'confirmed', 'level': 'campus_entity',
         'basis': 'campus.json 校门实体“风洞门”，OSM 节点 osm_node_8386702126。'},
        {'status': 'confirmed',
         'basis': 'OSM railway=tram_stop 名称与资料一致；position_role=mapped_point，routing_ready=false。'},
        walk_m=813.9, duration_s=651.1, snap=(0, 1.97),
        confidence='medium',
    ),
    connection(
        '东门', 'campus_gate_east', '林湾公交站', None,
        ep('osm_node_13469658219', '东门出校节点', 103.991294, 30.771698, 'gate_exit'),
        None,
        {'status': 'confirmed', 'level': 'application_entity',
         'basis': 'campus_gate_east 应用层实体；OSM 东门进/出车道已合并，通行状态未核实。'},
        {'status': 'unconfirmed',
         'basis': '源数据只有“林湾公交站”名称线索，无可用坐标，无法查询路线。'},
        endpoint_note='缺林湾公交站坐标，未能查询步行路线；不填固定距离。',
        confidence='low',
    ),
]

# 有明确地铁起点站的接驳记录，按线路站序预生成到各城市目的地的换乘 itinerary。
# 没有起点站资料的公交/有轨电车接驳不生成，页面显示“城市段线路待核实”。
for c in GATE_CONNECTIONS:
    if (not c.get('metro') or
            c['gate_match'].get('status') != 'confirmed' or
            c['hub_match'].get('status') != 'confirmed'):
        continue
    c['itineraries'] = {}
    for d in DESTINATIONS:
        it = metro_itinerary(c['metro']['line_id'], c['metro']['station'],
                             d['line'], d['station'])
        if it:
            c['itineraries'][d['id']] = it

for d in DESTINATIONS:
    d['dist_km'] = round(hav((HUB['lng'], HUB['lat']), (d['lng'], d['lat'])) / 1000, 1)

city = {
    'data_status': 'manual_public_common_knowledge',
    'data_note': '城市段数据为人工整理的公开常识，未经本项目核验，仅用于演示与方案对比；校门接驳距离优先采用 OpenStreetMap routed-foot 路线查询结果（2026-11-06）；校门对应未确认时不输出步行/直线距离，无可用端点或站名未确认时保留 null，原始文字记录保留在 evidence 中。',
    'gate_connection_method': '接驳距离优先采用可追溯的 OSM 步行路线；校门对应未确认时一律显示接驳待核实，不输出坐标或路线推导的距离；仅有端点坐标且校门对应已确认时只显示直线距离，walk_m 为 null；地铁接驳的换乘信息由线路站点序列生成，不按目的地线路反推直达。',
    'route_query': {'service': ROUTE_SERVICE, 'profile': ROUTE_PROFILE, 'crs': 'WGS84', 'query_date': ROUTE_QUERY_DATE},
    'hub': HUB,
    'lines': [{
        'id': l['id'], 'name': l['name'], 'color': l['color'],
        'stations': [{'name': s[0], 'lng': s[1], 'lat': s[2]} for s in l['stations']],
    } for l in LINES],
    'destinations': DESTINATIONS,
    'gate_connections': GATE_CONNECTIONS,
}

with open(os.path.join(OUT, 'city.json'), 'w', encoding='utf-8') as f:
    json.dump(city, f, ensure_ascii=False, separators=(',', ':'))
with open(os.path.join(OUT, 'city.js'), 'w', encoding='utf-8') as f:
    f.write('window.__CITY_DATA__=')
    json.dump(city, f, ensure_ascii=False, separators=(',', ':'))
    f.write(';\n')
print('city ok', os.path.getsize(os.path.join(OUT, 'city.json')) // 1024, 'KB')
