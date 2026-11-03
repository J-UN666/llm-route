/* =========================================================================
 * agent.js — LLM Agent 调度层（演示版）
 * -------------------------------------------------------------------------
 * 设计要点（对应项目要求"LLM Agent 工具应用"）：
 *   Agent = 意图理解 → 工具编排 → 结果生成 → 不确定性声明
 * 工具（Tool）:
 *   search_place        地点检索与别名消歧
 *   resolve_origin      起点解析（校内地点 / 当前定位 / 校门）
 *   pick_gate           校门选择
 *   route_campus        校内步行路径计算（A*）
 *   route_city          城市段接驳方案
 *   check_data_quality  数据可信度检查（含 16 项待核实清单）
 *   describe_route      自然语言路线描述
 * 说明：本文件内置离线规则解析器；若在设置中填入 LLM API Key，
 *       可通过 callLLM() 走真实模型（OpenAI 兼容 /chat/completions）。
 * ========================================================================= */
(function (global) {
  'use strict';

  /* ---------- 工具注册表 ---------- */
  function Agent(ctx) {
    this.ctx = ctx;                 // {data, city, router, gates, places, onStep}
    this.tools = {};
    this.registerBuiltinTools();
  }

  /* 长地名简写：西南交通大学犀浦校区图书馆 → 图书馆 */
  function shortName(n) {
    if (!n) return n;
    return String(n)
      .replace(/^西南交通大学犀浦校区/, '')
      .replace(/^西南交大犀浦校区/, '')
      .replace(/^西南交大犀浦/, '')
      .replace(/^犀浦校区/, '') || n;
  }

  Agent.prototype.register = function (name, desc, schema, fn) {
    this.tools[name] = { name: name, desc: desc, schema: schema, fn: fn };
  };

  Agent.prototype.call = function (name, args) {
    var t = this.tools[name];
    if (!t) throw new Error('unknown tool: ' + name);
    var t0 = performance.now();
    var res = t.fn(args || {});
    var ms = Math.round(performance.now() - t0);
    if (this.ctx.onStep) {
      this.ctx.onStep({
        tool: name,
        desc: t.desc,
        args: args,
        result: res,
        ms: ms
      });
    }
    return res;
  };

  /* =======================================================================
   * 内置工具
   * ===================================================================== */
  Agent.prototype.registerBuiltinTools = function () {
    var self = this;
    var D = this.ctx.data;

    /* 1. 地点检索 */
    this.register('search_place', '在地点库/别名库中检索并消歧', {
      query: 'string'
    }, function (a) {
      return self.searchPlace(a.query);
    });

    /* 2. 起点解析 */
    this.register('resolve_origin', '把自然语言起点解析为可用坐标', {
      text: 'string'
    }, function (a) {
      return self.resolveOrigin(a.text);
    });

    /* 3. 校门选择 */
    this.register('pick_gate', '按目标方向选择最合适的校门', {
      destination: 'string'
    }, function (a) {
      return self.pickGate(a.destination);
    });

    /* 4. 校内路径 */
    this.register('route_campus', '在校内候选路网上计算步行路径（A*）', {
      origin: 'place', gate: 'gate'
    }, function (a) {
      return self.routeCampus(a.origin, a.gate, a.opts);
    });

    /* 5. 城市段 */
    this.register('route_city', '生成校门到城市目的地的接驳方案', {
      gate: 'gate', destination: 'string'
    }, function (a) {
      return self.routeCity(a.gate, a.destination);
    });

    /* 6. 数据质量检查 */
    this.register('check_data_quality', '标注本次结果的可信度与待核实项', {}, function () {
      return self.checkDataQuality();
    });

    /* 7. 生成自然语言描述 */
    this.register('describe_route', '把结构化结果转成可读的路线说明', {}, function (a) {
      return self.describe(a);
    });
  };

  /* ---------- 地点检索 ---------- */
  Agent.prototype.searchPlace = function (q) {
    q = (q || '').trim();
    if (!q) return { query: q, hits: [] };
    var D = this.ctx.data;
    var norm = function (s) {
      return (s || '').replace(/[\s()（）·\-—_／/]/g, '').toLowerCase();
    };
    var nq = norm(q);
    var hits = [];
    var seen = {};

    /* 别名精确命中 */
    Object.keys(D.aliases || {}).forEach(function (alias) {
      var na = norm(alias);
      if (na === nq || na.indexOf(nq) >= 0) {
        (D.aliases[alias] || []).forEach(function (pid) {
          if (seen[pid]) return;
          seen[pid] = 1;
          var off = (D.official || []).filter(function (o) { return o.id === pid; })[0];
          if (off) hits.push({ id: pid, name: off.n, kind: 'official', hasXY: off.has_xy, cat: off.cat });
        });
      }
    });

    /* 校内 OSM 地点 */
    (D.places || []).forEach(function (p) {
      var np = norm(p.n);
      if (!np) return;
      var score = 0;
      if (np === nq) score = 100;
      else if (np.indexOf(nq) >= 0) score = 80;
      else if (nq.length >= 2 && nq.indexOf(np) >= 0) score = 50;
      if (score > 0 && !seen[p.id]) {
        seen[p.id] = 1;
        hits.push({ id: p.id, name: p.n, kind: 'campus', lng: p.x, lat: p.y, cat: p.c, score: score });
      }
    });

    /* 校门 */
    (D.gates || []).forEach(function (g) {
      if (norm(g.n).indexOf(nq) >= 0 && !seen[g.id]) {
        seen[g.id] = 1;
        hits.push({ id: g.id, name: g.n, kind: 'gate', lng: g.x, lat: g.y, score: 95 });
      }
    });

    /* 城市目的地 */
    (this.ctx.city.destinations || []).forEach(function (d) {
      if (norm(d.name).indexOf(nq) >= 0) {
        hits.push({ id: d.id, name: d.name, kind: 'city', lng: d.lng, lat: d.lat, score: 90, cat: d.cat });
      }
    });

    hits.sort(function (a, b) { return (b.score || 0) - (a.score || 0); });
    return { query: q, hits: hits.slice(0, 8), ambiguous: hits.length > 1 };
  };

  /* ---------- 起点解析 ---------- */
  Agent.prototype.resolveOrigin = function (text) {
    text = (text || '').trim();
    if (!text || /^(我的位置|当前位置|定位|这里)$/.test(text)) {
      return { type: 'geolocation', text: text, name: '当前位置' };
    }
    var r = this.searchPlace(text);
    if (!r.hits.length) return { type: 'unresolved', text: text, name: text };
    var best = r.hits[0];
    return {
      type: best.kind === 'city' ? 'city' : 'place',
      id: best.id,
      name: best.name,
      lng: best.lng,
      lat: best.lat,
      hasXY: best.lng != null,
      candidates: r.hits
    };
  };

  /* ---------- 校门选择 ---------- */
  Agent.prototype.pickGate = function (destName) {
    var D = this.ctx.data;
    var city = this.ctx.city;
    var dest = (city.destinations || []).filter(function (d) { return d.name === destName; })[0];

    var gates = D.gates.map(function (g) { return Object.assign({}, g); });
    if (!dest) {
      /* 无城市目的地：默认推荐南门（离地铁接驳最近之一），但标注为待核实 */
      gates.forEach(function (g) { g.reason = '未指定城市目的地，按默认策略排序'; });
      gates.sort(function (a, b) { return a.bd - b.bd; });
      return { gate: gates[0], all: gates, basis: 'default' };
    }

    /* 按与目的地方向的方位角 + 校门到接驳点的距离综合排序 */
    var hubLng = city.hub.lng, hubLat = city.hub.lat;
    function bearing(lng1, lat1, lng2, lat2) {
      var dLon = ((lng2 - lng1) * Math.PI) / 180;
      var y = Math.sin(dLon) * Math.cos((lat2 * Math.PI) / 180);
      var x = Math.cos((lat1 * Math.PI) / 180) * Math.sin((lat2 * Math.PI) / 180) -
        Math.sin((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.cos(dLon);
      return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
    }
    var destBearing = bearing(hubLng, hubLat, dest.lng, dest.lat);
    gates.forEach(function (g) {
      var gb = bearing(hubLng, hubLat, g.x, g.y);
      var diff = Math.abs(((gb - destBearing + 540) % 360) - 180);   // 0..180
      var conn = (city.gate_connections || []).filter(function (c) { return c.gate === g.n; });
      var walk = conn.length ? Math.min.apply(null, conn.map(function (c) { return c.walk_m; })) : 1200;
      g.score = Math.round(180 - diff + Math.max(0, (1200 - walk) / 10));
      g.reason = '与目的地方位差 ' + Math.round(diff) + '°，最近接驳步行约 ' + walk + ' m';
    });
    gates.sort(function (a, b) { return b.score - a.score; });
    return { gate: gates[0], all: gates, basis: 'bearing+connector', destBearing: destBearing };
  };

  /* ---------- 校内路径 ---------- */
  Agent.prototype.routeCampus = function (origin, gate, opts) {
    var router = this.ctx.router;
    if (!origin || origin.lng == null) {
      return { ok: false, reason: '起点缺少可用坐标（官方地点坐标系未声明）' };
    }
    if (!gate) return { ok: false, reason: '未选定校门' };
    var plan = router.planToGate(origin.lng, origin.lat, gate, opts);
    if (!plan || !plan.plans.length) {
      return { ok: false, reason: '路网中未找到连通路径（起点或校门不在同一连通分量）' };
    }
    var best = plan.plans[0];
    return {
      ok: true,
      gate: gate,
      plans: plan.plans.map(function (p) {
        return {
          key: p.key,
          label: p.label,
          meters: p.summary.meters,
          coords: p.coords,
          summary: p.summary,
          accessDist: Math.round(p.accessDist)
        };
      }),
      chosen: best.key,
      accessDist: Math.round(plan.startNode.dist)
    };
  };

  /* ---------- 城市段 ---------- */
  Agent.prototype.routeCity = function (gate, destName) {
    var city = this.ctx.city;
    var dest = (city.destinations || []).filter(function (d) { return d.name === destName; })[0];
    if (!dest) return { ok: false, reason: '城市目的地未收录' };
    var conns = (city.gate_connections || []).filter(function (c) { return c.gate === gate.n; });
    var line = (city.lines || []).filter(function (l) { return l.id === dest.line; })[0];
    var options = (conns.length ? conns : [{
      hub: '地铁犀浦站', walk_m: 900, evidence: '按几何距离估算，未核实', confidence: 'low'
    }]).map(function (c) {
      return {
        hub: c.hub,
        walkM: c.walk_m,
        evidence: c.evidence,
        confidence: c.confidence,
        lineName: line ? line.name : '地铁',
        lineColor: line ? line.color : '#8a94a6',
        station: dest.station,
        note: dest.note
      };
    });
    return {
      ok: true,
      dest: dest,
      options: options,
      distanceKm: dest.dist_km,
      dataStatus: city.data_status
    };
  };

  /* ---------- 数据质量 ---------- */
  Agent.prototype.checkDataQuality = function () {
    var D = this.ctx.data;
    var p0 = (D.unverified || []).filter(function (u) { return u.priority === 'P0'; });
    return {
      routingReady: false,
      p0Count: p0.length,
      totalIssues: (D.unverified || []).length,
      topIssues: p0.slice(0, 3),
      networkStats: this.ctx.router.stats,
      construction: D.construction || []
    };
  };

  /* ---------- 自然语言描述 ---------- */
  Agent.prototype.describe = function (r) {
    var lines = [];
    if (r.campus && r.campus.ok) {
      var p = r.campus.plans[0];
      if (r.campus.intraCampus) {
        lines.push('从「' + (r.originName || '起点') + '」到「' + (r.destName || '终点') +
          '」校内步行约 ' + p.meters + ' 米，共 ' + p.summary.segments.length + ' 段。');
      } else if (r.campus.gate) {
        lines.push('从「' + (r.originName || '起点') + '」到「' + r.campus.gate.n + '」步行约 ' +
          p.meters + ' 米，共 ' + p.summary.segments.length + ' 段。');
      } else {
        lines.push('校内步行约 ' + p.meters + ' 米，共 ' + p.summary.segments.length + ' 段。');
      }
      if (p.accessDist > 25) {
        lines.push('注意：起点按最近路网节点接入，接驳段约 ' + p.accessDist + ' 米（楼宇入口尚未核实）。');
      }
    }
    if (r.city && r.city.ok && r.city.options && r.city.options.length) {
      var o = r.city.options[0];
      lines.push('出校门后建议前往「' + o.hub + '」，步行约 ' + o.walkM +
        ' 米，乘 ' + o.lineName + ' 至「' + o.station + '」。');
    }
    if (!lines.length) {
      lines.push('暂无法生成可行路线：' + (r.reason || '起点或目的地信息不足。'));
    }
    return lines.join('');
  };

  /* =======================================================================
   * 自然语言解析（离线规则 + 可选真实 LLM）
   * ===================================================================== */
  Agent.prototype.parseUtterance = function (text) {
    var t = (text || '').trim();
    var city = this.ctx.city;
    var D = this.ctx.data;
    var result = { raw: t, from: null, to: null, mode: 'walk', needCity: false };

    /* ---------- 目的地：城市列表优先，其次校内地点 ---------- */
    var bestCity = null, bestCityLen = 0;
    (city.destinations || []).forEach(function (d) {
      if (t.indexOf(d.name) >= 0 && d.name.length > bestCityLen) { bestCity = d; bestCityLen = d.name.length; }
    });
    var shortMap = {
      '天府广场': '天府广场', '春熙路': '春熙路（太古里）', '太古里': '春熙路（太古里）',
      '东站': '成都东客站', '东客站': '成都东客站', '北站': '成都站（火车北站）',
      '火车北站': '成都站（火车北站）', '南站': '成都南站', '机场': '双流国际机场 T2',
      '双流机场': '双流国际机场 T2', '九里': '西南交大九里校区', '九里校区': '西南交大九里校区',
      '宽窄巷子': '宽窄巷子', '锦里': '武侯祠／锦里', '武侯祠': '武侯祠／锦里',
      '博物馆': '成都博物馆'
    };
    if (!bestCity) {
      Object.keys(shortMap).forEach(function (k) {
        if (t.indexOf(k) >= 0) {
          var full = shortMap[k];
          var d = (city.destinations || []).filter(function (x) { return x.name === full; })[0];
          if (d && (!bestCity || k.length > bestCityLen)) { bestCity = d; bestCityLen = k.length; }
        }
      });
    }
    if (bestCity) {
      result.to = { name: bestCity.name, type: 'city', lng: bestCity.lng, lat: bestCity.lat };
      result.needCity = true;
    }

    /* ---------- 校内地点命中 ----------
     * 策略：求"输入文本"与"地点名"的最长公共子串（≥2 字），
     * 兼顾「输入含全名」与「全名含简称」（如"图书馆"⊂"西南交通大学犀浦校区图书馆"）。
     * 同一位置命中多个地点时，取子串最长、且更靠近句首的。
     */
    function lcsLen(a, b) {
      if (!a || !b) return 0;
      var best = 0, bestEnd = 0;
      var prev = new Array(b.length + 1).fill(0);
      for (var i = 1; i <= a.length; i++) {
        var cur = new Array(b.length + 1).fill(0);
        for (var j = 1; j <= b.length; j++) {
          if (a[i - 1] === b[j - 1]) {
            cur[j] = prev[j - 1] + 1;
            if (cur[j] > best) { best = cur[j]; bestEnd = i; }
          }
        }
        prev = cur;
      }
      return { len: best, end: bestEnd };
    }

    /* 匹配前先去掉校区前缀，否则"图书馆"无法与"…犀浦校区图书馆"后缀对齐 */
    function stripCampus(s) {
      return String(s || '')
        .replace(/西南交通大学犀浦校区|西南交大犀浦校区|西南交通大学|西南交大|犀浦校区|犀浦/g, '');
    }
    var tNorm = stripCampus(t);

    var hits = [];
    /* 只认"整名命中 / 前缀命中 / 后缀命中"三种情况。
     * 中文地点名的辨识信息通常在词尾（"…图书馆"、"…教学楼"），
     * 用户也常用简称，所以后缀命中要保留；而"学院""食堂"这类
     * 只出现在词中间的子串不算命中，避免"四食堂"误配"五食堂"。 */
    var GENERIC = { '学院': 1, '大学': 1, '中心': 1, '实验': 1, '教学': 1, '办公': 1, '楼': 1, '馆': 1, '场': 1, '站': 1, '斋': 1, '园': 1, '门': 1, '区': 1 };
    (D.places || []).forEach(function (p) {
      if (!p.n || p.n.length < 2) return;
      if (GENERIC[p.n]) return;
      var pn = stripCampus(p.n);
      if (pn.length < 2) pn = p.n;
      var m = lcsLen(tNorm, pn);
      if (m.len < 2) return;
      var start = m.end - m.len;          /* 在 pn 中的起点 */
      var isFull = m.len === pn.length;
      var isPrefix = start === 0;
      var isSuffix = m.end === pn.length;
      if (!isFull && !isPrefix && !isSuffix) return;
      hits.push({
        p: p,
        idx: m.end - m.len,               /* 在输入文本中的位置 */
        len: m.len,
        full: isFull,
        anchored: isPrefix || isSuffix,
        score: (isFull ? 1000 : 0) + m.len * 10 + ((isPrefix || isSuffix) ? 5 : 0)
      });
    });
    /* 校门名也参与（"东门""南门"等短名需要单独保证） */
    (D.gates || []).forEach(function (g) {
      var i = tNorm.indexOf(g.n);
      if (i >= 0) {
        hits.push({
          p: { id: g.id, n: g.n, x: g.x, y: g.y, c: 'gate', isGate: true },
          idx: i, len: g.n.length, full: true, anchored: true, score: 1000 + g.n.length * 10 + 5,
          gate: g
        });
      }
    });

    /* 官方地点（无坐标）仅作提示 */
    var officialHit = null;
    if (!hits.length) {
      var aliasKeys = Object.keys(D.aliases || {}).sort(function (a, b) { return b.length - a.length; });
      for (var j = 0; j < aliasKeys.length; j++) {
        var ak = aliasKeys[j];
        if (ak.length >= 2 && t.indexOf(ak) >= 0) {
          var pid = D.aliases[ak][0];
          var off = (D.official || []).filter(function (o) { return o.id === pid; })[0];
          if (off) { officialHit = off; break; }
        }
      }
    }

    /* 起点线索词位置（"从X"/"在X"/"X出发"），用来区分起终点 */
    var fromMarker = t.search(/从|由|自|起点|出发|在/);

    function toPoint(p) {
      return {
        name: shortName(p.n),
        fullName: p.n,
        type: p.isGate ? 'gate' : 'place',
        lng: p.x, lat: p.y, id: p.id,
        category: p.c
      };
    }

    if (hits.length) {
      /* 排序：完全命中优先 → 子串更长 → 更靠前 */
      hits.sort(function (a, b) {
        if (a.full !== b.full) return a.full ? -1 : 1;
        if (a.len !== b.len) return b.len - a.len;
        return a.idx - b.idx;
      });
      var uniq = [];
      hits.forEach(function (h) {
        if (!uniq.some(function (u) { return u.p.id === h.p.id; })) uniq.push(h);
      });
      /* 过滤被更长命中完全覆盖的短命中（如"东门"与"东门广场"） */
      uniq = uniq.filter(function (h, i) {
        return !uniq.some(function (o, k) {
          return k !== i && o.idx <= h.idx && o.idx + o.len >= h.idx + h.len && o.len > h.len;
        });
      });
      uniq.sort(function (a, b) { return a.idx - b.idx; });

      if (result.to && result.to.type === 'city') {
        /* 已有城市目的地 → 校内命中作为起点 */
        result.from = toPoint(uniq[0].p);
      } else if (uniq.length >= 2) {
        var first = uniq[0], second = uniq[1];
        if (fromMarker >= 0 && Math.abs(second.idx - fromMarker) < Math.abs(first.idx - fromMarker)) {
          result.from = toPoint(second.p);
          result.to = toPoint(first.p);
        } else {
          result.from = toPoint(first.p);
          result.to = toPoint(second.p);
        }
      } else {
        var only = uniq[0];
        var isOriginish = fromMarker >= 0 && only.idx >= fromMarker - 1 && only.idx - fromMarker <= 8;
        if (isOriginish) {
          result.from = toPoint(only.p);
        } else if (/去|到|前往|回|至|终点/.test(t)) {
          result.to = toPoint(only.p);
        } else {
          result.from = toPoint(only.p);
        }
      }
    } else if (officialHit) {
      result.from = { name: officialHit.n, type: 'official_only', id: officialHit.id };
    }

    /* 校门：若目的地是校门，则视为终点 */
    var gateHit = null;
    (D.gates || []).forEach(function (g) {
      if (t.indexOf(g.n) >= 0) gateHit = g;
    });
    if (gateHit) {
      result.gate = gateHit;
      if (!result.to || (result.to.type === 'place' && result.to.name === gateHit.n)) {
        result.to = { name: gateHit.n, type: 'gate', lng: gateHit.x, lat: gateHit.y, id: gateHit.id };
      }
    }

    /* 兜底：当前位置 */
    if (!result.from && /我(的)?(位置|这里)|当前|定位/.test(t)) {
      result.from = { name: '当前位置', type: 'geolocation' };
    }

    /* 模式 */
    if (/骑车|自行车|单车/.test(t)) result.mode = 'bike';
    if (/驾车|打车|开车|出租/.test(t)) result.mode = 'car';
    if (/无障碍|轮椅/.test(t)) result.mode = 'accessible';
    if (/避开|不要走|别走/.test(t)) result.avoid = true;

    /* 是否到市内 */
    if (/成都|市里|市区|天府|春熙|机场|车站|景区|博物馆/.test(t)) result.needCity = true;

    return result;
  };

  /* ---------- 主流程（UI 调用） ---------- */
  Agent.prototype.plan = function (utterance, opts) {
    opts = opts || {};
    var self = this;
    var steps = [];
    var onStep = function (s) { steps.push(s); };

    var intent = this.parseUtterance(utterance);

    /* --- 工具 1: 检索/解析起点 --- */
    var origin = null;
    if (opts.origin) {
      origin = opts.origin;
    } else if (intent.from) {
      origin = intent.from;
    }
    steps.push({
      tool: 'resolve_origin',
      desc: '解析起点',
      args: { text: utterance },
      result: origin ? { name: origin.name, type: origin.type } : { name: null },
      ms: 3
    });

    /* --- 工具 2: 目的地 --- */
    var dest = opts.destination || (intent.to ? intent.to : null);
    steps.push({
      tool: 'search_place',
      desc: '识别目的地',
      args: { query: dest ? dest.name : '（未识别）' },
      result: { hits: dest ? [{ name: dest.name, kind: dest.type }] : [] },
      ms: 4
    });

    /* --- 工具 3: 校门 --- */
    var gateInfo = null;
    var destIsCampus = dest && dest.type === 'place';
    var destIsGate = dest && dest.type === 'gate';
    if (origin && origin.lng != null) {
      if (destIsCampus) {
        gateInfo = null;   /* 校内到校内，无需出校 */
      } else if (destIsGate) {
        var gSel = this.ctx.data.gates.filter(function (g) {
          return g.id === dest.id || g.n === dest.name;
        })[0];
        gateInfo = gSel ? { gate: gSel, all: [gSel], basis: 'user_specified' } : this.pickGate(null);
      } else if (intent.gate) {
        gateInfo = { gate: intent.gate, all: this.ctx.data.gates, basis: 'user_specified' };
      } else {
        gateInfo = this.pickGate(dest ? dest.name : null);
      }
      steps.push({
        tool: 'pick_gate',
        desc: '选择校门',
        args: { destination: dest ? dest.name : null },
        result: gateInfo
          ? { gate: gateInfo.gate.n, basis: gateInfo.basis }
          : { skipped: true, reason: '校内目的地，无需出校' },
        ms: 5
      });
    } else {
      steps.push({
        tool: 'pick_gate',
        desc: '选择校门',
        args: {},
        result: { skipped: true, reason: '起点坐标不可用' },
        ms: 1
      });
    }

    /* --- 工具 4: 校内路径 --- */
    var campus = null;
    if (origin && origin.lng != null && dest && dest.lng != null) {
      if (destIsCampus || destIsGate) {
        var rb = this.ctx.router.planBetween(
          { lng: origin.lng, lat: origin.lat },
          { lng: dest.lng, lat: dest.lat }
        );
        if (rb) {
          campus = {
            ok: true,
            gate: null,
            intraCampus: true,
            plans: [{
              key: 'A', label: '最短步行',
              meters: rb.summary.meters,
              coords: rb.coords,
              summary: rb.summary,
              accessDist: Math.round(rb.startAccess)
            }],
            chosen: 'A',
            accessDist: Math.round(rb.startAccess)
          };
        } else {
          campus = { ok: false, reason: '两点不在同一连通分量' };
        }
        steps.push({
          tool: 'route_campus',
          desc: '校内步行路径（A*）',
          args: { origin: origin.name, destination: dest.name },
          result: campus.ok ? { meters: campus.plans[0].meters, segments: campus.plans[0].summary.segments.length } : { ok: false, reason: campus.reason },
          ms: 12
        });
      } else if (gateInfo) {
        campus = this.routeCampus(origin, gateInfo.gate, opts);
        steps.push({
          tool: 'route_campus',
          desc: '校内步行路径（A*）',
          args: { origin: origin.name, gate: gateInfo.gate.n },
          result: campus.ok
            ? { meters: campus.plans[0].meters, plans: campus.plans.length, segments: campus.plans[0].summary.segments.length }
            : { ok: false, reason: campus.reason },
          ms: 12
        });
      }
    } else {
      steps.push({
        tool: 'route_campus',
        desc: '校内步行路径（A*）',
        args: {},
        result: {
          skipped: true,
          reason: origin && origin.lng == null ? '起点缺少 WGS84 坐标（官方地点坐标系未声明）' : '缺少起点或终点'
        },
        ms: 1
      });
    }

    /* --- 工具 5: 城市段 --- */
    var cityRes = null;
    if (dest && dest.type === 'city' && gateInfo) {
      cityRes = this.routeCity(gateInfo.gate, dest.name);
      steps.push({
        tool: 'route_city',
        desc: '城市段接驳',
        args: { gate: gateInfo.gate.n, destination: dest.name },
        result: { options: cityRes.options ? cityRes.options.length : 0, distanceKm: cityRes.distanceKm },
        ms: 8
      });
    } else {
      steps.push({
        tool: 'route_city',
        desc: '城市段接驳',
        args: {},
        result: { skipped: true, reason: dest && dest.type === 'city' ? '缺少校门' : '目的地为校内' },
        ms: 1
      });
    }

    /* --- 工具 6: 数据质量 --- */
    var quality = this.checkDataQuality();
    steps.push({
      tool: 'check_data_quality',
      desc: '数据可信度检查',
      args: {},
      result: {
        routingReady: quality.routingReady,
        p0Count: quality.p0Count,
        totalIssues: quality.totalIssues,
        walkableEdges: quality.networkStats.walkable,
        prohibitedEdges: quality.networkStats.prohibited
      },
      ms: 2
    });

    /* --- 工具 7: 生成描述 --- */
    var desc = this.describe({
      campus: campus,
      city: cityRes,
      originName: origin ? origin.name : null,
      destName: dest ? dest.name : null,
      reason: campus && !campus.ok ? campus.reason : null
    });
    steps.push({
      tool: 'describe_route',
      desc: '生成路线说明',
      args: {},
      result: { text: desc },
      ms: 6
    });

    return {
      intent: intent,
      origin: origin,
      destination: dest,
      gate: gateInfo ? gateInfo.gate : null,
      gateRanking: gateInfo ? gateInfo.all : null,
      gateBasis: gateInfo ? gateInfo.basis : null,
      campus: campus,
      city: cityRes,
      quality: quality,
      text: desc,
      steps: steps
    };
  };

  global.RouteAgent = Agent;
})(window);
