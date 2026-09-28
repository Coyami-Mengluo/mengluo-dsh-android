import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';
const script = readFileSync(new URL('../app/src/main/assets/web-compat.js', import.meta.url), 'utf8');
function legacy() {
  function LegacySignal() { throw new TypeError('Illegal constructor'); }
  LegacySignal.prototype = AbortSignal.prototype;
  const context = createContext({ AbortController, AbortSignal: LegacySignal });
  runInContext('delete Promise.withResolvers', context);
  runInContext(script, context);
  return context;
}
test('promise resolve/reject and subclass are preserved', async () => {
  const context = legacy();
  const resolved = runInContext('const a=Promise.withResolvers(); a.resolve(42); a.promise', context);
  assert.equal(await resolved, 42);
  const rejected = runInContext('const b=Promise.withResolvers(); b.reject("test"); b.promise', context);
  await assert.rejects(rejected, reason => reason === 'test');
  assert.equal(runInContext('class Child extends Promise {}; Child.withResolvers().promise instanceof Child', context), true);
});
test('abort preserves the first reason and does not abort its source signals', () => {
  const context = legacy(); const first = new AbortController(), second = new AbortController();
  const combined = context.AbortSignal.any([first.signal, first.signal, second.signal]);
  second.abort('second'); first.abort('first');
  assert.equal(combined.reason, 'second');
  const waiting = new AbortController(); const already = new AbortController(); already.abort('ready');
  assert.equal(context.AbortSignal.any([waiting.signal, already.signal]).reason, 'ready');
  assert.equal(waiting.signal.aborted, false);
  assert.equal(context.AbortSignal.any([]).aborted, false);
});
test('invalid signals are rejected and native implementations are untouched', () => {
  const context = legacy();
  assert.throws(() => context.AbortSignal.any([{}]), /AbortSignal/);
  assert.throws(() => context.AbortSignal.any(12), /iterable/);
  const native = createContext({ AbortController, AbortSignal });
  const promiseMethod = runInContext('Promise.withResolvers', native);
  runInContext(script, native);
  assert.equal(runInContext('Promise.withResolvers', native), promiseMethod);
  assert.equal(native.AbortSignal.any, AbortSignal.any);
});
