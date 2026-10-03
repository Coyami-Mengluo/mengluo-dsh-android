import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { mkdtempSync, mkdirSync } from 'node:fs';
import { installTools, installSkill, ownerFor, inject } from '../app/src/main/assets/phone/plugin.mjs';

// Opt-in: use a separately installed official SDK; never install into a user's Harness slot.
const sdk = process.env.MENG_LUO_HARNESS_SDK;
if (sdk) mkdirSync(resolve('.tools/phone-probe/tests'), { recursive: true });
test('real official SDK: registration, session identity, cancellation injection and skill loading', { skip: !sdk }, async () => {
  const require = createRequire(pathToFileURL(join(resolve(sdk), 'package.json')));
  const load = name => import(pathToFileURL(require.resolve(name)).href);
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: ToolRuntime } = await load('@deepseek-ai/dsh-tools');
  const { Session, SessionId } = await load('@deepseek-ai/dsh-session');
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const { default: SkillRegistry } = await load('@deepseek-ai/dsh-skill');
  const filesystem = await load('@deepseek-ai/dsh-skill-filesystem');
  const { loadOverlayPatches } = await load('@deepseek-ai/dsh-app-boot');
  const { sessionFormatCatalog } = await load('@deepseek-ai/dsh-session-format-catalog');
  const { default: Persistence } = await load('@deepseek-ai/dsh-session-persistence-jsonl');
  const patches = loadOverlayPatches('phone-sdk-test', resolve('app/src/main/assets/phone/patch.yml'));
  assert.equal(patches.length, 1);
  assert.equal(patches[0].insert[0].name, pathToFileURL(resolve('/opt/mengluo-phone/plugin.mjs')).href);
  const ctx = new Context();
  try {
    await ctx.plugin(SystemPrompt); await ctx.plugin(ToolRuntime); await ctx.plugin(SkillRegistry);
    await ctx.plugin(Persistence, { root: mkdtempSync(resolve('.tools/phone-probe/tests/session-v4-')), compression: 'none' });
    await ctx.plugin(filesystem, { includeDefaultRoots: false, watch: false });
    ctx.skills.register({ name: 'official-fixture', description: 'Unrelated existing skill', content: 'Keep this unchanged', source: 'bundled' });
    const originalBundledRoot = process.env.DSH_BUNDLED_SKILL_DIR;
    const notices = [], session = Session.create(SessionId('phone-sdk-test'));
    session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Only the isolated fixture' }] }), { surfaceOp: 'append' });
    const agent = { id: session.id, status: 'running', session, ctx, inject: message => { notices.push(message); session.append('user/message', message, { surfaceOp: 'append' }); } };
    let status = 'active';
    await ctx.plugin({ name: 'native-phone-sdk-test', inject, apply(inner) {
      installSkill(inner);
      installTools(inner, async req => req.op === 'begin' ? { status: 'active', lease: 'private-lease', allowedApps: ['fixture'] } : { status });
    } });
    assert.equal(ctx.tools.schemas().filter(value => value.name.startsWith('phone_')).length, 9); assert.ok(ownerFor(agent));
    const run = (name, args = {}) => ctx.tools.execute({ callId: 'probe-' + Math.random(), name, arguments: args, agent, signal: AbortSignal.timeout(3000) });
    const begun = await run('phone_begin', { purpose: 'Read fixture' }); assert.equal(begun.value.status, 'active'); assert.equal(begun.value.lease, undefined);
    // Registration alone must not hide a schema regression: exercise both touch
    // outputs through the real SDK, including the uncertain-execution null value.
    for (const tool of ['phone_tap', 'phone_gesture']) {
      const args = { screenshot: 'fixture-image', x: 20, y: 40, target: 'Fixture', ...(tool === 'phone_gesture' ? { gesture: 'long_press' } : {}) };
      for (const [nativeStatus, expected] of [['screen_changed', false], ['stale_screenshot', false], ['confirmation_required', false],
          ['action_dispatched', true], ['gesture_cancelled', null], ['action_unknown', null]]) {
        status = nativeStatus;
        const touch = await run(tool, args);
        assert.equal(touch.value.status, nativeStatus);
        assert.equal(touch.value.actionDispatched, expected);
      }
    }
    status = 'user_cancelled'; assert.equal((await run('phone_observe')).value.status, 'user_cancelled');
    assert.equal(notices.length, 1); assert.equal(notices[0].source.kind, 'plugin:mengluo-android-phone');
    const noticeEvent = session.snapshotEvents().find(event => event.type === 'user/message' && event.data.id === notices[0].id);
    assert.ok(noticeEvent);
    assert.throws(() => sessionFormatCatalog.encodeCurrentEvent({ ...noticeEvent, data: { ...noticeEvent.data, source: { kind: 'plugin', plugin: 'mengluo-android-phone', form: 'notice', summary: 'old cancelled message' } } }), /producer-owned source kind/);
    assert.doesNotThrow(() => sessionFormatCatalog.encodeCurrentEvent(noticeEvent));
    assert.equal((await run('phone_begin', { purpose: 'forbidden retry' })).value.status, 'user_cancelled');
    const skill = await ctx.skills.get('android-phone'); assert.equal(skill.name, 'android-phone');
    assert.match(skill.content, /native stop pill/);
    assert.equal((await ctx.skills.get('official-fixture')).content, 'Keep this unchanged');
    assert.equal(process.env.DSH_BUNDLED_SKILL_DIR, originalBundledRoot);
    // The original smoke test only appended to an in-memory Session. Exercise the actual V4 disk boundary.
    const writer = await ctx.sessionPersistence.create(session.header);
    await writer.append(session.snapshotEvents()); await writer.flush(); await writer.close();
    const reader = await ctx.sessionPersistence.open(session.id, 'read');
    assert.ok((await reader.read()).events.some(event => event.type === 'user/message' && event.data.id === notices[0].id));
    await reader.close();
    // A rejected old notice must not require deleting a user's conversation on disk.
    const legacy = Session.create(SessionId('old-notice-recovery'));
    legacy.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Keep this prior message' }] }), { surfaceOp: 'append' });
    const legacyWriter = await ctx.sessionPersistence.create(legacy.header);
    await legacyWriter.append(legacy.snapshotEvents()); await legacyWriter.flush();
    const bad = legacy.append('user/message', { ...notices[0], source: { kind: 'plugin', plugin: 'mengluo-android-phone', form: 'notice', summary: 'old stopped notification' } }, { surfaceOp: 'append' });
    await assert.rejects(legacyWriter.append([bad]), /producer-owned source kind/);
    try { await legacyWriter.close(); } catch (error) { assert.match(String(error), /producer-owned source kind/); }
    const recovered = await ctx.sessionPersistence.open(legacy.id, 'read');
    const recoveredEvents = (await recovered.read()).events;
    assert.equal(recoveredEvents.length, 1); assert.equal(recoveredEvents[0].data.content[0].text, 'Keep this prior message');
    await recovered.close();
    session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'A new explicit fixture task' }] }), { surfaceOp: 'append' });
    status = 'active'; assert.equal((await run('phone_begin', { purpose: 'New task after Stop' })).value.status, 'active');
  } finally { await ctx.fiber.dispose(); }
});

