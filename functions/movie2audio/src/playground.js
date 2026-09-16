import { readFile } from 'node:fs/promises';
import { publicSettings } from './config.js';

const assets = { 'app.js': 'text/javascript; charset=utf-8', 'style.css': 'text/css; charset=utf-8' };
const headers = {
  'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src blob:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
};
async function resource(name, type) {
  return { status: 200, headers: { ...headers, 'Content-Type': type },
    body: await readFile(new URL('../resources/playground/' + name, import.meta.url)) };
}
export function createPlayground(config) {
  return {
    page(request) {
      const path = new URL(request.url).pathname;
      if (path.endsWith('/')) return { status: 302, headers: { ...headers, Location: path.replace(/^\/+/, '/').replace(/\/+$/, '') } };
      return resource('index.html', 'text/html; charset=utf-8');
    },
    asset(request) {
      const name = request.params.name;
      const type = Object.hasOwn(assets, name) ? assets[name] : null;
      return type ? resource(name, type) : { status: 404, headers, body: 'Asset not found' };
    },
    configuration() {
      return { status: 200, headers: { ...headers, 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(publicSettings(config)) };
    },
  };
}
