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

/* 7. 东大门：进/出车道合并为一个校门实体，表单与自然语言同一终点 */
const eastGates = DATA.gates.filter(g => g.n === '东门');
assert.strictEqual(eastGates.length, 1, '东门应只有一个稳定校门实体');
const eastGate = eastGates[0];
assert.strictEqual(eastGate.id, 'campus_gate_east', '东门稳定 ID 应为 campus_gate_east');
assert.strictEqual(JSON.stringify(eastGate.aliases), JSON.stringify(['东大门']), '东门别名应包含东大门');
assert.strictEqual(JSON.stringify(eastGate.nodeIds),
  JSON.stringify(['osm_node_4150911420', 'osm_node_13469658219']),
  '东门应关联进校与出校两个原始节点');
assert.strictEqual(eastGate.routeNodes.default.in, 'osm_node_4150911420',
  '默认进校节点=4150911420');
assert.strictEqual(eastGate.routeNodes.default.out, 'osm_node_13469658219',
  '默认出校节点=13469658219');
assert.strictEqual(eastGate.routeNodeId, 'osm_node_13469658219', '默认路由终点应为出校车道');
assert.strictEqual(eastGate.campusOnly, undefined, '东门应保留城市接驳（非 campusOnly）');
assert.strictEqual(DATA.gates.some(g => g.id === 'osm_node_4150911420' ||
  g.id === 'osm_node_13469658219'), false, '两条车道节点不能继续作为独立门候选');

/* 两个原始节点及连边仍保留 */
['osm_node_4150911420', 'osm_node_13469658219'].forEach(nid => {
  const idx = DATA.nodeIds.indexOf(nid);
  assert.ok(idx >= 0, nid + ' 应保留在路网节点中');
  const hasEdge = DATA.edges.some(e => e[0] === idx || e[1] === idx);
  assert.ok(hasEdge, nid + ' 应保留至少一条连边');
});

/* 表单/自然语言检索与别名 */
['东门', '东大门'].forEach(q => {
  const s = agent.searchPlace(q);
  const gates = s.hits.filter(h => h.kind === 'gate');
  assert.strictEqual(gates.length, 1, '“' + q + '”应只命中一个校门');
  assert.strictEqual(gates[0].id, 'campus_gate_east', '“' + q + '”应命中稳定校门 ID');
  assert.strictEqual(s.hits.some(h => h.kind === 'campus' && h.name === '东门'), false,
    '“' + q + '”不能再命中 barrier_or_gate 的独立地点条目');
});
assert.strictEqual(agent.findGate('东大门').id, 'campus_gate_east', 'findGate 应识别别名');
assert.strictEqual(agent.findGate({ id: 'campus_gate_east' }).n, '东门', 'findGate 应优先按稳定 ID');

/* 自然语言“到东门/到东大门”走同一出校节点 */
const nlEast = agent.plan('从图书馆到东门', { mode: 'walk' });
const nlEastAlias = agent.plan('从图书馆到东大门', { mode: 'walk' });
[nlEast, nlEastAlias].forEach(r2 => {
  assert.strictEqual(r2.gate.id, 'campus_gate_east', '自然语言应选用合并后的东门');
  assert.strictEqual(r2.destination.id, 'campus_gate_east', '自然语言目的地应为合并后的东门');
  assert.ok(r2.campus && r2.campus.ok, '自然语言到东门应有校内路线');
  assert.strictEqual(r2.campus.gateNodeId, 'osm_node_13469658219', '校内→校外应走出校车道');
  assert.strictEqual(r2.campus.direction, 'out', '默认方向应为出校');
  assert.ok(r2.text.indexOf('出校车道') >= 0, '路线说明应标明出校车道');
});
assert.strictEqual(nlEast.campus.plans[0].meters, nlEastAlias.campus.plans[0].meters,
  '“东门”和“东大门”的路线距离应一致');
assert.strictEqual(JSON.stringify(nlEast.campus.plans[0].coords),
  JSON.stringify(nlEastAlias.campus.plans[0].coords),
  '“东门”和“东大门”的路径终点应一致');

/* 表单路径：与自然语言同一起点、方式、方向 → 同一终点与距离 */
const formGate = agent.findGate({ id: 'campus_gate_east', label: '东门', kind: 'gate' });
const formEast = agent.routeCampus(
  { lng: nlEast.origin.lng, lat: nlEast.origin.lat, name: '图书馆' }, formGate, { mode: 'walk' });
assert.ok(formEast.ok, '表单选东门应能生成校内路线');
assert.strictEqual(formEast.gateNodeId, nlEast.campus.gateNodeId, '表单与自然语言应使用同一路由终点');
assert.strictEqual(formEast.direction, nlEast.campus.direction, '表单与自然语言方向应一致');
assert.strictEqual(formEast.plans[0].meters, nlEast.campus.plans[0].meters,
  '表单与自然语言校内距离应一致');
assert.strictEqual(JSON.stringify(formEast.plans[0].coords),
  JSON.stringify(nlEast.campus.plans[0].coords),
  '表单与自然语言校内路径应完全一致');

