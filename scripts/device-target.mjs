import { readFileSync } from 'node:fs'

const config = JSON.parse(readFileSync(new URL('../.tools/test-device.json', import.meta.url), 'utf8'))
if (typeof config.adb !== 'string' || !config.adb.trim()) throw new Error('Set adb in .tools/test-device.json')
if (typeof config.serial !== 'string' || !config.serial.trim() || /\s/u.test(config.serial))
  throw new Error('Set an explicit device serial in .tools/test-device.json')
if (!['arm64-v8a', 'x86_64'].includes(config.abi)) throw new Error('Set a supported ABI in .tools/test-device.json')

export const { adb, serial, abi } = config
