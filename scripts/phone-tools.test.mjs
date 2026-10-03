import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { installTools, ownerFor } from '../app/src/main/assets/phone/plugin.mjs';
import { createTransport } from '../app/src/main/assets/phone/transport.mjs';

function fixture(rpc, attachments) {
  const tools = new Map(), notices = [], effects = [];
  const ctx = { tools: { register: tool => tools.set(tool.name, tool) }, attachments, effect: effect => effects.push(effect()) };
  const agent = { id: 'session-1', status: 'running', session: { events: [{ type: 'user/message', data: { id: 'human-1', source: { kind: 'user' } } }] }, inject: message => notices.push(message) };
  const controller = new AbortController();
  const plugin = installTools(ctx, rpc, { interval: 100_000 });
  return { tools, notices, agent, ctx, controller, plugin,
    call: (name, args = {}) => tools.get(name).execute(args, { agent, signal: controller.signal }), close: () => effects.forEach(fn => fn()) };
}
test('owner comes from the human message, never model/plugin context', () => {
  const f = fixture(async () => ({ status: 'idle' }));
  try {
    const first = ownerFor(f.agent);
    f.agent.session.events.push({ type: 'user/message', data: { id: 'injected', source: { kind: 'plugin:mengluo-android-phone' } } });
    assert.equal(ownerFor(f.agent), first);
    f.agent.session.events.push({ type: 'user/message', data: { id: 'human-2', source: { kind: 'user' } } });
    assert.notEqual(ownerFor(f.agent), first);
    assert.equal(ownerFor({ ...f.agent, session: { events: [] } }), undefined);
    assert.equal(ownerFor({ ...f.agent, session: { header: { origin: 'subagent' }, events: f.agent.session.events } }), undefined);
    assert.equal(ownerFor({ ...f.agent, session: { snapshotEvents: () => f.agent.session.events } }), ownerFor(f.agent));
  } finally { f.close(); }
});
test('permission-required requests end once and permission changes do not auto-retry', async () => {
  let calls = 0; const f = fixture(async () => { calls++; return { status: 'permission_required' }; });
  try {
    assert.equal((await f.call('phone_begin', { purpose: 'test' })).status, 'permission_required');
    await f.plugin.poll(); assert.equal(calls, 1); assert.equal((await f.call('phone_begin', { purpose: 'automatic retry' })).status, 'permission_required'); assert.equal(calls, 1);
    assert.equal(f.notices.length, 1);
  } finally { f.close(); }
});
test('screenshots use attachment storage and never expose base64 as tool text', async () => {
  const f = fixture(async req => req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: 'ok', image: 'dGVzdA==', mimeType: 'image/jpeg' });
  try {
    // In absence of the official attachment service, image sharing must fail closed.
    await f.call('phone_begin', { purpose: 'test' }); const value = await f.call('phone_screenshot');
    assert.equal(value.status, 'screenshot_api_unavailable'); assert.equal(value.image, undefined);
    const blocks = f.tools.get('phone_screenshot').output.render({}, { status: 'ok', attachment: { attachmentId: 'safe-ref' } });
    assert.equal(blocks[1].type, 'image'); assert.deepEqual(blocks[1].attachment, { attachmentId: 'safe-ref' }); assert.ok(!blocks[0].text.includes('attachmentId'));
  } finally { f.close(); }
});
test('mutations require consent and arguments cannot forge native identity', async () => {
  const calls = []; const f = fixture(async req => { calls.push(req); return req.op === 'begin' ? { status: 'active', lease: 'native-lease', allowedApps: ['calculator'] } : { status: 'ok' }; });
  try {
    assert.equal((await f.call('phone_action', { action: 'click' })).status, 'consent_required'); assert.equal(calls.length, 0);
    const result = await f.call('phone_begin', { purpose: 'test', owner: 'spoof', op: 'action' }); assert.equal(result.lease, undefined);
    await f.call('phone_action', { action: 'launch', package: 'calculator', op: 'begin', owner: 'spoof', lease: 'spoof' });
    assert.equal(calls[1].op, 'action'); assert.equal(calls[1].lease, 'native-lease'); assert.equal(calls[1].owner, ownerFor(f.agent));
  } finally { f.close(); }
});

