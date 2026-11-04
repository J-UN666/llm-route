/**
 * 无浏览器逻辑测试：在 Node 中加载数据 + 路由 + Agent，跑通主流程。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

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

/* =======================================================================
 * 验收断言：地点精确匹配、无坐标不硬算、图书馆主流程不回退
 * ===================================================================== */
console.log('\n=== 验收断言 ===');

/* 1. 材料学院实验教学中心：官方地点只有名称线索，不能配到其他实体，也不能算路 */
assert.strictEqual(edge3.origin.type, 'official_only', '材料学院起点应为 official_only');
assert.strictEqual(edge3.origin.id, 'swjtu_org_100', '材料学院起点应指向官方地点 swjtu_org_100');
assert.strictEqual(edge3.origin.name, '材料学院实验教学中心', '起点名称应保留正式名');
assert.strictEqual(edge3.origin.lng, null, '官方地点没有可用坐标，lng 必须是 null');
assert.strictEqual(edge3.gate, null, '没有可靠起点坐标时不应选门');
assert.ok(edge3.campus && edge3.campus.ok === false,
  '没有可靠起点坐标时应以失败态明确提示，而不是返回校内路线');
assert.ok(/坐标|无法计算路线/.test(edge3.campus.reason), '校内段失败原因应说明缺少坐标');
assert.strictEqual(edge3.city, null, '没有可靠起点坐标时不应返回城市路线');
assert.ok(/无法计算路线/.test(edge3.text), '应明确说明无法计算路线');
assert.ok(/坐标/.test(edge3.text), '应说明缺少可靠坐标');
assert.strictEqual(edge3.text.indexOf('大学生'), -1, '结果说明不应出现错误地点');
assert.strictEqual(JSON.stringify(edge3.steps).indexOf('大学生'), -1, '工具轨迹不应出现错误地点');

const matSteps = {};
edge3.steps.forEach(s => { matSteps[s.tool] = s.result; });
assert.strictEqual(matSteps.route_campus.skipped, true, 'route_campus 应跳过');
assert.strictEqual(matSteps.route_city.skipped, true, 'route_city 应跳过');
assert.ok(/坐标|无法计算路线/.test(matSteps.pick_gate.reason), 'pick_gate 应说明原因');

/* 2. 目的地是官方地点时同样不硬算，原因应指向目的地而不是起点 */
const matDest = agent.plan('从图书馆到材料学院实验教学中心', {});
assert.strictEqual(matDest.destination.type, 'official_only', '官方地点目的地应为 official_only');
assert.strictEqual(matDest.destination.lng, null, '官方地点目的地没有可用坐标');
assert.strictEqual(matDest.gate, null, '目的地无坐标时不应选门');
assert.ok(matDest.campus && matDest.campus.ok === false, '目的地无坐标时应明确提示校内段无法生成');
assert.strictEqual(matDest.city, null, '目的地无坐标时不应返回城市路线');
assert.ok(/目的地/.test(matDest.text) && /无法计算路线/.test(matDest.text),
  '应明确说明目的地没有可靠坐标');

/* 3. 图书馆正常查询：优先命中带坐标的校内地点，校内段和城市段都要可用 */
const lib2 = agent.plan('我从图书馆出发，想去天府广场，怎么走最快？', {});
assert.strictEqual(lib2.origin.type, 'place', '图书馆起点应为带坐标的 place');
assert.strictEqual(lib2.origin.name, '图书馆', '图书馆起点名称应为简称');
assert.ok(typeof lib2.origin.lng === 'number' && typeof lib2.origin.lat === 'number',
  '图书馆必须有 WGS84 坐标');
assert.ok(lib2.campus && lib2.campus.ok, '图书馆到校门应有校内路线');
assert.ok(lib2.city && lib2.city.options && lib2.city.options.length > 0,
  '图书馆到天府广场应有城市接驳方案');
assert.ok(/图书馆/.test(lib2.text) && /天府广场/.test(lib2.text), '说明应包含起点和城市目的地');

const libSearch = agent.searchPlace('图书馆');
assert.strictEqual(libSearch.hits[0].kind, 'campus', '图书馆检索首条应是带坐标的校内地点');
assert.ok(typeof libSearch.hits[0].lng === 'number', '图书馆检索首条必须有坐标');
const libResolve = agent.resolveOrigin('图书馆');
assert.strictEqual(libResolve.type, 'place', '图书馆起点解析应为 place');
assert.ok(typeof libResolve.lng === 'number', '图书馆起点解析必须有坐标');

/* 官方别名“犀浦校区图书馆”比地点简称长，不能被官方空坐标覆盖 */
const libAlias = agent.plan('从犀浦校区图书馆到天府广场', {});
assert.strictEqual(libAlias.origin.type, 'place', '犀浦校区图书馆应优先用带坐标的地点');
assert.ok(typeof libAlias.origin.lng === 'number', '犀浦校区图书馆必须有可用坐标');
assert.ok(libAlias.campus && libAlias.campus.ok, '犀浦校区图书馆应能生成校内路线');
const libAliasSearch = agent.searchPlace('犀浦校区图书馆');
assert.strictEqual(libAliasSearch.hits[0].kind, 'campus', '犀浦校区图书馆检索首条应是带坐标地点');

