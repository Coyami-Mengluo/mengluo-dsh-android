import { existsSync, mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { download, root, run } from './download.mjs'

const tools = join(root, '.tools')
const sdk = join(tools, 'android-sdk')
mkdirSync(sdk, { recursive: true })
const entries = [
  ['https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip', 'commandline-tools.zip', '54a582f3bf73e04253602f2d1c80bd5868aac115', 'sha1', join(sdk, 'cmdline-tools'), 'cmdline-tools', '19.0'],
  ['https://dl.google.com/android/repository/platform-35_r02.zip', 'platform-35.zip', '0bb560a90a7a2cbd0dd8348224d518b638fe7949', 'sha1', join(sdk, 'platforms'), 'android-35', 'android-35'],
  ['https://dl.google.com/android/repository/build-tools_r35_windows.zip', 'build-tools-35.zip', 'af059bb67cf7786f45ee0db85e2d24985df1b4b6', 'sha1', join(sdk, 'build-tools'), 'android-15', '35.0.0'],
  ['https://downloads.gradle.org/distributions/gradle-8.9-bin.zip', 'gradle-8.9-bin.zip', 'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab', 'sha256', tools, 'gradle-8.9', 'gradle-8.9'],
]
await Promise.all(entries.map(async ([url, name, hash, algo, destination, extracted, finalName]) => {
  if (existsSync(join(destination, finalName))) return
  const archive = await download(url, name, hash, algo)
  mkdirSync(destination, { recursive: true })
  run('tar.exe', ['-xf', archive, '-C', destination])
  if (extracted !== finalName) renameSync(join(destination, extracted), join(destination, finalName))
}))
writeFileSync(join(root, 'local.properties'), `sdk.dir=${sdk.replaceAll('\\', '/')}\n`)
console.log('Pinned Android SDK and Gradle prepared; SDK license acceptance is handled by sdkmanager.')
