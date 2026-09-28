# Native component source bundle

`native-corresponding-source.tar.gz`, distributed next to each APK in Releases, contains the upstream source archives plus the Termux build framework, package recipes and patches at commit `3797184e81b2ae6cb220c6f2562d63ec80b57f9f`.

The native binaries are unmodified bytes from the pinned Termux packages recorded in `runtime-lock.json` and `runtime-lock.arm64-v8a.json`. Only their APK filenames are changed. No executable byte patching is performed.

| APK file | Upstream source | Termux recipe |
| --- | --- | --- |
| libproot.so, libproot-loader.so | termux/proot v5.1.107.95 (GPL-2.0) | packages/proot |
| libtalloc.so | talloc 2.4.3 (Termux GPL-3.0 classification; consult upstream per-file licenses) | packages/libtalloc |
| libandroid-shmem.so | termux/libandroid-shmem v0.7 (BSD-3-Clause) | packages/libandroid-shmem |

Extract the included `termux-packages` archive on a supported Linux build host and follow its README/build documentation to prepare the Android NDK/SDK build environment. Build the packages and dependencies with its `build-package.sh`, choosing `aarch64` for arm64-v8a or `x86_64`. The package recipes contain the source checksums, configure flags, patches, compiler flags and installation steps. `scripts/prepare-runtime.mjs` in this repository documents which regular files to extract and how they map to APK filenames. The public APKs use the pinned prebuilt package bytes; a rebuilt toolchain may produce different bytes and must be independently validated. No reproducible-byte claim is made.

The `sources.json` in the bundle lists source URLs and SHA-256 hashes. Full upstream copyright/license texts remain in the source archives and the APK's `assets/notices/` directory. The shell's MIT license does not replace those licenses.

Ubuntu Base and Node archives are **not distributed inside the public APK**. On first installation they are fetched from the upstream or selected mirror and checked against the pinned hashes. Ubuntu package copyright files remain under `/usr/share/doc/`; Node's full license is `/opt/node/LICENSE`. Subsequent Harness updates leave this base and all user project directories intact.
