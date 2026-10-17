import { readFileSync } from 'node:fs';
import { Script, createContext } from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app/src/main/assets/task-events.js', import.meta.url), 'utf8');
function fixture(origin = 'http://127.0.0.1:47821', fetch) {
  const messages = [];
  class Socket {
    static OPEN = 1;
    constructor(url, protocols) { this.url = new URL(url, origin).href; this.protocols = protocols; this.events = new Map(); this.sent = []; this.synthetic = []; }
    addEventListener(name, callback) { const list = this.events.get(name) ?? []; list.push(callback); this.events.set(name, list); }
    dispatch(name, value) { for (const callback of this.events.get(name) ?? []) callback(value); }
    dispatchEvent(event) { this.synthetic.push(event); this.dispatch(event.type, event); return true; }
    frame(value) { this.dispatch('message', { data: JSON.stringify(value) }); }
    send(value) { this.sent.push(value); }
  }
  const scope = { WebSocket: Socket, location: new URL(origin), URL, TextDecoder, MessageEvent, AbortController, setTimeout, clearTimeout, fetch, MengLuoTaskEvents: { postMessage: value => messages.push(JSON.parse(value)) } };
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

const questionBatch = [
  { id: 'mode', header: '模式', question: '选哪个模式？', options: [{ label: '标准', description: '保持默认设置' }, { label: '高级' }] },
  { id: 'features', question: '需要哪些功能？', multiSelect: true, options: [{ label: '字幕' }, { label: '对话' }] },
  { id: 'note', question: '还有什么要求？' }
];
const fullAnswer = [
  { id: 'mode', selected: ['标准'] },
  { id: 'features', selected: ['字幕', '对话'], custom: '再加上日志' },
  { id: 'note', selected: [], custom: '请保留设置' }
];
const ask = (socket, questions = questionBatch, eventId = 'q', extra = {}) => current(socket, {
  type: 'waterfall', event: 'user-questions/request', agentId: 's', eventId, request: { questions, ...extra }
});
const questionMessage = f => f.messages.findLast(message => message.kind === 'question');
const respond = (f, answers = fullAnswer, message = questionMessage(f)) => f.scope.__mengluoReplyQuestion({ channel: message.channel, request: message.request, answers });
const ack = (args, result = { ok: true }) => new Response(JSON.stringify({ type: 'server-response', rpcId: JSON.parse(args[1].body).rpcId, result }));

test('mirrors bounded official questions and sends the explicit complete answer through that same client', async () => {
  const sent = [];
  const f = fixture(undefined, (...args) => { sent.push(args); return Promise.resolve(ack(args)); });
  const socket = f.open(); ready(socket); ask(socket);
  const message = questionMessage(f);
  assert.equal(message.session, 's'); assert.equal(message.request, 'q'); assert.deepEqual(message.questions, questionBatch);
  assert.equal(sent.length, 0); assert.deepEqual(socket.sent, []);
  assert.equal(respond(f), true); assert.equal(respond(f), false);
  await settle();
  assert.equal(sent.length, 1); assert.equal(sent[0][0], '/api/$events/result');
  const init = sent[0][1], request = JSON.parse(init.body);
  assert.equal(init.method, 'POST'); assert.equal(init.credentials, 'same-origin'); assert.equal(init.redirect, 'error');
  assert.equal(init.headers['Content-Type'], 'application/json');
  assert.equal(request.type, 'client-request'); assert.equal(request.method, '$events/result');
  assert.deepEqual(request.payload.args, { clientId: 'client', eventId: 'q', outcome: { kind: 'result', value: { answers: fullAnswer } } });
  assert.equal(f.messages.at(-1).kind, 'resolved'); assert.equal(respond(f), false);
  assert.equal(socket.synthetic.length, 1);
  assert.deepEqual(JSON.parse(socket.synthetic[0].data), { type: 'item', streamId: 'events', value: { type: 'cancel', eventId: 'q' } });
  assert.deepEqual(socket.sent, []);
});

test('question answers require exact identities, valid offered labels and complete single/multiple-select batches', () => {
  const sent = [], f = fixture(undefined, (...args) => { sent.push(args); return Promise.resolve(ack(args)); });
  const socket = f.open(); ready(socket); ask(socket);
  for (const answers of [null, [], fullAnswer.slice(0, 2), [fullAnswer[0], fullAnswer[0], fullAnswer[2]],
    [{ id: 'other', selected: [] }, ...fullAnswer.slice(1)],
    [{ id: 'mode', selected: ['不存在'] }, ...fullAnswer.slice(1)],
    [{ id: 'mode', selected: ['标准', '高级'] }, ...fullAnswer.slice(1)],
    [{ id: 'mode', selected: ['标准'], custom: '另一个' }, ...fullAnswer.slice(1)],
    [{ id: 'mode', selected: [] }, ...fullAnswer.slice(1)],
    [fullAnswer[0], fullAnswer[1], { id: 'note', selected: [], custom: '   \n' }],
    [fullAnswer[0], { id: 'features', selected: ['字幕', '字幕'] }, fullAnswer[2]],
    [fullAnswer[0], fullAnswer[1], { id: 'note', selected: [], custom: 'x'.repeat(2001) }]]) {
    assert.equal(respond(f, answers), false);
  }
  const message = questionMessage(f);
  assert.equal(respond(f, fullAnswer, { ...message, channel: 'another-channel' }), false);
  assert.equal(respond(f, fullAnswer, { ...message, request: 'another-request' }), false);
  assert.equal(sent.length, 0);
});

test('unsupported or oversized requests stay in the official page without truncated questions or automatic replies', () => {
  const sent = [], f = fixture(undefined, (...args) => { sent.push(args); return Promise.resolve(ack(args)); });
  const socket = f.open(); ready(socket);
  const invalid = [[], Array(5).fill(questionBatch[0]),
    [{ ...questionBatch[0], question: 'x'.repeat(1001) }],
    [{ ...questionBatch[0], detail: 'x'.repeat(8001) }],
    [{ ...questionBatch[0], header: 'x'.repeat(81) }],
    [{ ...questionBatch[0], options: Array(9).fill({ label: 'a' }) }],
    [{ ...questionBatch[0], options: [{ label: 'a' }, { label: 'a' }] }],
    [{ ...questionBatch[0], options: [{ label: 'x'.repeat(201) }] }],
    [{ ...questionBatch[0], options: [{ label: 'a', description: 'x'.repeat(501) }] }],
    [{ ...questionBatch[0], multiSelect: 'true' }],
    [{ ...questionBatch[0], id: 'bad\nidentity' }]];
  invalid.forEach((items, index) => ask(socket, items, 'invalid-' + index));
  assert.equal(f.messages.filter(message => message.kind === 'waiting').length, invalid.length);
  assert.equal(questionMessage(f), undefined); assert.equal(sent.length, 0);
  current(socket, { type: 'waterfall', event: 'approval/request', agentId: 's', eventId: 'approval', request: { questions: questionBatch } });
  assert.equal(questionMessage(f), undefined);
});

test('plan review keeps the complete detail and approving label; unknown intents defer to the official renderer', () => {
  const f = fixture(undefined, () => Promise.reject(new Error('not submitted'))), socket = f.open(); ready(socket);
  const items = [{ ...questionBatch[0], detail: '# 完整计划\n' + '步骤'.repeat(1000), intent: { kind: 'plan-review', approve: '标准', callId: 'plan' } }];
  ask(socket, items); assert.deepEqual(questionMessage(f).questions, items);
  const count = f.messages.filter(message => message.kind === 'question').length;
  ask(socket, [{ ...items[0], intent: { kind: 'unknown' } }], 'unknown');
  ask(socket, [{ ...items[0], intent: { kind: 'plan-review', approve: '不存在' } }], 'invalid');
  assert.equal(f.messages.filter(message => message.kind === 'question').length, count);
});

test('cancel, retired sockets, logical stream changes and client changes invalidate native drafts', () => {
  for (const invalidate of [
    socket => current(socket, { type: 'cancel', eventId: 'q' }),
    socket => socket.dispatch('close', {}),
    socket => socket.frame({ type: 'end', streamId: 'events' }),
    socket => current(socket, { type: 'ready', clientId: 'replacement' }),
    socket => current(socket, { type: 'ready', clientId: 'client' }, 'replacement-stream')
  ]) {
    const sent = [], f = fixture(undefined, (...args) => { sent.push(args); return Promise.resolve(ack(args)); });
    const socket = f.open(); ready(socket); ask(socket); const message = questionMessage(f);
    invalidate(socket); assert.equal(respond(f, fullAnswer, message), false); assert.equal(sent.length, 0);
  }
  const f = fixture(undefined, () => Promise.reject(new Error('not submitted'))), old = f.open(); ready(old); ask(old);
  const message = questionMessage(f), next = f.open(); ready(next);
  assert.equal(respond(f, fullAnswer, message), false);
});

test('a failed answer preserves the draft for an explicit retry; mismatched acknowledgements never resolve it', async () => {
  for (const makeResponse of [() => Promise.reject(new Error('offline')), () => Promise.resolve(new Response('invalid')),
    () => Promise.resolve(new Response('x'.repeat(8193))),
    () => Promise.resolve(new Response(JSON.stringify({ type: 'server-response', rpcId: 'wrong', result: { ok: true } }))),
    args => Promise.resolve(ack(args, { ok: false }))]) {
    const f = fixture(undefined, (...args) => makeResponse(args)), socket = f.open(); ready(socket); ask(socket);
    assert.equal(respond(f), true); await settle();
    assert.equal(f.messages.at(-1).kind, 'question-failed'); assert.equal(f.messages.some(message => message.kind === 'resolved'), false);
    assert.deepEqual(socket.synthetic, []);
    assert.equal(respond(f), true); await settle();
  }
});

test('an official page reply or timeout disables duplicate native submission while pending and clears after its acknowledgement', async () => {
  let finish;
  const promise = new Promise(resolve => { finish = resolve; });
  const f = fixture(undefined, () => promise), socket = f.open(); ready(socket); ask(socket, questionBatch, 'q', { wait: { callId: 'call', timed: true } });
  const nativeQuestion = questionMessage(f);
  const body = { type: 'client-request', rpcId: 'page-reply', method: '$events/result', payload: { args: { clientId: 'client', eventId: 'q', outcome: { kind: 'rejected', error: { name: 'UserQuestionError', code: 'ASK_TIMED_OUT', message: 'expired' } } } } };
  const init = { method: 'POST', body: JSON.stringify(body) };
  assert.equal(f.scope.fetch('/api/$events/result', init), promise);
  assert.equal(respond(f), false);
  finish(new Response(JSON.stringify({ type: 'server-response', rpcId: 'page-reply', result: { ok: true } })));
  await settle(); assert.equal(f.messages.at(-1).kind, 'resolved');
  assert.equal(respond(f, fullAnswer, nativeQuestion), false);
  assert.deepEqual(socket.synthetic, []);
});

test('a successful native answer synchronizes only its matching official form without another POST', async () => {
  let finish;
  const sent = [], f = fixture(undefined, (...args) => { sent.push(args); return new Promise(resolve => { finish = () => resolve(ack(args)); }); });
  const socket = f.open(); ready(socket); ask(socket); ask(socket, [{ id: 'other', question: '另一个问题？' }], 'other');
  const closed = [], controllers = new Map([['q', new AbortController()], ['other', new AbortController()]]);
  socket.addEventListener('message', event => {
    const frame = JSON.parse(event.data);
    if (frame.type === 'item' && frame.streamId === 'events' && frame.value.type === 'cancel') {
      closed.push(frame.value.eventId);
      controllers.get(frame.value.eventId)?.abort();
    }
  });
  const message = f.messages.find(message => message.kind === 'question' && message.request === 'q');
  assert.equal(respond(f, fullAnswer, message), true);
  assert.deepEqual(closed, []); assert.equal(sent.length, 1);
  finish(); await settle();
  assert.deepEqual(closed, ['q']); assert.equal(controllers.get('q').signal.aborted, true); assert.equal(controllers.get('other').signal.aborted, false);
  assert.equal(sent.length, 1); assert.equal(f.messages.filter(message => message.kind === 'resolved' && message.request === 'q').length, 1);
});

test('late acknowledgements after cancellation, client changes or reconnection cannot inject local lifecycle frames', async () => {
  for (const invalidate of [
    socket => current(socket, { type: 'cancel', eventId: 'q' }),
    socket => socket.dispatch('close', {}),
    socket => socket.frame({ type: 'end', streamId: 'events' }),
    socket => current(socket, { type: 'ready', clientId: 'replacement' }),
    socket => current(socket, { type: 'ready', clientId: 'client' }, 'replacement-stream'),
    (_socket, f) => ready(f.open())
  ]) {
    let finish;
    const f = fixture(undefined, (...args) => new Promise(resolve => { finish = () => resolve(ack(args)); }));
    const socket = f.open(); ready(socket); ask(socket);
    assert.equal(respond(f), true); invalidate(socket, f); finish(); await settle();
    assert.deepEqual(socket.synthetic, []);
  }
});

test('session removal and errors invalidate only questions belonging to that session', async () => {
  for (const event of ['api-session/removed', 'api-session/error']) {
    let finish;
    const f = fixture(undefined, (...args) => new Promise(resolve => { finish = () => resolve(ack(args)); }));
    const socket = f.open(); ready(socket); ask(socket);
    const question = questionMessage(f);
    current(socket, { type: 'emit', event, args: ['another-session'] });
    assert.equal(respond(f, fullAnswer, question), true);
    current(socket, { type: 'emit', event, args: ['s'] });
    finish(); await settle();
    assert.equal(respond(f, fullAnswer, question), false);
    assert.deepEqual(socket.synthetic, []);
    assert.equal(f.messages.some(message => message.kind === 'resolved'), false);
  }
});
