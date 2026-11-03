/* =========================================================================
 * app.js — 应用主逻辑
 *   · 地图图层装配（建筑/道路/路网/地点/校门/交通点/路线）
 *   · 起终点组合框与自然语言输入
 *   · Agent 调用与结果渲染
 *   · 数据可信度 / 项目说明 视图
 * ========================================================================= */
(function () {
  'use strict';

  var DATA = window.__CAMPUS_DATA__;
  var CITY = window.__CITY_DATA__;
  var MapEngine = window.MapEngine;
  var CampusRouter = window.CampusRouting.CampusRouter;
  var RouteAgent = window.RouteAgent;

  if (!DATA) {
    document.body.innerHTML = '<div style="padding:40px;font-family:sans-serif">数据加载失败：请确认 web/data/campus.js 存在。</div>';
    return;
  }

  /* ---------- 主题色 ---------- */
  var C = {
    boundaryLine: 'rgba(29,111,191,.38)',
    building: '#e4e9f0',
    buildingEdge: '#cdd6e0',
    place: '#5b6a7d',
    gate: '#e08a1e',
    transit: '#1f9d6b',
    route: '#1d6fbf',
    net: 'rgba(150,168,190,.45)'
  };

  /* 道路按等级分组，决定绘制顺序与宽度 */
  var ROAD_STYLE = {
    tertiary: { w: 1.00, color: '#f2f5f9', edge: '#d3dce6', z: 0 },
    residential: { w: 0.86, color: '#f6f8fb', edge: '#d8e0e9', z: 0 },
    living_street: { w: 0.80, color: '#f6f8fb', edge: '#d8e0e9', z: 0 },
    service: { w: 0.66, color: '#f8fafc', edge: '#dde4ec', z: 0 },
    pedestrian: { w: 0.86, color: '#f3f7fb', edge: '#d5e2ee', z: 0 },
    cycleway: { w: 0.62, color: '#f8fafc', edge: '#dde4ec', z: 1 },
    footway: { w: 0.58, color: '#fafcfe', edge: '#e0e7ef', z: 1 },
    path: { w: 0.52, color: '#fbfcfe', edge: '#e3e9f0', z: 1 },
    steps: { w: 0.55, color: '#f0f4f9', edge: '#d6dfe9', z: 1 },
    track: { w: 0.5, color: '#f7f9fc', edge: '#dde4ec', z: 1 },
    default: { w: 0.6, color: '#f8fafc', edge: '#dde4ec', z: 1 }
  };

  /* ================= 初始化地图 ================= */
  var map = new MapEngine.MapCanvas(document.getElementById('map'), {
    center: [103.9817, 30.7662],
    zoom: 16.4,
    minZoom: 14.2,
    maxZoom: 19.2
  });

  var state = {
    layers: {},
    showLayers: {
      buildings: true, roads: true, network: false,
      places: true, gates: true, transit: true
    },
    plans: null,
    activePlan: 0,
    origin: null,
    dest: null,
    mode: 'walk'
  };

  /* ---------- 1. 底图：校园边界 + 绿地 ---------- */
  var bgLayer = map.addLayer({
    id: 'bg',
    z: 0,
    draw: function (ctx, m) {
      /* 背景 */
      ctx.fillStyle = '#eef3f8';
      ctx.fillRect(0, 0, m.w, m.h);

      /* 校园边界面 */
      var rings = DATA.boundary;
      var outer = rings[0];
      if (MapEngine.pathPolyline(ctx, m, outer, true)) {
        ctx.fillStyle = 'rgba(255,255,255,.94)';
        ctx.fill();
        ctx.strokeStyle = C.boundaryLine;
        ctx.lineWidth = 1.6;
        ctx.setLineDash([7, 5]);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    }
  });

  /* ---------- 2. 建筑 ---------- */
  var buildingLayer = map.addLayer({
    id: 'buildings',
    z: 2,
    draw: function (ctx, m) {
      if (!state.showLayers.buildings) return;
      var z = m.zoom;
      ctx.lineWidth = Math.max(0.6, (z - 15) * 0.5);
      DATA.buildings.forEach(function (b) {
        if (!MapEngine.pathPolyline(ctx, m, b.r, true)) return;
        ctx.fillStyle = C.building;
        ctx.fill();
        ctx.strokeStyle = C.buildingLine;
        ctx.stroke();
      });
      /* 建筑名称（放大后显示） */
      if (z >= 17.4) {
        ctx.font = '500 10.5px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        DATA.buildings.forEach(function (b) {
          if (!b.n) return;
          var ring = b.r;
          var sx = 0, sy = 0;
          ring.forEach(function (p) {
            var s = m.toScreen(p[0], p[1]);
            sx += s.x; sy += s.y;
          });
          sx /= ring.length; sy /= ring.length;
          if (sx < -40 || sy < -20 || sx > m.w + 40 || sy > m.h + 20) return;
          var txt = b.n.replace(/^西南交通大学犀浦校区/, '').replace(/^西南交大犀浦/, '');
          if (!txt) return;
          ctx.fillStyle = 'rgba(255,255,255,.82)';
          var wdt = ctx.measureText(txt).width;
          MapEngine.roundRect(ctx, sx - wdt / 2 - 3, sy - 7, wdt + 6, 14, 3);
          ctx.fill();
          ctx.fillStyle = '#41505f';
          ctx.fillText(txt, sx, sy);
        });
      }
    },
    hitTest: function (ctx, m, x, y) {
      if (!state.showLayers.buildings) return null;
      for (var i = DATA.buildings.length - 1; i >= 0; i--) {
        var b = DATA.buildings[i];
        if (pointInRing(m, b.r, x, y)) {
          return { kind: 'building', name: b.n, category: b.c };
        }
      }
      return null;
    }
  });

  function pointInRing(m, ring, x, y) {
    var inside = false;
    for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      var pi = m.toScreen(ring[i][0], ring[i][1]);
      var pj = m.toScreen(ring[j][0], ring[j][1]);
      if (((pi.y > y) !== (pj.y > y)) && (x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x)) {
        inside = !inside;
      }
    }
    return inside;
  }

  /* ---------- 3. 校园道路（按等级分层绘制） ---------- */
  var roadLayer = map.addLayer({
    id: 'roads',
    z: 1.5,
    draw: function (ctx, m) {
      if (!state.showLayers.roads) return;
      var roads = DATA.roads || [];
      if (!roads.length) return;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      var zf = Math.max(0.55, Math.min(1.9, (m.zoom - 13.2) * 0.42 + 0.5));

      /* 先画描边（全部），再画路面（全部），避免同层互相压盖 */
      var groups = [[], []];
      roads.forEach(function (r) {
        var st = ROAD_STYLE[r[1]] || ROAD_STYLE['default'];
        (groups[st.z] || groups[0]).push([r, st]);
      });

      [0, 1].forEach(function (gi) {
        /* 描边 */
        ctx.strokeStyle = gi === 0 ? '#d3dce6' : '#dde4ec';
        groups[gi].forEach(function (it) {
          var st = it[1];
          ctx.lineWidth = Math.max(0.9, st.w * 9 * zf) + 1.4;
          if (MapEngine.pathPolyline(ctx, m, it[0][0], false)) ctx.stroke();
        });
        /* 路面 */
        groups[gi].forEach(function (it) {
          var st = it[1];
          ctx.strokeStyle = gi === 0 ? '#f4f7fa' : '#fafcfe';
          ctx.lineWidth = Math.max(0.9, st.w * 9 * zf);
          if (MapEngine.pathPolyline(ctx, m, it[0][0], false)) ctx.stroke();
        });
      });

      /* 道路名 */
      if (m.zoom >= 17.6) {
        ctx.font = '500 10px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        DATA.roadlines.forEach(function (r) {
          if (!r.l || r.l.length < 2) return;
          var mid = r.l[Math.floor(r.l.length / 2)];
          var s = m.toScreen(mid[0], mid[1]);
          if (s.x < 0 || s.y < 0 || s.x > m.w || s.y > m.h) return;
          var w = ctx.measureText(r.n).width;
          MapEngine.roundRect(ctx, s.x - w / 2 - 3, s.y - 7, w + 6, 13, 3);
          ctx.fillStyle = 'rgba(255,255,255,.82)';
          ctx.fill();
          ctx.fillStyle = '#8b9aab';
          ctx.fillText(r.n, s.x, s.y);
        });
      }
    }
  });

  /* ---------- 4. 候选路网 ---------- */
  var netLayer = map.addLayer({
    id: 'network',
    z: 2.5,
    draw: function (ctx, m) {
      if (!state.showLayers.network) return;
      ctx.strokeStyle = C.net;
      ctx.lineWidth = Math.max(0.7, (m.zoom - 14) * 0.32);
      ctx.beginPath();
      DATA.edges.forEach(function (e) {
        var a = DATA.nodes[e[0]], b = DATA.nodes[e[1]];
        var pa = m.toScreen(a[0], a[1]), pb = m.toScreen(b[0], b[1]);
        if (Math.max(pa.x, pb.x) < 0 || Math.min(pa.x, pb.x) > m.w) return;
        if (Math.max(pa.y, pb.y) < 0 || Math.min(pa.y, pb.y) > m.h) return;
        ctx.moveTo(pa.x, pa.y);
        ctx.lineTo(pb.x, pb.y);
      });
      ctx.stroke();

      if (m.zoom >= 18) {
        ctx.fillStyle = 'rgba(120,142,168,.75)';
        DATA.nodes.forEach(function (n) {
          var p = m.toScreen(n[0], n[1]);
          if (p.x < 0 || p.y < 0 || p.x > m.w || p.y > m.h) return;
          ctx.beginPath();
          ctx.arc(p.x, p.y, 1.6, 0, Math.PI * 2);
          ctx.fill();
        });
      }
    }
  });

  /* ---------- 5. 校门 ---------- */
  var gateLayer = map.addLayer({
    id: 'gates',
    z: 6,
    draw: function (ctx, m) {
      if (!state.showLayers.gates) return;
      DATA.gates.forEach(function (g) {
        var p = m.toScreen(g.x, g.y);
        if (p.x < -40 || p.y < -40 || p.x > m.w + 40 || p.y > m.h + 40) return;
        /* 光晕 */
        ctx.beginPath();
        ctx.arc(p.x, p.y, 11, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(224,138,30,.16)';
        ctx.fill();
        /* 圆点 */
        ctx.beginPath();
        ctx.arc(p.x, p.y, 5.4, 0, Math.PI * 2);
        ctx.fillStyle = C.gate;
        ctx.fill();
        ctx.lineWidth = 2;
        ctx.strokeStyle = '#fff';
        ctx.stroke();
        /* 标签 */
        ctx.font = '600 11px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        var txt = g.n;
        var wdt = ctx.measureText(txt).width;
        MapEngine.roundRect(ctx, p.x + 10, p.y - 9, wdt + 12, 18, 5);
        ctx.fillStyle = 'rgba(255,255,255,.95)';
        ctx.fill();
        ctx.strokeStyle = 'rgba(224,138,30,.4)';
        ctx.lineWidth = 1;
        ctx.stroke();
        ctx.fillStyle = '#9a5b0d';
        ctx.fillText(txt, p.x + 16, p.y);
      });
    },
    hitTest: function (ctx, m, x, y) {
      if (!state.showLayers.gates) return null;
      for (var i = 0; i < DATA.gates.length; i++) {
        var g = DATA.gates[i];
        var p = m.toScreen(g.x, g.y);
        if (Math.hypot(p.x - x, p.y - y) < 15) {
          return { kind: 'gate', data: g };
        }
      }
      return null;
    }
  });

  /* ---------- 6. 校园地点 ---------- */
  var CAT_ICON = {
    teaching_or_lab: '#4a7fb5', dormitory: '#7a6bab', library: '#2f8f83',
    restaurant: '#c4692f', sports_or_recreation: '#3f8f56', transit: '#1f9d6b',
    parking: '#6b7c90', bicycle_parking: '#6b7c90', barrier_or_gate: '#a0aab8',
    landmark: '#8a7a5c', toilets: '#7c8a9c', shop: '#b0682f', default: '#5b6a7d'
  };

  var placeLayer = map.addLayer({
    id: 'places',
    z: 5,
    draw: function (ctx, m) {
      if (!state.showLayers.places) return;
      var showLabel = m.zoom >= 17.2;
      DATA.places.forEach(function (p) {
        if (p.c === 'barrier_or_gate') return;
        var s = m.toScreen(p.x, p.y);
        if (s.x < -60 || s.y < -20 || s.x > m.w + 60 || s.y > m.h + 20) return;
        var col = CAT_ICON[p.c] || CAT_ICON.default;
        ctx.beginPath();
        ctx.arc(s.x, s.y, 3.6, 0, Math.PI * 2);
        ctx.fillStyle = col;
        ctx.fill();
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 1.4;
        ctx.stroke();

        if (showLabel && p.n && p.n.length <= 14) {
          ctx.font = '500 10.5px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';
          ctx.textAlign = 'left';
          ctx.textBaseline = 'middle';
          var w = ctx.measureText(p.n).width;
          MapEngine.roundRect(ctx, s.x + 7, s.y - 7.5, w + 8, 15, 4);
          ctx.fillStyle = 'rgba(255,255,255,.9)';
          ctx.fill();
          ctx.fillStyle = '#3d4b5c';
          ctx.fillText(p.n, s.x + 11, s.y);
        }
      });
    },
    hitTest: function (ctx, m, x, y) {
      if (!state.showLayers.places) return null;
      for (var i = 0; i < DATA.places.length; i++) {
        var p = DATA.places[i];
        if (p.c === 'barrier_or_gate') continue;
        var s = m.toScreen(p.x, p.y);
        if (Math.hypot(s.x - x, s.y - y) < 11) {
          return { kind: 'place', data: p };
        }
      }
      return null;
    }
  });

  /* ---------- 7. 周边交通点 ---------- */
  var TRANSIT_STYLE = {
    subway: { color: '#1a73e8', r: 5.5, label: '地铁' },
    subway_entrance: { color: '#5b8def', r: 4.2, label: '出入口' },
    tram: { color: '#1f9d6b', r: 4.6, label: '有轨电车' },
    bus: { color: '#e08a1e', r: 4.4, label: '公交' },
    transit: { color: '#6b7c90', r: 4, label: '交通点' }
  };

  var transitLayer = map.addLayer({
    id: 'transit',
    z: 5.5,
    draw: function (ctx, m) {
      if (!state.showLayers.transit) return;
      DATA.transit.forEach(function (t) {
        var st = TRANSIT_STYLE[t.k] || TRANSIT_STYLE.transit;
        var s = m.toScreen(t.x, t.y);
        if (s.x < -50 || s.y < -20 || s.x > m.w + 50 || s.y > m.h + 20) return;

        ctx.beginPath();
        ctx.arc(s.x, s.y, st.r, 0, Math.PI * 2);
        ctx.fillStyle = st.color;
        ctx.fill();
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 1.8;
        ctx.stroke();

        if (m.zoom >= 16.6) {
          ctx.font = '600 10px -apple-system, "PingFang SC", sans-serif';
          ctx.textAlign = 'left';
          ctx.textBaseline = 'middle';
          var lbl = t.n;
          if (!lbl) return;
          var w = ctx.measureText(lbl).width;
          MapEngine.roundRect(ctx, s.x + 8, s.y - 7.5, w + 9, 15, 4);
          ctx.fillStyle = 'rgba(255,255,255,.92)';
          ctx.fill();
          ctx.fillStyle = st.color;
          ctx.fillText(lbl, s.x + 12, s.y);
        }
      });
    },
    hitTest: function (ctx, m, x, y) {
      if (!state.showLayers.transit) return null;
      for (var i = 0; i < DATA.transit.length; i++) {
        var t = DATA.transit[i];
        var s = m.toScreen(t.x, t.y);
        if (Math.hypot(s.x - x, s.y - y) < 12) {
          return { kind: 'transit', data: t };
        }
      }
      return null;
    }
  });

  /* ---------- 8. 路线 + 起终点 ---------- */
  var routeLayer = map.addLayer({
    id: 'route',
    z: 8,
    draw: function (ctx, m) {
      if (!state.plans) return;
      var plan = state.plans[state.activePlan];

      /* 备选（灰色虚线） */
      if (state.plans.length > 1) {
        state.plans.forEach(function (p, i) {
          if (i === state.activePlan) return;
          ctx.beginPath();
          p.coords.forEach(function (c, k) {
            var s = m.toScreen(c[0], c[1]);
            if (k === 0) ctx.moveTo(s.x, s.y); else ctx.lineTo(s.x, s.y);
          });
          ctx.strokeStyle = 'rgba(140,155,175,.55)';
          ctx.lineWidth = 4;
          ctx.setLineDash([7, 6]);
          ctx.lineCap = 'round';
          ctx.stroke();
          ctx.setLineDash([]);
        });
      }

      /* 主路线：白色外描边 + 蓝色主线 */
      ctx.beginPath();
      plan.coords.forEach(function (c, i) {
        var s = m.toScreen(c[0], c[1]);
        if (i === 0) ctx.moveTo(s.x, s.y); else ctx.lineTo(s.x, s.y);
      });
      ctx.strokeStyle = 'rgba(255,255,255,.95)';
      ctx.lineWidth = 11;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.stroke();

      ctx.strokeStyle = C.route;
      ctx.lineWidth = 6;
      ctx.stroke();

      /* 流向动画光点 */
      var now = performance.now();
      var path = plan.coords;
      if (path.length > 1) {
        var total = 0;
        var segs = [];
        for (var i = 1; i < path.length; i++) {
          var a = m.toScreen(path[i - 1][0], path[i - 1][1]);
          var b = m.toScreen(path[i][0], path[i][1]);
          var L = Math.hypot(b.x - a.x, b.y - a.y);
          segs.push({ a: a, b: b, L: L, acc: total });
          total += L;
        }
        if (total > 1) {
          var t = ((now / 22) % total);
          for (var k = 0; k < segs.length; k++) {
            var sg = segs[k];
            if (t >= sg.acc && t <= sg.acc + sg.L) {
              var f = (t - sg.acc) / sg.L;
              var px = sg.a.x + (sg.b.x - sg.a.x) * f;
              var py = sg.a.y + (sg.b.y - sg.a.y) * f;
              var grd = ctx.createRadialGradient(px, py, 0, px, py, 13);
              grd.addColorStop(0, 'rgba(255,255,255,.95)');
              grd.addColorStop(1, 'rgba(255,255,255,0)');
              ctx.beginPath();
              ctx.arc(px, py, 13, 0, Math.PI * 2);
              ctx.fillStyle = grd;
              ctx.fill();
              ctx.beginPath();
              ctx.arc(px, py, 3, 0, Math.PI * 2);
              ctx.fillStyle = '#fff';
              ctx.fill();
              break;
            }
          }
        }
      }

      /* 起点 / 终点标记 */
      if (plan.coords.length) {
        markerAt(ctx, m, plan.coords[0], '起', '#1f9d6b');
        markerAt(ctx, m, plan.coords[plan.coords.length - 1], '终', '#d64545');
      }
    }
  });

  function markerAt(ctx, m, c, label, color) {
    var s = m.toScreen(c[0], c[1]);
    ctx.beginPath();
    ctx.arc(s.x, s.y, 12, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,255,255,.96)';
    ctx.fill();
    ctx.lineWidth = 3;
    ctx.strokeStyle = color;
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(s.x, s.y, 8, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();
    ctx.font = '700 10px -apple-system, "PingFang SC", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#fff';
    ctx.fillText(label, s.x, s.y + 0.5);
  }

  /* 让动画持续刷新 */
  setInterval(function () {
    if (state.plans) routeLayer && map.invalidate();
  }, 60);

  /* ================= 地图交互 ================= */
  var toastEl = document.getElementById('mapToast');
  var toastTimer = null;
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 2600);
  }

  map.on('click', function (e) {
    if (!e.hit) return;
    var h = e.hit;
    if (h.kind === 'gate') openGateDrawer(h.data);
    else if (h.kind === 'place') openPlaceDrawer(h.data);
    else if (h.kind === 'transit') openTransitDrawer(h.data);
    else if (h.kind === 'building') openBuildingDrawer(h);
  });

  map.on('hover', function (e) {
    if (!e.hit) { document.body.style.cursor = ''; return; }
    var h = e.hit;
    var txt = h.kind === 'gate' ? h.data.n :
      h.kind === 'place' ? h.data.n :
        h.kind === 'transit' ? h.data.n :
          h.kind === 'building' ? (h.name || '未命名建筑') : '';
    if (txt) document.body.style.cursor = 'pointer';
  });

  /* 比例尺 */
  var scaleEl = document.getElementById('mapScale');
  function updateScale() {
    var mpp = map.metersPerPixel(map.center.lat);
    var targetPx = 80;
    var meters = mpp * targetPx;
    var nice = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000];
    var best = nice[0];
    for (var i = 0; i < nice.length; i++) {
      if (nice[i] <= meters) best = nice[i];
    }
    var px = best / mpp;
    scaleEl.querySelector('.scale-bar').style.width = Math.round(px) + 'px';
    scaleEl.querySelector('.scale-text').textContent =
      best >= 1000 ? (best / 1000) + ' km' : best + ' m';
  }
  map.on('zoom', updateScale);
  map.on('move', updateScale);

  /* 缩放按钮 */
  document.getElementById('zoomIn').onclick = function () {
    map.setView(map.center.lng, map.center.lat, Math.min(map.maxZoom, map.zoom + 0.6), true);
  };
  document.getElementById('zoomOut').onclick = function () {
    map.setView(map.center.lng, map.center.lat, Math.max(map.minZoom, map.zoom - 0.6), true);
  };
  document.getElementById('btnReset').onclick = function () {
    resetView(true);
  };
  function resetView(animate) {
    map.fitBounds(DATA.boundary[0].filter(function (_, i) { return i % 3 === 0; }), 50, animate);
  }

  /* 图层开关 */
  document.getElementById('layerList').addEventListener('change', function (e) {
    var cb = e.target;
    if (cb.dataset.layer) {
      state.showLayers[cb.dataset.layer] = cb.checked;
      map.invalidate();
    }
  });

  /* ================= 路由引擎 ================= */
  var router = new CampusRouter(DATA);
  var idMap = {};
  DATA.nodeIds.forEach(function (id, i) { idMap[id] = i; });
  router.setNodeIdMap(idMap);
  router.buildGraph();

  var agent = new RouteAgent({
    data: DATA,
    city: CITY,
    router: router
  });

  /* 数据统计 */
  document.getElementById('statEdges').textContent = router.stats.total + ' 段';
  document.getElementById('statWalk').textContent = router.stats.walkable + ' 段';
  document.getElementById('statProh').textContent = router.stats.prohibited + ' 段';
  document.getElementById('statDate').textContent = DATA.collected_on;
  document.getElementById('dataStatusText').textContent =
    'OSM WGS84 · ' + DATA.gates.length + ' 个校门候选 · 未实地验收';

  /* ================= 组合框 ================= */
  function buildCombo(inputEl, listEl, opts) {
    var items = [];
    var hl = -1;

    function close() { listEl.classList.remove('open'); hl = -1; }

    function render(q) {
      var hits = [];
      q = (q || '').trim();

      /* 校门 */
      DATA.gates.forEach(function (g) {
        hits.push({ label: g.n, meta: '校门 · 待核实', lng: g.x, lat: g.y, kind: 'gate', id: g.id });
      });
      /* 校园地点 */
      DATA.places.forEach(function (p) {
        if (p.c === 'barrier_or_gate') return;
        hits.push({ label: p.n, meta: (CAT_NAMES[p.c] || p.c), lng: p.x, lat: p.y, kind: 'place', id: p.id });
      });
      /* 城市目的地（仅目的地框） */
      if (opts.allowCity) {
        CITY.destinations.forEach(function (d) {
          hits.push({ label: d.name, meta: '成都 · ' + d.cat, lng: d.lng, lat: d.lat, kind: 'city', id: d.id });
        });
      }

      if (q) {
        var nq = q.toLowerCase();
        hits = hits.filter(function (h) {
          return (h.label || '').toLowerCase().indexOf(nq) >= 0;
        });
        hits.sort(function (a, b) {
          var ai = a.label.toLowerCase().indexOf(nq);
          var bi = b.label.toLowerCase().indexOf(nq);
          if (ai !== bi) return ai - bi;
          return a.label.length - b.label.length;
        });
      }

      /* 去重 */
      var seen = {};
      hits = hits.filter(function (h) {
        var k = h.label + '|' + h.kind;
        if (seen[k]) return false;
        seen[k] = 1;
        return true;
      });

      items = hits.slice(0, 40);
      listEl.innerHTML = '';
      if (!items.length) {
        listEl.innerHTML = '<div class="combo-empty">没有匹配的地点</div>';
        listEl.classList.add('open');
        return;
      }
      items.forEach(function (h, i) {
        var d = document.createElement('div');
        d.className = 'combo-item';
        d.innerHTML = '<span class="ci-name"></span><span class="ci-meta"></span>';
        d.querySelector('.ci-name').textContent = h.label || '未命名';
        d.querySelector('.ci-meta').textContent = h.meta;
        d.onclick = function () { pick(h); };
        d.onmouseenter = function () { hl = i; paint(); };
        listEl.appendChild(d);
      });
      listEl.classList.add('open');
      hl = -1;
    }

    function paint() {
      Array.prototype.forEach.call(listEl.children, function (c, i) {
        if (c.classList) c.classList.toggle('hl', i === hl);
      });
    }

    function pick(h) {
      inputEl.value = h.label;
      close();
      if (opts.onPick) opts.onPick(h);
    }

    inputEl.addEventListener('focus', function () { render(inputEl.value); });
    inputEl.addEventListener('input', function () { render(inputEl.value); });
    inputEl.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { e.preventDefault(); hl = Math.min(items.length - 1, hl + 1); paint(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); hl = Math.max(0, hl - 1); paint(); }
      else if (e.key === 'Enter') {
        if (hl >= 0 && items[hl]) { e.preventDefault(); pick(items[hl]); }
        else if (opts.allowFree) {
          e.preventDefault();
          close();
          if (opts.onPick) opts.onPick({ label: inputEl.value, kind: 'free' });
        }
      } else if (e.key === 'Escape') close();
    });
    document.addEventListener('click', function (e) {
      if (!inputEl.parentNode.contains(e.target)) close();
    });
    return { close: close };
  }

  var CAT_NAMES = {
    teaching_or_lab: '教学实验', dormitory: '宿舍', library: '图书馆', restaurant: '餐饮',
    sports_or_recreation: '体育', transit: '交通', parking: '停车', bicycle_parking: '自行车停放',
    landmark: '地标', toilets: '卫生间', shop: '商铺', barrier_or_gate: '门禁',
    office_or_library: '办公/图书', public_facility: '生活设施', building: '建筑'
  };

  var originInput = document.getElementById('originInput');
  var destInput = document.getElementById('destInput');
  var originHint = document.getElementById('originHint');
  var destHint = document.getElementById('destHint');

  var originCombo = buildCombo(originInput, document.getElementById('originList'), {
    allowCity: false,
    onPick: function (h) {
      state.origin = h;
      setHint(originHint, h, true);
    }
  });
  var destCombo = buildCombo(destInput, document.getElementById('destList'), {
    allowCity: true,
    onPick: function (h) {
      state.dest = h;
      setHint(destHint, h, false);
    }
  });

  function setHint(el, h, isOrigin) {
    el.classList.remove('warn');
    if (h.kind === 'city') {
      el.textContent = '城市目的地 · ' + (h.meta || '成都') + ' · 城市段数据为人工整理，仅供参考';
      el.classList.add('warn');
    } else if (h.kind === 'gate') {
      el.textContent = '校门 · 开放状态待核实（数据包未提供通行规则）';
      el.classList.add('warn');
    } else if (h.kind === 'place') {
      el.textContent = '校内地点 · ' + (h.meta || CAT_NAMES[h.c] || '地点') + ' · 坐标来自 OSM，入口未核实';
    } else if (h.kind === 'free' || h.kind === 'official_only') {
      el.textContent = '未在校园坐标库中匹配到该地点，将使用名称提示';
      el.classList.add('warn');
    } else {
      el.textContent = h.meta || '';
    }
  }

  /* 默认值 */
  var libPlace = DATA.places.filter(function (p) { return p.n === '图书馆'; })[0];
  if (libPlace) {
    state.origin = { label: '图书馆', lng: libPlace.x, lat: libPlace.y, kind: 'place', id: libPlace.id };
    setHint(originHint, state.origin, true);
  }

  /* 交换 */
  document.getElementById('btnSwap').onclick = function () {
    var o = state.origin, d = state.dest;
    state.origin = d; state.dest = o;
    originInput.value = d ? d.label : '';
    destInput.value = o ? o.label : '';
    if (state.origin) setHint(originHint, state.origin, true);
    if (state.dest) setHint(destHint, state.dest, false);
    if (state.plans) runPlan();
  };

  /* 出行方式 */
  document.querySelectorAll('.mode').forEach(function (b) {
    b.onclick = function () {
      document.querySelectorAll('.mode').forEach(function (x) { x.classList.remove('active'); });
      b.classList.add('active');
      state.mode = b.dataset.mode;
      if (state.plans) runPlan();
    };
  });

  /* ================= 执行规划 ================= */
  var btnPlan = document.getElementById('btnPlan');
  var resultBody = document.getElementById('resultBody');
  var traceEl = document.getElementById('trace');
  var traceCount = document.getElementById('traceCount');

  function runPlan(utterance) {
    var text = utterance || document.getElementById('nlInput').value.trim();

    /* 如果输入框里有内容，优先走自然语言解析 */
    var useNL = !!(text && !state.dest);

    btnPlan.classList.add('loading');
    btnPlan.querySelector('.btn-label').textContent = '规划中';

    traceEl.innerHTML = '';
    traceCount.textContent = '0 步';

    setTimeout(function () {
      var res;
      if (useNL) {
        res = agent.plan(text, { mode: state.mode });
        /* 回填输入框 */
        if (res.origin) {
          originInput.value = res.origin.name;
          state.origin = { label: res.origin.name, lng: res.origin.lng, lat: res.origin.lat, kind: 'place' };
          setHint(originHint, state.origin, true);
        }
        if (res.destination) {
          destInput.value = res.destination.name;
          state.dest = { label: res.destination.name, lng: res.destination.lng, lat: res.destination.lat, kind: res.destination.type };
          setHint(destHint, state.dest, false);
        }
      } else {
        res = planFromFields();
      }

      renderTrace(res.steps);
      renderResult(res);
      drawPlans(res);

      btnPlan.classList.remove('loading');
      btnPlan.querySelector('.btn-label').textContent = '生成路线';
    }, 320);
  }

  function planFromFields() {
    var o = state.origin, d = state.dest;
    var steps = [];
    var intent = {
      raw: (o ? o.label : '') + ' → ' + (d ? d.label : ''),
      from: o, to: d
    };

    /* 起点解析 */
    steps.push({
      tool: 'resolve_origin',
      desc: '解析起点',
      args: { text: o ? o.label : null },
      result: o ? { name: o.label, type: o.kind, lng: o.lng, lat: o.lat } : { name: null },
      ms: 3
    });

    /* 目的地 */
    steps.push({
      tool: 'search_place',
      desc: '识别目的地',
      args: { query: d ? d.label : null },
      result: { hits: d ? [{ name: d.label, kind: d.kind }] : [] },
      ms: 4
    });

    /* 校门 */
    var gateInfo = null;
    if (o && o.lng != null) {
      if (d && d.kind === 'gate') {
        var g = DATA.gates.filter(function (x) { return x.n === d.label || x.id === d.id; })[0];
        if (g) gateInfo = { gate: g, all: [g], basis: 'user_specified' };
      } else if (d && d.kind === 'place') {
        /* 校内两点：不需要城市校门 */
        gateInfo = null;
      } else {
        gateInfo = agent.pickGate(d ? d.label : null);
      }
      steps.push({
        tool: 'pick_gate',
        desc: '选择校门',
        args: { destination: d ? d.label : null },
        result: gateInfo ? { gate: gateInfo.gate.n, basis: gateInfo.basis } : { skipped: true, reason: '校内目的地，无需出校' },
        ms: 5
      });
    } else {
      steps.push({ tool: 'pick_gate', desc: '选择校门', args: {}, result: { skipped: true, reason: '起点坐标不可用' }, ms: 1 });
    }

    /* 校内路径 */
    var campus = null;
    if (o && o.lng != null && d) {
      if (d.kind === 'place') {
        /* 校内点到校内点 */
        var r = router.planBetween(
          { lng: o.lng, lat: o.lat },
          { lng: d.lng, lat: d.lat }
        );
        if (r) {
          campus = {
            ok: true,
            plans: [{
              key: 'A', label: '最短步行',
              meters: r.summary.meters,
              coords: r.coords,
              summary: r.summary,
              accessDist: Math.round(r.startAccess)
            }],
            chosen: 'A',
            gate: null,
            accessDist: Math.round(r.startAccess)
          };
        } else {
          campus = { ok: false, reason: '两点不在同一连通分量' };
        }
        steps.push({
          tool: 'route_campus',
          desc: '校内步行路径（A*）',
          args: { origin: o.label, destination: d.label },
          result: campus.ok ? { meters: campus.plans[0].meters, segments: campus.plans[0].summary.segments.length } : { ok: false, reason: campus.reason },
          ms: 14
        });
      } else if (gateInfo) {
        campus = agent.routeCampus({ lng: o.lng, lat: o.lat, name: o.label }, gateInfo.gate, { mode: state.mode });
        steps.push({
          tool: 'route_campus',
          desc: '校内步行路径（A*）',
          args: { origin: o.label, gate: gateInfo.gate.n },
          result: campus.ok
            ? { meters: campus.plans[0].meters, plans: campus.plans.length, segments: campus.plans[0].summary.segments.length }
            : { ok: false, reason: campus.reason },
          ms: 14
        });
      }
    } else {
      steps.push({ tool: 'route_campus', desc: '校内步行路径（A*）', args: {}, result: { skipped: true, reason: '缺少可用坐标' }, ms: 1 });
    }

    /* 城市段 */
    var cityRes = null;
    if (d && d.kind === 'city' && gateInfo) {
      cityRes = agent.routeCity(gateInfo.gate, d.label);
      steps.push({
        tool: 'route_city',
        desc: '城市段接驳',
        args: { gate: gateInfo.gate.n, destination: d.label },
        result: { options: cityRes.options ? cityRes.options.length : 0, distanceKm: cityRes.distanceKm },
        ms: 9
      });
    } else {
      steps.push({
        tool: 'route_city', desc: '城市段接驳', args: {},
        result: { skipped: true, reason: d && d.kind === 'city' ? '缺少校门' : '目的地为校内' },
        ms: 1
      });
    }

    /* 质量 */
    var q = agent.checkDataQuality();
    steps.push({
      tool: 'check_data_quality',
      desc: '数据可信度检查',
      args: {},
      result: {
        routingReady: q.routingReady, p0Count: q.p0Count, totalIssues: q.totalIssues,
        walkableEdges: q.networkStats.walkable, prohibitedEdges: q.networkStats.prohibited
      },
      ms: 2
    });

    var descText = agent.describe({ campus: campus, city: cityRes, originName: o ? o.label : null });
    steps.push({
      tool: 'describe_route', desc: '生成路线说明', args: {},
      result: { text: descText }, ms: 6
    });

    return {
      intent: intent,
      origin: o,
      destination: d,
      gate: gateInfo ? gateInfo.gate : null,
      gateRanking: gateInfo ? gateInfo.all : null,
      gateBasis: gateInfo ? gateInfo.basis : null,
      campus: campus,
      city: cityRes,
      quality: q,
      text: descText,
      steps: steps
    };
  }

  /* ---------- 轨迹渲染 ---------- */
  function renderTrace(steps) {
    traceEl.innerHTML = '';
    steps.forEach(function (s, i) {
      var div = document.createElement('div');
      div.className = 'trace-item done';
      var resTxt = fmtResult(s.result);
      div.innerHTML =
        '<div class="trace-idx">' + (i + 1) + '</div>' +
        '<div class="trace-content">' +
        '<div><span class="trace-tool">' + esc(s.tool) + '</span>' +
        '<span class="trace-desc">' + esc(s.desc || '') + '</span></div>' +
        '<div class="trace-result">' + esc(resTxt) + '</div>' +
        '</div>' +
        '<div class="trace-ms">' + (s.ms || 0) + 'ms</div>';
      traceEl.appendChild(div);
    });
    traceCount.textContent = steps.length + ' 步';
  }

  function fmtResult(r) {
    if (!r) return '';
    if (r.skipped) return '⊘ ' + (r.reason || '跳过');
    if (r.ok === false) return '✕ ' + (r.reason || '失败');
    if (r.meters != null) return '→ ' + r.meters + ' m' + (r.plans ? ' · ' + r.plans + ' 方案' : '');
    if (r.gate) return '→ ' + r.gate;
    if (r.options) return '→ ' + r.options + ' 个接驳方案 · ' + r.distanceKm + ' km';
    if (r.hits) return r.hits.length ? '→ ' + r.hits[0].name : '∅ 无匹配';
    if (r.name) return '→ ' + r.name;
    if (r.text) return r.text.slice(0, 46) + (r.text.length > 46 ? '…' : '');
    if (r.walkableEdges != null) return 'walkable ' + r.walkableEdges + ' / P0 ' + r.p0Count;
    return JSON.stringify(r).slice(0, 46);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* ---------- 结果渲染 ---------- */
  function renderResult(res) {
    var h = [];

    /* 起终点摘要 */
    if (res.origin || res.destination) {
      h.push('<div class="res-card">');
      h.push('<div class="res-card-head"><span class="dotmark"></span>本次查询</div>');
      h.push('<div class="res-card-body" style="padding:10px 12px">');
      h.push('<div style="font-size:12.5px;line-height:1.9">');
      h.push('<div><span class="pill pill-info" style="margin-right:6px">起</span>' +
        esc(res.origin ? res.origin.name || res.origin.label : '—') + '</div>');
      h.push('<div><span class="pill pill-danger" style="margin-right:6px">终</span>' +
        esc(res.destination ? res.destination.name || res.destination.label : '—') + '</div>');
      if (res.gate) {
        h.push('<div><span class="pill pill-warn" style="margin-right:6px">门</span>' +
          esc(res.gate.n) + ' <span style="color:#9aa8b8;font-size:11px">（' +
          (res.gateBasis === 'user_specified' ? '用户指定' : 'Agent 按方位推荐') + '）</span></div>');
      }
      h.push('</div></div></div>');
    }

    /* 校内段 */
    if (res.campus && res.campus.ok) {
      var plans = res.campus.plans;
      h.push('<div class="res-card plan-main">');
      h.push('<div class="res-card-head"><span class="dotmark"></span>校内步行段</div>');
      h.push('<div class="res-card-body">');

      /* 方案切换 */
      if (plans.length > 1) {
        h.push('<div class="plan-tabs" id="planTabs">');
        plans.forEach(function (p, i) {
          h.push('<button class="plan-tab' + (i === 0 ? ' active' : '') + '" data-idx="' + i + '">' +
            esc(p.label) + '<small>' + p.meters + ' m</small></button>');
        });
        h.push('</div>');
      }

      var p0 = plans[0];
      h.push('<div class="plan-metrics">');
      h.push('<div class="metric"><div class="m-label">步行距离</div><div class="m-value">' +
        p0.meters + '<small>m</small></div></div>');
      h.push('<div class="metric"><div class="m-label">预计用时</div><div class="m-value">' +
        Math.max(1, Math.round(p0.meters / 78)) + '<small>分钟</small></div></div>');
      h.push('</div>');

      h.push('<div id="stepList">' + renderSteps(p0) + '</div>');
      h.push('</div></div>');
    } else if (res.campus && !res.campus.ok) {
      h.push('<div class="notice notice-warn"><span class="n-ico">⚠</span><div>' +
        '校内段未能生成：' + esc(res.campus.reason) + '</div></div>');
    }

    /* 城市段 */
    if (res.city && res.city.ok) {
      h.push('<div class="res-card">');
      h.push('<div class="res-card-head"><span class="dotmark"></span>城市接驳段' +
        '<span class="pill pill-warn" style="margin-left:auto">未核验</span></div>');
      h.push('<div class="res-card-body">');
      h.push('<div style="font-size:11.5px;color:#7c8a9c;margin-bottom:9px">' +
        '直线距离约 ' + res.city.distanceKm + ' km · 城市段数据为人工整理</div>');
      res.city.options.forEach(function (o) {
        h.push('<div class="city-opt">');
        h.push('<div class="line-badge" style="background:' + o.lineColor + '">' +
          esc((o.lineName || '').replace('地铁', '').slice(0, 3)) + '</div>');
        h.push('<div class="city-opt-info">');
        h.push('<div class="co-name">' + esc(o.hub) + '</div>');
        h.push('<div class="co-meta">步行 ' + o.walkM + ' m · ' + esc(o.lineName) +
          ' → ' + esc(o.station) + '</div>');
        h.push('</div>');
        h.push('<span class="pill ' + (o.confidence === 'low' ? 'pill-warn' : 'pill-info') + '">' +
          (o.confidence === 'low' ? '低置信' : '中置信') + '</span>');
        h.push('</div>');
      });
      h.push('</div></div>');
    }

    /* 数据提示 */
    h.push('<div class="notice notice-warn"><span class="n-ico">⚠</span><div>' +
      '本结果基于<strong>未实地验收</strong>的候选路网：楼宇质心≠入口，校门开放规则缺失，' +
      '接驳距离存在官方来源冲突（300m / 500m）。请勿作为实际导航使用。</div></div>');

    /* 校车信息 */
    var sh = (DATA.shuttle || []).filter(function (s) { return s.d === 'weekday'; }).slice(0, 8);
    if (sh.length) {
      h.push('<div class="res-card">');
      h.push('<div class="res-card-head"><span class="dotmark" style="background:#e08a1e"></span>' +
        '校车参考 <span class="pill pill-warn" style="margin-left:auto">2022 历史</span></div>');
      h.push('<div class="res-card-body">');
      h.push('<div style="font-size:11.5px;color:#7c8a9c;margin-bottom:8px">' +
        '犀浦 → 九里 · 工作日部分班次（非实时，禁止作为班次推荐）</div>');
      h.push('<div class="shuttle-list">');
      sh.forEach(function (s) {
        h.push('<div class="shuttle-time">' + esc(s.t) + '</div>');
      });
      h.push('</div></div></div>');
    }

    /* 施工提示 */
    if (DATA.construction && DATA.construction.length) {
      var c = DATA.construction[0];
      h.push('<div class="notice notice-info"><span class="n-ico">🚧</span><div>' +
        '<strong>' + esc(c.name) + '</strong><br />计划 ' + esc(c.planned_start) + ' ~ ' + esc(c.planned_end) +
        '，' + esc(c.daily_hours) + '。' + esc(c.notice_effect) +
        '（未地理配准，不自动封闭道路）</div></div>');
    }

    resultBody.innerHTML = h.join('');

    /* 方案切换事件 */
    var tabs = document.getElementById('planTabs');
    if (tabs) {
      tabs.addEventListener('click', function (e) {
        var b = e.target.closest('.plan-tab');
        if (!b) return;
        var idx = +b.dataset.idx;
        state.activePlan = idx;
        Array.prototype.forEach.call(tabs.children, function (c) { c.classList.remove('active'); });
        b.classList.add('active');
        var p = res.campus.plans[idx];
        document.getElementById('stepList').innerHTML = renderSteps(p);
        var metrics = resultBody.querySelectorAll('.plan-main .metric .m-value');
        if (metrics[0]) metrics[0].innerHTML = p.meters + '<small>m</small>';
        if (metrics[1]) metrics[1].innerHTML = Math.max(1, Math.round(p.meters / 78)) + '<small>分钟</small>';
        map.invalidate();
      });
    }
  }

  function renderSteps(p) {
    var h = ['<div class="steps">'];
    h.push('<div class="step-item"><div class="step-dot solid"></div>' +
      '<div class="step-text"><div class="st-name">出发</div>' +
      '<div class="st-meta">接入最近路网节点' +
      (p.accessDist > 20 ? '（接驳 ' + p.accessDist + ' m，入口未核实）' : '（已贴近路网）') + '</div></div></div>');

    var segs = p.summary.segments || [];
    var maxShow = 7;
    segs.slice(0, maxShow).forEach(function (s) {
      var meta = s.meters + ' m · ' + (s.highwayZh || s.highway || '道路');
      if (s.bridge) meta += ' · 桥梁';
      if (s.perm === 1) meta += ' · 条件通行';
      h.push('<div class="step-item"><div class="step-dot"></div>' +
        '<div class="step-text"><div class="st-name">' + esc(s.name) + '</div>' +
        '<div class="st-meta">' + esc(meta) + '</div></div></div>');
    });
    if (segs.length > maxShow) {
      h.push('<div class="step-item"><div class="step-dot"></div>' +
        '<div class="step-text"><div class="st-meta">… 另有 ' + (segs.length - maxShow) + ' 段</div></div></div>');
    }
    h.push('</div>');
    return h.join('');
  }

  /* ---------- 画路线 ---------- */
  function drawPlans(res) {
    if (res.campus && res.campus.ok) {
      state.plans = res.campus.plans;
      state.activePlan = 0;
      var pts = [];
      res.campus.plans.forEach(function (p) {
        p.coords.forEach(function (c) { pts.push(c); });
      });
      map.fitBounds(pts, 80, true);
    } else {
      state.plans = null;
      map.invalidate();
    }
  }

  /* ================= 事件绑定 ================= */
  btnPlan.onclick = function () { runPlan(); };
  document.getElementById('btnClear').onclick = function () {
    document.getElementById('nlInput').value = '';
    resultBody.innerHTML = '<div class="empty-state"><div class="empty-ico">🧭</div>' +
      '<p>输入一句话，Agent 会自动完成<br />「理解 → 选门 → 算路 → 校验」</p></div>';
    traceEl.innerHTML = '';
    traceCount.textContent = '0 步';
    state.plans = null;
    map.invalidate();
  };

  /* 结果面板收起/展开 */
  var btnCollapse = document.getElementById('btnCollapse');
  btnCollapse.onclick = function () {
    var collapsed = document.querySelector('.panel-right').classList.toggle('collapsed');
    btnCollapse.textContent = collapsed ? '展开' : '收起';
    if (!collapsed) setTimeout(function () { map._resize(); }, 260);
  };

  document.getElementById('nlInput').addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); runPlan(); }
  });

  document.querySelectorAll('#exampleChips .chip').forEach(function (c) {
    c.onclick = function () {
      document.getElementById('nlInput').value = c.dataset.q;
      state.dest = null;
      runPlan(c.dataset.q);
    };
  });

  /* 视图切换 */
  document.querySelectorAll('.tab').forEach(function (t) {
    t.onclick = function () {
      document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });
      t.classList.add('active');
      var v = t.dataset.view;
      document.getElementById('viewPlanner').style.display = v === 'planner' ? '' : 'none';
      document.getElementById('viewQuality').hidden = v !== 'quality';
      document.getElementById('viewAbout').hidden = v !== 'about';
      if (v === 'planner') setTimeout(function () { map._resize(); }, 30);
    };
  });

  /* ================= 抽屉 ================= */
  var drawer = document.getElementById('drawer');
  var drawerMask = document.getElementById('drawerMask');
  var drawerTitle = document.getElementById('drawerTitle');
  var drawerBody = document.getElementById('drawerBody');

  function openDrawer(title, html) {
    drawerTitle.textContent = title;
    drawerBody.innerHTML = html;
    drawer.hidden = false;
    drawerMask.hidden = false;
  }
  function closeDrawer() {
    drawer.hidden = true;
    drawerMask.hidden = true;
  }
  document.getElementById('drawerClose').onclick = closeDrawer;
  drawerMask.onclick = closeDrawer;
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeDrawer(); });

  function kv(k, v) {
    return '<div class="kv"><div class="k">' + esc(k) + '</div><div class="v">' + esc(v == null ? '—' : v) + '</div></div>';
  }

  function openGateDrawer(g) {
    var html = kv('名称', g.n) + kv('OSM ID', g.id) +
      kv('坐标', g.x + ', ' + g.y) + kv('坐标系', 'WGS84') +
      kv('距边界', g.bd + ' m') + kv('通行状态', g.pass) +
      kv('routing_ready', 'false') +
      '<div class="notice notice-warn" style="margin-top:14px"><span class="n-ico">⚠</span><div>' +
      '数据包明确要求：<strong>不能凭相似名称视为同一校门</strong>，6 个候选点中包含两个"东门"节点，' +
      '开放时间与步行权限均未核实。</div></div>';
    openDrawer(g.n, html);
  }

  function openPlaceDrawer(p) {
    var html = kv('名称', p.n) + kv('类别', CAT_NAMES[p.c] || p.c) +
      kv('OSM ID', p.id) + kv('坐标', p.x + ', ' + p.y) + kv('坐标系', 'WGS84') +
      kv('位置角色', p.r || 'mapped_point') + kv('routing_ready', 'false') +
      '<div class="notice notice-warn" style="margin-top:14px"><span class="n-ico">⚠</span><div>' +
      '该点可能是<strong>建筑轮廓质心</strong>而非真实入口，不能直接作为导航起点。</div></div>';
    openDrawer(p.n, html);
  }

  function openTransitDrawer(t) {
    var kindName = { subway: '地铁站', subway_entrance: '地铁出入口', tram: '有轨电车站', bus: '公交站', transit: '交通点' };
    var html = kv('名称', t.n) + kv('类型', kindName[t.k] || t.k) +
      kv('出口编号', t.ref) + kv('OSM ID', t.id) +
      kv('坐标', t.x + ', ' + t.y) + kv('坐标系', 'WGS84') +
      '<div class="notice notice-info" style="margin-top:14px"><span class="n-ico">ℹ</span><div>' +
      '站台、站点、出口分别保留为独立对象，未经关系核对，不给未命名出口强加所属站名。</div></div>';
    openDrawer(t.n, html);
  }

  function openBuildingDrawer(b) {
    var html = kv('名称', b.name || '未命名建筑') + kv('类别', b.category || '—') +
      '<div class="notice notice-warn" style="margin-top:14px"><span class="n-ico">⚠</span><div>' +
      '建筑轮廓来自 OSM，中心点不等于入口。</div></div>';
    openDrawer(b.name || '未命名建筑', html);
  }

  /* ================= 数据可信度视图 ================= */
  function buildQualityView() {
    var q = agent.checkDataQuality();
    var grid = document.getElementById('qualityGrid');
    var cards = [
      { label: '候选路网节点', value: DATA.nodes.length, note: 'OSM 道路相邻节点拆分所得' },
      { label: '候选路网边', value: DATA.edges.length, note: 'Haversine 球面长度，非实测' },
      { label: '可步行边', value: q.networkStats.walkable, note: '已排除 prohibited 边' },
      { label: '禁行边', value: q.networkStats.prohibited, note: '来源标注步行禁止' },
      { label: '条件通行边', value: q.networkStats.conditional, note: 'private/permit/customers 等' },
      { label: '跨校界边段', value: q.networkStats.crossBoundary, note: '未伪造校门连接' },
      { label: '校门候选', value: DATA.gates.length, note: '含 2 个"东门"节点，需消歧' },
      { label: 'P0 待核实项', value: q.p0Count, note: '最高优先级数据风险' },
      { label: 'routing_ready', value: 'false', small: true, note: '全部数据尚未通过实地验收' }
    ];
    grid.innerHTML = cards.map(function (c) {
      return '<div class="q-card"><div class="q-label">' + esc(c.label) + '</div>' +
        '<div class="q-value' + (c.small ? ' small' : '') + '">' + esc(c.value) + '</div>' +
        '<div class="q-note">' + esc(c.note) + '</div></div>';
    }).join('');

    var issues = DATA.unverified || [];
    var t = document.getElementById('issueTable');
    var rows = ['<div class="issue-row head"><div>编号</div><div>问题</div><div class="i-action">建议行动</div></div>'];
    issues.forEach(function (u) {
      rows.push('<div class="issue-row">' +
        '<div class="i-id"><span class="prio prio-' + esc(u.priority) + '">' + esc(u.priority) + '</span>' + esc(u.id) + '</div>' +
        '<div class="i-title">' + esc(u.title) + '<div style="font-size:11px;color:#a8b4c2;margin-top:2px">' + esc(u.impact) + '</div></div>' +
        '<div class="i-action">' + esc(u.action) + '</div>' +
        '</div>');
    });
    t.innerHTML = rows.join('');
  }

  /* ================= 启动 ================= */
  updateScale();
  buildQualityView();

  /* 首屏自动演示一次 */
  setTimeout(function () {
    map.fitBounds(DATA.boundary[0].filter(function (_, i) { return i % 3 === 0; }), 56, false);
    updateScale();
    document.getElementById('nlInput').value = '我从图书馆出发，想去天府广场，怎么走最快？';
  }, 60);

})();
