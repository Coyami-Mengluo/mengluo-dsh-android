/* Mirror the official page's existing event carrier. Only explicit human
 * question answers use that same live client. After the Host acknowledges one,
 * deliver its cancel lifecycle locally too (the Host excludes the replying
 * client) so the official form closes. Approvals stay in the official UI. */
(() => {
  'use strict';
  if (window !== window.top || window.__mengluoTaskEvents || !window.MengLuoTaskEvents || typeof window.WebSocket !== 'function') return;
  const paths = new Set(['/api/remote.mux', '/api/events.host', '/api/events.mux']);
  const validId = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
  const post = value => { try { window.MengLuoTaskEvents.postMessage(JSON.stringify(value)); } catch (_) {} };
  const NativeSocket = window.WebSocket;
  const nativeFetch = window.fetch;
  const connections = new Map();
  const clients = new Map();
  const questions = new Map();
  const keyFor = (channel, request) => channel + '\n' + request;
  const clearQuestions = channel => {
    for (const [key, value] of questions) if (value.channel === channel) questions.delete(key);
  };
  const resolved = (channel, request) => {
    questions.delete(keyFor(channel, request));
    post({ kind: 'resolved', channel, request });
  };
  let sequence = 0;
  const pageId = Math.random().toString(36).slice(2);
  const emit = (channel, kind, session, extra = {}) => {
    if (validId(session)) post({ channel, kind, session, ...extra });
  };
  const text = (value, max, required = false) => typeof value === 'string' && value.length <= max && (!required || value.trim().length > 0);
  function questionItems(value) {
    if (!Array.isArray(value) || !value.length || value.length > 4) return;
    const ids = new Set(), result = [];
    for (const item of value) {
      if (!item || !validId(item.id) || /[\u0000-\u001f\u007f]/.test(item.id) || ids.has(item.id)
          || !text(item.question, 1000, true) || (item.header !== undefined && !text(item.header, 80))
          || (item.detail !== undefined && !text(item.detail, 8000))
          || (item.multiSelect !== undefined && typeof item.multiSelect !== 'boolean')) return;
      ids.add(item.id);
      const copy = { id: item.id, question: item.question };
      for (const name of ['header', 'detail', 'multiSelect']) if (item[name] !== undefined) copy[name] = item[name];
      if (item.options !== undefined) {
        if (!Array.isArray(item.options) || item.options.length > 8) return;
        const labels = new Set();
        copy.options = [];
        for (const option of item.options) {
          if (!option || !text(option.label, 200, true) || labels.has(option.label)
              || (option.description !== undefined && !text(option.description, 500))) return;
          labels.add(option.label);
          copy.options.push({ label: option.label, ...(option.description === undefined ? {} : { description: option.description }) });
        }
      }
      // Intents affect presentation only. Preserve the full plan detail and its
      // approving label; requests beyond this renderer's limits stay on the page.
      if (item.intent !== undefined) {
        if (!item.intent || item.intent.kind !== 'plan-review' || !text(item.intent.approve, 200, true)
            || !copy.options?.some(option => option.label === item.intent.approve) || item.detail === undefined
            || (item.intent.callId !== undefined && !validId(item.intent.callId))) return;
        copy.intent = { kind: 'plan-review', approve: item.intent.approve,
          ...(item.intent.callId === undefined ? {} : { callId: item.intent.callId }) };
      }
      result.push(copy);
    }
    return result;
  }
  function answerItems(value, items) {
    if (!Array.isArray(value) || value.length !== items.length) return;
    const ids = new Set(), result = [];
    for (const answer of value) {
      const item = items.find(item => item.id === answer?.id);
      if (!item || ids.has(item.id) || !Array.isArray(answer.selected) || answer.selected.length > 8
          || (answer.custom !== undefined && !text(answer.custom, 2000))) return;
      ids.add(item.id);
      const selected = answer.selected;
      if (new Set(selected).size !== selected.length || selected.some(label => !item.options?.some(option => option.label === label))
          || (!selected.length && !answer.custom?.trim())
          || (!item.multiSelect && (selected.length > 1 || (selected.length && answer.custom?.trim())))) return;
      result.push({ id: item.id, selected: [...selected], ...(answer.custom === undefined ? {} : { custom: answer.custom }) });
    }
    return result;
  }
  async function acknowledgement(response, rpcId) {
    if (!response.ok || response.redirected) return false;
    const reader = response.clone().body?.getReader();
    if (!reader) return false;
    let body = '', size = 0;
    const decoder = new TextDecoder();
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 8192) { void reader.cancel().catch(() => {}); return false; }
        body += decoder.decode(value, { stream: true });
      }
      const ack = JSON.parse(body + decoder.decode());
      return ack.type === 'server-response' && ack.rpcId === rpcId && ack.result?.ok === true;
    } finally { reader.releaseLock(); }
  }
  window.__mengluoReplyQuestion = message => {
    if (!message || !validId(message.channel) || !validId(message.request) || typeof nativeFetch !== 'function') return false;
    const key = keyFor(message.channel, message.request), pending = questions.get(key);
    if (!pending || pending.busy || connections.get('/api/remote.mux') !== pending.socket
        || clients.get(pending.client) !== pending.channel || !pending.live()) return false;
    const answers = answerItems(message.answers, pending.items);
    if (!answers) return false;
    pending.busy = true;
    const rpcId = 'mengluo-question-' + pageId + '-' + (++sequence);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    const failed = () => {
      if (questions.get(key) !== pending) return;
      pending.busy = false;
      post({ kind: 'question-failed', channel: pending.channel, request: pending.request });
    };
    try {
      const promise = Reflect.apply(nativeFetch, window, ['/api/$events/result', {
        method: 'POST', credentials: 'same-origin', redirect: 'error', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({ type: 'client-request', rpcId, method: '$events/result', payload: { args: {
          clientId: pending.client, eventId: pending.request, outcome: { kind: 'result', value: { answers } }
        } } })
      }]);
      void Promise.resolve(promise).then(async response => {
        if (!await acknowledgement(response, rpcId)) { failed(); return; }
        if (questions.get(key) !== pending || !pending.live() || clients.get(pending.client) !== pending.channel) return;
        // Gateway 0.2.0-rc.2 removes the replying client's delivery before
        // broadcasting cancel. Its official client treats cancel as a local
        // AbortSignal, not a second answer. Reproduce only that confirmed
        // lifecycle for this live stream; never fabricate a tool response.
        try {
          pending.socket.dispatchEvent(new MessageEvent('message', { origin: location.origin, data: JSON.stringify({
            type: 'item', streamId: pending.stream, value: { type: 'cancel', eventId: pending.request }
          }) }));
        } catch (_) { /* Native reminder can still close if a renderer rejects dispatch. */ }
        if (questions.get(key) === pending) resolved(pending.channel, pending.request);
      }).catch(failed).finally(() => clearTimeout(timer));
    } catch (_) { clearTimeout(timer); failed(); }
    return true;
  };
  function observe(socket, url) {
    const channel = pageId + ':' + (++sequence);
    let stream, currentClient;
    const route = url.pathname;
    // One current socket per known route; late messages from retired connections are ignored.
    const previous = connections.get(route);
    if (previous) for (const value of questions.values()) if (value.socket === previous) clearQuestions(value.channel);
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
            currentClient = null;
            clearQuestions(channel);
            for (const [client, owner] of clients) if (owner === channel) clients.delete(client);
            post({ kind: 'disconnected', channel });
            return;
          }
          if (envelope.type !== 'item' || !validId(envelope.streamId)) return;
          const frame = envelope.value;
          if (!frame || typeof frame !== 'object') return;
          if (frame.type === 'ready' && validId(frame.clientId)) {
            if (stream !== undefined && (stream !== envelope.streamId || currentClient !== frame.clientId)) {
              clearQuestions(channel);
              for (const [client, owner] of clients) if (owner === channel) clients.delete(client);
              post({ kind: 'reset', channel, route });
            }
            stream = envelope.streamId;
            currentClient = frame.clientId;
            clients.set(frame.clientId, channel);
            while (clients.size > 3) clients.delete(clients.keys().next().value);
            post({ kind: 'connected', channel });
            return;
          }
          if (stream !== envelope.streamId) return;
          if (frame.type === 'emit' && Array.isArray(frame.args)) {
            const [session, running] = frame.args;
            if ((frame.event === 'api-session/error' || frame.event === 'api-session/removed') && validId(session)) {
              for (const [key, pending] of questions) if (pending.channel === channel && pending.session === session) questions.delete(key);
            }
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
            if (frame.event === 'user-questions/request' && validId(frame.agentId) && currentClient && typeof nativeFetch === 'function') {
              const items = questionItems(frame.request?.questions);
              const key = keyFor(channel, frame.eventId);
              if (items && questions.size < 32 && !questions.has(key)) {
                const owningStream = stream, client = currentClient;
                questions.set(key, { channel, session: frame.agentId, request: frame.eventId, items, socket, client, stream: owningStream, busy: false,
                  live: () => connections.get(route) === socket && stream === owningStream && currentClient === client });
                emit(channel, 'question', frame.agentId, { request: frame.eventId, questions: items });
              }
            }
          } else if (frame.type === 'cancel' && validId(frame.eventId)) resolved(channel, frame.eventId);
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
      clearQuestions(channel);
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
  // Observe the page's own successful replies too, so another form cannot answer
  // the same pending request. The official fetch arguments/promise stay intact.
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
        const key = keyFor(channel, result.eventId), pending = questions.get(key);
        if (pending) pending.busy = true;
        void promise.then(async response => {
          if (await acknowledgement(response, envelope.rpcId) && clients.get(result.clientId) === channel) resolved(channel, result.eventId);
        }).catch(() => {}).finally(() => {
          if (pending && questions.get(key) === pending) pending.busy = false;
        });
      } catch (_) {}
      return promise;
    }
  });
  window.__mengluoTaskEvents = true;
})();
