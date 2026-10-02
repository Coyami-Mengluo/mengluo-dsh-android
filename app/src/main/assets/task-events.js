/* Observe the official page's existing event carrier. Never open a second client,
 * send a reply, consume an event, or change approval/question handling. */
(() => {
  'use strict';
  if (window !== window.top || window.__mengluoTaskEvents || !window.MengLuoTaskEvents || typeof window.WebSocket !== 'function') return;
  const paths = new Set(['/api/remote.mux', '/api/events.host', '/api/events.mux']);
  const validId = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
  const post = value => { try { window.MengLuoTaskEvents.postMessage(JSON.stringify(value)); } catch (_) {} };
  const NativeSocket = window.WebSocket;
  const connections = new Map();
  const clients = new Map();
  let sequence = 0;
  const pageId = Math.random().toString(36).slice(2);
  const emit = (channel, kind, session, extra = {}) => {
    if (validId(session)) post({ channel, kind, session, ...extra });
  };
  function observe(socket, url) {
    const channel = pageId + ':' + (++sequence);
    let stream;
    const route = url.pathname;
    // One current socket per known route; late messages from retired connections are ignored.
    connections.set(route, socket);
    post({ kind: 'reset', channel, route });
    if (route !== '/api/remote.mux') socket.addEventListener('open', () => {
      if (connections.get(route) === socket) post({ kind: 'connected', channel });
    }, { once: true });
    socket.addEventListener('message', event => {
      if (connections.get(route) !== socket || typeof event.data !== 'string' || event.data.length > 1024 * 1024) return;
      try {
        const envelope = JSON.parse(event.data);
        if (!envelope || typeof envelope !== 'object') return;
        if (route === '/api/remote.mux') {
          if ((envelope.type === 'end' || envelope.type === 'error') && stream && envelope.streamId === stream) {
            stream = null;
            for (const [client, owner] of clients) if (owner === channel) clients.delete(client);
            post({ kind: 'disconnected', channel });
            return;
          }
          if (envelope.type !== 'item' || !validId(envelope.streamId)) return;
          const frame = envelope.value;
          if (!frame || typeof frame !== 'object') return;
          if (frame.type === 'ready' && validId(frame.clientId)) {
            if (stream !== undefined && stream !== envelope.streamId) post({ kind: 'reset', channel, route });
            stream = envelope.streamId;
            clients.set(frame.clientId, channel);
            while (clients.size > 3) clients.delete(clients.keys().next().value);
            post({ kind: 'connected', channel });
            return;
          }
          if (stream !== envelope.streamId) return;
          if (frame.type === 'emit' && Array.isArray(frame.args)) {
            const [session, running] = frame.args;
            if (frame.event === 'api-session/status' && typeof running === 'boolean') emit(channel, 'status', session, { running });
            else if (frame.event === 'api-session/error') emit(channel, 'error', session);
            else if (frame.event === 'api-session/removed') emit(channel, 'removed', session);
            else if (frame.event === 'api-session/added' && session && typeof session === 'object') {
              // Summary/upsert is not a completion event, especially during reconnect.
              emit(channel, 'summary', session.sessionId, { subagent: !!session.parentSessionId || session.origin === 'subagent' });
            }
          } else if (frame.type === 'waterfall' && validId(frame.eventId)
              && (frame.event === 'approval/request' || frame.event === 'user-questions/request')) {
            emit(channel, 'waiting', frame.agentId, { request: frame.eventId });
          } else if (frame.type === 'cancel' && validId(frame.eventId)) post({ kind: 'resolved', channel, request: frame.eventId });
        } else {
          if (envelope.type !== 'server-request') return;
          const frame = envelope.payload;
          if (!frame || typeof frame !== 'object') return;
          if (route === '/api/events.host') {
            if (frame.type === 'host/session-status' && typeof frame.running === 'boolean') emit(channel, 'status', frame.sessionId, { running: frame.running });
            else if (frame.type === 'host/agent-error') emit(channel, 'error', frame.sessionId);
            else if (frame.type === 'host/session-removed') emit(channel, 'removed', frame.sessionId);
            else if (frame.type === 'host/session-added') emit(channel, 'summary', frame.sessionId, { subagent: !!frame.parentSessionId || frame.origin === 'subagent' });
          } else if (frame.type === 'approval/requested' && validId(frame.approvalId)) emit(channel, 'waiting', frame.sessionId, { request: frame.approvalId });
          else if (frame.type === 'question/requested' && validId(envelope.rpcId)) emit(channel, 'waiting', frame.sessionId, { request: envelope.rpcId });
          else if (frame.type === 'approval/resolved' && validId(frame.approvalId)) post({ kind: 'resolved', channel, request: frame.approvalId });
          else if (frame.type === 'question/resolved' && validId(frame.questionRpcId)) post({ kind: 'resolved', channel, request: frame.questionRpcId });
        }
      } catch (_) { /* Malformed/unknown/new protocol frames must not affect the official UI. */ }
    });
    socket.addEventListener('close', () => {
      if (connections.get(route) !== socket) return;
      connections.delete(route);
      for (const [client, owner] of clients) if (owner === channel) clients.delete(client);
      post({ kind: 'disconnected', channel });
    }, { once: true });
  }
  window.WebSocket = new Proxy(NativeSocket, {
    construct(target, args, newTarget) {
      const socket = Reflect.construct(target, args, newTarget);
      try {
        const url = new URL(socket.url, location.href);
        if (url.protocol === 'ws:' && location.protocol === 'http:' && url.host === location.host
            && url.hostname === '127.0.0.1' && !url.username && !url.password && paths.has(url.pathname)) observe(socket, url);
      } catch (_) {}
      return socket;
    }
  });
  // The new gateway excludes the replying client from its cancellation broadcast.
  // Observe that client's successful reply, without modifying, delaying or sending it.
  if (typeof window.fetch === 'function') window.fetch = new Proxy(window.fetch, {
    apply(target, receiver, args) {
      const promise = Reflect.apply(target, receiver, args);
      try {
        const [input, init] = args;
        if ((typeof input !== 'string' && !(input instanceof URL)) || init?.method?.toUpperCase() !== 'POST'
            || typeof init.body !== 'string' || init.body.length > 65536) return promise;
        const url = new URL(input, location.href);
        if (url.origin !== location.origin || url.pathname !== '/api/$events/result') return promise;
        const envelope = JSON.parse(init.body), result = envelope?.payload?.args;
        if (envelope.type !== 'client-request' || envelope.method !== '$events/result' || !validId(envelope.rpcId)
            || !validId(result?.eventId) || !['result', 'rejected'].includes(result?.outcome?.kind)) return promise;
        const channel = clients.get(result.clientId);
        if (!channel) return promise;
        void promise.then(async response => {
          if (!response.ok || response.redirected) return;
          const copy = response.clone(), reader = copy.body?.getReader();
          if (!reader) return;
          let text = '', size = 0;
          const decoder = new TextDecoder();
          try {
            while (true) {
              const { value, done } = await reader.read();
              if (done) break;
              size += value.byteLength;
              if (size > 8192) { void reader.cancel().catch(() => {}); return; }
              text += decoder.decode(value, { stream: true });
            }
            text += decoder.decode();
            const ack = JSON.parse(text);
            if (ack.type === 'server-response' && ack.rpcId === envelope.rpcId && ack.result?.ok === true && clients.get(result.clientId) === channel)
              post({ kind: 'resolved', channel, request: result.eventId });
          } finally { reader.releaseLock(); }
        }).catch(() => {});
      } catch (_) {}
      return promise;
    }
  });
  window.__mengluoTaskEvents = true;
})();
