/* =============================================================================
 * chengdu-map.js — 高德地图（成都）独立补充模块
 * -----------------------------------------------------------------------------
 * 用途：在不改动现有校园地图代码的前提下，为页面增加「成都市区地图」能力。
 *
 * 接入方式（只需两步，无需改任何已有 js/html 结构）：
 *   1. 把本文件放到 web/ 目录下
 *   2. 在 index.html 的 </body> 前加一行：
 *        <script src="chengdu-map.js"></script>
 *
 * 首次打开会弹出配置框，粘贴「Web端(JS API) Key」和「安全密钥」即可，
 * 配置保存在 localStorage，不用改代码、不用重新部署。
 *
 * 对外 API（可选，供你自己的路线规划调用）：
 *   window.ChengduMap.open()                     打开成都地图
 *   window.ChengduMap.close()                    关闭
 *   window.ChengduMap.toggle()                   开关切换
 *   window.ChengduMap.routeTo('天府广场')         直接规划到某个目的地
 *   window.ChengduMap.setOrigin(lng, lat, '西门') 设置起点（WGS84 坐标）
 *   window.ChengduMap.setMode('transfer'|'driving'|'walking')
 *
 * 坐标系说明（重要）：
 *   本项目校园数据是 WGS84；高德底图与接口是 GCJ-02。
 *   直接把 WGS84 画到高德上会偏移约 400~600 米，因此本文件在送入高德前
 *   统一做 WGS84 → GCJ-02 转换（见 wgs2gcj）。
 * ========================================================================== */
