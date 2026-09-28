import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, createWriteStream } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { pipeline } from 'node:stream/promises'
import { spawnSync } from 'node:child_process'

export const root = resolve(import.meta.dirname, '..')
export const cache = resolve(root, '.tools', 'downloads')
mkdirSync(cache, { recursive: true })

/** Honor Windows static system proxy when enabled; no persistent network changes. */
export function proxyEnvironment() {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    '$p=Get-ItemProperty -LiteralPath "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"; if($p.ProxyEnable -eq 1){ $p.ProxyServer }'],
  { encoding: 'utf8', windowsHide: true })
  if (result.status !== 0) throw new Error('Unable to read Windows system proxy')
  const proxy = result.stdout.trim()
  if (!proxy) return { ...process.env }
  if (!/^(?:https?:\/\/)?(?:127\.0\.0\.1|localhost):\d+$/u.test(proxy)) throw new Error('Unsupported proxy setting; explicit review required')
  const url = proxy.startsWith('http') ? proxy : `http://${proxy}`
  return { ...process.env, HTTP_PROXY: url, HTTPS_PROXY: url, NODE_USE_ENV_PROXY: '1', NO_PROXY: '127.0.0.1,localhost' }
}

export async function download(url, name, digest, algorithm = 'sha256') {
  if (!url.startsWith('https://')) throw new Error('Downloads require HTTPS')
  const target = resolve(cache, name)
  if (dirname(target) !== cache) throw new Error('Invalid download filename')
  const valid = () => existsSync(target) && (!digest || createHash(algorithm).update(readFileSync(target)).digest('hex') === digest)
  if (valid()) return target
  console.log(`Downloading ${name}`)
  const temporary = `${target}.partial`
  for (let attempt = 1; ; attempt++) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(900_000) })
      if (!response.ok || !response.body) throw new Error(`Download failed: ${name}, HTTP ${response.status}`)
      await pipeline(response.body, createWriteStream(temporary))
      break
    } catch (error) {
      if (attempt >= 3) throw error
      console.log(`Retrying ${name} after interrupted transfer (${attempt}/3)`)
    }
  }
  const actual = createHash(algorithm).update(readFileSync(temporary)).digest('hex')
  if (digest && actual !== digest) throw new Error(`Checksum mismatch: ${name}`)
  renameSync(temporary, target)
  console.log(`Verified ${name}: ${algorithm} ${actual}`)
  return target
}

export function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, env: proxyEnvironment(), windowsHide: true, stdio: 'inherit', timeout: 900_000, ...options })
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message || result.status}`)
}