test('repeated begin preserves only the native boolean action-confirmation preference', async () => {
  for (const nativeValue of [true, false, undefined, 'true']) {
    const calls = [];
    const f = fixture(async req => {
      calls.push(req);
      return { status: 'active', lease: 'private-lease', allowedApps: ['fixture'], skipActionConfirmation: nativeValue };
    });
    try {
      const first = await f.call('phone_begin', { purpose: 'test' });
      const repeated = await f.call('phone_begin', { purpose: 'same task', skipActionConfirmation: true });
      assert.equal(first.lease, undefined);
      assert.deepEqual(repeated, { status: 'active', allowedApps: ['fixture'], skipActionConfirmation: nativeValue === true });
      assert.equal(calls.length, 1, 'Repeating begin must not re-request a task or change its native setting');
    } finally { f.close(); }
  }
});

test('the model cannot set the native action-confirmation preference through any tool', async () => {
  const calls = [];
  const f = fixture(async req => {
    calls.push(req);
    return req.op === 'begin' ? { status: 'active', lease: 'private-lease', skipActionConfirmation: false }
      : { status: 'ok', skipActionConfirmation: false };
  });
  try {
    for (const tool of f.tools.values()) {
      assert.equal(tool.parameters.additionalProperties, false);
      assert.equal(tool.parameters.properties.skipActionConfirmation, undefined);
    }
    const status = await f.call('phone_status', { skipActionConfirmation: true });
    assert.equal(status.skipActionConfirmation, false);
    const begun = await f.call('phone_begin', { purpose: 'test', skipActionConfirmation: true });
    assert.equal(begun.skipActionConfirmation, false);
    await f.call('phone_action', { action: 'click', ask: true, skipActionConfirmation: true });
    await f.call('phone_tap', { screenshot: 'image', x: 20, y: 40, target: 'Send', ask: true, skipActionConfirmation: true });
    await f.call('phone_gesture', { screenshot: 'image', gesture: 'long_press', x: 20, y: 40, target: 'Target', ask: true, skipActionConfirmation: true });
    await f.call('phone_ask', { question: 'Use the first destination?', skipActionConfirmation: true });
    assert.ok(calls.every(req => !Object.hasOwn(req, 'skipActionConfirmation')));
    assert.ok(calls.filter(req => ['action', 'tap', 'gesture'].includes(req.op)).every(req => req.ask === true),
      'Native settings, not the plugin, decide whether ask=true displays an approval');
  } finally { f.close(); }
});

test('skip action confirmation never fabricates an answer to a real question', async () => {
  for (const answer of ['confirmed', 'user_cancelled']) {
    let answerQuestion, settled = false;
    const calls = [];
    const f = fixture(req => {
      calls.push(req.op);
      if (req.op === 'begin') return Promise.resolve({ status: 'active', lease: 'lease', skipActionConfirmation: true });
      if (req.op === 'ask') return new Promise(resolve => { answerQuestion = resolve; });
      return Promise.resolve({ status: 'model_cancelled' });
    });
    try {
      await f.call('phone_begin', { purpose: 'test' });
      const question = f.call('phone_ask', { question: 'Use the first destination?' }).then(value => { settled = true; return value; });
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(settled, false, 'A question must wait for the native human response even with the setting enabled');
      assert.deepEqual(calls, ['begin', 'ask']);
      answerQuestion({ status: answer });
      assert.equal((await question).status, answer);
      assert.equal(calls.filter(op => op === 'ask').length, 1);
    } finally { f.close(); }
  }
});