/* 路径终点坐标 = 出校节点坐标，且选门信息与实际终点一致 */
const outIdx = DATA.nodeIds.indexOf('osm_node_13469658219');
const lastCoord = nlEast.campus.plans[0].coords[nlEast.campus.plans[0].coords.length - 1];
assert.strictEqual(JSON.stringify(lastCoord), JSON.stringify(DATA.nodes[outIdx]),
  '自然语言路径终点应为出校节点坐标');
assert.strictEqual(formEast.plans[0].coords.slice(-1)[0][0], DATA.nodes[outIdx][0],
  '表单路径终点 X 应为出校节点坐标');
assert.strictEqual(formEast.plans[0].coords.slice(-1)[0][1], DATA.nodes[outIdx][1],
  '表单路径终点 Y 应为出校节点坐标');

/* 按方向/通行方式解析门节点 */
const inPlan = router.planToGate(nlEast.origin.lng, nlEast.origin.lat, eastGate,
  { mode: 'walk', direction: 'in' });
assert.strictEqual(inPlan.gateNodeId, 'osm_node_4150911420', 'direction=in 应选进校节点');
assert.strictEqual(inPlan.gateIdx, DATA.nodeIds.indexOf('osm_node_4150911420'),
  'direction=in 的路由终点索引应为进校节点');
assert.ok(inPlan.plans.length > 0, '进校方向也应有可行路径');
assert.strictEqual(router.gateRouteNodeId(eastGate, { mode: 'bike', direction: 'out' }),
  'osm_node_13469658219', '未单独配置的方式应回退 default 的出校节点');

/* 反向查询：校门作为起点时必须从进校车道节点起步，不能沿用门外坐标 */
const inIdxRev = DATA.nodeIds.indexOf('osm_node_4150911420');
const revNL = agent.plan('从东门到图书馆', { mode: 'walk' });
assert.ok(revNL.campus && revNL.campus.ok, '从东门到图书馆应能生成校内路线');
assert.strictEqual(revNL.campus.gateRole, 'origin', '校门起点应标记为 origin');
assert.strictEqual(revNL.campus.gateNodeId, 'osm_node_4150911420',
  '校门起点应从进校车道节点 4150911420 起步');
assert.strictEqual(revNL.campus.direction, 'in', '校门起点方向应为进校');
assert.ok(revNL.text.indexOf('进校车道') >= 0, '反向路线说明应标明进校车道');
assert.strictEqual(revNL.campus.plans[0].coords[0][0], DATA.nodes[inIdxRev][0],
  '反向路径首点 X 应为进校节点坐标');
assert.strictEqual(revNL.campus.plans[0].coords[0][1], DATA.nodes[inIdxRev][1],
  '反向路径首点 Y 应为进校节点坐标');

/* 表单等价：同一个 routeFromGate 入口，与自然语言同终点、同轨迹 */
const revForm = agent.routeFromGate(formGate, nlEast.origin, { mode: 'walk' });
assert.ok(revForm.ok, '表单以东门为起点应能生成校内路线');
assert.strictEqual(revForm.gateNodeId, revNL.campus.gateNodeId,
  '表单与自然语言反向查询应使用同一进校节点');
assert.strictEqual(revForm.direction, 'in', '表单反向查询方向应为进校');
assert.strictEqual(revForm.plans[0].meters, revNL.campus.plans[0].meters,
  '表单与自然语言反向距离应一致');
assert.strictEqual(JSON.stringify(revForm.plans[0].coords),
  JSON.stringify(revNL.campus.plans[0].coords),
  '表单与自然语言反向轨迹应完全一致');
const revAlias = agent.plan('从东大门到图书馆', { mode: 'walk' });
assert.strictEqual(revAlias.campus.gateNodeId, 'osm_node_4150911420',
  '别名“东大门”作为起点也应从进校节点起步');
assert.strictEqual(revAlias.campus.plans[0].meters, revNL.campus.plans[0].meters,
  '“东门”和“东大门”作为起点距离应一致');

/* 城市接驳和其他校门不回退 */
const eastCity = agent.routeCity(eastGate, '天府广场');
assert.ok(eastCity.ok && eastCity.options.length === 2, '东门应保留两条城市接驳');
['南门', '西北一门', '风洞门', '西南门', '西门'].forEach(name => {
  const s = agent.searchPlace(name);
  assert.ok(s.hits.some(h => h.kind === 'gate' && h.name === name), name + ' 仍应可检索');
});
const southPlan = agent.plan('四食堂到南门', {});
assert.ok(southPlan.campus && southPlan.campus.ok, '其他校门仍应能生成校内路线');
assert.strictEqual(southPlan.gate.n, '南门', '其他校门目的地不应被东门影响');
const westPlan = agent.plan('四食堂到西门', {});
assert.ok(westPlan.campus && westPlan.campus.ok, '西门仍应能生成校内路线');
assert.strictEqual(westPlan.campus.gateNodeId, 'osm_node_11668769188', '西门应继续走既有 routeNodeId');

console.log('验收断言通过：东门两条单行车道合并为一个校门实体（保留节点/连边），东门/东大门表单与自然语言同终点；校内→校外走出校车道，校门→校内走进校车道；其他校门与城市接驳正常。');
