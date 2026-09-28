// Minimal standards fallbacks for the emulator's older WebView. No native bridge,
// network hooks, permission changes or edits to the official Harness package.
(() => {
  'use strict';
  if (typeof Promise.withResolvers !== 'function') {
    Object.defineProperty(Promise, 'withResolvers', {
      configurable: true, writable: true,
      value: function withResolvers() {
        let resolve, reject;
        const promise = new this((yes, no) => { resolve = yes; reject = no; });
        return { promise, resolve, reject };
      }
    });
  }
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    Object.defineProperty(AbortSignal, 'any', {
      configurable: true, writable: true,
      value: function any(signals) {
        if (signals == null || typeof signals[Symbol.iterator] !== 'function') throw new TypeError('Expected an iterable of AbortSignals');
        const inputs = Array.from(signals);
        for (const signal of inputs) if (!(signal instanceof AbortSignal)) throw new TypeError('Expected an AbortSignal');
        const controller = new AbortController();
        for (const signal of inputs) {
          if (signal.aborted) { controller.abort(signal.reason); return controller.signal; }
        }
        const listeners = new Map();
        const cleanup = () => {
          for (const [signal, listener] of listeners) signal.removeEventListener('abort', listener);
          listeners.clear();
        };
        controller.signal.addEventListener('abort', cleanup, { once: true });
        for (const signal of inputs) {
          if (listeners.has(signal)) continue;
          const listener = () => controller.abort(signal.reason);
          listeners.set(signal, listener);
          signal.addEventListener('abort', listener, { once: true });
        }
        return controller.signal;
      }
    });
  }
})();