test('real official loader mounts the shipped overlay and plugin entrypoint', { skip: !sdk }, async () => {
  const require = createRequire(pathToFileURL(join(resolve(sdk), 'package.json')));
  const load = name => import(pathToFileURL(require.resolve(name)).href);
  const { boot, loadOverlayPatches } = await load('@deepseek-ai/dsh-app-boot');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: ToolRuntime } = await load('@deepseek-ai/dsh-tools');
  const { default: SkillRegistry } = await load('@deepseek-ai/dsh-skill');
  const patches = loadOverlayPatches('phone-sdk-test', resolve('app/src/main/assets/phone/patch.yml'));
  // The packaged guest path is remapped only in this host-side SDK fixture.
  patches[0].insert[0].name = pathToFileURL(resolve('app/src/main/assets/phone/plugin.mjs')).href;
  const previousUrl = process.env.ML_PHONE_URL, previousToken = process.env.ML_PHONE_TOKEN;
  process.env.ML_PHONE_URL = 'http://127.0.0.1:1/v1'; process.env.ML_PHONE_TOKEN = 'a'.repeat(64);
  let ctx;
  try {
    ctx = await boot('phone-sdk-test', resolve('scripts/fixtures/phone-sdk-root.yml'), patches, async inner => {
      await inner.plugin(SystemPrompt); await inner.plugin(ToolRuntime); await inner.plugin(SkillRegistry);
    });
    assert.equal(ctx.tools.schemas().filter(value => value.name.startsWith('phone_')).length, 9);
    assert.equal((await ctx.skills.get('android-phone')).provider, 'mengluo-android-phone');
  } finally {
    if (ctx) await ctx.fiber.dispose();
    if (previousUrl === undefined) delete process.env.ML_PHONE_URL; else process.env.ML_PHONE_URL = previousUrl;
    if (previousToken === undefined) delete process.env.ML_PHONE_TOKEN; else process.env.ML_PHONE_TOKEN = previousToken;
  }
});

