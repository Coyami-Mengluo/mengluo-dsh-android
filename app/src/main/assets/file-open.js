// Android file-opening adapter. Known file controls only; ordinary links and directory rows stay official.
(() => {
  'use strict';
  if (window !== window.top || window.__mengluoFileOpen || !window.MengLuoFiles) return;
  window.__mengluoFileOpen = true;
  const absolute = value => typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    && value.length <= 4096 && !/[\x00-\x1f\x7f\\]/.test(value) && !value.split('/').includes('..') ? value : null;
  function resourcePath(value) {
    try {
      if (value.startsWith('file:///')) return absolute(decodeURIComponent(new URL(value).pathname));
      if (value.startsWith('dsh-resource://file/absolute/')) return absolute('/' + value.slice(29).split(/[?#]/)[0].split('/').map(decodeURIComponent).join('/'));
      if (value.startsWith('dsh-resource://file/session/')) {
        const segments = value.slice(28).split(/[?#]/)[0].split('/');
        segments.shift(); return absolute(segments.map(decodeURIComponent).join('/'));
      }
    } catch { /* Unknown/malformed addresses are left to Harness, never guessed. */ }
    return null;
  }
  function pathFor(element) {
    const anchor = element.closest('a[href]');
    if (anchor) return resourcePath(anchor.getAttribute('href') || '');
    const row = element.closest('[data-files-entry="file"][data-files-path]');
    if (row && element.closest('button')) {
      const path = row.getAttribute('data-files-path');
      const root = row.closest('[data-files-root]')?.getAttribute('data-files-root');
      return absolute(path) || (absolute(root) && absolute(root.replace(/\/$/, '') + '/' + path));
    }
    const card = element.closest('[data-presented-file]');
    const button = element.closest('button');
    if (card && button && (button.hasAttribute('title') || /^(打开|Open|预览|Preview)$/i.test(button.textContent.trim()))) {
      return absolute(card.querySelector('button[title]')?.getAttribute('title'));
    }
    return null;
  }
  const send = path => window.MengLuoFiles.postMessage(JSON.stringify(path ? { action: 'open', path } : { action: 'browse' }));
  document.addEventListener('click', event => {
    if (!event.isTrusted || !(event.target instanceof Element) || event.button !== 0) return;
    const path = pathFor(event.target);
    if (!path) return;
    event.preventDefault(); event.stopImmediatePropagation(); send(path);
  }, true);
  function update() {
    for (const panel of document.querySelectorAll('[data-textpreview-state]')) {
      const missing = panel.getAttribute('data-textpreview-state') === 'loading'
        && /文件资源服务不可用|The file resource service is unavailable\./.test(panel.textContent);
      if (!missing || panel.querySelector('[data-mengluo-system-open]')) continue;
      const button = document.createElement('button'); button.type = 'button'; button.dataset.mengluoSystemOpen = '';
      const path = resourcePath(panel.getAttribute('data-textpreview-url') || '');
      button.textContent = path ? '使用系统应用打开' : '从代码文件打开…';
      button.style.cssText = 'display:block;margin:16px auto;padding:10px 18px;border-radius:12px;border:1px solid var(--dsw-alias-border-l3,#888);background:var(--dsw-alias-button-elevated-fill,transparent);color:inherit;font:inherit;cursor:pointer';
      button.addEventListener('click', event => { if (event.isTrusted) send(path); });
      panel.append(button);
    }
  }
  let scheduled = false;
  const schedule = () => {
    if (scheduled) return; scheduled = true;
    requestAnimationFrame(() => { scheduled = false; update(); });
  };
  const start = () => { new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true, characterData: true }); update(); };
  if (document.body) start(); else document.addEventListener('DOMContentLoaded', start, { once: true });
})();