test('a native preference change cancels the old task and a new human task reads the new value', async () => {
  const calls = [];
  let skipActionConfirmation = true, status = 'active';
  const f = fixture(async req => {
    calls.push(req.op);
    return req.op === 'begin' ? { status: 'active', lease: 'lease', skipActionConfirmation }
      : { status, skipActionConfirmation };
  });
  try {
    assert.equal((await f.call('phone_begin', { purpose: 'first' })).skipActionConfirmation, true);
    status = 'user_cancelled'; skipActionConfirmation = false;
    await f.plugin.poll();
    assert.equal(f.notices.length, 1);
    const count = calls.length;
    assert.equal((await f.call('phone_begin', { purpose: 'same turn retry' })).status, 'user_cancelled');
    assert.equal(calls.length, count, 'Changing settings must not authorize a restart');
    f.agent.session.events.push({ type: 'user/message', data: { id: 'human-2', source: { kind: 'user' } } });
    status = 'active';
    assert.equal((await f.call('phone_begin', { purpose: 'new explicit task' })).skipActionConfirmation, false);
    assert.equal((await f.call('phone_begin', { purpose: 'same new task' })).skipActionConfirmation, false);
  } finally { f.close(); }
});
test('image coordinate actions preserve image metadata without exposing image bytes or forging authority', async () => {
  const calls = [], saved = [];
  const f = fixture(async req => {
    calls.push(req);
    return req.op === 'begin' ? { status: 'active', lease: 'native-lease' } : req.op === 'screenshot'
      ? { status: 'ok', image: 'dGVzdA==', mimeType: 'image/jpeg', screenshot: 'image-id', width: 1280, height: 720,
        coordinateSpace: 'image_pixels', captureMode: 'full_display', maskedOwnOverlay: true, validForMs: 60000 }
      : { status: 'action_dispatched' };
  }, { saveImage: async value => { saved.push(value); return { attachmentId: 'image-ref' }; } });
  try {
    for (const tool of ['phone_tap', 'phone_gesture']) {
      assert.match(f.tools.get(tool).description, /does not track moving targets/);
      assert.match(f.tools.get(tool).description, /after scrolling or major changes/);
      assert.match(f.tools.get(tool).description, /confirmation_required.*ask=true/);
    }
    assert.equal((await f.call('phone_tap', { screenshot: 'old', x: 2, y: 3 })).status, 'consent_required');
    assert.equal((await f.call('phone_gesture', { gesture: 'swipe' })).status, 'consent_required');
    assert.equal(calls.length, 0);
    await f.call('phone_begin', { purpose: 'fixture' });
    const picture = await f.call('phone_screenshot');
    assert.equal(picture.screenshot, 'image-id'); assert.equal(picture.width, 1280); assert.equal(picture.height, 720);
    assert.equal(picture.coordinateSpace, 'image_pixels'); assert.equal(picture.captureMode, 'full_display'); assert.equal(picture.maskedOwnOverlay, true);
    assert.equal(picture.image, undefined); assert.equal(saved[0].data.toString(), 'test');
    await f.call('phone_tap', { screenshot: picture.screenshot, x: 120, y: 60, target: 'Search', ask: true, owner: 'fake', lease: 'fake', op: 'begin', width: 9999 });
    assert.equal(calls.at(-1).op, 'tap'); assert.equal(calls.at(-1).owner, ownerFor(f.agent)); assert.equal(calls.at(-1).lease, 'native-lease');
    assert.equal(calls.at(-1).x, 120); assert.equal(calls.at(-1).ask, true); assert.equal(calls.at(-1).width, undefined);
    await f.call('phone_gesture', { screenshot: 'new-image', gesture: 'swipe', x: 50, y: 500, endX: 50, endY: 200, durationMs: 500, target: 'Scroll', op: 'begin', owner: 'fake' });
    assert.equal(calls.at(-1).op, 'gesture'); assert.equal(calls.at(-1).owner, ownerFor(f.agent)); assert.equal(calls.at(-1).lease, 'native-lease');
    assert.equal(calls.at(-1).endY, 200); assert.equal(calls.at(-1).durationMs, 500);
  } finally { f.close(); }
});
test('pre-dispatch image touch refusals explicitly say no action happened and never auto-retry', async () => {
  let next = 'screen_changed'; const calls = [];
  const f = fixture(async req => {
    calls.push(req.op);
    return req.op === 'begin' ? { status: 'active', lease: 'lease' }
      : { status: next, instruction: 'Native refusal detail.', actionDispatched: true };
  });
  const refusals = ['screen_changed', 'stale_screenshot', 'invalid_coordinates', 'invalid_target', 'invalid_duration',
    'unsupported_gesture', 'own_overlay_blocked', 'coordinate_mapping_unavailable', 'outside_foreground_app',
    'screen_unavailable', 'outside_allowed_apps', 'sensitive_screen', 'device_locked', 'invalid_overlay_bounds',
    'screenshot_too_large', 'gesture_unavailable', 'invalid_request', 'invalid_lease', 'session_limit', 'busy', 'unsupported_operation', 'confirmation_required'];
  try {
    await f.call('phone_begin', { purpose: 'fixture' });
    for (const toolName of ['phone_tap', 'phone_gesture']) for (const status of refusals) {
      next = status; const before = calls.length;
      const value = await f.call(toolName, { screenshot: 'image-id', gesture: 'swipe', x: 20, y: 60, endX: 20, endY: 10, target: 'Fixture' });
      assert.equal(value.status, status); assert.equal(value.actionDispatched, false);
      assert.match(value.instruction, /BEFORE dispatch.*NOT executed/);
      assert.match(value.instruction, /Do not claim.*click or gesture was sent/);
      assert.match(value.instruction, /NEW phone_screenshot.*identify the target again/);
      assert.match(value.instruction, /Native refusal detail/);
      if (status === 'confirmation_required') {
        assert.match(value.instruction, /previous approval did not cover/);
        assert.match(value.instruction, /After the new screenshot.*NEW action with ask=true/);
        assert.match(value.instruction, /Do not set ask=false.*bypass confirmation/);
      }
      assert.equal(calls.length, before + 1, `${toolName}/${status} must not trigger an automatic retry or screenshot`);
      assert.equal(calls.at(-1), toolName === 'phone_tap' ? 'tap' : 'gesture');
      const rendered = f.tools.get(toolName).output.render({}, value);
      assert.equal(JSON.parse(rendered[0].text).actionDispatched, false);
    }
    assert.equal(f.notices.length, 0, 'A pre-dispatch refusal must not fabricate task cancellation');
  } finally { f.close(); }
});
test('image touch dispatch completion is not business success and partial execution stays uncertain', async () => {
  let next = 'action_dispatched'; const calls = [];
  const f = fixture(async req => {
    calls.push(req.op);
    return req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: next, actionDispatched: false };
  });
  try {
    await f.call('phone_begin', { purpose: 'fixture' });
    for (const toolName of ['phone_tap', 'phone_gesture']) {
      next = 'action_dispatched';
      const dispatched = await f.call(toolName, { screenshot: 'image-id', gesture: 'long_press', x: 20, y: 60, target: 'Fixture' });
      assert.equal(dispatched.actionDispatched, true);
      assert.match(dispatched.instruction, /completed dispatching/);
      assert.match(dispatched.instruction, /does NOT prove.*task succeeded/);
      assert.match(dispatched.instruction, /verify the actual outcome/);
      for (const status of ['gesture_cancelled', 'action_unknown', 'action_failed', 'duplicate_request']) {
        next = status; const before = calls.length;
        const uncertain = await f.call(toolName, { screenshot: 'image-id', gesture: 'swipe', x: 20, y: 60, endX: 20, endY: 10, target: 'Fixture' });
        assert.equal(uncertain.status, status); assert.equal(uncertain.actionDispatched, null);
        assert.match(uncertain.instruction, /may have executed partially or fully/);
        assert.match(uncertain.instruction, /do not blindly retry/);
        assert.match(uncertain.instruction, /consequential action, stop and ask the human/);
        assert.equal(calls.length, before + 1, 'Uncertain execution must never auto-replay the action');
      }
    }
  } finally { f.close(); }
});
test('touch outcome annotations do not fabricate certainty for unknown results or non-touch tools', async () => {
  let next = 'screen_changed';
  const f = fixture(async req => req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: next });
  try {
    await f.call('phone_begin', { purpose: 'fixture' });
    assert.equal((await f.call('phone_screenshot')).actionDispatched, undefined);
    assert.equal((await f.call('phone_observe')).actionDispatched, undefined);
    next = 'future_native_error';
    assert.equal((await f.call('phone_tap', { screenshot: 'image-id', x: 20, y: 60, target: 'Fixture' })).actionDispatched, undefined);
    assert.equal((await f.call('phone_gesture', { gesture: 'swipe' })).actionDispatched, undefined);
    next = 'user_cancelled';
    const cancelled = await f.call('phone_tap', { screenshot: 'image-id', x: 20, y: 60, target: 'Fixture' });
    assert.equal(cancelled.actionDispatched, undefined, 'Task cancellation does not prove a pending touch was never dispatched');
    assert.match(cancelled.instruction, /Previously executed actions were not undone/);
  } finally { f.close(); }
});
test('native Stop is delivered to the model once and cannot be restarted on that turn', async () => {
  const calls = []; const f = fixture(async req => { calls.push(req); return req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: 'user_cancelled' }; });
  try {
    await f.call('phone_begin', { purpose: 'test' }); await f.plugin.poll(); await f.plugin.poll();
    assert.equal(f.notices.length, 1); assert.equal(f.notices[0].source.kind, 'plugin:mengluo-android-phone'); assert.equal(f.notices[0].source.plugin, undefined); assert.match(f.notices[0].content[0].text, /user_cancelled/);
    const count = calls.length;
    assert.equal((await f.call('phone_begin', { purpose: 'retry' })).status, 'user_cancelled');
    assert.equal((await f.call('phone_action', { action: 'click' })).status, 'user_cancelled'); assert.equal(calls.length, count);
    assert.equal((await f.call('phone_tap', { screenshot: 'old', x: 1, y: 1 })).status, 'user_cancelled');
    assert.equal((await f.call('phone_gesture', { gesture: 'swipe' })).status, 'user_cancelled'); assert.equal(calls.length, count);
  } finally { f.close(); }
});
test('a new human message can start again after Stop, but never an injected notice', async () => {
  const f = fixture(async req => req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: 'user_cancelled' });
  try {
    await f.call('phone_begin', { purpose: 'first task' }); await f.call('phone_observe');
    assert.equal((await f.call('phone_begin', { purpose: 'not a new turn' })).status, 'user_cancelled');
    f.agent.session.events.push({ type: 'user/message', data: { id: 'human-2', source: { kind: 'user' } } });
    assert.equal((await f.call('phone_begin', { purpose: 'explicit new task' })).status, 'active');
  } finally { f.close(); }
});
test('model can request a native question or require confirmation, not forge its owner', async () => {
  const calls = []; const f = fixture(async req => { calls.push(req); return req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: 'confirmed' }; });
  try {
    await f.call('phone_begin', { purpose: 'test' });
    await f.call('phone_ask', { question: 'Proceed with this choice?', owner: 'forged' });
    assert.equal(calls.at(-1).op, 'ask'); assert.equal(calls.at(-1).question, 'Proceed with this choice?'); assert.equal(calls.at(-1).owner, ownerFor(f.agent));
    await f.call('phone_action', { action: 'click', ask: true }); assert.equal(calls.at(-1).ask, true);
    await f.call('phone_action', { action: 'click', ask: 'false' }); assert.equal(calls.at(-1).ask, false);
  } finally { f.close(); }
});
test('screen-sharing consent never fabricates an image and revocation terminates the task', async () => {
  let next = 'screen_capture_ready'; const calls = [];
  const f = fixture(async req => { calls.push(req.op); return req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: next }; });
  try {
    await f.call('phone_begin', { purpose: 'fixture' });
    const ready = await f.call('phone_screenshot'); assert.equal(ready.status, 'screen_capture_ready'); assert.equal(ready.attachment, undefined); assert.equal(f.notices.length, 0);
    next = 'screen_capture_permission_denied'; assert.equal((await f.call('phone_screenshot')).status, next); assert.equal(f.notices.length, 0);
    next = 'screen_capture_stopped'; await f.plugin.poll(); assert.equal(f.notices.length, 1);
    const count = calls.length;
    assert.equal((await f.call('phone_gesture', { gesture: 'swipe' })).status, 'screen_capture_stopped'); assert.equal(calls.length, count);
  } finally { f.close(); }
});
test('aborting a pending tool sends native cancellation, not an automatic retry', async () => {
  const calls = []; const f = fixture((req, signal) => {
    calls.push(req.op);
    if (req.op === 'cancel') return Promise.resolve({ status: 'model_cancelled' });
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
  });
  try { const pending = f.call('phone_begin', { purpose: 'test' }); f.controller.abort(); assert.equal((await pending).status, 'model_cancelled'); assert.equal(calls.filter(op => op === 'begin').length, 1); assert.ok(calls.includes('cancel')); }
  finally { f.close(); }
});
test('completion and agent idle both revoke leases without faking successful cancellation', async () => {
  const calls = []; const f = fixture(async req => { calls.push(req.op); return req.op === 'begin' ? { status: 'active', lease: 'lease' } : { status: req.op === 'finish' ? 'completed' : 'model_cancelled' }; });
  try {
    await f.call('phone_begin', { purpose: 'test' }); f.agent.status = 'idle'; await f.plugin.poll();
    assert.ok(calls.includes('cancel')); assert.equal((await f.call('phone_finish')).status, 'model_cancelled');
  } finally { f.close(); }
});
test('transport refuses non-loopback addresses and browser-compatible credentials', () => {
  for (const url of ['http://localhost:123/v1', 'https://127.0.0.1:123/v1', 'http://127.0.0.1:123/v1?token=x', 'http://example.org/v1']) assert.throws(() => createTransport(url, 'a'.repeat(64)));
});
test('transport performs a single direct loopback POST with header-only auth', async () => {
  let count = 0;
  const server = createServer((req, res) => {
    count++; assert.equal(req.url, '/v1'); assert.equal(req.headers.authorization, `Bearer ${'a'.repeat(64)}`); assert.equal(req.headers.origin, undefined);
    let body = ''; req.on('data', value => { body += value; }); req.on('end', () => { assert.match(JSON.parse(body).requestId, /^[a-f0-9-]{36}$/); res.end(JSON.stringify({ status: 'ok' })); });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try { assert.equal((await createTransport(`http://127.0.0.1:${server.address().port}/v1`, 'a'.repeat(64))({ op: 'status' })).status, 'ok'); assert.equal(count, 1); }
  finally { server.close(); }
});
