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

  /* ---------- 地点名称规范化与精确索引 ----------
   * 仅把正式名、稳定别名、去校区前缀/去末尾括号后的完整名称作为
   * 可命中词条；不做“输入与地名的最长公共子串”式猜测。
   */
  var GENERIC_ALIAS = { '停车场': 1, '停车区': 1, '景点': 1 };

  function normName(s) {
    return String(s == null ? '' : s)
      .replace(/[\s()（）·\-—_／/，。！？、,.!?;:：；"'“”‘’]/g, '')
      .toLowerCase();
  }

  function normalizeForMatch(s) {
    return normName(s);
  }

  /* 完整实体边界：地名前后只能是路径词、问句词或本校限定词。
   * 这样“四川省图书馆”“成都大学图书馆”里的“图书馆”不会被当成
   * 校内图书馆；而“从图书馆出发”“犀浦校区图书馆”仍然可命中。 */
  var CAMPUS_QUALIFIERS = [
    '西南交通大学犀浦校区', '西南交大犀浦校区', '西南交通大学犀浦',
    '西南交通大学', '西南交大', '西南交大犀浦', '犀浦校区', '犀浦', '交大'
  ];
  var PREFIX_BOUNDARY = [
    '从', '由', '自', '在', '到', '去', '往', '至', '回',
    '请问', '请', '问一下', '想问', '我想', '我要', '想',
    '帮我', '帮忙', '带我', '查一下', '查下', '查询', '导航',
    '前往', '起点', '终点', '出发', '和', '与', '及'
  ];
  var SUFFIX_BOUNDARY = [
    '到', '去', '往', '至', '回', '从', '在',
    '前往', '去往', '赶往', '步行', '骑车', '打车', '开车', '坐车', '乘车',
    '想去', '想', '要', '准备', '打算', '然后', '再',
    '怎么', '如何', '多远', '多久', '出发', '起点', '终点',
    '吗', '呢', '呀', '啊', '附近', '周边', '门口', '前面', '旁边',
    '和', '与', '及'
  ];
  var LEAD_IN = /^(?:我们|我|请问|请|问一下|想问|我想|我要|想|帮我|帮忙|带我|查一下|查下|查询|导航|前往|从|由|自|在|到|去|往|至|回|和|与|及)+/;

  function endsWithAny(s, words) {
    for (var i = 0; i < words.length; i++) {
      if (s.slice(-words[i].length) === words[i]) return true;
    }
    return false;
  }

  function startsWithAny(s, words) {
    for (var i = 0; i < words.length; i++) {
      if (s.indexOf(words[i]) === 0) return true;
    }
    return false;
  }

  function mentionBoundaryOk(s, start, end) {
    var before = s.slice(0, start);
    var after = s.slice(end);
    if (before && !endsWithAny(before, PREFIX_BOUNDARY)) {
      var core = before.replace(LEAD_IN, '');
      if (CAMPUS_QUALIFIERS.indexOf(core) < 0) return false;
    }
    if (!after) return true;
    return startsWithAny(after, SUFFIX_BOUNDARY);
  }

  function stripTrailingParen(n) {
    var s = String(n == null ? '' : n).trim();
    var out = s.replace(/[（(][^（）()]*[）)]\s*$/, '').trim();
    return out || s;
  }

  function entityRank(e) {
    if (e.kind === 'place') return 30;
    if (e.kind === 'gate') return 20;
    return 10;
  }

  function noCoordReason(e, role) {
    var label = role === 'destination' ? '目的地' : '起点';
    var why = e.coordStatus === 'UNDECLARED'
      ? '官方地点坐标未声明，仅作名称线索'
      : '官方地点没有可用坐标';
    return label + '「' + e.name + '」没有可用于算路的可靠坐标（' + why + '），无法计算路线。';
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

  /* ---------- 地点索引 ---------- */
  Agent.prototype._buildPlaceIndex = function () {
    var D = this.ctx.data;
    var entries = [];
    var forms = [];

    function addForm(map, name) {
      var nf = normName(name);
      if (nf.length >= 2) map[nf] = name;
    }
    function pushEntry(e) {
      entries.push(e);
      Object.keys(e.forms).forEach(function (nf) {
        forms.push({ entry: e, norm: nf, text: e.forms[nf] });
      });
    }

    /* 校内 OSM 地点：有 WGS84 坐标，可直接参与算路 */
    (D.places || []).forEach(function (p) {
      if (!p || !p.n) return;
      var f = {};
      addForm(f, p.n);
      addForm(f, shortName(p.n));
      addForm(f, stripTrailingParen(p.n));
      addForm(f, stripTrailingParen(shortName(p.n)));
      pushEntry({
        kind: 'place', id: p.id, name: p.n, fullName: p.n,
        lng: p.x, lat: p.y, category: p.c, forms: f
      });
    });

    /* 校门 */
    (D.gates || []).forEach(function (g) {
      if (!g || !g.n) return;
      var f = {};
      addForm(f, g.n);
      pushEntry({
        kind: 'gate', id: g.id, name: g.n, fullName: g.n,
        lng: g.x, lat: g.y, category: 'gate', forms: f
      });
    });

    /* 官方地点：只有名称/别名线索；坐标未声明，不能用于算路 */
    var aliasesByOfficial = {};
    Object.keys(D.aliases || {}).forEach(function (alias) {
      (D.aliases[alias] || []).forEach(function (pid) {
        (aliasesByOfficial[pid] = aliasesByOfficial[pid] || []).push(alias);
      });
    });
    (D.official || []).forEach(function (o) {
      if (!o || !o.n) return;
      var f = {};
      addForm(f, o.n);
      addForm(f, stripTrailingParen(o.n));
      (aliasesByOfficial[o.id] || []).forEach(function (alias) {
        if (GENERIC_ALIAS[alias]) return;
        addForm(f, alias);
        addForm(f, stripTrailingParen(alias));
      });
      pushEntry({
        kind: 'official', id: o.id, name: o.n, fullName: o.n,
        category: o.cat, hasXY: !!o.has_xy,
        coordStatus: o.has_xy ? 'UNDECLARED' : 'MISSING',
        forms: f
      });
    });

    this._placeIndex = { entries: entries, forms: forms };
    return this._placeIndex;
  };

  Agent.prototype.findMentions = function (text, kinds) {
    var index = this._placeIndex || this._buildPlaceIndex();
    var ntext = normalizeForMatch(text);
    if (!ntext) return [];
    var allow = {};
    (kinds || ['place', 'gate', 'official']).forEach(function (k) { allow[k] = 1; });

    var candidates = [];
    index.forms.forEach(function (f) {
      if (!allow[f.entry.kind]) return;
      var from = 0, pos;
      while ((pos = ntext.indexOf(f.norm, from)) >= 0) {
        if (mentionBoundaryOk(ntext, pos, pos + f.norm.length)) {
          candidates.push({ entry: f.entry, start: pos, end: pos + f.norm.length });
        }
        from = pos + 1;
      }
    });

    /* 同一段文字命中多个实体时，按 地点 > 校门 > 官方地点 取主实体；
     * 同一实体的不同写法去重。 */
    var grouped = {};
    candidates.forEach(function (c) {
      var key = c.start + ':' + c.end;
      (grouped[key] = grouped[key] || []).push(c);
    });
    var spans = [];
    Object.keys(grouped).forEach(function (key) {
      var list = grouped[key];
      var seen = {}, uniq = [];
      list.forEach(function (c) {
        var id = c.entry.kind + ':' + c.entry.id;
        if (seen[id]) return;
        seen[id] = 1;
        uniq.push(c);
      });
      uniq.sort(function (a, b) {
        return entityRank(b.entry) - entityRank(a.entry) ||
          (b.entry.name.length - a.entry.name.length);
      });
      spans.push({
        start: list[0].start,
        end: list[0].end,
        primary: uniq[0].entry,
        alternates: uniq.slice(1).map(function (c) { return c.entry; }),
        all: uniq.map(function (c) { return c.entry; })
      });
    });

    /* 最长、最靠前的完整片段优先，短片段若被更长片段覆盖则丢弃
     * （例如“西南门”里的“南门”）。 */
    spans.sort(function (a, b) {
      return a.start - b.start || (b.end - b.start) - (a.end - a.start);
    });
    var out = [], lastEnd = -1;
    spans.forEach(function (s) {
      if (s.start < lastEnd) return;
      out.push(s);
      lastEnd = s.end;
    });

    /* 官方别名可能比带坐标的地点简称更长（如“犀浦校区图书馆”“交大图书馆”），
     * 覆盖后会让可算路的地点丢失。若官方片段以某个带坐标的地点名结尾，
     * 把该地点提为主实体；官方地点仍保留在候选里，不合并身份。 */
    out.forEach(function (s) {
      if (s.primary.kind === 'place') return;
      var spanText = ntext.slice(s.start, s.end);
      var best = null;
      candidates.forEach(function (c) {
        if (c.entry.kind !== 'place' || c.end !== s.end || c.start < s.start) return;
        var formText = ntext.slice(c.start, c.end);
        if (!formText || spanText.slice(-formText.length) !== formText) return;
        if (!best || formText.length > best.formText.length) {
          best = { entry: c.entry, formText: formText };
        }
      });
      if (!best) return;
      s.all = [best.entry].concat(s.all.filter(function (e) {
        return !(e.kind === best.entry.kind && e.id === best.entry.id);
      }));
      s.primary = best.entry;
      s.alternates = s.all.slice(1);
    });

    return out;
  };

  /* ---------- 地点检索 ---------- */
  Agent.prototype.searchPlace = function (q) {
    q = (q || '').trim();
    if (!q) return { query: q, hits: [] };
    var nq = normName(q);
    var hits = [];
    var seen = {};

    function push(h) {
      var key = h.kind + ':' + h.id;
      if (seen[key]) return;
      seen[key] = 1;
      hits.push(h);
    }

    this.findMentions(q, ['place', 'gate', 'official']).forEach(function (m) {
      m.all.forEach(function (e) {
        if (e.kind === 'place') {
          push({
            id: e.id, name: e.name, fullName: e.fullName, kind: 'campus',
            lng: e.lng, lat: e.lat, cat: e.category, score: 100
          });
        } else if (e.kind === 'gate') {
          push({
            id: e.id, name: e.name, fullName: e.fullName, kind: 'gate',
            lng: e.lng, lat: e.lat, score: 95
          });
        } else {
          push({
            id: e.id, name: e.name, fullName: e.fullName, kind: 'official',
            lng: null, lat: null, hasXY: false, sourceHasXY: e.hasXY,
            coordStatus: e.coordStatus, cat: e.category, score: 60
          });
        }
      });
    });

    /* 城市目的地仍保留包含式检索，用于“春熙路”“天府”等短查询 */
    (this.ctx.city.destinations || []).forEach(function (d) {
      if (normName(d.name).indexOf(nq) >= 0) {
        push({ id: d.id, name: d.name, kind: 'city', lng: d.lng, lat: d.lat, score: 90, cat: d.cat });
      }
    });

    hits.sort(function (a, b) {
      return (b.score || 0) - (a.score || 0) || String(a.name).length - String(b.name).length;
    });
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
    if (best.kind === 'official') {
      return {
        type: 'official_only',
        id: best.id,
        name: best.name,
        fullName: best.fullName || best.name,
        lng: null,
        lat: null,
        hasXY: false,
        coordStatus: best.coordStatus,
        reason: noCoordReason(best, 'origin'),
        candidates: r.hits
      };
    }
    return {
      type: best.kind === 'city' ? 'city' : (best.kind === 'gate' ? 'gate' : 'place'),
      id: best.id,
      name: best.name,
      fullName: best.fullName || best.name,
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
      return { ok: false, reason: (origin && origin.reason) || '起点缺少可用坐标，无法计算路线' };
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
      var rr = r.reason || '起点或目的地信息不足。';
      lines.push(/无法|不能/.test(rr) ? rr : ('暂无法生成可行路线：' + rr));
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
     * 只认正式名、稳定别名、校区前缀简称和括号简称的完整命中。
     * 不再做跨实体的最长公共子串猜测，避免把“材料学院实验教学中心”
     * 配到“大学生创新创业教育中心”。官方地点没有可用坐标时只作为
     * 名称线索保留，由 plan() 明确返回“无法计算路线”。
     */
    var mentions = this.findMentions(t, ['place', 'official']);
    var hits = mentions.map(function (m) {
      var e = m.primary;
      var p;
      if (e.kind === 'official') {
        p = { id: e.id, n: e.name, c: e.category, isOfficial: true, official: e };
      } else if (e.kind === 'gate') {
        p = { id: e.id, n: e.name, x: e.lng, y: e.lat, c: 'gate', isGate: true };
      } else {
        p = { id: e.id, n: e.name, x: e.lng, y: e.lat, c: e.category };
      }
      return { p: p, idx: m.start, len: m.end - m.start, full: true, anchored: true, score: 1000 };
    });

    /* 起点线索词位置（"从X"/"在X"/"X出发"），用来区分起终点。
     * 与 findMentions 使用同一套规范化文本，保证位置可比。 */
    var ntext = normalizeForMatch(t);
    var fromMarker = ntext.search(/从|由|自|起点|出发|在/);

    function toPoint(p, role) {
      if (p.isOfficial) {
        return {
          name: p.n,
          fullName: p.n,
          type: 'official_only',
          lng: null, lat: null, id: p.id,
          category: p.c,
          hasXY: false,
          coordStatus: p.official.coordStatus,
          reason: noCoordReason(p.official, role)
        };
      }
      return {
        name: p.isGate ? p.n : shortName(p.n),
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
        result.from = toPoint(uniq[0].p, 'origin');
      } else if (uniq.length >= 2) {
        var first = uniq[0], second = uniq[1];
        if (fromMarker >= 0 && Math.abs(second.idx - fromMarker) < Math.abs(first.idx - fromMarker)) {
          result.from = toPoint(second.p, 'origin');
          result.to = toPoint(first.p, 'destination');
        } else {
          result.from = toPoint(first.p, 'origin');
          result.to = toPoint(second.p, 'destination');
        }
      } else {
        var only = uniq[0];
        var isOriginish = fromMarker >= 0 && only.idx >= fromMarker - 1 && only.idx - fromMarker <= 8;
        if (isOriginish) {
          result.from = toPoint(only.p, 'origin');
        } else if (/去|到|前往|回|至|终点/.test(t)) {
          result.to = toPoint(only.p, 'destination');
        } else {
          result.from = toPoint(only.p, 'origin');
        }
      }
    }

    /* 校门：若目的地是校门，则视为终点。
     * 沿用旧逻辑按 D.gates 顺序覆盖，保证“东门”两个同名门里仍选
     * osm_node_13469658219，并让“西南门”优先于其中的“南门”。 */
    var gateHit = null;
    (D.gates || []).forEach(function (g) {
      var ng = normName(g.n);
      if (!ng) return;
      var gp = -1;
      while ((gp = ntext.indexOf(ng, gp + 1)) >= 0) {
        if (mentionBoundaryOk(ntext, gp, gp + ng.length)) {
          gateHit = g;
          break;
        }
      }
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
      result: origin
        ? { name: origin.name, type: origin.type, hasXY: origin.lng != null }
        : { name: null },
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

    /* 起点/目的地缺少可靠坐标时，先给出明确原因，后续工具不再硬算。 */
    var failureReason = null;
    if (!origin && !dest) {
      failureReason = '未识别到起点或目的地，无法计算路线。';
    } else if (!origin) {
      failureReason = '未识别到起点，无法计算路线。';
    } else if (origin.lng == null) {
      failureReason = origin.reason ||
        ('起点「' + origin.name + '」没有可用于算路的可靠坐标，无法计算路线。');
    } else if (!dest) {
      failureReason = '未识别到目的地，无法计算路线。';
    } else if (dest.type !== 'city' && dest.lng == null) {
      failureReason = dest.reason ||
        ('目的地「' + dest.name + '」没有可用于算路的可靠坐标，无法计算路线。');
    }

    /* --- 工具 3: 校门 --- */
    var gateInfo = null;
    var destIsCampus = dest && dest.type === 'place';
    var destIsGate = dest && dest.type === 'gate';
    var gateSkipReason = null;
    if (!origin || origin.lng == null) {
      gateSkipReason = (origin && origin.reason) || '起点缺少可用坐标，无法选门';
    } else if (!dest) {
      gateSkipReason = '未识别目的地，无法选门';
    } else if (dest.type !== 'city' && dest.lng == null) {
      gateSkipReason = dest.reason || '目的地缺少可用坐标，无法选门';
    }

    if (origin && origin.lng != null && dest && (dest.type === 'city' || dest.lng != null)) {
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
        result: {
          skipped: true,
          reason: gateSkipReason || '无法选择校门'
        },
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
      var campusSkipReason;
      if (origin && origin.lng == null) {
        campusSkipReason = origin.reason || '起点缺少可用坐标，无法计算路线';
      } else if (!dest) {
        campusSkipReason = '未识别目的地，无法计算路线';
      } else if (dest.type !== 'city' && dest.lng == null) {
        campusSkipReason = dest.reason || '目的地缺少可用坐标，无法计算路线';
      } else {
        campusSkipReason = '缺少起点或终点';
      }
      /* 以失败态返回，前端现有的“校内段未能生成”提示即可显示原因，
       * 不需要为缺少坐标另加一套静默分支。 */
      var campusReason = failureReason || campusSkipReason;
      campus = { ok: false, reason: campusReason };
      steps.push({
        tool: 'route_campus',
        desc: '校内步行路径（A*）',
        args: {},
        result: { skipped: true, reason: campusReason },
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
      var citySkipReason;
      if (!dest) {
        citySkipReason = '未识别目的地';
      } else if (dest.type !== 'city') {
        citySkipReason = '目的地为校内或非城市地点';
      } else if (!origin || origin.lng == null) {
        citySkipReason = (origin && origin.reason) || '起点缺少可用坐标，无法选门';
      } else {
        citySkipReason = '缺少校门';
      }
      steps.push({
        tool: 'route_city',
        desc: '城市段接驳',
        args: {},
        result: { skipped: true, reason: citySkipReason },
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
    var reason = failureReason || (campus && !campus.ok ? campus.reason : null);
    var desc = this.describe({
      campus: campus,
      city: cityRes,
      originName: origin ? origin.name : null,
      destName: dest ? dest.name : null,
      reason: reason
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
      reason: reason,
      text: desc,
      steps: steps
    };
  };

  global.RouteAgent = Agent;
})(window);
