import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { root } from './download.mjs'
const adb = 'C:/Program Files/Netease/MuMu Player 12/nx_main/adb.exe'
const serial = '127.0.0.1:16384'
const result = spawnSync(adb, ['-s', serial, 'shell', 'dumpsys', 'window'], { encoding: 'utf8', windowsHide: true })
const focus = result.stdout.split('\n').filter(line => /mCurrentFocus=|mFocusedApp=/u.test(line))
if (!focus.some(line => line.includes('ai.mengluo.dsh.android/'))) throw new Error('Our test app is not in the foreground; screenshot not taken')
const capture = spawnSync(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { windowsHide: true, maxBuffer: 12 * 1024 * 1024 })
if (capture.status !== 0 || !capture.stdout.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) throw new Error('Invalid device screenshot')
const name = process.argv[2] ?? 'device.png'
if (!/^[a-z0-9-]+\.png$/u.test(name)) throw new Error('Invalid output name')
const directory = join(root, '.tools/verification'); mkdirSync(directory, { recursive: true })
const output = join(directory, name); writeFileSync(output, capture.stdout); console.log(output)
