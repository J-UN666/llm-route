/* =========================================================================
 * routing.js — 校园步行路网引擎
 * 数据来源：cleaned/graph_nodes_candidates + graph_edges_candidates (OSM, WGS84)
 * 重要约束（来自数据包 README / 数据质量说明）：
 *   1. 楼宇质心 ≠ 入口，routing_ready 全为 false；
 *   2. walk_permission=prohibited 的边默认不参与步行；
 *   3. 校门、接驳距离均为"待核实"，不得当作已验收结果。
 * 本引擎把这些约束显式化为：接驳段单列、置信度分级、禁用边计数。
 * ========================================================================= */
(function (global) {
  'use strict';

  var R = 6371008.8;

  /* OSM highway 值 → 中文说明（用于路线分段展示） */
  var HIGHWAY_ZH = {
    footway: '步行道', pedestrian: '步行广场', path: '小径', steps: '台阶',
    cycleway: '骑行道', living_street: '生活性道路', residential: '居住区道路',
    service: '内部服务道', tertiary: '支路', track: '土路', unknown: '未知道路'
  };

  function hav(lng1, lat1, lng2, lat2) {
    var p1 = (lat1 * Math.PI) / 180, p2 = (lat2 * Math.PI) / 180;
    var dp = p2 - p1, dl = ((lng2 - lng1) * Math.PI) / 180;
    var h = Math.sin(dp / 2) * Math.sin(dp / 2) +
      Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  /* ---------- 最小堆 ---------- */
  function Heap() { this.a = []; }
  Heap.prototype.push = function (k, v) {
    var a = this.a; a.push([k, v]);
    var i = a.length - 1;
    while (i > 0) {
      var p = (i - 1) >> 1;
      if (a[p][0] <= a[i][0]) break;
      var t = a[p]; a[p] = a[i]; a[i] = t; i = p;
    }
  };
  Heap.prototype.pop = function () {
    var a = this.a;
    if (!a.length) return null;
    var top = a[0], last = a.pop();
    if (a.length) {
      a[0] = last;
      var i = 0, n = a.length;
      for (;;) {
        var l = 2 * i + 1, r = l + 1, m = i;
        if (l < n && a[l][0] < a[m][0]) m = l;
        if (r < n && a[r][0] < a[m][0]) m = r;
        if (m === i) break;
        var t = a[m]; a[m] = a[i]; a[i] = t; i = m;
      }
    }
    return top;
  };
  Heap.prototype.size = function () { return this.a.length; };

  /* =======================================================================
   * CampusRouter
   * ===================================================================== */
  function CampusRouter(data) {
    this.data = data;
    this.nodes = data.nodes;          // [[lng,lat],...]
    this.rawEdges = data.edges;       // [a,b,len,perm,hw,bridge,cross,nameIdx]
    this.hw = data.highways;
    this.edgeNames = data.edgeNames || [];
    this.adj = new Array(this.nodes.length);
    for (var i = 0; i < this.nodes.length; i++) this.adj[i] = [];

    var self = this;
    this.stats = { total: 0, walkable: 0, prohibited: 0, conditional: 0, bridge: 0, crossBoundary: 0 };
    this.rawEdges.forEach(function (e) {
      self.stats.total++;
      if (e[3] === 3) self.stats.prohibited++;
      if (e[3] === 1) self.stats.conditional++;
      if (e[5]) self.stats.bridge++;
      if (e[6]) self.stats.crossBoundary++;
    });
  }

  CampusRouter.prototype.edgeName = function (e) {
    var nm = this.edgeNames[e[7]];
    return nm || '';
  };

  /* 构建可步行邻接表（prohibited 排除） */
  CampusRouter.prototype.buildGraph = function (opts) {
    opts = opts || {};
    var allowProhibited = !!opts.allowProhibited;
    var self = this;
    var adj = new Array(this.nodes.length);
    for (var i = 0; i < this.nodes.length; i++) adj[i] = [];
    var walkable = 0;
    this.rawEdges.forEach(function (e) {
      if (e[3] === 3 && !allowProhibited) return;
      walkable++;
      var w = e[2];
      adj[e[0]].push({ to: e[1], w: w, e: e });
      adj[e[1]].push({ to: e[0], w: w, e: e });
    });
    this.adj = adj;
    this.stats.walkable = walkable;
    this._labelComponents();
    return adj;
  };

  /* 标记连通分量，记住最大分量（接入点只允许落在最大分量上） */
  CampusRouter.prototype._labelComponents = function () {
    var n = this.nodes.length;
    var comp = new Int32Array(n).fill(-1);
    var sizes = [];
    var c = 0;
    for (var i = 0; i < n; i++) {
      if (comp[i] >= 0 || !this.adj[i].length) continue;
      var st = [i], cnt = 0;
      comp[i] = c;
      while (st.length) {
        var u = st.pop();
        cnt++;
        var list = this.adj[u];
        for (var k = 0; k < list.length; k++) {
          var v = list[k].to;
          if (comp[v] < 0) { comp[v] = c; st.push(v); }
        }
      }
      sizes.push(cnt);
      c++;
    }
    var main = 0;
    for (var m = 1; m < sizes.length; m++) if (sizes[m] > sizes[main]) main = m;
    this._comp = comp;
    this._compSizes = sizes;
    this._mainComp = sizes.length ? main : -1;
    this.stats.components = sizes.length;
    this.stats.mainComponentSize = sizes.length ? sizes[main] : 0;
  };

  CampusRouter.prototype.nearestNode = function (lng, lat, opts) {
    opts = opts || {};
    var best = -1, bd = Infinity;
    var fallback = -1, fbd = Infinity;
    var main = this._mainComp;
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i];
      var dx = (n[0] - lng) * 102400, dy = (n[1] - lat) * 111000;
      var d = dx * dx + dy * dy;
      if (d < bd) { bd = d; best = i; }
      /* 接入点必须落在最大连通分量上（否则算不出路径） */
      if (this.adj[i] && this.adj[i].length > 0 && this._comp[i] === main && d < fbd) {
        fbd = d; fallback = i;
      }
    }
    if (opts.connectedOnly && fallback >= 0) {
      return { index: fallback, dist: Math.sqrt(fbd), rawIndex: best, rawDist: Math.sqrt(bd) };
    }
    return { index: best, dist: Math.sqrt(bd) };
  };

  /* A*：返回 {path:[nodeIdx], meters, edges:[rawEdge]} */
  CampusRouter.prototype.astar = function (startIdx, goalIdx, weightFn) {
    var nodes = this.nodes, adj = this.adj;
    if (startIdx === goalIdx) return { path: [startIdx], meters: 0, edges: [] };
    var gx = nodes[goalIdx][0], gy = nodes[goalIdx][1];
    function h(i) {
      var dx = (nodes[i][0] - gx) * 102400, dy = (nodes[i][1] - gy) * 111000;
      return Math.sqrt(dx * dx + dy * dy) * 1.0;
    }
    var gScore = new Float64Array(nodes.length).fill(Infinity);
    var prev = new Int32Array(nodes.length).fill(-1);
    var prevEdge = new Array(nodes.length).fill(null);
    var closed = new Uint8Array(nodes.length);
    var heap = new Heap();
    gScore[startIdx] = 0;
    heap.push(h(startIdx), startIdx);

    while (heap.size()) {
      var item = heap.pop();
      var u = item[1];
      if (closed[u]) continue;
      closed[u] = 1;
      if (u === goalIdx) break;
      var list = adj[u];
      for (var k = 0; k < list.length; k++) {
        var nb = list[k];
        var w = weightFn ? weightFn(nb.e, nb.w) : nb.w;
        var ng = gScore[u] + w;
        if (ng < gScore[nb.to]) {
          gScore[nb.to] = ng;
          prev[nb.to] = u;
          prevEdge[nb.to] = nb.e;
          heap.push(ng + h(nb.to), nb.to);
        }
      }
    }
    if (gScore[goalIdx] === Infinity) return null;
    var path = [], edges = [], cur = goalIdx;
    while (cur !== -1) {
      path.push(cur);
      if (prevEdge[cur]) edges.push(prevEdge[cur]);
      cur = prev[cur];
    }
    path.reverse(); edges.reverse();
    var meters = 0;
    edges.forEach(function (e) { meters += e[2]; });
    return { path: path, meters: meters, edges: edges };
  };

  /* 路径 → [[lng,lat],...] */
  CampusRouter.prototype.pathCoords = function (path) {
    var self = this;
    return path.map(function (i) { return [self.nodes[i][0], self.nodes[i][1]]; });
  };

  /* 路径分段统计 */
  CampusRouter.prototype.summarize = function (result) {
    var self = this;
    var segs = [];
    var cur = null;
    result.edges.forEach(function (e) {
      var hwName = self.hw[e[4]] || 'unknown';
      var nm = self.edgeName(e) || (HIGHWAY_ZH[hwName] || hwName);
      if (!cur || cur.name !== nm) {
        cur = {
          name: nm,
          meters: 0,
          highway: hwName,
          highwayZh: HIGHWAY_ZH[hwName] || hwName,
          perm: e[3],
          bridge: !!e[5]
        };
        segs.push(cur);
      }
      cur.meters += e[2];
    });
    segs.forEach(function (s) { s.meters = Math.round(s.meters); });
    return {
      meters: Math.round(result.meters),
      segments: segs,
      bridges: result.edges.filter(function (e) { return e[5]; }).length,
      prohibited: result.edges.filter(function (e) { return e[3] === 3; }).length,
      conditional: result.edges.filter(function (e) { return e[3] === 1; }).length,
      steps: result.edges.filter(function (e) { return self.hw[e[4]] === 'steps'; }).length,
      edgeCount: result.edges.length
    };
  };

  /* ---------- 多方案规划 ----------
   * 方案 A：最短步行
   * 方案 B：避开"条件通行"路段（更稳妥）
   * 方案 C：经指定校门（若起点/终点在校内且目标为城市）
   */
  /* 校门可关联多个路网节点（如东门的进校/出校车道）。routeNodes 支持：
   *   { default: {in, out}, walk: {...}, bike: {...} }   按通行方式覆盖 default
   *   [{ direction:'in', nodeId:'...', modes:['walk'] }]  数组写法
   * direction 缺省为 out（校内→校外）；没有 routeNodes 的旧数据回退
   * routeNodeId / id，保持西门等既有校门可用。 */
  CampusRouter.prototype.gateRouteNodeId = function (gate, opts) {
    opts = opts || {};
    var direction = opts.direction === 'in' ? 'in' : 'out';
    var mode = opts.mode || 'walk';
    if (!gate) return null;
    var rn = gate.routeNodes;
    if (Array.isArray(rn)) {
      var sameDir = rn.filter(function (r) { return r && r.direction === direction; });
      var hit = sameDir.filter(function (r) {
        return !r.modes || r.modes.indexOf(mode) >= 0;
      })[0] || sameDir[0];
      return hit ? hit.nodeId : null;
    }
    if (rn && typeof rn === 'object') {
      var byMode = rn[mode] || rn.default;
      if (byMode && byMode[direction]) return byMode[direction];
      if (rn[direction]) return rn[direction];
    }
    return gate.routeNodeId || gate.id || null;
  };

  CampusRouter.prototype.gateNodeIndex = function (gate, opts) {
    var nodeId = this.gateRouteNodeId(gate, opts);
    var index = nodeId != null ? this.nodeIndexById(nodeId) : -1;
    if (index < 0 && gate && gate.x != null && gate.y != null) {
      index = this.nearestNode(gate.x, gate.y, { connectedOnly: true }).index;
      nodeId = null;
    }
    return { index: index, nodeId: nodeId };
  };

  CampusRouter.prototype.planToGate = function (fromLng, fromLat, gate, opts) {
    opts = opts || {};
    var self = this;
    var startN = this.nearestNode(fromLng, fromLat, { connectedOnly: true });
    var gateRes = this.gateNodeIndex(gate, opts);
    var gateIdx = gateRes.index;
    if (gateIdx < 0) return null;
    var gateNodeId = gateRes.nodeId ||
      (this.data.nodeIds && this.data.nodeIds[gateIdx]) || null;
    var direction = opts.direction === 'in' ? 'in' : 'out';

    var plans = [];

    /* A: 最短 */
    var a = this.astar(startN.index, gateIdx, null);
    if (a) plans.push({ key: 'A', label: '最短步行', result: a });

    /* B: 避开条件通行/台阶 */
    var b = this.astar(startN.index, gateIdx, function (e, w) {
      if (e[3] === 1) return w * 2.6;
      if (self.hw[e[4]] === 'steps') return w * 1.6;
      return w;
    });
    if (b && (!a || Math.abs(b.meters - a.meters) > 5)) {
      plans.push({ key: 'B', label: '稳妥步行（避开条件通行）', result: b });
    }

    plans.forEach(function (p) {
      p.coords = self.pathCoords(p.result.path);
      p.summary = self.summarize(p.result);
      p.accessDist = startN.dist;
    });
    return {
      startNode: startN,
      gateIdx: gateIdx,
      gateNodeId: gateNodeId,
      direction: direction,
      plans: plans
    };
  };

  CampusRouter.prototype.nodeIndexById = function (id) {
    if (!this._idIdx) {
      this._idIdx = {};
      var self = this;
      // node_id 在 data 中未直接保存，用坐标反查不现实；此处由外部注入
      this._idIdx = this._nodeIdMap || {};
    }
    var v = this._nodeIdMap ? this._nodeIdMap[id] : undefined;
    return v == null ? -1 : v;
  };

  CampusRouter.prototype.setNodeIdMap = function (map) { this._nodeIdMap = map; };

  /* 校内任意两点 */
  CampusRouter.prototype.planBetween = function (a, b) {
    var self = this;
    var s = this.nearestNode(a.lng, a.lat, { connectedOnly: true });
    var t = this.nearestNode(b.lng, b.lat, { connectedOnly: true });
    var r = this.astar(s.index, t.index, null);
    if (!r) return null;
    return {
      coords: this.pathCoords(r.path),
      summary: this.summarize(r),
      startAccess: s.dist,
      endAccess: t.dist
    };
  };

  /* 城市段方案生成 */
  CampusRouter.prototype.planCity = function (gateName, dest, city) {
    var conn = (city.gate_connections || []).filter(function (c) { return c.gate === gateName; });
    var line = null, stationName = dest.station;
    (city.lines || []).forEach(function (l) {
      if (l.id === dest.line) line = l;
    });
    var out = [];
    var hubCandidates = conn.length ? conn : [{ hub: '地铁犀浦站', walk_m: 800, evidence: '按几何估算', confidence: 'low' }];

    hubCandidates.forEach(function (c) {
      out.push({
        hub: c.hub,
        walkM: c.walk_m,
        evidence: c.evidence,
        confidence: c.confidence,
        line: line ? line.name : '地铁',
        lineColor: line ? line.color : '#888',
        station: stationName,
        note: dest.note
      });
    });
    return out;
  };

  global.CampusRouting = {
    CampusRouter: CampusRouter,
    hav: hav
  };
})(window);
