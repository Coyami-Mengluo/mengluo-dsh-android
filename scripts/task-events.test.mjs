import { readFileSync } from 'node:fs';
import { Script, createContext } from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app/src/main/assets/task-events.js', import.meta.url), 'utf8');
function fixture(origin = 'http://127.0.0.1:47821', fetch) {
  const messages = [];
  class Socket {
    static OPEN = 1;
    constructor(url, protocols) { this.url = new URL(url, origin).href; this.protocols = protocols; this.events = new Map(); this.sent = []; }
    addEventListener(name, callback) { const list = this.events.get(name) ?? []; list.push(callback); this.events.set(name, list); }
    dispatch(name, value) { for (const callback of this.events.get(name) ?? []) callback(value); }
    frame(value) { this.dispatch('message', { data: JSON.stringify(value) }); }
    send(value) { this.sent.push(value); }
  }
  const scope = { WebSocket: Socket, location: new URL(origin), URL, TextDecoder, fetch, MengLuoTaskEvents: { postMessage: value => messages.push(JSON.parse(value)) } };
  scope.window = scope; scope.top = scope;
  const context = createContext(scope); new Script(source).runInContext(context);
  return { scope, Socket, messages, context, open: (path = '/api/remote.mux', port = 47821) => new scope.WebSocket(`ws://127.0.0.1:${port}${path}`) };
}
const ready = socket => socket.frame({ type: 'item', streamId: 'events', value: { type: 'ready', clientId: 'client' } });
const current = (socket, value, streamId = 'events') => socket.frame({ type: 'item', streamId, value });
test('current official carrier: status, permission, question, resolution; never responds or consumes', () => {
  const f = fixture(), socket = f.open(); let forwarded = 0;
  socket.addEventListener('message', () => forwarded++); ready(socket);
  current(socket, { type: 'emit', event: 'api-session/status', args: ['s', true] });
  current(socket, { type: 'emit', event: 'api-session/status', args: ['s', false] });
  current(socket, { type: 'waterfall', event: 'approval/request', agentId: 's', eventId: 'a', request: { secret: 'never-native' } });
  current(socket, { type: 'waterfall', event: 'user-questions/request', agentId: 's', eventId: 'q', request: { prompt: 'never-native' } });
  current(socket, { type: 'cancel', eventId: 'a' });
  assert.deepEqual(f.messages.map(m => m.kind), ['reset', 'connected', 'status', 'status', 'waiting', 'waiting', 'resolved']);
  assert.equal(forwarded, 6); assert.deepEqual(socket.sent, []);
  assert.equal(JSON.stringify(f.messages).includes('never-native'), false);
});
test('legacy official host/mux frames are observed without replying', () => {
  const f = fixture(), host = f.open('/api/events.host'), mux = f.open('/api/events.mux');
  host.frame({ type: 'server-request', rpcId: 'r', payload: { type: 'host/session-status', sessionId: 's', running: true } });
  mux.frame({ type: 'server-request', rpcId: 'r1', payload: { type: 'approval/requested', sessionId: 's', approvalId: 'a' } });
  mux.frame({ type: 'server-request', rpcId: 'q', payload: { type: 'question/requested', sessionId: 's' } });
  mux.frame({ type: 'server-request', payload: { type: 'question/resolved', sessionId: 's', questionRpcId: 'q' } });
  assert.deepEqual(f.messages.map(m => m.kind), ['reset', 'reset', 'status', 'waiting', 'waiting', 'resolved']);
  assert.equal(f.messages.at(-2).request, 'q'); assert.deepEqual(host.sent, []); assert.deepEqual(mux.sent, []);
});
test('other origins, ports, streams, routes and malformed payloads are ignored', () => {
  const f = fixture();
  for (const url of ['ws://evil.example/api/remote.mux', 'ws://127.0.0.1:9999/api/remote.mux', 'ws://127.0.0.1:47821/plugin/ws', 'ws://user@127.0.0.1:47821/api/remote.mux']) {
    ready(new f.scope.WebSocket(url));
  }
  assert.equal(f.messages.length, 0);
  const socket = f.open(); ready(socket);
  current(socket, { type: 'emit', event: 'api-session/status', args: ['s', true] }, 'unrelated');
  socket.dispatch('message', { data: '{bad' }); socket.dispatch('message', { data: 'x'.repeat(1024 * 1024 + 1) });
  current(socket, { type: 'emit', event: 'api-session/status', args: ['s', 'false'] });
  current(socket, { type: 'emit', event: 'unrelated', args: ['s', false] });
  assert.equal(f.messages.length, 2);
  const external = fixture('https://example.com'); ready(external.open()); assert.equal(external.messages.length, 0);
});
test('constructor/static/prototype/send behavior is unchanged, adapter is idempotent', () => {
  const f = fixture(); new Script(source).runInContext(f.context);
  const socket = f.open(); assert.ok(socket instanceof f.Socket); assert.ok(socket instanceof f.scope.WebSocket);
  assert.equal(f.scope.WebSocket.OPEN, 1); assert.throws(() => f.scope.WebSocket('ws://127.0.0.1:47821'));
  socket.send('official-reply'); assert.deepEqual(socket.sent, ['official-reply']);
  assert.equal(f.messages.length, 1);
});
test('retired sockets cannot generate stale task notices', () => {
  const f = fixture(), old = f.open(); ready(old);
  const next = f.open(); ready(next); const count = f.messages.length;
  current(old, { type: 'emit', event: 'api-session/status', args: ['s', false] }); old.dispatch('close', {});
  assert.equal(f.messages.length, count);
  next.dispatch('close', {}); assert.equal(f.messages.at(-1).kind, 'disconnected');
});
test('ending and reopening the logical event stream on one socket resets completion state', () => {
  const f = fixture(), socket = f.open(); ready(socket);
  socket.frame({ type: 'end', streamId: 'events' }); assert.equal(f.messages.at(-1).kind, 'disconnected');
  current(socket, { type: 'emit', event: 'api-session/status', args: ['s', false] });
  assert.equal(f.messages.at(-1).kind, 'disconnected');
  current(socket, { type: 'ready', clientId: 'new-client' }, 'new-events');
  assert.deepEqual(f.messages.slice(-2).map(m => m.kind), ['reset', 'connected']);
});

