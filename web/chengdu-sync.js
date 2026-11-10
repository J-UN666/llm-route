/* =============================================================================
 * chengdu-sync.js — 把主程序选好的「校门」同步给成都地图
 * -----------------------------------------------------------------------------
 * 解决的问题：
 *   左边 Agent 算出来的出门校门（比如"南门"），和成都地图默认的"西南门"不一致。
 *   这个脚本监听结果区，把主程序实际选中的校门与目的地同步过去。
 *
 * 接入方式（不需要改任何现有代码）：
 *   在 index.html 里，紧跟在 chengdu-map.js 之后加一行：
 *     <script src="chengdu-sync.js"></script>
 *
 * 原理：
 *   结果卡里"门"那一行同时带了校门名称和 OSM 节点 ID（osm_node_xxxx），
 *   拿 ID 去 __CAMPUS_DATA__.gates 里精确匹配，不靠猜。
 *   如果你的结果卡结构变了、匹配不到，脚本会安静地什么都不做，
 *   成都地图仍然可以用默认门，不会报错。
 * ========================================================================== */
(function () {
  'use strict';

  var lastGateId = null;
  var lastDest = null;
  var timer = null;

  function gates() {
    var D = window.__CAMPUS_DATA__;
    return (D && D.gates) || [];
  }

  /* 从结果区读出 起点 / 终点 / 校门 */
  function parseResult() {
    var body = document.getElementById('resultBody');
    if (!body) return null;

    var out = { gate: null, gateId: null, dest: null, origin: null };
    var pills = body.querySelectorAll('.pill');

    for (var i = 0; i < pills.length; i++) {
      var pill = pills[i];
      var label = (pill.textContent || '').trim();
      var row = pill.parentNode;
      if (!row) continue;

      var text = (row.textContent || '').replace(/\s+/g, ' ').trim();

      if (label === '门') {
        var m = text.match(/osm_node_\d+/);
        if (m) out.gateId = m[0];
        var nm = text.replace(/^门\s*/, '').split(/[（(]/)[0].trim();
        if (nm) out.gate = nm;
      } else if (label === '终') {
        out.dest = text.replace(/^终\s*/, '').split(/[（(]/)[0].trim();
      } else if (label === '起') {
        out.origin = text.replace(/^起\s*/, '').split(/[（(]/)[0].trim();
      }
    }
    return out;
  }

  function sync() {
    if (!window.ChengduMap) return;

    var r = parseResult();
    if (!r) return;

    /* ---- 1. 同步校门（用 OSM ID 精确匹配，退化为按名称匹配）---- */
    if (r.gateId || r.gate) {
      var list = gates();
      var g = null;
      if (r.gateId) {
        for (var i = 0; i < list.length; i++) {
          if (list[i].id === r.gateId) { g = list[i]; break; }
        }
      }
      if (!g && r.gate) {
        for (var j = 0; j < list.length; j++) {
          if (list[j].n === r.gate) { g = list[j]; break; }
        }
      }
      if (g && g.id !== lastGateId) {
        lastGateId = g.id;
        window.ChengduMap.setOrigin(g.x, g.y, g.n, { source: 'synced' });
      }
    }

    /* ---- 2. 同步目的地（只有城市目的地才会被成都地图接受）---- */
    if (r.dest && r.dest !== lastDest) {
      lastDest = r.dest;
      if (window.ChengduMap.setSuggestedDestination) {
        window.ChengduMap.setSuggestedDestination(r.dest);
      }
    }
  }

  function start() {
    var body = document.getElementById('resultBody');
    if (!body) return;

    /* 结果区每次重绘都会触发，合并成一次处理 */
    var mo = new MutationObserver(function () {
      if (timer) clearTimeout(timer);
      timer = setTimeout(sync, 120);
    });
    mo.observe(body, { childList: true, subtree: true, characterData: true });

    /* 页面刚打开时可能已经有结果 */
    sync();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
