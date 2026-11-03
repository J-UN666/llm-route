/**
 * 无浏览器逻辑测试：在 Node 中加载数据 + 路由 + Agent，跑通主流程。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', 'web');
const sandbox = { window: {}, console, performance: { now: () => Date.now() }, setTimeout, clearTimeout };
sandbox.global = sandbox;
vm.createContext(sandbox);

function loadScript(rel) {
  const code = fs.readFileSync(path.join(ROOT, rel), 'utf8');
  vm.runInContext(code, sandbox, { filename: rel });
}

loadScript('data/campus.js');
loadScript('data/city.js');
loadScript('js/routing.js');
loadScript('js/agent.js');

const DATA = sandbox.window.__CAMPUS_DATA__;
const CITY = sandbox.window.__CITY_DATA__;
const CampusRouter = sandbox.window.CampusRouting.CampusRouter;
const RouteAgent = sandbox.window.RouteAgent;

console.log('=== 数据 ===');
console.log('nodes', DATA.nodes.length, 'edges', DATA.edges.length,
  'buildings', DATA.buildings.length, 'places', DATA.places.length,
  'gates', DATA.gates.length, 'transit', DATA.transit.length);
console.log('boundary rings', DATA.boundary.length, 'outer pts', DATA.boundary[0].length);
console.log('roadlines', DATA.roadlines.length, 'edgeNames', DATA.edgeNames.length);

const router = new CampusRouter(DATA);
const idMap = {};
DATA.nodeIds.forEach((id, i) => (idMap[id] = i));
router.setNodeIdMap(idMap);
router.buildGraph();
console.log('network stats', JSON.stringify(router.stats));

const agent = new RouteAgent({ data: DATA, city: CITY, router });

console.log('\n=== 测试 1: 自然语言 图书馆 → 天府广场 ===');
let r = agent.plan('我从图书馆出发，想去天府广场，怎么走最快？', {});
console.log('origin', r.origin && r.origin.name, 'dest', r.destination && r.destination.name);
console.log('gate', r.gate && r.gate.n, 'basis', r.gateBasis);
console.log('campus ok', r.campus && r.campus.ok, 'meters', r.campus && r.campus.ok ? r.campus.plans[0].meters : '-');
console.log('plans', r.campus && r.campus.ok ? r.campus.plans.length : 0);
console.log('city options', r.city && r.city.options ? r.city.options.map(o => o.hub + '/' + o.walkM) : null);
console.log('steps', r.steps.length, r.steps.map(s => s.tool).join(' → '));
console.log('text:', r.text);

console.log('\n=== 测试 2: 校内 四食堂 → 东门 ===');
r = agent.plan('四食堂到东门怎么走', {});
console.log('origin', r.origin && r.origin.name, 'dest', r.destination && r.destination.name, 'gate', r.gate && r.gate.n);
console.log('campus', r.campus && r.campus.ok ? r.campus.plans[0].meters + ' m' : JSON.stringify(r.campus));

console.log('\n=== 测试 3: 天佑斋1号楼 → 成都东客站 ===');
r = agent.plan('从天佑斋1号楼到成都东客站', {});
console.log('origin', r.origin && r.origin.name, 'dest', r.destination && r.destination.name, 'gate', r.gate && r.gate.n);
console.log('campus', r.campus && r.campus.ok ? r.campus.plans[0].meters + ' m / ' + r.campus.plans.length + ' 方案' : JSON.stringify(r.campus));
console.log('city', r.city && r.city.options ? r.city.options.map(o => o.hub + ' walk ' + o.walkM + ' ' + o.lineName) : null);

console.log('\n=== 测试 4: 体育馆 → 春熙路 ===');
r = agent.plan('体育馆到春熙路', {});
console.log('origin', r.origin && r.origin.name, 'dest', r.destination && r.destination.name, 'gate', r.gate && r.gate.n);
console.log('campus', r.campus && r.campus.ok ? r.campus.plans[0].meters + ' m' : JSON.stringify(r.campus));

console.log('\n=== 测试 5: 全部 MVP 地点 → 各校门可达性 ===');
let okCount = 0, failCount = 0;
DATA.mvp.forEach(p => {
  DATA.gates.forEach(g => {
    const res = router.planToGate(p.x, p.y, g, {});
    if (res && res.plans.length) okCount++; else { failCount++; console.log('  unreachable:', p.n, '→', g.n); }
  });
});
console.log('reachable pairs', okCount, 'unreachable', failCount);

console.log('\n=== 测试 6: 全部地点两两可达性抽样 ===');
let sampleFail = 0, sampleOk = 0;
for (let i = 0; i < DATA.places.length; i += 7) {
  for (let j = i + 1; j < DATA.places.length; j += 11) {
    const a = DATA.places[i], b = DATA.places[j];
    const res = router.planBetween({ lng: a.x, lat: a.y }, { lng: b.x, lat: b.y });
    res ? sampleOk++ : (sampleFail++, console.log('  fail', a.n, '→', b.n));
  }
}
console.log('sample ok', sampleOk, 'fail', sampleFail);

console.log('\n=== 测试 7: 边界情况 ===');
const edge = agent.plan('', {});
console.log('empty utterance steps', edge.steps.length, 'campus', edge.campus);
const edge2 = agent.plan('我想去一个不存在的地方', {});
console.log('unknown dest -> dest', edge2.destination, 'steps', edge2.steps.length);
const edge3 = agent.plan('从材料学院实验教学中心到天府广场', {});
console.log('official-only origin ->', JSON.stringify(edge3.origin), 'campus', edge3.campus);