test('real agent loop and V4 persistence survive Stop and accept the next human turn', { skip: !sdk, timeout: 15000 }, async () => {
  const require = createRequire(pathToFileURL(join(resolve(sdk), 'package.json')));
  const load = name => import(pathToFileURL(require.resolve(name)).href);
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: Llm, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const { default: Sessions, SessionId } = await load('@deepseek-ai/dsh-session');
  const { default: Agents } = await load('@deepseek-ai/dsh-agent');
  const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
  const { default: Loop } = await load('@deepseek-ai/dsh-agent-loop');
  const { default: Prompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  const { default: Persistence } = await load('@deepseek-ai/dsh-session-persistence-jsonl');
  const ctx = new Context(), requests = [];
  function call(id, name, args) {
    const json = JSON.stringify(args);
    return [{ type: 'block-start', index: 0, blockType: 'tool-call' },
      { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: json },
      { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: json } },
      { type: 'finish', reason: { kind: 'tool-calls' } }];
  }
  function text(value) { return [{ type: 'block-start', index: 0, blockType: 'text' }, { type: 'text-delta', index: 0, text: value },
    { type: 'block-end', index: 0, block: { type: 'text', text: value } }, { type: 'finish', reason: { kind: 'stop' } }]; }
  const script = [call('start', 'phone_begin', { purpose: 'Only the fixture' }), call('observe', 'phone_observe', {}), text('用户已停止手机操作。'), text('新消息正常处理。')];
  class Adapter extends LlmAdapter {
    async resolveModel(provider, model) { return { provider, id: model, name: model }; }
    async *stream(options) {
      requests.push(options); const next = script.shift(); assert.ok(next, 'No unexpected extra model turn');
      for (const chunk of next) yield chunk;
    }
  }
  try {
    for (const plugin of [Llm, Sessions, Prompt, Tools, Agents, Projections]) await ctx.plugin(plugin);
    await ctx.plugin(Persistence, { root: mkdtempSync(resolve('.tools/phone-probe/tests/loop-v4-')), compression: 'none' });
    await ctx.plugin(Loop, { agents: [] }); ctx.llm.registerAdapter(['fixture'], new Adapter());
    await ctx.plugin({ name: 'phone-loop-fixture', inject: ['tools'], apply(inner) {
      installTools(inner, async req => req.op === 'begin' ? { status: 'active', lease: 'test' } : { status: 'user_cancelled' });
    } });
    const agent = await ctx.agentLoop.create(SessionId('phone-loop-test'), { provider: 'fixture', model: 'fixture' });
    const send = text => agent.followup(createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text }] }));
    send('Operate the fixture only'); await agent.whenIdle();
    let ends = agent.session.snapshotEvents().filter(event => event.type === 'turn/end');
    assert.equal(ends.length, 1); assert.equal(ends[0].data.reason.kind, 'completed');
    assert.ok(JSON.stringify(requests.at(-1)).includes('user_cancelled'), 'The model must receive the cancellation');
    send('A new message after Stop'); await agent.whenIdle();
    ends = agent.session.snapshotEvents().filter(event => event.type === 'turn/end');
    assert.equal(ends.length, 2); assert.ok(ends.every(event => event.data.reason.kind === 'completed'));
    await ctx.sessionPersistence.flush();
    const reader = await ctx.sessionPersistence.open(agent.id, 'read');
    assert.equal((await reader.read()).events.filter(event => event.type === 'turn/end').length, 2); await reader.close();
  } finally { await ctx.fiber.dispose(); }
});
