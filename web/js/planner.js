(function () {
  'use strict';

  const byId = id => document.getElementById(id);
  const element = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  };
  const settings = byId('settings');
  const messages = byId('messages');
  const clarifications = byId('clarifications');
  const routeMap = new window.RouteMap(byId('route-map'), byId('map-status'));
  const chatSessionKey = 'zhixing.web.conversation.v1';
  const defaultMapSection = 'campus';
  const views = {form: {}, chat: {}};
  const labels = {CAMPUS_WALK: '校内步行', WALK: '步行', BUS: '公交', METRO: '地铁',
    SUBWAY: '地铁', RAILWAY: '铁路', TAXI: '打车', TAXI_WAIT: '等车'};
  const formatDeparture = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'});
  let mode = 'form';
  let serviceToken = null;
  let serviceInfo = null;
  let personalMode = false;
  let settingsBusy = false;
  let formBusy = false;
  let gateRequest;
  let chatBusy = false;
  let conversation = null;
  let pending = null;

  class ApiError extends Error {
    constructor(message, {code, status, requestId, retryable} = {}) {
      super(message);
      Object.assign(this, {code, status, requestId, retryable});
    }
  }

  function feedback(node, text = '', failed = false) {
    node.textContent = text;
    node.dataset.error = String(failed);
  }

  function explain(error) {
    return error.requestId ? `${error.message}（编号 ${error.requestId}）` : error.message;
  }

  function status(text = '', failed = false, target = mode) {
    Object.assign(views[target], {status: text, failed});
    if (target === mode) feedback(byId('query-status'), text, failed);
  }

  function personalHeaders() {
    if (!personalMode) return {};
    if (!serviceToken || (serviceInfo?.expiresAt && Date.parse(serviceInfo.expiresAt) <= Date.now())) {
      expireService();
      throw new ApiError('个人 Key 已失效，请到「服务设置」重新验证', {code: 'SERVICE_CONFIG_EXPIRED'});
    }
    return {'X-Service-Token': serviceToken};
  }

  async function request(path, {method = 'GET', body, personal = false, headers = {}, signal} = {}) {
    const requestHeaders = {...(personal ? personalHeaders() : {}), ...headers};
    if (body !== undefined) requestHeaders['Content-Type'] = 'application/json';
    let response;
    try {
      response = await fetch(path, {method, headers: requestHeaders, signal, cache: 'no-store',
        redirect: 'error', body: body === undefined ? undefined : JSON.stringify(body)});
    } catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new ApiError('连不上交大智行服务，请确认演示已启动', {retryable: true});
    }
    if (response.status === 204) return null;
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload) {
      const error = new ApiError(payload?.message || `服务请求失败（HTTP ${response.status}）`,
        {status: response.status, code: payload?.code, requestId: payload?.requestId,
          retryable: payload?.retryable ?? response.status >= 500});
      if (error.code === 'SERVICE_CONFIG_EXPIRED') expireService();
      throw error;
    }
    return payload;
  }

  function renderServices() {
    byId('service-summary').textContent = !personalMode ? '默认服务' : !serviceInfo
      ? '个人配置待验证' : [serviceInfo.amapVerified && '高德已验证',
        serviceInfo.modelVerified && '模型已验证'].filter(Boolean).join(' · ') || '个人配置待验证';
    byId('settings-mode').textContent = personalMode ? '当前使用个人配置' : '当前使用默认服务';
    byId('amap-status').textContent = serviceInfo?.amapVerified ? '高德已验证' : '未验证';
    byId('model-status').textContent = serviceInfo?.modelVerified ? '模型已验证' : '未验证';
    byId('use-default').hidden = !personalMode;
    byId('remove-amap').hidden = !serviceInfo?.amapVerified;
    byId('remove-model').hidden = !serviceInfo?.modelVerified;
  }

  function expireService() {
    serviceToken = null;
    serviceInfo = null;
    renderServices();
  }

  function configHeaders() {
    return {'X-Service-Token': serviceToken};
  }

  async function configure(action) {
    if (settingsBusy) return;
    settingsBusy = true;
    const controls = [...settings.querySelectorAll('.dialog-body input, .dialog-body select, .dialog-body button')];
    controls.forEach(control => { control.disabled = true; });
    feedback(byId('config-feedback'), '正在联系服务…');
    try {
      const confirmation = await action();
      feedback(byId('config-feedback'), confirmation);
    } catch (error) {
      feedback(byId('config-feedback'), explain(error), true);
    } finally {
      settingsBusy = false;
      controls.forEach(control => { control.disabled = false; });
      renderServices();
    }
  }

  byId('open-settings').addEventListener('click', () => {
    settings.showModal();
    if (serviceInfo?.expiresAt && Date.parse(serviceInfo.expiresAt) <= Date.now()) expireService();
    if (!byId('model').options.length) {
      void configure(async () => {
        const {models} = await request('/api/service-config/options');
        byId('model').replaceChildren(...models.map(name => new Option(name, name)));
        return '';
      });
    }
  });

  settings.addEventListener('close', () => {
    byId('amap-key').value = '';
    byId('model-key').value = '';
  });

  for (const service of ['amap', 'model']) {
    byId(`${service}-config`).addEventListener('submit', event => {
      event.preventDefault();
      const input = byId(`${service}-key`);
      const key = input.value.trim();
      input.value = '';
      void configure(async () => {
        if (!serviceToken) {
          const session = await request('/api/service-config/sessions', {method: 'POST'});
          serviceToken = session.ownerToken;
        }
        const body = service === 'amap' ? {key} : {key, model: byId('model').value};
        serviceInfo = await request(`/api/service-config/${service}`,
          {method: 'PUT', headers: configHeaders(), body});
        personalMode = true;
        return '验证成功，已启用个人配置';
      });
    });
    byId(`remove-${service}`).addEventListener('click', () => {
      void configure(async () => {
        serviceInfo = await request(`/api/service-config/${service}`,
          {method: 'DELETE', headers: configHeaders()});
        return '已撤销';
      });
    });
  }

  byId('use-default').addEventListener('click', () => {
    void configure(async () => {
      if (serviceToken) {
        try {
          await request('/api/service-config', {method: 'DELETE', headers: configHeaders()});
        } catch (error) {
          if (error.code !== 'SERVICE_CONFIG_EXPIRED') throw error;
        }
      }
      personalMode = false;
      expireService();
      return '已切换到默认服务';
    });
  });

  if (location.protocol !== 'https:') {
    feedback(byId('config-feedback'), '填写个人 Key 需要从 HTTPS 演示地址打开网页', true);
  }
  renderServices();

  function placeButton(item) {
    const button = element('button', 'place-option');
    button.type = 'button';
    button.disabled = !item.routingReady;
    button.append(element('strong', '', item.name));
    const detail = [item.address, !item.routingReady && (item.unavailableReason || '暂无法算路')].filter(Boolean);
    if (detail.length) button.append(element('small', 'unemphasized', detail.join(' · ')));
    return button;
  }

  function placePicker(id, scope, onChange) {
    const input = byId(id);
    const list = byId(`${id}-results`);
    const hint = byId(`${id}-feedback`);
    let current = null;
    let timer;
    let abort;
    input.addEventListener('input', () => {
      current = null;
      onChange?.();
      clearTimeout(timer);
      abort?.abort();
      list.replaceChildren();
      list.hidden = true;
      feedback(hint);
      const keyword = input.value.trim();
      if (!keyword) return;
      const controller = new AbortController();
      abort = controller;
      timer = setTimeout(async () => {
        feedback(hint, '正在搜索…');
        try {
          const query = new URLSearchParams({keyword, scope});
          const {items} = await request(`/api/places?${query}`,
            {personal: scope === 'CITY', signal: controller.signal});
          if (controller.signal.aborted) return;
          feedback(hint, items.length ? '请选择一个地点' : '没有找到匹配的地点');
          for (const item of items) {
            const button = placeButton(item);
            button.addEventListener('click', () => {
              current = item;
              input.value = item.name;
              list.hidden = true;
              feedback(hint, item.address || '已选定');
              onChange?.();
              input.focus();
            });
            const li = element('li');
            li.append(button);
            list.append(li);
          }
          list.hidden = items.length === 0;
        } catch (error) {
          if (!controller.signal.aborted) feedback(hint, explain(error), true);
        }
      }, 270);
    });
    return {selected: () => current, focus: () => input.focus()};
  }

  const origin = placePicker('origin', 'CAMPUS', () => refreshGates(true));
  const destination = placePicker('destination', 'CITY');

  function departureTime() {
    return byId('departure').value ? `${byId('departure').value}:00+08:00` : '';
  }

  async function refreshGates(reset = false) {
    gateRequest?.abort();
    const select = byId('exit-gate');
    const hint = byId('gate-feedback');
    const start = origin.selected();
    const departure = departureTime();
    if (reset || !start) {
      select.replaceChildren(new Option('自动选择最快校门', ''));
    }
    select.disabled = !start || !departure;
    feedback(hint);
    if (select.disabled) return;
    const controller = new AbortController();
    gateRequest = controller;
    try {
      const params = new URLSearchParams({originId: start.id, departureTime: departure, atExit: 'true'});
      const {connectors} = await request(`/api/campus/connectors?${params}`, {signal: controller.signal});
      if (controller.signal.aborted) return;
      const chosen = select.value;
      const oldLabel = select.selectedOptions[0]?.dataset.name;
      const gates = new Map(connectors.map(gate => [gate.gateId, gate.gateName]));
      const options = [...gates].map(([id, name]) => {
        const option = new Option(name, id);
        option.dataset.name = name;
        return option;
      });
      // 出发时间改变时保留用户指定的校门，让用户确认是否改门。
      if (chosen && !gates.has(chosen)) {
        const unavailable = new Option(`${oldLabel || chosen}（当前不可用）`, chosen);
        unavailable.dataset.name = oldLabel || chosen;
        options.push(unavailable);
      }
      select.replaceChildren(new Option('自动选择最快校门', ''), ...options);
      select.value = chosen;
    } catch (error) {
      if (!controller.signal.aborted) feedback(hint, `暂时无法更新校门。 ${explain(error)}`, true);
    }
  }

  function setDepartureNow() {
    const nextMinute = new Date(Math.ceil((Date.now() + 1000) / 60000) * 60000);
    const parts = Object.fromEntries(formatDeparture.formatToParts(nextMinute).map(part => [part.type, part.value]));
    byId('departure').value = `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
  }
  setDepartureNow();
  refreshGates();
  byId('departure').addEventListener('change', () => refreshGates());
  byId('departure-now').addEventListener('click', () => {
    setDepartureNow();
    refreshGates();
  });

  function minutes(seconds) {
    return Number.isFinite(seconds) ? `${Math.ceil(seconds / 60)} 分钟` : '耗时未提供';
  }

  function distance(value) {
    return Number.isFinite(value) ? value >= 1000 ? `${(value / 1000).toFixed(1)} 公里`
      : `${Math.round(value)} 米` : '距离未提供';
  }

  function showJourney(target, journey) {
    Object.assign(views[target], {journey, selected: 0, section: defaultMapSection});
    if (target === mode) renderJourney();
  }

  function renderJourney() {
    const {journey, selected, section} = views[mode];
    const plans = (journey?.plans || []).map(item => item.route ? {...item.route, travelMode: item.mode} : item);
    const plan = plans[selected];
    byId('route-options').hidden = plans.length === 0;
    byId('itinerary').hidden = !plan;
    byId('map-sections').hidden = !plan;
    byId('provider').textContent = journey?.provider === 'AMAP' ? '高德地图'
      : journey?.provider === 'DEMO' ? '离线示例' : journey?.provider || '';
    byId('plan-list').replaceChildren(...plans.map((item, index) => {
      const button = element('button', 'plan-card');
      button.type = 'button';
      button.setAttribute('aria-pressed', String(index === selected));
      const title = labels[item.travelMode] || `方案 ${index + 1}`;
      button.append(element('span', '', `${title} · ${item.gateExitPoint?.name || '校门待定'}`),
        element('strong', '', minutes(item.totalDurationSeconds)));
      if (item.travelMode) {
        button.append(element('span', '', `全程 ${distance(item.totalDistanceMeters)}`));
        const wait = item.segments.find(step => step.mode === 'TAXI_WAIT');
        if (wait) button.append(element('span', '', `含预估等车 ${minutes(wait.durationSeconds)}`));
      } else {
        const transfer = item.transferCount == null ? '换乘未提供' : `换乘 ${item.transferCount} 次`;
        button.append(element('span', '', `步行 ${distance(item.totalWalkingMeters)} · ${transfer}`));
      }
      if (item.costYuan != null) button.append(element('span', '', `约 ${item.costYuan} 元`));
      button.addEventListener('click', () => {
        views[mode].selected = index;
        renderJourney();
      });
      return button;
    }));
    const warnings = journey?.warnings || [];
    byId('route-warnings').hidden = warnings.length === 0;
    byId('route-warnings').replaceChildren(...warnings.map(warning => element('li', '', warning)));
    document.querySelectorAll('[data-section]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.section === section));
    });
    routeMap.setPlan(plan, section || defaultMapSection);
    renderSteps(plan);
  }

  function renderSteps(plan) {
    const target = byId('route-steps');
    target.replaceChildren();
    if (!plan) return;
    const ordered = [...plan.segments].sort((a, b) => a.sequence - b.sequence);
    const campus = ordered.filter(step => step.mode === 'CAMPUS_WALK');
    const city = ordered.filter(step => step.mode !== 'CAMPUS_WALK');
    for (const [title, group] of [['校内到校门', campus],
      [`校门到${plan.destinationPoint?.name || '目的地'}`, city]]) {
      const list = element('ol');
      for (const step of group) {
        const description = step.description || step.lineName || labels[step.mode] || '行程分段';
        const item = element('li', '', description);
        const stops = step.boardingStation && step.alightingStation
          ? `${step.boardingStation} → ${step.alightingStation}` : null;
        const details = [stops && !description.includes(stops) && stops,
          Number.isFinite(step.durationSeconds) && minutes(step.durationSeconds),
          Number.isFinite(step.distanceMeters) && distance(step.distanceMeters)].filter(Boolean);
        if (details.length) item.append(element('small', '', details.join(' · ')));
        list.append(item);
      }
      const leg = element('section', 'leg');
      leg.append(element('h3', '', title));
      leg.append(group.length ? list : element('p', 'unemphasized', '暂无分段信息'));
      target.append(leg);
    }
  }

  document.querySelectorAll('[data-section]').forEach(button => button.addEventListener('click', () => {
    views[mode].section = button.dataset.section;
    renderJourney();
  }));
  byId('zoom-in').addEventListener('click', () => routeMap.zoom(0.65));
  byId('zoom-out').addEventListener('click', () => routeMap.zoom(-0.65));
  byId('fit-map').addEventListener('click', () => routeMap.fit());

  byId('route-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (formBusy) return;
    const start = origin.selected();
    const end = destination.selected();
    if (!start || !end) {
      status(`请从候选中选定${start ? '城市终点' : '校园起点'}`, true, 'form');
      (start ? destination : origin).focus();
      return;
    }
    const body = {originId: start.id, destinationId: end.id, departureTime: departureTime(),
      gateId: byId('exit-gate').value || null};
    formBusy = true;
    byId('form-fields').disabled = true;
    status('正在查询路线…', false, 'form');
    showJourney('form', null);
    try {
      const journey = await queryRoutes(body);
      showJourney('form', journey);
      status(journey.status === 'NO_ROUTE' ? journey.warnings?.join('；') || '当前暂无可用路线' : '', false, 'form');
    } catch (error) {
      status(explain(error), true, 'form');
    } finally {
      formBusy = false;
      byId('form-fields').disabled = false;
    }
  });

  async function queryRoutes(body) {
    try {
      return await request('/api/journeys/by-mode', {method: 'POST', personal: true, body});
    } catch (error) {
      // 离线演示继续使用原有的公交地铁示例接口。
      if (error.status !== 404 || error.code) throw error;
      if (body.gateId) throw new ApiError('离线演示使用默认校门，请改选自动');
      const {originId, destinationId, departureTime} = body;
      return request('/api/journeys', {method: 'POST', personal: true,
        body: {originId, destinationId, departureTime, preference: 'RECOMMENDED'}});
    }
  }

  function rememberConversation() {
    try {
      if (conversation) sessionStorage.setItem(chatSessionKey, JSON.stringify(conversation));
      else sessionStorage.removeItem(chatSessionKey);
    } catch (error) {
      // Continue with the current tab if the browser disables session storage.
    }
  }

  function chatHeaders() {
    return {'X-Conversation-Token': conversation.ownerToken};
  }

  function chatPath() {
    return `/api/assistant/conversations/${encodeURIComponent(conversation.conversationId)}`;
  }

  function chatControls(busy) {
    chatBusy = busy;
    byId('new-chat').disabled = busy;
    byId('chat-send').disabled = busy;
    byId('chat-retry').hidden = busy || !pending;
    messages.querySelectorAll('button').forEach(button => { button.disabled = busy; });
    clarifications.querySelectorAll('button:not([data-unavailable])').forEach(button => { button.disabled = busy; });
  }

  function appendMessage(role, text, historicRequest) {
    const bubble = element('div', `message ${role === 'user' ? 'user' : 'assistant'}`, text);
    if (historicRequest) {
      const button = element('button', 'button text-button', '查看这次路线');
      button.type = 'button';
      button.addEventListener('click', () => void showHistory(historicRequest));
      bubble.append(button);
    }
    messages.append(bubble);
    messages.scrollTop = messages.scrollHeight;
  }

  async function showHistory(requestId) {
    if (chatBusy) return;
    chatControls(true);
    try {
      const journey = await request(`${chatPath()}/journeys/${encodeURIComponent(requestId)}`,
        {headers: chatHeaders()});
      showJourney('chat', journey);
      status('正在查看历史路线', false, 'chat');
    } catch (error) {
      status(explain(error), true, 'chat');
    } finally {
      chatControls(false);
    }
  }

  function renderChoices(items = []) {
    clarifications.replaceChildren();
    for (const item of items) {
      if (!item.confirmationId) continue;
      const heading = element('strong', '', item.field === 'ORIGIN' ? '请选择起点' : '请选择终点');
      const list = element('ul', 'places');
      for (const candidate of item.candidates) {
        const button = placeButton(candidate);
        button.toggleAttribute('data-unavailable', !candidate.routingReady);
        button.addEventListener('click', () => {
          const confirmation = {confirmationId: item.confirmationId, placeId: candidate.id};
          void turn({confirmation}, `选择 ${candidate.name}`);
        });
        const li = element('li');
        li.append(button);
        list.append(li);
      }
      clarifications.append(heading, list);
    }
  }

  async function restoreConversation() {
    if (!conversation) return;
    try {
      const snapshot = await request(chatPath(), {headers: chatHeaders()});
      conversation.stateVersion = snapshot.stateVersion;
      rememberConversation();
      messages.replaceChildren();
      for (const message of snapshot.messages) {
        appendMessage(message.role, message.content, message.role === 'assistant' && message.journeyRequestId);
      }
      renderChoices(snapshot.clarifications);
      showJourney('chat', snapshot.latest?.journey || null);
    } catch (error) {
      if (error.status === 404) {
        conversation = null;
        rememberConversation();
        return;
      }
      throw error;
    }
  }

  async function turn(input, label, retry = false) {
    if (chatBusy) return;
    chatControls(true);
    status('正在问路…', false, 'chat');
    showJourney('chat', null);
    try {
      if (!conversation) {
        conversation = await request('/api/assistant/conversations', {method: 'POST'});
        rememberConversation();
      }
      if (!retry) {
        pending = {body: {...input, conversationId: conversation.conversationId,
          stateVersion: conversation.stateVersion, clientRequestId: crypto.randomUUID()}, label};
      }
      const reply = await request('/api/assistant/query',
        {method: 'POST', personal: true, headers: chatHeaders(), body: pending.body});
      conversation.stateVersion = reply.stateVersion;
      rememberConversation();
      renderChoices(reply.clarifications);
      appendMessage('user', pending.label);
      appendMessage('assistant', reply.answer, reply.journeyRequestId);
      if (pending.body.message && byId('message').value.trim() === pending.body.message) byId('message').value = '';
      pending = null;
      showJourney('chat', reply.journey);
      status(reply.error ? explain(reply.error) : '', Boolean(reply.error), 'chat');
    } catch (error) {
      if (!error.retryable) pending = null;
      if (error.status === 409) {
        await restoreConversation().catch(() => {});
      }
      status(explain(error), true, 'chat');
    } finally {
      chatControls(false);
    }
  }

  byId('chat-form').addEventListener('submit', event => {
    event.preventDefault();
    const message = byId('message').value.trim();
    if (message) void turn({message}, message);
  });
  byId('chat-retry').addEventListener('click', () => {
    if (pending) void turn(null, null, true);
  });
  byId('new-chat').addEventListener('click', async () => {
    if (chatBusy) return;
    chatControls(true);
    try {
      if (conversation) await request(`${chatPath()}/close`, {method: 'POST', headers: chatHeaders()});
      conversation = null;
      pending = null;
      rememberConversation();
      messages.replaceChildren();
      renderChoices([]);
      showJourney('chat', null);
      status('', false, 'chat');
      byId('message').value = '';
      byId('message').focus();
    } catch (error) {
      status(explain(error), true, 'chat');
    } finally {
      chatControls(false);
    }
  });

  document.querySelectorAll('[data-mode]').forEach(button => button.addEventListener('click', () => {
    mode = button.dataset.mode;
    document.querySelectorAll('[data-mode]').forEach(option => {
      option.setAttribute('aria-pressed', String(option === button));
    });
    byId('route-form').hidden = mode !== 'form';
    byId('chat-panel').hidden = mode !== 'chat';
    feedback(byId('query-status'), views[mode].status, views[mode].failed);
    renderJourney();
  }));

  try {
    const saved = JSON.parse(sessionStorage.getItem(chatSessionKey) || 'null');
    if (saved?.conversationId && saved?.ownerToken) conversation = saved;
  } catch (error) {
    // A missing or invalid saved conversation starts a new one.
  }
  if (conversation) {
    chatControls(true);
    void restoreConversation().catch(error => status(explain(error), true, 'chat'))
      .finally(() => chatControls(false));
  }

  if (location.protocol === 'http:' && location.hostname === '127.0.0.1' && location.port === '5178') {
    fetch('/actuator/health/liveness', {cache: 'no-store'}).then(response => {
      if (response.ok) return;
      const link = element('a', '', '打开 HTTPS 网页演示');
      link.href = 'https://127.0.0.1:8443/web/index.html';
      byId('query-status').replaceChildren('请先启动个人 Key 演示，再', link, '。');
    }).catch(() => {});
  }
})();
