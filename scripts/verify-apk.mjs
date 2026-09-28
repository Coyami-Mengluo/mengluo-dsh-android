import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import assert from 'node:assert/strict'
import { root } from './download.mjs'

const abi = process.argv[2] ?? 'arm64-v8a'
assert.ok(['x86_64', 'arm64-v8a'].includes(abi))
const arm = abi === 'arm64-v8a'
const variant = process.argv[3] ?? 'debug'
assert.ok(['debug', 'release'].includes(variant))
const apk = resolve(root, `app/${arm ? 'build-arm64' : 'build'}/outputs/apk/${variant}/app-${variant}.apk`)
const lock = JSON.parse(readFileSync(resolve(root, arm ? 'runtime-lock.arm64-v8a.json' : 'runtime-lock.json'), 'utf8'))
const digest = bytes => createHash('sha256').update(bytes).digest('hex')
function tar(args, input) {
  const output = spawnSync('tar.exe', args, { input, maxBuffer: 256 * 1024 * 1024, windowsHide: true })
  if (output.status !== 0) throw new Error(output.stderr.toString())
  return output.stdout
}
function elf(bytes, name, android) {
  assert.equal(bytes.readUInt32BE(0), 0x7f454c46, `${name}: ELF`)
  assert.equal(bytes[4], 2, `${name}: 64-bit`)
  assert.equal(bytes[5], 1, `${name}: little endian`)
  assert.equal(bytes.readUInt16LE(18), arm ? 183 : 62, `${name}: target machine`)
  const start = Number(bytes.readBigUInt64LE(32)), entry = bytes.readUInt16LE(54), count = bytes.readUInt16LE(56)
  const loadAlignments = []; let interpreter = null
  for (let i = 0; i < count; i++) {
    const offset = start + i * entry, type = bytes.readUInt32LE(offset)
    if (type === 1) loadAlignments.push(Number(bytes.readBigUInt64LE(offset + 48)))
    if (type === 3) {
      const position = Number(bytes.readBigUInt64LE(offset + 8)), length = Number(bytes.readBigUInt64LE(offset + 32))
      interpreter = bytes.subarray(position, position + length).toString().replace(/\0$/u, '')
    }
  }
  assert.ok(loadAlignments.length, `${name}: LOAD segments`)
  if (android && interpreter) assert.equal(interpreter, '/system/bin/linker64')
  console.log(JSON.stringify({ file: name, abi, interpreter, loadAlignments, aligned16K: loadAlignments.every(value => value >= 16384) }))
}
const entries = tar(['-tf', apk]).toString().split(/\r?\n/u).filter(Boolean)
const native = entries.filter(name => name.startsWith('lib/') && name.endsWith('.so'))
assert.deepEqual(native.sort(), lock.native.map(item => `lib/${abi}/${item.name}`).sort(), 'Only the selected ABI can be packaged')
assert.ok(!entries.some(name => /(?:\.keystore|\.jks|shared_prefs|\/profile\/|\/workspace\/)/u.test(name)), 'No local private state or signing key in APK')
const manifest = JSON.parse(tar(['-xOf', apk, 'assets/runtime/manifest.json']))
assert.deepEqual(manifest, lock)
for (const item of lock.native) {
  const bytes = tar(['-xOf', apk, `lib/${abi}/${item.name}`])
  assert.equal(digest(bytes), item.sha256)
  elf(bytes, item.name, true)
}
for (const item of lock.assets) {
  assert.ok(!entries.includes(`assets/runtime/${item.name}`), 'Base archives must be downloaded on demand, not bundled')
  assert.ok(item.url.startsWith('https://') && /^[a-f0-9]{64}$/.test(item.sha256))
}
assert.ok(entries.includes('assets/runtime-verify.mjs'))
assert.ok(entries.includes('assets/web-compat.js'))
assert.ok(entries.includes('assets/permission-hint.js'))
for (const name of ['proot', 'libtalloc', 'libandroid-shmem']) assert.ok(entries.includes(`assets/notices/${name}-copyright.txt`))
console.log(`APK structure, checksums and ${abi} runtime verified. SHA256 ${digest(readFileSync(apk))}`)
