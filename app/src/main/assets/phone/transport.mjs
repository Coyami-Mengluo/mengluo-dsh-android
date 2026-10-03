import http from 'node:http';
import { randomUUID } from 'node:crypto';

// Never use fetch/global proxy agents here: the capability must stay on device loopback.
export function createTransport(endpoint, token) {
  const url = new URL(endpoint);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.pathname !== '/v1'
      || url.username || url.password || url.search || url.hash || !/^[a-f0-9]{64}$/.test(token)) throw new Error('Invalid native phone bridge');
  return (message, signal) => new Promise((resolve, reject) => {
    const body = JSON.stringify({ ...message, requestId: randomUUID() });
    const request = http.request(url, { method: 'POST', agent: false, signal,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), Authorization: `Bearer ${token}` } }, response => {
      let size = 0; const chunks = [];
      response.on('data', chunk => { size += chunk.length; if (size > 3_000_000) { request.destroy(new Error('Native phone response too large')); return; } chunks.push(chunk); });
      response.on('error', reject);
      response.on('end', () => {
        try {
          if (response.statusCode !== 200) throw new Error('Native phone bridge unavailable');
          const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (!value || typeof value.status !== 'string') throw new Error('Invalid native phone result');
          resolve(value);
        } catch { reject(new Error('Invalid native phone result')); }
      });
    });
    request.setTimeout(105_000, () => request.destroy(new Error('Native phone confirmation timed out')));
    request.on('error', () => reject(new Error('Native phone request interrupted; do not repeat consequential actions automatically')));
    request.end(body);
  });
}