const reply = { method: 'POST', body: JSON.stringify({ type: 'client-request', rpcId: 'rpc1', method: '$events/result', payload: { args: { clientId: 'client', eventId: 'a', outcome: { kind: 'result', value: 'allowed-once' } } } }) };
const settle = async () => { for (let i = 0; i < 8; i++) await new Promise(resolve => setImmediate(resolve)); };
test('observes successful official confirmation reply without changing its request/promise/response', async () => {
  const body = JSON.stringify({ type: 'server-response', rpcId: 'rpc1', result: { ok: true } });
  const response = new Response(body), promise = Promise.resolve(response), sent = [];
  const f = fixture(undefined, (...args) => { sent.push(args); return promise; });
  const socket = f.open(); ready(socket);
  current(socket, { type: 'waterfall', event: 'approval/request', agentId: 's', eventId: 'a' });
  assert.equal(f.scope.fetch('/api/$events/result', reply), promise);
  await settle();
  assert.equal(sent.length, 1); assert.equal(sent[0][1], reply); assert.equal(await response.text(), body);
  assert.equal(f.messages.at(-1).kind, 'resolved'); assert.equal(f.messages.at(-1).request, 'a');
  assert.equal(JSON.stringify(f.messages).includes('allowed-once'), false); assert.deepEqual(socket.sent, []);
});
test('failed, mismatched, oversized and external replies cannot clear a pending reminder', async () => {
  for (const body of [JSON.stringify({ type: 'server-response', rpcId: 'rpc1', result: { ok: false } }), JSON.stringify({ type: 'server-response', rpcId: 'wrong', result: { ok: true } }), 'x'.repeat(8193)]) {
    const f = fixture(undefined, () => Promise.resolve(new Response(body))); ready(f.open());
    await f.scope.fetch('/api/$events/result', reply); await settle();
    assert.equal(f.messages.some(m => m.kind === 'resolved'), false);
  }
  const f = fixture(undefined, () => Promise.resolve(new Response(JSON.stringify({ type: 'server-response', rpcId: 'rpc1', result: { ok: true } })))); ready(f.open());
  await f.scope.fetch('http://127.0.0.1:9999/api/$events/result', reply); await settle();
  assert.equal(f.messages.some(m => m.kind === 'resolved'), false);
});
