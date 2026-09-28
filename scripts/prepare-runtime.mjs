import { mkdirSync, cpSync, writeFileSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { download, root, run } from './download.mjs'

const abi = process.argv[2] ?? 'x86_64'
if (!['x86_64', 'arm64-v8a'].includes(abi)) throw new Error('Supported ABIs: x86_64, arm64-v8a')
const arm = abi === 'arm64-v8a'
const stage = join(root, '.tools/runtime', abi)
const assets = join(stage, 'assets/runtime')
const libs = join(stage, 'jniLibs', abi)
mkdirSync(assets, { recursive: true })
mkdirSync(libs, { recursive: true })
// Checksums from Ubuntu SHA256SUMS, Node SHASUMS256.txt and the Termux architecture index.
const inputs = arm ? [
  ['https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz', 'ubuntu-base.tgz', '7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048'],
  ['https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.gz', 'node-linux.tgz', 'd28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f'],
] : [
  ['https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz', 'ubuntu-base.tgz', '6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9'],
  ['https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-x64.tar.gz', 'node-linux.tgz', 'f625d97cd707df4ff96254916fbc5ff014f09c09effe5a1e0ca8f6d41a8789d4'],
]
await Promise.all(inputs.map(async ([url, name, hash]) => {
  cpSync(await download(url, arm ? `${abi}-${name}` : name, hash), join(assets, name))
}))
const packages = arm ? [
  ['pool/main/p/proot/proot_5.1.107.95_aarch64.deb', '0a1b3d0f6ef76436c5ed924cd8e8f5a6b7186e99e1650eb2d9bc734e218a74cb'],
  ['pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb', 'ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da'],
  ['pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb', '0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6'],
] : [
  ['pool/main/p/proot/proot_5.1.107.95_x86_64.deb', 'f63ce9bd0d38715eae0163a3772f3395913587444c7ce7232091c6d359afe3c3'],
  ['pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb', '7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628'],
  ['pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb', 'ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797'],
]
const extraction = join(root, '.tools/termux')
const notices = join(stage, 'assets/notices')
mkdirSync(notices, { recursive: true })
const apache = spawnSync('tar.exe', ['-xOf', join(assets, 'ubuntu-base.tgz'), 'usr/share/common-licenses/Apache-2.0'], { windowsHide: true, maxBuffer: 1024 * 1024 })
if (apache.status !== 0 || apache.stdout.length < 1000) throw new Error('Missing Apache-2.0 license text')
writeFileSync(join(notices, 'APACHE-2.0.txt'), apache.stdout)
for (const [path, hash] of packages) {
  const archive = await download(`https://packages.termux.dev/apt/termux-main/${path}`, path.split('/').at(-1), hash)
  const directory = join(extraction, path.split('/').at(-1))
  mkdirSync(directory, { recursive: true })
  run('tar.exe', ['-xf', archive, '-C', directory])
  const name = path.split('/').at(-1).split('_')[0]
  // Extract individual regular files only: Windows does not need to create Unix symlinks.
  const mappings = path.includes('/proot/') ? [
    ['bin/proot', 'libproot.so'], ['libexec/proot/loader', 'libproot-loader.so'],
  ] : path.includes('/libtalloc/') ? [['lib/libtalloc.so.2.4.3', 'libtalloc.so']] : [['lib/libandroid-shmem.so', 'libandroid-shmem.so']]
  for (const [relative, destination] of mappings) {
    const inside = `./data/data/com.termux/files/usr/${relative}`
    run('tar.exe', ['-xf', join(directory, 'data.tar.xz'), '-C', directory, inside])
    const input = join(directory, inside)
    const elf = readFileSync(input)
    if (elf.readUInt32BE(0) !== 0x7f454c46 || elf[4] !== 2 || elf[5] !== 1 || elf.readUInt16LE(18) !== (arm ? 183 : 62))
      throw new Error(`Wrong ELF architecture: ${destination}`)
    cpSync(input, join(libs, destination))
  }
  // Termux's GPL copyright entries are symlinks to its shared license package.
  // Ubuntu bundles the same full GNU license texts as regular files.
  const gpl = name === 'proot' ? 'GPL-2' : name === 'libtalloc' ? 'GPL-3' : null
  const licenseArchive = gpl ? join(assets, 'ubuntu-base.tgz') : join(directory, 'data.tar.xz')
  const licensePath = gpl ? `usr/share/common-licenses/${gpl}` : `./data/data/com.termux/files/usr/share/doc/${name}/copyright`
  const license = spawnSync('tar.exe', ['-xOf', licenseArchive, licensePath], { windowsHide: true, maxBuffer: 1024 * 1024 })
  if (license.status !== 0 || license.stdout.length < 100) throw new Error(`Missing license text: ${name}`)
  writeFileSync(join(notices, `${name}-copyright.txt`), license.stdout)
}
const manifest = { schema: 1, abi, harnessVersion: '0.1.7-rc.2', nodeVersion: '24.19.0',
  assets: inputs.map(([url, name, sha256]) => ({ name, url, sha256 })),
  native: ['libproot.so', 'libproot-loader.so', 'libtalloc.so', 'libandroid-shmem.so'].map(name => ({ name, sha256: createHash('sha256').update(readFileSync(join(libs, name))).digest('hex') })),
  packages: packages.map(([path, sha256]) => ({ url: `https://packages.termux.dev/apt/termux-main/${path}`, sha256 })),
}
writeFileSync(join(assets, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
writeFileSync(join(root, arm ? 'runtime-lock.arm64-v8a.json' : 'runtime-lock.json'), JSON.stringify(manifest, null, 2) + '\n')
console.log(`Pinned ${abi} runtime assets staged independently. Physical-device compatibility requires testing.`)
