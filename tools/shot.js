/**
 * 用 Chrome DevTools Protocol 做无头渲染检查 + 截图（不依赖 puppeteer）。
 * 用法: node tools/shot.js <url> <outfile.png> [width] [height] [waitMs] [actionsJson]
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const CHROME = process.env.CHROME || (process.platform === 'win32'
  ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  : process.platform === 'darwin'
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
    : '/usr/bin/google-chrome');
const url = process.argv[2] || 'http://127.0.0.1:5178/';
const out = process.argv[3] || path.join(__dirname, '..', 'shots', 'shot.png');
const W = parseInt(process.argv[4] || '1440', 10);
const H = parseInt(process.argv[5] || '900', 10);
const WAIT = parseInt(process.argv[6] || '2500', 10);
let actions = [];
if (process.argv[7]) {
  // 支持传入 JSON 字符串，或 @文件路径（避免命令行引号转义问题）
  const arg = process.argv[7];
  if (arg.startsWith('@')) {
    const raw = fs.readFileSync(arg.slice(1), 'utf8').trim();
    const parsed = JSON.parse(raw);
    actions = Array.isArray(parsed) ? parsed : [parsed];
  } else {
    actions = JSON.parse(arg);
  }
}

const PORT = 9333 + Math.floor(Math.random() * 400);
const userDir = path.join(require('os').tmpdir(), 'cdp-' + Date.now());

function get(pathname) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path: pathname }, (res) => {
      let d = '';
      res.on('data', (c) => (d += c));
      res.on('end', () => resolve(JSON.parse(d)));
    }).on('error', reject);
  });
}

function waitForEndpoint(tries = 80) {
  return new Promise((resolve, reject) => {
    const tick = (n) => {
      get('/json/version').then(resolve).catch(() => {
        if (n <= 0) return reject(new Error('chrome devtools not ready'));
        setTimeout(() => tick(n - 1), 250);
      });
    };
    tick(tries);
  });
}

(async () => {
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const chrome = spawn(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--hide-scrollbars',
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + userDir,
    '--window-size=' + W + ',' + H,
    '--force-device-scale-factor=1',
    'about:blank'
  ], { stdio: 'ignore' });

  const errors = [];
  const logs = [];
  let ws;

  try {
    await waitForEndpoint();
    const targets = await get('/json/list');
    const page = targets.find((t) => t.type === 'page');
    if (!page) throw new Error('no page target');

    const WebSocket = await loadWS();
    ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

    let id = 0;
    const pending = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
        return;
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails;
        errors.push((d.exception && (d.exception.description || d.exception.value)) || d.text);
      }
      if (msg.method === 'Runtime.consoleAPICalled') {
        const txt = (msg.params.args || []).map((a) => a.value !== undefined ? a.value : (a.description || a.type)).join(' ');
        logs.push(msg.params.type + ': ' + txt);
        if (msg.params.type === 'error') errors.push(txt);
      }
      if (msg.method === 'Log.entryAdded') {
        const e = msg.params.entry;
        logs.push('log/' + e.level + ': ' + e.text);
        if (e.level === 'error') errors.push(e.text);
      }
    };

    const send = (method, params) => new Promise((resolve, reject) => {
      const mid = ++id;
      pending.set(mid, { resolve, reject });
      ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
    });

    await send('Runtime.enable');
    await send('Log.enable');
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', {
      width: W, height: H, deviceScaleFactor: 1, mobile: false
    });

    await send('Page.navigate', { url });
    // 等待真正的应用文档加载完成（避免抓到 about:blank / 初始文档）
    for (let i = 0; i < 60; i++) {
      const r = await send('Runtime.evaluate', {
        expression: 'document.readyState + "|" + (document.querySelector(".map-canvas") ? "1" : "0") + "|" + document.title',
        returnByValue: true
      });
      const v = (r.result && r.result.value) || '';
      if (v.startsWith('complete') && v.indexOf('|1|') > 0) break;
      await new Promise((r2) => setTimeout(r2, 250));
    }
    await new Promise((r) => setTimeout(r, WAIT));

    for (const act of actions) {
      if (act.type === 'click') {
        await send('Runtime.evaluate', {
          expression: `(()=>{const el=document.querySelector(${JSON.stringify(act.sel)}); if(!el) return 'missing'; el.click(); return 'ok';})()`,
          returnByValue: true
        });
      } else if (act.type === 'eval') {
        const r = await send('Runtime.evaluate', {
          expression: act.js, returnByValue: true, awaitPromise: true
        });
        if (r && r.result) {
          const v = r.result.value !== undefined ? r.result.value : r.result.description;
          console.log('EVAL ' + (typeof v === 'string' ? v : JSON.stringify(v)));
        }
        if (r && r.exceptionDetails) {
          console.log('EVAL_ERROR ' + (r.exceptionDetails.text || '') + ' ' +
            ((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || ''));
        }
      } else if (act.type === 'wait') {
        await new Promise((r) => setTimeout(r, act.ms || 500));
      }
    }

    const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    fs.writeFileSync(out, Buffer.from(shot.data, 'base64'));

    // 页面自检信息
    const info = await send('Runtime.evaluate', {
      expression: `JSON.stringify({
        title: document.title,
        canvas: !!document.querySelector('.map-canvas'),
        cw: document.querySelector('.map-canvas') ? document.querySelector('.map-canvas').width : 0,
        ch: document.querySelector('.map-canvas') ? document.querySelector('.map-canvas').height : 0,
        trace: document.querySelectorAll('.trace-item').length,
        cards: document.querySelectorAll('.res-card').length,
        stats: document.querySelector('#statEdges') ? document.querySelector('#statEdges').textContent : null
      })`,
      returnByValue: true
    });

    console.log('PAGE_INFO ' + (info.result && info.result.value));
    console.log('SHOT ' + out);
    if (errors.length) {
      console.log('ERRORS ' + errors.length);
      errors.slice(0, 25).forEach((e) => console.log('  ! ' + String(e).split('\n')[0].slice(0, 300)));
    } else {
      console.log('ERRORS 0');
    }
    const warns = logs.filter((l) => /warn|error/i.test(l)).slice(0, 12);
    warns.forEach((w) => console.log('  ~ ' + w.slice(0, 240)));
  } catch (e) {
    console.error('FAILED: ' + e.message);
    process.exitCode = 1;
  } finally {
    try { if (ws) ws.close(); } catch (_) {}
    try { chrome.kill(); } catch (_) {}
  }
})();

async function loadWS() {
  // Node 22+ 自带 WebSocket
  if (typeof WebSocket !== 'undefined') return WebSocket;
  throw new Error('no global WebSocket');
}