(function () {
  'use strict';

  if (window.__CHENGDU_MAP_LOADED__) return;
  window.__CHENGDU_MAP_LOADED__ = true;

  /* =====================================================================
   * 一、配置 —— 【只需要改这两个字符串】
   * ---------------------------------------------------------------------
   * 去高德开放平台（console.amap.com）→ 应用管理 → 我的应用：
   *   1) 添加 Key，服务平台选「Web端(JS API)」
   *   2) 在该 Key 下方点「安全密钥」，生成 32 位 securityJsCode
   * 然后把两串字符分别粘到下面 key 和 securityJsCode 的引号里。
   *
   * ⚠ 注意：
   *   · 一定要是「Web端(JS API)」的 Key，用「Web服务」的 Key 会报
   *     USERKEY_PLAT_NOMATCH。
   *   · 只填 Key 不填安全密钥，v2.0 会报 INVALID_USER_SCODE。
   *   · 本地用 http://localhost 调试；file:// 或未加白名单的域名会被拒绝。
   *
   * 如果留空不填，首次打开页面时会弹出一个配置框让你粘进去，
   * 配置只存在本机浏览器，同样可用。
   * =================================================================== */
  var CFG = {
    key: '',              // ← 建议不要写在这里，改填到 key.local.js（见下）
    securityJsCode: '',   // ← 同上

    version: '2.0',
    city: '成都',
    cityCode: '028',
    center: [104.0668, 30.5728],  // 成都市中心（GCJ-02）
    zoom: 11,
    defaultOrigin: [103.9825419, 30.759961] // 犀浦校区西南门（WGS84）
  };

  /* 清理粘贴时常见的脏字符：首尾空格、换行、误带的引号
   * （很多人从高德控制台复制时会把引号一起粘进来） */
  function cleanCred(v) {
    return String(v == null ? '' : v)
      .replace(/\s+/g, '')
      .replace(/^["'`]+/, '')
      .replace(/["'`]+$/, '');
  }

  /* ---------------------------------------------------------------------
   * 外部配置覆盖：key.local.js 里写 window.CHENGDU_MAP_CONFIG = {...}
   *
   * 这样做的目的：本文件（chengdu-map.js）以后升级时可以直接覆盖，
   * 你的 Key 放在 key.local.js 里不受影响，也不会被提交到 GitHub。
   * 只覆盖非空值，避免空字符串把已有的 Key 冲掉。
   * 加载顺序随意 —— 这里在启动时还会再读一次。
   * ------------------------------------------------------------------- */
  function applyExternalConfig() {
    var ext = window.CHENGDU_MAP_CONFIG;
    if (ext) {
      for (var k in ext) {
        if (Object.prototype.hasOwnProperty.call(ext, k) && ext[k] != null && ext[k] !== '') {
          CFG[k] = ext[k];
        }
      }
    }
    CFG.key = cleanCred(CFG.key);
    CFG.securityJsCode = cleanCred(CFG.securityJsCode);
  }

  applyExternalConfig();

  /* localStorage 只在上面都没填的情况下兜底
   * （即：代码里填了就以代码为准，避免旧的浏览器缓存覆盖你的填写） */
  try {
    if (!CFG.key) CFG.key = cleanCred(localStorage.getItem('cdmap_key') || '');
    if (!CFG.securityJsCode) CFG.securityJsCode = cleanCred(localStorage.getItem('cdmap_secret') || '');
  } catch (e) { /* 隐私模式忽略 */ }

  /* =====================================================================
   * 二、坐标系转换 WGS84 <-> GCJ-02
   * =================================================================== */
  var PI = Math.PI;
  var AXIS = 6378245.0;
  var EE = 0.00669342162296594323;

  function outOfChina(lng, lat) {
    return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
  }
  function tLat(x, y) {
    var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
    r += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
    r += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
    r += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
    return r;
  }
  function tLng(x, y) {
    var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
    r += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
    r += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
    r += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
    return r;
  }
  /** WGS84 → GCJ-02，返回 [lng, lat] */
  function wgs2gcj(lng, lat) {
    if (outOfChina(lng, lat)) return [lng, lat];
    var dLat = tLat(lng - 105.0, lat - 35.0);
    var dLng = tLng(lng - 105.0, lat - 35.0);
    var radLat = lat / 180.0 * PI;
    var magic = Math.sin(radLat);
    magic = 1 - EE * magic * magic;
    var sqrtMagic = Math.sqrt(magic);
    dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI);
    dLng = (dLng * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
    return [lng + dLng, lat + dLat];
  }
  /** GCJ-02 → WGS84（迭代反解，误差 < 1m） */
  function gcj2wgs(lng, lat) {
    var wlng = lng, wlat = lat;
    for (var i = 0; i < 3; i++) {
      var g = wgs2gcj(wlng, wlat);
      wlng += lng - g[0];
      wlat += lat - g[1];
    }
    return [wlng, wlat];
  }

  /* =====================================================================
   * 三、样式（自注入，不需要额外 css 文件）
   * =================================================================== */
  var CSS = [
    '.cdmap-fab{position:absolute;right:16px;bottom:70px;z-index:30;display:inline-flex;align-items:center;gap:7px;',
    'padding:9px 15px;border:none;border-radius:10px;cursor:pointer;font:600 13px/1 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;',
    'color:#fff;background:linear-gradient(135deg,#1d6fbf,#2b8ce0);box-shadow:0 4px 14px rgba(29,111,191,.42);',
    'transition:transform .15s,box-shadow .15s}',
    '.cdmap-fab:hover{transform:translateY(-2px);box-shadow:0 8px 22px rgba(29,111,191,.5)}',
    '.cdmap-fab:active{transform:translateY(0)}',

    '.cdmap-mask{position:fixed;inset:0;background:rgba(10,20,35,.55);z-index:9000;display:none;backdrop-filter:blur(3px)}',
    '.cdmap-mask.on{display:block}',
    '.cdmap-panel{position:fixed;inset:3vh 3vw;background:#fff;border-radius:16px;z-index:9001;display:none;',
    'flex-direction:column;overflow:hidden;box-shadow:0 24px 70px rgba(0,0,0,.35);font:14px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}',
    '.cdmap-panel.on{display:flex}',
    '.cdmap-head{display:flex;align-items:center;gap:12px;padding:12px 18px;background:linear-gradient(100deg,#0b2545,#164a7d);color:#fff;flex-shrink:0}',
    '.cdmap-head h3{margin:0;font-size:15.5px;font-weight:650;letter-spacing:.02em}',
    '.cdmap-head .cdmap-sub{font-size:11.5px;color:rgba(255,255,255,.62);margin-left:2px}',
    '.cdmap-head .cdmap-sp{flex:1}',
    '.cdmap-x{width:30px;height:30px;border:none;border-radius:8px;background:rgba(255,255,255,.14);color:#fff;font-size:16px;cursor:pointer;line-height:1}',
    '.cdmap-x:hover{background:rgba(255,255,255,.26)}',

    '.cdmap-body{display:flex;flex:1;min-height:0}',
    '.cdmap-side{width:320px;flex-shrink:0;border-right:1px solid #e6ebf2;display:flex;flex-direction:column;min-height:0;background:#fafbfd}',
    '.cdmap-side-head{padding:12px 15px 8px;font-size:11.5px;font-weight:700;letter-spacing:.06em;color:#8593a6;text-transform:uppercase}',
    '.cdmap-switch{display:flex;align-items:center;gap:7px;margin:0 15px 8px;font-size:12px;color:#4a5a6d;cursor:pointer;user-select:none}',
    '.cdmap-switch input{width:14px;height:14px;accent-color:#1d6fbf;cursor:pointer}',
    '.cdmap-origin{display:flex;align-items:center;gap:8px;margin:12px 12px 4px;padding:9px 11px;background:#eef5fd;border:1px solid #d5e6f8;border-radius:9px}',
    '.cdmap-origin-k{font-size:10.5px;font-weight:700;color:#4a7fb5;background:#fff;padding:2px 6px;border-radius:5px;flex-shrink:0}',
    '.cdmap-origin-v{font-size:13px;font-weight:650;color:#17518c;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.cdmap-origin-tag{margin-left:auto;font-size:10px;font-weight:700;padding:2px 7px;border-radius:20px;flex-shrink:0;background:#e8edf3;color:#8593a6}',
    '.cdmap-origin-tag.sync{background:#e3f6ee;color:#167a53}',
    '.cdmap-origin-tip{margin:0 12px 6px;padding:8px 11px;font-size:11.5px;line-height:1.6;color:#8a6d3b;background:#fdf2e0;border:1px solid #f3dcb8;border-radius:8px}',
    '.cdmap-modes{display:flex;gap:6px;padding:0 15px 10px}',
    '.cdmap-mode{flex:1;padding:7px 4px;border:1px solid #e2e8f0;background:#fff;border-radius:8px;font-size:12.5px;color:#5b6a7d;cursor:pointer;transition:.15s;text-align:center}',
    '.cdmap-mode:hover{border-color:#bcd8f5;color:#1d6fbf}',
    '.cdmap-mode.on{background:#e6f1fb;border-color:#9fcaf0;color:#17518c;font-weight:650}',
    '.cdmap-list{flex:1;overflow-y:auto;padding:0 10px 12px}',
    '.cdmap-item{display:flex;align-items:center;gap:9px;padding:9px 10px;border-radius:9px;cursor:pointer;transition:.14s;border:1px solid transparent}',
    '.cdmap-item:hover{background:#eef5fd;border-color:#d5e6f8}',
    '.cdmap-item.on{background:#e6f1fb;border-color:#9fcaf0}',
    '.cdmap-item .dot{width:8px;height:8px;border-radius:50%;background:#1d6fbf;flex-shrink:0}',
    '.cdmap-item .nm{flex:1;font-size:13px;color:#22303f;font-weight:550;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.cdmap-item .ct{font-size:10.5px;color:#9aa8b8;flex-shrink:0}',

    /* 地图上的目的地名牌 */
    '.cdmap-pin{display:flex;align-items:center;gap:4px;cursor:pointer;transform:translate(0,0)}',
    '.cdmap-pin-dot{width:8px;height:8px;border-radius:50%;background:#d64545;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35);flex-shrink:0}',
    '.cdmap-pin-name{font-size:11px;color:#5a3a3a;background:rgba(255,255,255,.92);padding:1px 6px;border-radius:4px;white-space:nowrap;box-shadow:0 1px 3px rgba(0,0,0,.12);display:none}',
    /* 放大后才显示全部地名，避免低缩放下标签互相压叠 */
    '.cdmap-panel.cdmap-zoom-in .cdmap-pin-name{display:inline-block}',
    '.cdmap-pin.on .cdmap-pin-name{display:inline-block !important}',
    '.cdmap-pin.on .cdmap-pin-dot{width:12px;height:12px;background:#e03131;box-shadow:0 0 0 4px rgba(224,49,49,.22),0 1px 4px rgba(0,0,0,.35)}',
    '.cdmap-pin.on .cdmap-pin-name{background:#e03131;color:#fff;font-weight:700}',
    '.cdmap-result{padding:11px 15px;border-top:1px solid #e6ebf2;font-size:12.5px;color:#4a5a6d;background:#fff;max-height:230px;overflow-y:auto;line-height:1.7}',
    '.cdmap-result b{color:#17518c}',
    '.cdmap-result .line{display:block;padding:3px 0;word-break:break-word}',
    '.cdmap-result .badge{display:inline-block;padding:0 6px;border-radius:4px;background:#e6f1fb;color:#17518c;font-size:11px;font-weight:700;margin-right:6px;vertical-align:1px}',
    '.cdmap-map{flex:1;min-width:0;position:relative;background:#e9eff5}',

    '.cdmap-setup{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;padding:24px;background:#f5f7fa}',
    '.cdmap-setup .box{width:100%;max-width:430px;background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:24px;box-shadow:0 10px 30px rgba(16,32,56,.1)}',
    '.cdmap-setup h4{margin:0 0 6px;font-size:16px}',
    '.cdmap-setup p{margin:0 0 16px;font-size:12.5px;color:#6b7a8d;line-height:1.7}',
    '.cdmap-setup label{display:block;font-size:12px;color:#5b6a7d;margin:11px 0 5px;font-weight:600}',
    '.cdmap-setup input{width:100%;box-sizing:border-box;padding:9px 11px;border:1.5px solid #e2e8f0;border-radius:9px;font-size:13px;outline:none;font-family:inherit}',
    '.cdmap-setup input:focus{border-color:#2b8ce0;box-shadow:0 0 0 3px rgba(43,140,224,.12)}',
    '.cdmap-setup .btn{width:100%;margin-top:18px;padding:11px;border:none;border-radius:10px;background:linear-gradient(135deg,#1d6fbf,#2b8ce0);color:#fff;font-size:13.5px;font-weight:650;cursor:pointer}',
    '.cdmap-setup .tip{margin-top:13px;padding:10px 12px;background:#fdf2e0;border:1px solid #f3dcb8;border-radius:8px;font-size:11.5px;color:#7c4a12;line-height:1.65}',
    '.cdmap-setup a{color:#1d6fbf}',
    '.cdmap-err{padding:10px 12px;background:#fdeaea;border:1px solid #f5c2c2;border-radius:8px;color:#a83232;font-size:12px;margin-top:12px;line-height:1.6}',

    '@media (max-width:820px){.cdmap-panel{inset:0;border-radius:0}.cdmap-side{width:200px}.cdmap-fab{bottom:16px;right:16px}}'
  ].join('');

  function injectCSS() {
    if (document.getElementById('cdmap-style')) return;
    var st = document.createElement('style');
    st.id = 'cdmap-style';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  /* =====================================================================
   * 四、内置兜底数据（页面没有提供 __CITY_DATA__ 时使用）
   * =================================================================== */
  var FALLBACK_DESTS = [
    { name: '天府广场', lng: 104.1187, lat: 30.6819, cat: '城市中心' },
    { name: '春熙路（太古里）', lng: 104.1260, lat: 30.6797, cat: '商圈' },
    { name: '成都东客站', lng: 104.1713, lat: 30.6669, cat: '交通枢纽' },
    { name: '成都站（火车北站）', lng: 104.0740, lat: 30.6990, cat: '交通枢纽' },
    { name: '成都南站', lng: 104.0690, lat: 30.6062, cat: '交通枢纽' },
    { name: '双流国际机场 T2', lng: 103.9526, lat: 30.5785, cat: '机场' },
    { name: '西南交大九里校区', lng: 104.0510, lat: 30.6990, cat: '校区' },
    { name: '宽窄巷子', lng: 104.0615, lat: 30.6698, cat: '景区' },
    { name: '武侯祠／锦里', lng: 104.0480, lat: 30.6458, cat: '景区' },
    { name: '成都博物馆', lng: 104.1140, lat: 30.6780, cat: '文化' }
  ];

  var FALLBACK_LINES = [
    { name: '地铁2号线', color: '#0f9d58', stations: [
      ['犀浦', 103.9730, 30.7605], ['天河路', 103.9886, 30.7412], ['百草路', 104.0018, 30.7293],
      ['金周路', 104.0148, 30.7215], ['金科北路', 104.0272, 30.7183], ['迎宾大道', 104.0408, 30.7127],
      ['茶店子客运站', 104.0522, 30.7071], ['羊犀立交', 104.0625, 30.7032], ['一品天下', 104.0722, 30.6991],
      ['蜀汉路东', 104.0808, 30.6958], ['白果林', 104.0896, 30.6925], ['中医大省医院', 104.0980, 30.6893],
      ['通惠门', 104.1060, 30.6866], ['人民公园', 104.1128, 30.6842], ['天府广场', 104.1187, 30.6819],
      ['春熙路', 104.1260, 30.6797], ['东门大桥', 104.1330, 30.6773], ['牛市口', 104.1430, 30.6748],
      ['东大路', 104.1523, 30.6723], ['塔子山公园', 104.1613, 30.6697], ['成都东客站', 104.1713, 30.6669]
    ]},
    { name: '地铁6号线', color: '#c75b12', stations: [
      ['望丛祠', 103.9210, 30.7823], ['兴业北街', 103.9800, 30.7631], ['犀浦', 103.9730, 30.7605],
      ['梓潼宫', 103.9900, 30.7533], ['侯家桥', 104.0033, 30.7432], ['兴盛', 104.0158, 30.7350],
      ['金石路', 104.0290, 30.7282], ['西南交大', 104.0418, 30.7220], ['沙湾', 104.0560, 30.7150],
      ['梁家巷', 104.0702, 30.7082], ['前锋路', 104.0838, 30.7018], ['建设北路', 104.0972, 30.6958],
      ['玉双路', 104.1105, 30.6893], ['牛王庙', 104.1232, 30.6828], ['东光', 104.1360, 30.6762],
      ['琉璃场', 104.1488, 30.6697]
    ]}
  ];

  /* 缓存一次：保证 findPlace 拿到的对象与列表里渲染的是同一份（高亮才生效） */
  var _placesCache = null;
  function getPlaces() {
    if (_placesCache) return _placesCache;
    var c = window.__CITY_DATA__;
    if (c && c.destinations && c.destinations.length) {
      _placesCache = c.destinations.map(function (d) {
        return { name: d.name, lng: d.lng, lat: d.lat, cat: d.cat || '' };
      });
    } else {
      _placesCache = FALLBACK_DESTS;
    }
    return _placesCache;
  }
  function getLines() {
    var c = window.__CITY_DATA__;
    if (c && c.lines && c.lines.length) {
      return c.lines.map(function (l) {
        return {
          name: l.name,
          color: l.color || '#1d6fbf',
          stations: (l.stations || []).map(function (s) { return [s.name, s.lng, s.lat]; })
        };
      });
    }
    return FALLBACK_LINES;
  }

  /* =====================================================================
   * 五、DOM 构建（不侵入现有页面结构）
   * =================================================================== */
  var ui = {};

  function buildFAB() {
    var host = document.querySelector('.map-wrap') || document.getElementById('map') || document.body;
    var fab = document.createElement('button');
    fab.className = 'cdmap-fab';
    fab.type = 'button';
    fab.innerHTML = '<span style="font-size:15px;line-height:1">◈</span><span>成都地图</span>';
    fab.title = '打开成都市区地图（高德）';
    fab.addEventListener('click', open);
    host.appendChild(fab);
    ui.fab = fab;
  }

  function buildPanel() {
    var mask = document.createElement('div');
    mask.className = 'cdmap-mask';

    var panel = document.createElement('div');
    panel.className = 'cdmap-panel';
    panel.innerHTML =
      '<div class="cdmap-head">' +
        '<h3>成都市区地图</h3>' +
        '<span class="cdmap-sub">高德地图 · GCJ-02</span>' +
        '<span class="cdmap-sp"></span>' +
        '<button class="cdmap-x" type="button" title="关闭 (Esc)">✕</button>' +
      '</div>' +
      '<div class="cdmap-body">' +
        '<div class="cdmap-side">' +
          '<div class="cdmap-origin" id="cdmap-origin-bar">' +
            '<span class="cdmap-origin-k">起点</span>' +
            '<span class="cdmap-origin-v" id="cdmap-origin-name">西南门</span>' +
            '<span class="cdmap-origin-tag" id="cdmap-origin-tag">默认</span>' +
          '</div>' +
          '<div class="cdmap-origin-tip" id="cdmap-origin-tip">' +
            '用的是默认校门。先在左侧输入问题并点「生成路线」，这里会自动同步成主程序选的校门。' +
          '</div>' +
          '<div class="cdmap-side-head">出行方式</div>' +
          '<div class="cdmap-modes">' +
            '<button class="cdmap-mode on" data-mode="transfer">公交/地铁</button>' +
            '<button class="cdmap-mode" data-mode="driving">驾车</button>' +
            '<button class="cdmap-mode" data-mode="walking">步行</button>' +
          '</div>' +
          '<div class="cdmap-side-head">目的地图层</div>' +
          '<label class="cdmap-switch">' +
            '<input type="checkbox" id="cdmap-toggle-lines" />' +
            '<span>显示地铁线网示意</span>' +
          '</label>' +
          '<div class="cdmap-side-head">目的地</div>' +
          '<div class="cdmap-list"></div>' +
          '<div class="cdmap-result"><span style="color:#9aa8b8">选择左侧目的地，从校门出发规划市区路线</span></div>' +
        '</div>' +
        '<div class="cdmap-map"></div>' +
      '</div>';

    document.body.appendChild(mask);
    document.body.appendChild(panel);

    ui.mask = mask;
    ui.panel = panel;
    ui.mapEl = panel.querySelector('.cdmap-map');
    ui.listEl = panel.querySelector('.cdmap-list');
    ui.resultEl = panel.querySelector('.cdmap-result');

    panel.querySelector('.cdmap-x').addEventListener('click', close);
    mask.addEventListener('click', close);

    var linesToggle = panel.querySelector('#cdmap-toggle-lines');
    if (linesToggle) {
      linesToggle.checked = !!state.showLines;
      linesToggle.addEventListener('change', function () {
        state.showLines = linesToggle.checked;
        applyLineVisibility();
      });
    }

    panel.querySelectorAll('.cdmap-mode').forEach(function (b) {
      b.addEventListener('click', function () {
        panel.querySelectorAll('.cdmap-mode').forEach(function (x) { x.classList.remove('on'); });
        b.classList.add('on');
        state.mode = b.dataset.mode;
        if (state.selected) planRoute(state.selected);
      });
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && panel.classList.contains('on')) close();
    });
  }

  function buildSetup() {
    var box = document.createElement('div');
    box.className = 'cdmap-setup';
    box.innerHTML =
      '<div class="box">' +
        '<h4>配置高德地图 Key</h4>' +
        '<p>需要在高德开放平台申请 <b>「Web端（JS API）」</b> 类型的 Key，' +
        '并在同一页面生成<b>安全密钥</b>（v2.0 必须，否则报 INVALID_USER_SCODE）。</p>' +
        '<label>Web端 Key</label>' +
        '<input id="cdmap-in-key" type="text" placeholder="例如 1a2b3c4d5e6f7g8h..." autocomplete="off" />' +
        '<label>安全密钥 securityJsCode</label>' +
        '<input id="cdmap-in-secret" type="text" placeholder="32 位字符串" autocomplete="off" />' +
        '<button class="btn" id="cdmap-in-save" type="button">保存并加载地图</button>' +
        '<div class="tip">' +
          '① 本地调试请用 <b>http://localhost</b> 打开（<code>file://</code> 会被高德拒绝）。<br />' +
          '② 别忘了在高德控制台把当前域名加入 <b>白名单</b>。<br />' +
          '③ 配置只保存在你本机浏览器，不会上传。' +
        '</div>' +
        '<div class="cdmap-err" id="cdmap-err" style="display:none"></div>' +
      '</div>';
    ui.mapEl.appendChild(box);
    ui.setup = box;

    box.querySelector('#cdmap-in-save').addEventListener('click', function () {
      var k = box.querySelector('#cdmap-in-key').value.trim();
      var s = box.querySelector('#cdmap-in-secret').value.trim();
      if (!k) return showError('请先填写 Key');
      if (!s) return showError('请填写安全密钥（JS API v2.0 必需）');
      try {
        localStorage.setItem('cdmap_key', k);
        localStorage.setItem('cdmap_secret', s);
      } catch (e) {}
      CFG.key = k;
      CFG.securityJsCode = s;

      /* 整页重载：高德的鉴权与插件加载是全局状态，
       * 重载一次比清空旧实例更可靠，也避免上一轮失败残留的 DOM。 */
      try { sessionStorage.setItem('cdmap_autopen', '1'); } catch (e) {}
      location.reload();
    });
  }

  function showError(msg) {
    var el = ui.setup && ui.setup.querySelector('#cdmap-err');
    if (!el) return;
    el.style.display = 'block';
    el.textContent = msg;
  }

  /** 把已保存的（可能无效的）配置回填到输入框，方便直接改 */
  function prefillSetup() {
    if (!ui.setup) return;
    var ki = ui.setup.querySelector('#cdmap-in-key');
    var si = ui.setup.querySelector('#cdmap-in-secret');
    if (ki && !ki.value) ki.value = CFG.key || '';
    if (si && !si.value) si.value = CFG.securityJsCode || '';
  }

  /* =====================================================================
   * 六、状态
   * =================================================================== */
  var state = {
    mode: 'transfer',
    selected: null,
    map: null,
    service: null,
    origin: null,       // {lng, lat, name}  WGS84
    suggested: null,    // 主程序推荐的目的地名称（尚未点击）
    originSource: 'default',  // default = 默认校门；synced = 已从主程序同步
    showLines: false,   // 地铁线网默认关闭，避免和规划路线抢视觉
    lineOverlays: [],
    destMarkers: [],
    overlays: {},
    loaded: false
  };

  /** 刷新面板上显示的"起点"（含"默认 / 已同步"标记与提示） */
  function updateOriginLabel() {
    var el = document.getElementById('cdmap-origin-name');
    if (el && state.origin) el.textContent = state.origin.name || '起点';

    var tag = document.getElementById('cdmap-origin-tag');
    var tip = document.getElementById('cdmap-origin-tip');
    var synced = state.originSource === 'synced';

    if (tag) {
      tag.textContent = synced ? '已同步' : '默认';
      tag.className = 'cdmap-origin-tag' + (synced ? ' sync' : '');
    }
    if (tip) tip.style.display = synced ? 'none' : 'block';
  }

  /* =====================================================================
   * 七、鉴权错误探测
   * ---------------------------------------------------------------------
   * 高德在 Key / 安全密钥 / 域名白名单有问题时，只在控制台打印错误，
   * 地图区域是一片空白，用户完全不知道发生了什么。
   * 这里临时接管 console.error/warn，把鉴权类错误抓出来转成可读提示。
   * =================================================================== */
  var authWatch = { on: false, msgs: [], origErr: null, origWarn: null };

  function startAuthWatch() {
    if (authWatch.on || !window.console) return;
    authWatch.on = true;
    authWatch.msgs = [];
    authWatch.origErr = console.error;
    authWatch.origWarn = console.warn;

    var AUTH_RE = /INVALID_USER_KEY|INVALID_USER_SCODE|USERKEY_PLAT_NOMATCH|INVALID_USER_DOMAIN|INVALID_USER_KEY|USER_KEY|SCODE|PLAT_NOMATCH/i;

    function wrap(orig) {
      return function () {
        try {
          var s = Array.prototype
            .map.call(arguments, function (a) { return typeof a === 'string' ? a : ''; })
            .join(' ');
          if (AUTH_RE.test(s)) authWatch.msgs.push(s);
        } catch (e) {}
        return orig.apply(console, arguments);
      };
    }
    console.error = wrap(authWatch.origErr);
    console.warn = wrap(authWatch.origWarn);
  }

  function stopAuthWatch() {
    if (!authWatch.on) return;
    authWatch.on = false;
    try {
      console.error = authWatch.origErr;
      console.warn = authWatch.origWarn;
    } catch (e) {}
  }

  /** 把高德的原始错误翻译成用户能看懂的话 */
  function humanizeAuthError(raw) {
    var s = String(raw || '');
    if (/INVALID_USER_KEY/i.test(s)) {
      return 'Key 无效：请确认填的是「Web端（JS API）」类型的 Key，而不是「Web服务」或「Android/iOS」的 Key。';
    }
    if (/INVALID_USER_SCODE/i.test(s)) {
      return '安全密钥不正确：v2.0 必须在加载地图前配置 securityJsCode，请检查安全密钥是否与这个 Key 配对。';
    }
    if (/USERKEY_PLAT_NOMATCH|PLAT_NOMATCH/i.test(s)) {
      return 'Key 类型不匹配：这个 Key 不是「Web端（JS API）」类型，请重新申请对应平台的 Key。';
    }
    if (/INVALID_USER_DOMAIN/i.test(s)) {
      return '域名未授权：请把当前访问域名（如 localhost 或你的 GitHub Pages 域名）加入高德控制台的白名单。';
    }
    return '高德鉴权失败：' + s.slice(0, 160);
  }

  /* =====================================================================
   * 八、加载高德 JS API
   * =================================================================== */
  function loadAMap(done) {
    if (window.AMap) return done(null);

    if (!CFG.key) return done(new Error('NO_KEY'));

    /* v2.0 安全密钥必须在脚本加载前设置 */
    if (CFG.securityJsCode) {
      window._AMapSecurityConfig = { securityJsCode: CFG.securityJsCode };
    }

    var finished = false;
    function finish(err) {
      if (finished) return;
      finished = true;
      done(err);
    }

    startAuthWatch();

    var s = document.createElement('script');
    s.src = 'https://webapi.amap.com/maps?v=' + CFG.version +
      '&key=' + encodeURIComponent(CFG.key) +
      '&plugin=AMap.Scale,AMap.ToolBar,AMap.Driving,AMap.Walking,AMap.Transfer';
    s.async = true;
    s.onload = function () {
      if (window.AMap) finish(null);
      else finish(new Error('高德脚本已加载但未挂载 AMap，通常是 Key 无效或域名未加入白名单'));
    };
    s.onerror = function () {
      finish(new Error('无法连接高德服务器：请检查网络、广告拦截插件，或域名白名单'));
    };
    document.head.appendChild(s);

    setTimeout(function () {
      if (!window.AMap) finish(new Error('加载高德地图超时（12 秒）'));
    }, 12000);
  }

  /* =====================================================================
   * 八、地图初始化与图层
   * =================================================================== */
  function g(lng, lat) {          // WGS84 -> GCJ-02 简写
    var p = wgs2gcj(lng, lat);
    return [p[0], p[1]];
  }
  function gPath(coords) {        // 坐标数组批量转换
    return coords.map(function (c) { return g(c[0], c[1]); });
  }

  function bootMap() {
    /* 再读一次外部配置：这样 key.local.js 写在 chengdu-map.js 之前或之后都能生效 */
    applyExternalConfig();

    loadAMap(function (err) {
      if (err) {
        if (!ui.setup) buildSetup();
        ui.setup.style.display = 'flex';
        if (err.message !== 'NO_KEY') showError(err.message);
        return;
      }
      if (ui.setup) ui.setup.style.display = 'none';
      initMap();
    });
  }

  function initMap() {
    if (state.map) return;

    try {
      state.map = new AMap.Map(ui.mapEl, {
        zoom: CFG.zoom,
        center: CFG.center,
        viewMode: '2D',
        zooms: [3, 20],
        resizeEnable: true,
        mapStyle: 'amap://styles/normal'
      });
    } catch (e) {
      stopAuthWatch();
      if (!ui.setup) buildSetup();
      ui.setup.style.display = 'flex';
      showError('地图初始化失败：' + e.message);
      return;
    }

    /* 缩放到校园，先给用户一个"确实加载成功"的落点 */
    var campusCenter = g(103.9817, 30.7662);

    state.map.addControl(new AMap.Scale());
    state.map.addControl(new AMap.ToolBar({ position: { right: '14px', bottom: '60px' } }));

    drawCampus();
    drawLines();
    drawDestinations();
    state.loaded = true;

    /* 根据缩放决定是否显示地名（低缩放只留点，避免标签打架） */
    function updateLabelZoom() {
      if (!ui.panel || !state.map) return;
      ui.panel.classList.toggle('cdmap-zoom-in', state.map.getZoom() >= 13);
    }
    state.map.on('zoomchange', updateLabelZoom);
    state.map.on('zoomend', updateLabelZoom);
    setTimeout(updateLabelZoom, 600);

    /* 鉴权探测：高德只在控制台报错，这里 3.5 秒后回收结果 */
    setTimeout(function () {
      var msgs = authWatch.msgs.slice();
      stopAuthWatch();
      if (!msgs.length) return;

      /* 鉴权失败：销毁空地图，回到配置面板并给出人话解释 */
      try { state.map.destroy(); } catch (e) {}
      state.map = null;
      state.loaded = false;

      if (!ui.setup) buildSetup();
      ui.setup.style.display = 'flex';
      prefillSetup();
      showError(humanizeAuthError(msgs[0]));
    }, 3500);

    /* 初始视野：把校区和所有目的地一起框进来，一打开就能看到全貌 */
    setTimeout(function () {
      if (!state.map) return;
      fitAll();
      state.map.resize();

      /* 主程序已经给出建议目的地 → 自动规划 */
      if (state.suggested && !state.selected) {
        var d = findPlace(state.suggested);
        if (d) planRoute(d);
      }
    }, 400);
  }

  /** 视野同时容纳校区与全部目的地 */
  function fitAll() {
    var pts = [g(103.9817, 30.7662)];
    getPlaces().forEach(function (d) { pts.push(g(d.lng, d.lat)); });

    var minL = 1e9, maxL = -1e9, minA = 1e9, maxA = -1e9;
    pts.forEach(function (p) {
      minL = Math.min(minL, p[0]); maxL = Math.max(maxL, p[0]);
      minA = Math.min(minA, p[1]); maxA = Math.max(maxA, p[1]);
    });

    try {
      var b = new AMap.Bounds(new AMap.LngLat(minL, minA), new AMap.LngLat(maxL, maxA));
      state.map.setBounds(b, false);
    } catch (e) {
      state.map.setZoomAndCenter(11, g(103.9817, 30.7662));
    }
  }

  /* ---------- 校园要素（WGS84 → GCJ-02 后叠加） ---------- */
  function drawCampus() {
    var D = window.__CAMPUS_DATA__;
    if (!D) return;

    var map = state.map;

    /* 校园边界 */
    if (D.boundary && D.boundary[0]) {
      var ring = gPath(D.boundary[0]);
      var poly = new AMap.Polygon({
        path: ring,
        strokeColor: '#1d6fbf',
        strokeWeight: 2,
        strokeStyle: 'dashed',
        strokeOpacity: 0.9,
        fillColor: '#1d6fbf',
        fillOpacity: 0.08,
        zIndex: 30,
        bubble: true
      });
      map.add(poly);
      state.overlays.campus = poly;

      /* 校区标注 */
      var c = ring.reduce(function (a, p) { return [a[0] + p[0], a[1] + p[1]]; }, [0, 0]);
      c = [c[0] / ring.length, c[1] / ring.length];
      var label = new AMap.Marker({
        position: c,
        content: '<div style="padding:3px 9px;background:rgba(11,37,69,.88);color:#fff;font-size:12px;' +
          'border-radius:6px;white-space:nowrap;font-weight:600">西南交大 · 犀浦校区</div>',
        offset: new AMap.Pixel(-70, -14),
        zIndex: 60,
        bubble: true
      });
      map.add(label);
      state.overlays.campusLabel = label;
    }

    /* 校门 */
    if (D.gates) {
      D.gates.forEach(function (gt) {
        var pos = g(gt.x, gt.y);
        var m = new AMap.Marker({
          position: pos,
          content: '<div style="display:flex;align-items:center;gap:4px;transform:translate(-50%,-50%)">' +
            '<span style="width:9px;height:9px;border-radius:50%;background:#e08a1e;border:2px solid #fff;' +
            'box-shadow:0 0 0 3px rgba(224,138,30,.22)"></span>' +
            '<span style="font-size:11px;color:#9a5b0d;background:rgba(255,255,255,.92);padding:1px 5px;' +
            'border-radius:4px;white-space:nowrap">' + gt.n + '</span></div>',
          offset: new AMap.Pixel(0, 0),
          zIndex: 55,
          bubble: true
        });
        map.add(m);
      });
    }
  }

  /* ---------- 地铁线路（默认关闭，避免和规划路线抢视觉） ---------- */
  function drawLines() {
    var map = state.map;
    state.lineOverlays = [];

    getLines().forEach(function (line) {
      /* 7 号线（环线）在数据里只是 9 个站的残缺子集，连起来会画成
       * 一条横穿市区的歪线，属于误导，所以这里不画。 */
      if (line.stations.length < 10) return;

      var path = line.stations.map(function (s) { return g(s[1], s[2]); });
      var pl = new AMap.Polyline({
        path: path,
        strokeColor: line.color,
        strokeWeight: 3,
        strokeOpacity: 0.55,
        lineJoin: 'round',
        lineCap: 'round',
        zIndex: 20,
        bubble: true
      });
      state.lineOverlays.push(pl);

      line.stations.forEach(function (s) {
        var cm = new AMap.CircleMarker({
          center: g(s[1], s[2]),
          radius: 2.5,
          strokeColor: '#fff',
          strokeWeight: 1,
          fillColor: line.color,
          fillOpacity: 0.7,
          zIndex: 21,
          bubble: true
        });
        state.lineOverlays.push(cm);
      });
    });

    applyLineVisibility();
  }
  /** 根据开关显示/隐藏线网 */
  function applyLineVisibility() {
    if (!state.lineOverlays || !state.map) return;
    if (state.showLines) {
      state.map.add(state.lineOverlays);
    } else {
      state.map.remove(state.lineOverlays);
    }
  }

  /* ---------- 城市目的地 ---------- */
  function drawDestinations() {
    var map = state.map;
    var dests = getPlaces();
    ui.listEl.innerHTML = '';
    state.destMarkers = [];

    dests.forEach(function (d) {
      /* 用 DOM 节点做标记内容，方便后面对单个目的地做高亮/淡化 */
      var node = document.createElement('div');
      node.className = 'cdmap-pin';
      var dot = document.createElement('span');
      dot.className = 'cdmap-pin-dot';
      var nm = document.createElement('span');
      nm.className = 'cdmap-pin-name';
      nm.textContent = d.name;
      node.appendChild(dot);
      node.appendChild(nm);

      var marker = new AMap.Marker({
        position: g(d.lng, d.lat),
        content: node,
        offset: new AMap.Pixel(-7, -7),
        zIndex: 50,
        bubble: true
      });
      marker.on('click', function () { planRoute(d); });
      map.add(marker);

      d.__marker = marker;
      d.__node = node;
      state.destMarkers.push(marker);

      var item = document.createElement('div');
      item.className = 'cdmap-item';
      item.innerHTML = '<span class="dot"></span><span class="nm"></span><span class="ct"></span>';
      item.querySelector('.nm').textContent = d.name;
      item.querySelector('.ct').textContent = d.cat || '';
      item.addEventListener('click', function () { planRoute(d); });
      ui.listEl.appendChild(item);
      d.__el = item;
    });
  }

  /** 高亮选中的目的地，其余压暗，让视线集中在当前路线上 */
  function highlightDestination(sel) {
    getPlaces().forEach(function (d) {
      var on = (d === sel);
      if (d.__marker) {
        try { d.__marker.setOpacity(sel ? (on ? 1 : 0.25) : 1); } catch (e) {}
      }
      if (d.__node) d.__node.classList.toggle('on', on);
      if (d.__node) d.__node.classList.toggle('dim', !!sel && !on);
      if (d.__el) d.__el.classList.toggle('on', on);
    });
  }

  /* =====================================================================
   * 九、路线规划（高德真实路径）
   * =================================================================== */
  function clearService() {
    if (state.service && typeof state.service.clear === 'function') {
      try { state.service.clear(); } catch (e) {}
    }
    state.service = null;
  }

  function originPoint() {
    if (state.origin) return g(state.origin.lng, state.origin.lat);
    return g(CFG.defaultOrigin[0], CFG.defaultOrigin[1]);
  }

  function originName() {
    if (state.origin) return state.origin.name || '起点';
    return '西南门';
  }

  /** 按名称找目的地（支持包含匹配，如 "天府广场" 命中 "天府广场"） */
  function findPlace(name) {
    if (!name) return null;
    var list = getPlaces();
    var n = String(name).trim();
    return list.filter(function (d) { return d.name === n; })[0] ||
      list.filter(function (d) { return d.name.indexOf(n) >= 0 || n.indexOf(d.name) >= 0; })[0] ||
      null;
  }

  function planRoute(dest) {
    if (!state.map) return;
    state.selected = dest;

    /* 列表 + 地图标记一起高亮，其余目的地压暗，避免视线被其他地点分散 */
    highlightDestination(dest);

    clearService();

    var from = originPoint();
    var to = g(dest.lng, dest.lat);
    var mode = state.mode;

    ui.resultEl.innerHTML = '<span style="color:#9aa8b8">正在规划 ' + escapeHTML(originName()) +
      ' → ' + escapeHTML(dest.name) + ' …</span>';

    var opts = {
      map: state.map,
      hideMarkers: true,
      autoFitView: true,
      city: CFG.city
    };

    var service;
    try {
      if (mode === 'driving') {
        opts.policy = AMap.DrivingPolicy && AMap.DrivingPolicy.LEAST_TIME;
        service = new AMap.Driving(opts);
      } else if (mode === 'walking') {
        service = new AMap.Walking(opts);
      } else {
        service = new AMap.Transfer(opts);
      }
    } catch (e) {
      return renderError('该出行方式在当前 Key 权限下不可用：' + e.message);
    }

    state.service = service;

    service.search(new AMap.LngLat(from[0], from[1]), new AMap.LngLat(to[0], to[1]), function (status, result) {
      if (status !== 'complete' || !result) {
        return renderError('高德返回：' + (status || '未知错误') + '（可尝试切换出行方式）');
      }
      renderRouteResult(mode, dest, result);

      /* 高德自动适配视野时贴得太紧，终点标签会被边缘截断，留一点余量 */
      if (state.map) {
        setTimeout(function () {
          if (!state.map) return;
          try { state.map.setZoom(state.map.getZoom() - 0.35); } catch (e) {}
        }, 420);
      }
    });
  }

  function renderRouteResult(mode, dest, result) {
    var html = '';

    if (mode === 'transfer') {
      var plan = result.plans && result.plans[0];
      if (!plan) return renderError('没有查到公交方案');
      var mins = Math.round((plan.time || 0) / 60);
      var km = ((plan.distance || 0) / 1000).toFixed(1);
      var cost = plan.cost != null ? plan.cost : '—';
      html += '<div class="line"><b>' + escapeHTML(originName()) + ' → ' + escapeHTML(dest.name) + '</b></div>';
      html += '<div class="line"><span class="badge">公交/地铁</span> 约 <b>' + mins + ' 分钟</b> · ' +
        km + ' km · 票价约 ' + cost + ' 元</div>';

      /* 高德 2.0 实际结构：
       *   segments[i] = { transit_mode, distance, time, instruction, transit }
       *   transit_mode = WALK | SUBWAY | BUS | RAILWAY | TAXI
       *   乘车段：transit = { on_station, off_station, via_num, via_stops, lines[], path }
       *   步行段：transit = { origin, destination, steps, path }
       */
      var segs = plan.segments || [];
      segs.forEach(function (sg) {
        var mode = String(sg.transit_mode || '').toUpperCase();
        var t = sg.transit || {};
        var dist = Math.round(sg.distance || 0);

        if (mode === 'WALK' || sg.walking) {
          if (dist >= 1) html += '<div class="line">🚶 步行 ' + dist + ' 米</div>';
          return;
        }
        if (mode === 'TAXI' || sg.taxi) {
          html += '<div class="line">🚕 出租车 ' + (dist / 1000).toFixed(1) + ' km</div>';
          return;
        }

        /* 乘车段 */
        var lines = t.lines || [];
        var name = lines.map(function (l) { return l.name; }).filter(Boolean).join(' / ');
        /* "地铁6号线(望丛祠--兰家沟)" → "地铁6号线"，去掉区间方向说明 */
        name = name.replace(/[（(][^）)]*[）)]/g, '').trim();
        if (!name) {
          name = mode === 'SUBWAY' ? '地铁' : (mode === 'RAILWAY' ? '铁路' : '公交');
        }
        var on = (t.on_station && t.on_station.name) || '';
        var off = (t.off_station && t.off_station.name) || '';
        var via = t.via_num != null ? t.via_num : (t.via_stops ? t.via_stops.length : 0);
        var icon = mode === 'SUBWAY' ? '🚇' : (mode === 'RAILWAY' ? '🚄' : '🚌');

        var txt = icon + ' 乘 <b>' + escapeHTML(name) + '</b>';
        if (on || off) txt += '（' + escapeHTML(on) + ' 上车 → ' + escapeHTML(off) + ' 下车）';
        if (via) txt += '，' + via + ' 站';
        html += '<div class="line">' + txt + '</div>';
      });
    } else if (mode === 'walking') {
      var r = result.routes && result.routes[0];
      if (!r) return renderError('没有查到步行方案');
      html += '<div class="line"><b>' + escapeHTML(originName()) + ' → ' + escapeHTML(dest.name) + '</b></div>';
      html += '<div class="line"><span class="badge">步行</span> 约 <b>' +
        Math.round((r.time || 0) / 60) + ' 分钟</b> · ' +
        ((r.distance || 0) / 1000).toFixed(2) + ' km</div>';
    } else {
      var rt = result.routes && result.routes[0];
      if (!rt) return renderError('没有查到驾车方案');
      html += '<div class="line"><b>' + escapeHTML(originName()) + ' → ' + escapeHTML(dest.name) + '</b></div>';
      html += '<div class="line"><span class="badge">驾车</span> 约 <b>' +
        Math.round((rt.time || 0) / 60) + ' 分钟</b> · ' +
        ((rt.distance || 0) / 1000).toFixed(1) + ' km</div>';
      if (rt.tolls) html += '<div class="line">过路费约 ' + rt.tolls + ' 元</div>';
    }

    html += '<div class="line" style="color:#9aa8b8;font-size:11px;margin-top:6px">' +
      '数据来源：高德地图实时路径规划 · 校园段仍由本地路网计算</div>';

    ui.resultEl.innerHTML = html;
  }

  function renderError(msg) {
    ui.resultEl.innerHTML = '<div style="color:#a83232">' + escapeHTML(msg) + '</div>';
  }

  function escapeHTML(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* =====================================================================
   * 十、开合控制
   * =================================================================== */
  function open() {
    if (!ui.panel) buildPanel();
    updateOriginLabel();
    ui.mask.classList.add('on');
    ui.panel.classList.add('on');
    if (!state.map) {
      bootMap();
    } else {
      setTimeout(function () { state.map.resize(); }, 120);
      /* 如果主程序已经给出建议目的地，打开时直接规划 */
      if (state.suggested && !state.selected) {
        var d = findPlace(state.suggested);
        if (d) setTimeout(function () { planRoute(d); }, 200);
      }
    }
  }

  function close() {
    ui.mask.classList.remove('on');
    ui.panel.classList.remove('on');
  }

  function toggle() {
    if (ui.panel && ui.panel.classList.contains('on')) close();
    else open();
  }

  /* =====================================================================
   * 十一、启动
   * =================================================================== */
  function start() {
    injectCSS();

    /* 起点：优先用校园数据里的西南门 */
    var D = window.__CAMPUS_DATA__;
    var sg = D && D.gates && D.gates.filter(function (x) { return x.n === '西南门'; })[0];
    state.origin = sg
      ? { lng: sg.x, lat: sg.y, name: '西南门' }
      : { lng: CFG.defaultOrigin[0], lat: CFG.defaultOrigin[1], name: '西南门' };

    buildFAB();

    /* 刚填完 Key 重载回来的话，自动把面板打开 */
    try {
      if (sessionStorage.getItem('cdmap_autopen') === '1') {
        sessionStorage.removeItem('cdmap_autopen');
        setTimeout(open, 260);
      }
    } catch (e) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }

  /* 对外 API */
  window.ChengduMap = {
    open: open,
    close: close,
    toggle: toggle,
    routeTo: function (name) {
      open();
      var found = getPlaces().filter(function (d) { return d.name.indexOf(name) >= 0; })[0];
      if (!found) return false;
      setTimeout(function () { planRoute(found); }, 400);
      return true;
    },
    setOrigin: function (lng, lat, name, opts) {
      state.origin = { lng: lng, lat: lat, name: name || '起点' };
      /* 标记来源：主程序同步过来的才算"已同步" */
      state.originSource = (opts && opts.source === 'synced') ? 'synced' : 'api';
      updateOriginLabel();
      /* 面板开着且有选中目的地时，立刻按新起点重算 */
      if (state.selected && ui.panel && ui.panel.classList.contains('on')) {
        planRoute(state.selected);
      }
    },
    /** 主程序推荐的目的地：下次打开面板时自动规划 */
    setSuggestedDestination: function (name) {
      state.suggested = name || null;
      if (state.suggested && ui.panel && ui.panel.classList.contains('on')) {
        var d = findPlace(state.suggested);
        if (d && (!state.selected || state.selected.name !== d.name)) planRoute(d);
      }
    },
    setMode: function (m) {
      if (['transfer', 'driving', 'walking'].indexOf(m) < 0) return;
      state.mode = m;
      document.querySelectorAll('.cdmap-mode').forEach(function (b) {
        b.classList.toggle('on', b.dataset.mode === m);
      });
      if (state.selected) planRoute(state.selected);
    },
    /** 飞到某个位置（WGS84），方便从你的结果卡里定位 */
    flyTo: function (lng, lat, zoom) {
      if (!state.map) return false;
      var p = wgs2gcj(lng, lat);
      state.map.setZoomAndCenter(zoom || 15, [p[0], p[1]]);
      return true;
    },
    /** 拿到 AMap 实例做更细的控制 */
    getMap: function () { return state.map; },
    /* 坐标工具，方便你在别处复用 */
    wgs2gcj: wgs2gcj,
    gcj2wgs: gcj2wgs,
    version: '1.0.0'
  };
})();
