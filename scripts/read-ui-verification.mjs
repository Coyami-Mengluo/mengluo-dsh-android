import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { root } from './download.mjs'
const directory = join(root, '.tools/verification')
mkdirSync(directory, { recursive: true })
for (const name of ['home-portrait', 'home-landscape', 'menu-portrait', 'menu-landscape', 'home-dark', 'projects-browser', 'menu-ball-collapsed', 'permission-bubble']) {
  const result = spawnSync('C:/Program Files/Netease/MuMu Player 12/nx_main/adb.exe', [
    '-s', '127.0.0.1:16384', 'exec-out', 'run-as', 'ai.mengluo.dsh.android', 'cat', `cache/ui-verification/${name}.png`
  ], { windowsHide: true, maxBuffer: 12 * 1024 * 1024 })
  if (result.status !== 0 || !result.stdout.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) throw new Error(`Missing test screenshot: ${name}`)
  const output = join(directory, `${name}.png`)
  writeFileSync(output, result.stdout)
  console.log(output)
}
