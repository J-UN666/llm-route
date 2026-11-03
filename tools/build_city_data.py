# -*- coding: utf-8 -*-
"""
城市段接驳数据（人工整理的公开常识数据，用于演示与算法占位）。
注意：城市段不是本数据包核验过的数据，页面中会显式标注 data_status。
"""
import json, os, sys, io, math

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
            ['兴业北街', 103.9800, 30.7631],
            ['犀浦', 103.9730, 30.7605],
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

# 校门 → 城市接驳点（步行段为估算，页面标注）
GATE_CONNECTIONS = [
    {'gate': '西南门', 'hub': '兴业北街站 D2口', 'walk_m': 400,
     'evidence': 'CON01/CON03 官方文字分别记 500m 与 300m，本包保留冲突', 'confidence': 'low'},
    {'gate': '东门', 'hub': '交大犀浦校区有轨电车站', 'walk_m': 350, 'evidence': 'OSM 站点位置邻近东门', 'confidence': 'medium'},
    {'gate': '南门', 'hub': '西南交大犀浦校区公交站', 'walk_m': 500, 'evidence': 'OSM 公交站点位置邻近南门', 'confidence': 'medium'},
    {'gate': '西北一门', 'hub': '犀方路有轨电车站', 'walk_m': 300, 'evidence': 'OSM 站点位置邻近西北一门', 'confidence': 'medium'},
    {'gate': '风洞门', 'hub': '犀方路有轨电车站', 'walk_m': 700, 'evidence': '按几何距离估算', 'confidence': 'low'},
    {'gate': '东门', 'hub': '林湾公交站', 'walk_m': 900, 'evidence': '按几何距离估算', 'confidence': 'low'},
]

for d in DESTINATIONS:
    d['dist_km'] = round(hav((HUB['lng'], HUB['lat']), (d['lng'], d['lat'])) / 1000, 1)

city = {
    'data_status': 'manual_public_common_knowledge',
    'data_note': '城市段数据为人工整理的公开常识，未经本项目核验，仅用于演示与方案对比；正式出行请以地图服务为准。',
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
