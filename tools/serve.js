const http = require('node:http');

const port = Number(process.env.PORT || 5178);
const target = new URL(process.env.ZHIXING_WEB_URL || 'https://127.0.0.1:8443/web/index.html');

http.createServer((request, response) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405, {Allow: 'GET, HEAD'}).end();
    return;
  }
  if (request.url === '/' || request.url === '/index.html') {
    response.writeHead(302, {Location: target.href, 'Cache-Control': 'no-store'}).end();
    return;
  }
  response.writeHead(404).end();
}).listen(port, '127.0.0.1', () => {
  console.log(`http://127.0.0.1:${port}/ → ${target.href}（请先启动交大智行后端）`);
});
