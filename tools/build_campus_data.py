# -*- coding: utf-8 -*-
"""
构建前端可用的压缩数据文件。
输入: source-data/校园数据 (原始数据包)
输出: web/data/campus.json + campus.js
"""
import json, os, sys, io, math

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'source-data', '校园数据')
OUT = os.path.join(ROOT, 'web', 'data')
os.makedirs(OUT, exist_ok=True)


def load(p):
    with open(os.path.join(SRC, p), encoding='utf-8') as f:
        return json.load(f)


nodes = load('cleaned/graph_nodes_candidates.json')
edges = load('cleaned/graph_edges_candidates.json')
gates = load('cleaned/campus_gate_candidates.json')
places = load('cleaned/osm_campus_places.json')
buildings_gj = load('geojson/osm_buildings.geojson')
boundary_gj = load('geojson/osm_campus_boundary.geojson')
transit = load('cleaned/nearby_transit_osm.json')
official = load('cleaned/official_places.json')
aliases = load('cleaned/place_aliases.json')
mvp = load('cleaned/mvp_seed_candidates.json')
shuttle = load('cleaned/historical_shuttle_departures_2022.json')
changes = load('cleaned/shuttle_changes_2025.json')
connector = load('cleaned/connector_evidence.json')
construction = load('cleaned/construction_notices.json')
summary = load('dataset_summary.json')
unverified = load('待核实清单.json')

# ---------- 节点索引 ----------
node_ids = [n['node_id'] for n in nodes]
nidx = {nid: i for i, nid in enumerate(node_ids)}

node_arr = [[round(n['longitude'], 7), round(n['latitude'], 7)] for n in nodes]
edge_arr = []
PERM = {'unspecified': 0, 'conditional': 1, 'explicit_foot_tag': 2, 'prohibited': 3}
HW = {}
ENAMES = []
ename_idx = {}
for e in edges:
    hw = e['highway'] or 'unknown'
    if hw not in HW:
        HW[hw] = len(HW)
    a, b = nidx.get(e['from_node_id']), nidx.get(e['to_node_id'])
    if a is None or b is None:
        continue
    nm = e.get('name') or ''
    if nm not in ename_idx:
        ename_idx[nm] = len(ENAMES)
        ENAMES.append(nm)
    edge_arr.append([
        a, b, round(e['length_m'], 1),
        PERM.get(e['walk_permission'], 0),
        HW[hw],
        1 if e['bridge'] == 'yes' else 0,
        1 if e['crosses_campus_boundary'] else 0,
        ename_idx[nm],
    ])

# ---------- 建筑轮廓 ----------
buildings = []
for f in buildings_gj['features']:
    p = f['properties']
    g = f['geometry']
    if g['type'] != 'Polygon':
        continue
    ring = [[round(x, 6), round(y, 6)] for x, y in g['coordinates'][0]]
    buildings.append({
        'n': p.get('name') or p.get('name_zh'),
        'c': p.get('category'),
        'r': ring,
        'id': p.get('place_id'),
    })

# ---------- 地点（可搜索） ----------
place_list = []
for p in places:
    name = p.get('name') or p.get('name_zh')
    if not name:
        continue
    place_list.append({
        'id': p['place_id'],
        'n': name,
        'c': p.get('category') or 'place',
        'x': round(p['longitude'], 6),
        'y': round(p['latitude'], 6),
        'r': p.get('position_role'),
    })

# MVP 候选地点（带"入口未核实"标记）
mvp_list = [{
    'id': p['place_id'],
    'n': p['name'],
    'c': p['category'],
    'x': round(p['longitude'], 6),
    'y': round(p['latitude'], 6),
} for p in mvp]

# ---------- 校门 ----------
gate_list = [{
    'id': g['gate_id'],
    'n': g['name'],
    'x': round(g['longitude'], 6),
    'y': round(g['latitude'], 6),
    'bd': g['boundary_distance_m'],
    'pass': g['current_passability'],
} for g in gates]

# 官方图上的西门对应两条车道。应用层用一个校门 ID，演示路线接到出校侧。
west_route_node_id = 'osm_node_11668769188'
west_node_ids = [west_route_node_id, 'osm_node_11668769187']
barriers_by_id = {g['gate_id']: g for g in load('cleaned/osm_barrier_points.json')}
west_label = next(p for p in load('cleaned/official_supplementary_names.json')
                  if p['label_id'] == 'LABEL_016')
if not all(node_id in nidx and node_id in barriers_by_id for node_id in west_node_ids):
    raise ValueError('西门关联的 OSM 门节点必须存在于门禁数据和路网中')