/* “前往”“想去”等正常表达不能因边界规则被截断 */
const libGo = agent.plan('从图书馆前往天府广场', {});
assert.strictEqual(libGo.origin.type, 'place', '“前往”表达中图书馆起点应被识别');
assert.strictEqual(libGo.origin.name, '图书馆', '“前往”表达中起点名称应为图书馆');
assert.ok(libGo.campus && libGo.campus.ok, '“前往”表达应生成校内路线');
assert.ok(libGo.city && libGo.city.options && libGo.city.options.length > 0,
  '“前往”表达应生成城市接驳方案');

const libAt = agent.plan('我在图书馆，想去天府广场', {});
assert.strictEqual(libAt.origin.type, 'place', '“我在图书馆，想去”表达中图书馆起点应被识别');
assert.strictEqual(libAt.origin.name, '图书馆', '“我在图书馆，想去”表达中起点名称应为图书馆');
assert.ok(libAt.campus && libAt.campus.ok, '“我在图书馆，想去”表达应生成校内路线');
assert.ok(libAt.city && libAt.city.options && libAt.city.options.length > 0,
  '“我在图书馆，想去”表达应生成城市接驳方案');

/* 4. 未知地点不硬配、不返回路线 */
const unknown = agent.plan('我想去一个不存在的地方', {});
assert.strictEqual(unknown.destination, null, '未知地点不能硬配成目的地');
assert.strictEqual(unknown.origin, null, '未知地点不能硬配成起点');
assert.strictEqual(unknown.gate, null, '未知地点不应选门');
assert.ok(unknown.campus && unknown.campus.ok === false, '未知地点应明确提示无法生成校内段');
assert.strictEqual(unknown.city, null, '未知地点不应返回城市路线');
assert.ok(/无法计算路线/.test(unknown.text), '未知地点应明确说明无法计算路线');
assert.strictEqual(unknown.steps.filter(s => s.tool === 'search_place')[0].result.hits.length, 0,
  '未知地点检索结果应为空');

/* 5. 只命中正式名/稳定别名，不跨实体猜测 */
const matSearch = agent.searchPlace('材料学院实验教学中心');
assert.strictEqual(matSearch.hits[0].kind, 'official', '材料学院检索首条应是官方地点');
assert.strictEqual(matSearch.hits[0].id, 'swjtu_org_100', '材料学院检索应精确指向 swjtu_org_100');
assert.strictEqual(matSearch.hits[0].lng, null, '官方地点检索结果不应伪造坐标');
assert.strictEqual(matSearch.hits.some(h => h.name.indexOf('大学生') >= 0), false,
  '材料学院检索不能命中大学生创新创业教育中心');
assert.strictEqual(matSearch.hits.some(h => h.kind === 'campus'), false,
  '材料学院检索不能把其他校内地点当作命中');

['教育中心', '实验教学中心'].forEach(q => {
  const res = agent.searchPlace(q);
  assert.strictEqual(res.hits.some(h => h.name.indexOf('大学生') >= 0), false,
    '“' + q + '”不能跨实体命中大学生创新创业教育中心');
});

/* 5b. 外部图书馆不能按“图书馆”子串硬配到校内图书馆 */
[
  '四川省图书馆',
  '西南财经大学图书馆',
  '成都大学图书馆'
].forEach(name => {
  const res = agent.searchPlace(name);
  assert.strictEqual(res.hits.some(h => h.kind === 'campus' || h.name === '图书馆'), false,
    name + ' 不应命中校内图书馆');

  ['从' + name + '到天府广场', name + '到天府广场'].forEach(q => {
    const r2 = agent.plan(q, {});
    assert.strictEqual(r2.origin, null, q + ' 不应把外部图书馆识别成起点');
    assert.strictEqual(r2.gate, null, q + ' 不应为外部图书馆选门');
    assert.ok(r2.campus && r2.campus.ok === false, q + ' 应明确提示校内段无法生成');
    assert.strictEqual(r2.city, null, q + ' 不应生成城市路线');
    assert.ok(/无法计算路线/.test(r2.text), q + ' 应明确说明无法计算路线');
    assert.strictEqual(r2.text.indexOf('图书馆'), -1, q + ' 不能把外部图书馆写成校内图书馆');
  });
});

/* 校内完整名称仍应命中同一实体 */
const libFullName = agent.plan('从西南交通大学图书馆到天府广场', {});
assert.strictEqual(libFullName.origin.type, 'place', '西南交通大学图书馆应命中带坐标地点');
assert.strictEqual(libFullName.origin.name, '图书馆', '校内完整名称应归一为图书馆简称');
assert.ok(libFullName.campus && libFullName.campus.ok, '校内完整名称应能生成校内路线');

/* 6. 原有示例主流程不回退 */
const examples = [
  { q: '从图书馆到天府广场', city: true },
  { q: '从天佑斋1号楼到成都东客站', city: true },
  { q: '四食堂到东门', city: false },
  { q: '体育馆到春熙路', city: true }
];
examples.forEach(ex => {
  const r2 = agent.plan(ex.q, {});
  assert.ok(r2.campus && r2.campus.ok, ex.q + ' 应有校内路线');
  if (ex.city) {
    assert.ok(r2.city && r2.city.options && r2.city.options.length > 0,
      ex.q + ' 应有城市接驳方案');
  }
});

console.log('验收断言通过：材料学院不误配且不硬算；外部图书馆不按“图书馆”子串硬配；图书馆、四食堂、天佑斋、体育馆主流程正常；未知地点不硬配。');
