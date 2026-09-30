// Android directory-picker starting point. Explicit paths and all other RPCs stay untouched.
(() => {
  'use strict';
  if (window !== window.top || window.__mengluoDirectoryDefault || typeof window.fetch !== 'function') return;
  const directory = "__MENG_LUO_STORAGE_ROOT__";
  if (!directory.startsWith('/storage/') || /[\x00-\x1f]/.test(directory)) return;
  window.__mengluoDirectoryDefault = true;
  const original = window.fetch;
  const emptyObject = value => value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0;
  window.fetch = function(input, init) {
    let next = init;
    try {
      // Known official carrier only. Never read a Request stream or touch credentials/responses.
      const address = typeof input === 'string' || input instanceof URL ? new URL(input, location.href) : null;
      if (address && address.origin === location.origin
          && (address.pathname === '/api/host.listDirectory' || address.pathname === '/api/directoryPicker/list')
          && init?.method?.toUpperCase() === 'POST' && typeof init.body === 'string' && init.body.length <= 8192) {
        const request = JSON.parse(init.body);
        if (request?.type === 'client-request' && typeof request.rpcId === 'string') {
          let payload;
          if (address.pathname === '/api/host.listDirectory' && request.method === 'host.listDirectory' && emptyObject(request.payload))
            payload = { path: directory };
          // Harness 0.2's Remote API wraps named parameters in args.
          if (address.pathname === '/api/directoryPicker/list' && request.method === 'directoryPicker/list'
              && request.payload && Object.keys(request.payload).length === 1 && emptyObject(request.payload.args))
            payload = { args: { path: directory } };
          if (payload) next = { ...init, body: JSON.stringify({ ...request, payload }) };
        }
      }
    } catch { /* An unknown request shape keeps the official behavior. */ }
    return original.call(this, input, next);
  };
})();