west_route_node = barriers_by_id[west_route_node_id]
gate_list.append({
    'id': 'campus_gate_west',
    'n': west_label['name'],
    'x': west_route_node['longitude'],
    'y': west_route_node['latitude'],
    'bd': west_route_node['boundary_distance_m'],
    'pass': west_route_node['current_passability'],
    'nodeIds': west_node_ids,
    'routeNodeId': west_route_node_id,
    'sourceIds': [west_label['source_id'], west_route_node['source_id']],
    'campusOnly': True,
})

# ---------- 周边交通点 ----------
transit_list = []
for t in transit:
    tag = t.get('tags') or {}
    kind = 'subway_entrance' if tag.get('railway') == 'subway_entrance' else (
        'subway' if tag.get('station') == 'subway' else (
            'tram' if tag.get('tram') == 'yes' else (
                'bus' if tag.get('highway') == 'bus_stop' or tag.get('amenity') == 'bus_station' else 'transit')))
    transit_list.append({
        'id': t['place_id'],
        'n': t.get('name') or (('出口 ' + tag['ref']) if tag.get('ref') else '未命名交通点'),
        'k': kind,
        'x': round(t['longitude'], 6),
        'y': round(t['latitude'], 6),
        'ref': tag.get('ref'),
    })

# ---------- 官方地点（用于别名搜索，无坐标） ----------
official_list = [{
    'id': p['place_id'],
    'n': p['name'],
    'c': p.get('category'),
    'cat': p.get('category_raw'),
    'has_xy': p.get('source_longitude') is not None,
} for p in official]

alias_map = {}
for a in aliases:
    alias_map.setdefault(a['alias'], []).append(a['place_id'])

# ---------- 校车 ----------
shuttle_list = [{
    't': s.get('departure_time'),
    'd': s.get('day_type'),
    'from': s.get('from_name'),
    'to': s.get('to_name'),
    'from_date': s.get('effective_from'),
    'note': s.get('exceptions'),
} for s in shuttle]
shuttle_changes = [{
    'id': c.get('change_id'),
    'type': c.get('change_type'),
    'from': c.get('from_name'),
    'to': c.get('to_name'),
    't': c.get('departure_time'),
    'd': c.get('day_type'),
    'trial': c.get('trial'),
    'from_date': c.get('effective_from'),
    'pub': c.get('source_published_at'),
} for c in changes]

# ---------- 道路几何（全部，用于底图；具名者单独标注） ----------
roads_gj = load('geojson/osm_campus_roads.geojson')
allroads = []
roadlines = []
for f in roads_gj['features']:
    p = f['properties']
    name = p.get('name')
    g = f['geometry']
    tags = p.get('tags') or {}
    hw = tags.get('highway') or ''
    lines = []
    if g['type'] == 'MultiLineString':
        lines = g['coordinates']
    elif g['type'] == 'LineString':
        lines = [g['coordinates']]
    for line in lines:
        coords = [[round(x, 6), round(y, 6)] for x, y in line]
        if len(coords) < 2:
            continue
        allroads.append([coords, hw])
        if name:
            roadlines.append({'n': name, 'l': coords})

campus = {
    'campus': summary['campus'],
    'collected_on': summary['collected_on'],
    'nodes': node_arr,
    'nodeIds': node_ids,
    'edges': edge_arr,
    'highways': list(HW.keys()),
    'edgeNames': ENAMES,
    'roads': allroads,
    'roadlines': roadlines,
    'buildings': buildings,
    'places': place_list,
    'mvp': mvp_list,
    'gates': gate_list,
    'transit': transit_list,
    'official': official_list,
    'aliases': alias_map,
    'shuttle': shuttle_list,
    'shuttle_changes': shuttle_changes,
    'connector': connector,
    'construction': construction,
    'boundary': boundary_gj['features'][0]['geometry']['coordinates'],
    'unverified': [{
        'id': u.get('issue_id'),
        'title': u.get('issue'),
        'priority': u.get('priority'),
        'impact': u.get('impact'),
        'action': u.get('action'),
        'dataset': u.get('dataset'),
    } for u in unverified],
}

with open(os.path.join(OUT, 'campus.json'), 'w', encoding='utf-8') as f:
    json.dump(campus, f, ensure_ascii=False, separators=(',', ':'))
# 同时输出 JS 版本，保证用 file:// 双击打开也能加载
with open(os.path.join(OUT, 'campus.js'), 'w', encoding='utf-8') as f:
    f.write('window.__CAMPUS_DATA__=')
    json.dump(campus, f, ensure_ascii=False, separators=(',', ':'))
    f.write(';\n')

print('campus.json', os.path.getsize(os.path.join(OUT, 'campus.json')) // 1024, 'KB')
print('nodes', len(node_arr), 'edges', len(edge_arr), 'roads', len(allroads),
      'buildings', len(buildings), 'places', len(place_list), 'gates', len(gate_list))
