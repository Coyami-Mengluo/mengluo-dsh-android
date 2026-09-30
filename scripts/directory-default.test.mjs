import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app/src/main/assets/directory-default.js', import.meta.url), 'utf8')
  .replace('"__MENG_LUO_STORAGE_ROOT__"', JSON.stringify('/storage/emulated/0'));
const origin = 'http://127.0.0.1:47821';
const body = payload => JSON.stringify({ type: 'client-request', rpcId: 'fixture-id', method: 'host.listDirectory', payload });
function fixture(child = false) {
  const calls = [], response = Promise.resolve('unchanged-response');
  const window = { fetch(input, init) { calls.push({ input, init }); return response; } };
  window.top = child ? {} : window;
  const context = createContext({ window, URL, location: { origin, href: origin + '/?token=fixture' } });
  const before = window.fetch;
  runInContext(source, context);
  return { window, calls, context, before, response };
}

test('only the empty directory-list payload gets the phone starting path; caller data is unchanged', () => {
  const f = fixture(), controller = new AbortController();
  const init = { method: 'POST', headers: { 'content-type': 'application/json' }, signal: controller.signal, credentials: 'same-origin', body: body({}) };
  const url = new URL('/api/host.listDirectory', origin);
  assert.equal(f.window.fetch(url, init), f.response);
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].input, url);
  assert.equal(f.calls[0].init.signal, controller.signal);
  assert.equal(f.calls[0].init.headers, init.headers);
  assert.equal(f.calls[0].init.credentials, 'same-origin');
  assert.equal(init.body, body({}));
  assert.deepEqual(JSON.parse(f.calls[0].init.body), { type: 'client-request', rpcId: 'fixture-id', method: 'host.listDirectory', payload: { path: '/storage/emulated/0' } });
});
test('explicit paths including /root, /workspace, and filesystem root are never overridden', () => {
  const f = fixture();
  for (const path of ['/root', '/workspace', '/', '/storage/emulated/0/中文项目', '', null]) {
    const init = { method: 'POST', body: body({ path }) };
    f.window.fetch('/api/host.listDirectory', init);
    assert.equal(f.calls.at(-1).init, init);
  }
});
test('the current directoryPicker Remote API gets the same default without changing explicit paths', () => {
  const f = fixture();
  const request = args => ({ method: 'POST', body: JSON.stringify({ type: 'client-request', rpcId: 'remote-fixture', method: 'directoryPicker/list', payload: { args } }) });
  f.window.fetch('api/directoryPicker/list', request({}));
  assert.deepEqual(JSON.parse(f.calls[0].init.body).payload, { args: { path: '/storage/emulated/0' } });
  for (const args of [{ path: '/root' }, { path: '/workspace' }, { path: '/' }, { path: '' }, { path: null }, [], null, { future: true }]) {
    const init = request(args); f.window.fetch('/api/directoryPicker/list', init); assert.equal(f.calls.at(-1).init, init);
  }
  const explicit = request({});
  f.window.fetch('/api/host.listDirectory', explicit); assert.equal(f.calls.at(-1).init, explicit);
  f.window.fetch('http://127.0.0.1:47822/api/directoryPicker/list', explicit); assert.equal(f.calls.at(-1).init, explicit);
});
test('external ports, hosts, methods, RPCs and unknown or streaming payloads pass through', () => {
  const f = fixture();
  for (const [input, init] of [
    ['http://127.0.0.1:47822/api/host.listDirectory', { method: 'POST', body: body({}) }],
    ['https://example.invalid/api/host.listDirectory', { method: 'POST', body: body({}) }],
    ['/api/host.listDirectory/extra', { method: 'POST', body: body({}) }],
    ['/api/host.createDirectory', { method: 'POST', body: body({}) }],
    ['/api/host.listDirectory', { method: 'GET', body: body({}) }],
    ['/api/host.listDirectory', { method: 'POST', body: '{broken' }],
    ['/api/host.listDirectory', { method: 'POST', body: body({ futureOption: true }) }],
    ['/api/host.listDirectory', { method: 'POST', body: body(null) }],
    ['/api/host.listDirectory', { method: 'POST', body: body([]) }],
    ['/api/host.listDirectory', { method: 'POST', body: body({}).replace('host.listDirectory', 'host.openPath') }],
    ['/api/host.listDirectory', { method: 'POST', body: new Uint8Array(10) }],
    [new Request(origin + '/api/host.listDirectory', { method: 'POST', body: body({}) }), undefined],
  ]) {
    f.window.fetch(input, init); assert.equal(f.calls.at(-1).input, input); assert.equal(f.calls.at(-1).init, init);
  }
});
test('injection is idempotent and does not run in a subframe', () => {
  const f = fixture(), installed = f.window.fetch;
  runInContext(source, f.context); assert.equal(f.window.fetch, installed);
  const child = fixture(true); assert.equal(child.window.fetch, child.before);
});
