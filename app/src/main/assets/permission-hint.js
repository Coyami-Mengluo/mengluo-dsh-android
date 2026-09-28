// Read rendered error/assistant text only. No RPC, network interception, commands,
// permissions changes, or conversation contents passed to the native application.
((expectedOrigin) => {
  'use strict';
  if (window !== window.top || location.origin !== expectedOrigin || window.__mengluoPermissionHintWatching) return;
  window.__mengluoPermissionHintWatching = true;
  const selector = '[data-chat-flow-kind="assistant"], [data-tool][data-state="error"], [data-error="true"]';
  const ignored = 'script, style, textarea, input, [contenteditable="true"], [data-chat-flow-kind="user"], [data-variant="think"]';
  const pending = new Set();
  let timer = null, stopped = false;

  function sandboxUnavailable(text) {
    return /\bno sandbox backend is usable\b/i.test(text)
      || /\bno (?:usable|available) sandbox backend\b/i.test(text)
      || /\bsandbox\b[^\n]{0,250}\brefusing to run unconfined\b/i.test(text)
      || /(?:bash|shell|本机|本地)[^\n。]{0,40}沙箱(?:后端)?(?:不可用|无法使用)/i.test(text)
      || /(?:没有|无)(?:任何)?可用的?沙箱后端/.test(text);
  }

  function renderedText(element) {
    if (element.closest(ignored) || !element.getClientRects().length || getComputedStyle(element).visibility !== 'visible') return '';
    const assistant = !!element.closest('[data-chat-flow-kind="assistant"]');
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let text = '', node, count = 0;
    // Bound both traversal and retained text, including unusually large tool output.
    while ((node = walker.nextNode()) && count++ < 512 && text.length < 12000) {
      const parent = node.parentElement;
      if (!parent || parent.closest(ignored) || !parent.getClientRects().length || getComputedStyle(parent).visibility !== 'visible') continue;
      // Quoted code/examples in an answer aren't a live diagnostic.
      if (assistant && parent.closest('pre, code, blockquote')) continue;
      text += node.textContent.slice(0, 12000 - text.length);
    }
    return text;
  }

  function stop() {
    stopped = true; observer.disconnect(); pending.clear();
    if (timer !== null) clearTimeout(timer);
    timer = null;
    document.removeEventListener('visibilitychange', visible);
  }

  function flush() {
    timer = null;
    if (stopped || document.hidden) return;
    const candidates = Array.from(pending); pending.clear();
    for (const element of candidates) {
      if (element.isConnected && sandboxUnavailable(renderedText(element))) {
        stop();
        // A fixed informational event; never include chat text or error details.
        console.log('mengluo:sandbox-unavailable-help');
        return;
      }
    }
  }

  function queue(element) {
    if (!element || stopped || element.closest(ignored)) return;
    const owner = element.closest(selector);
    if (owner) {
      if (pending.size >= 32) pending.delete(pending.values().next().value);
      pending.add(owner);
    }
    const children = element.querySelectorAll(selector);
    for (let i = Math.max(0, children.length - 32); i < children.length; i++) {
      if (pending.size >= 32) pending.delete(pending.values().next().value);
      pending.add(children[i]);
    }
    if (pending.size && timer === null && !document.hidden) timer = setTimeout(flush, 600);
  }

  const observer = new MutationObserver(records => {
    for (const record of records) {
      // Only inspect changed diagnostic/chat nodes, not the entire transcript per token.
      const target = record.target.nodeType === 1 ? record.target : record.target.parentElement;
      if (target && target.closest(selector)) queue(target);
      if (record.type === 'attributes') queue(target);
      for (const added of record.addedNodes || []) queue(added.nodeType === 1 ? added : added.parentElement);
    }
  });
  function visible() { if (!document.hidden) queue(document.body); }
  if (!document.body) return;
  observer.observe(document.body, { subtree: true, childList: true, characterData: true,
    attributes: true, attributeFilter: ['data-state', 'data-error', 'data-chat-flow-kind'] });
  document.addEventListener('visibilitychange', visible);
  window.addEventListener('pagehide', stop, { once: true });
  queue(document.body);
})
