# Project instructions

## Default device for testing

The user selected the connected physical Xiaomi Mi 6 (`sagit`) for this project's
future device tests. Its OriginOS port reports a vivo model; identify the device
by the serial in the local `.tools/test-device.json`, not that model string.

- Read `.tools/test-device.json` for `adb`, `serial`, and `abi`. Keep this machine's
  device identifiers and tool paths in that ignored file.
- Always address the selected device with `adb -s <serial>`. If it is disconnected
  or unauthorized, resolve that connection; do not fall back to an emulator or
  another connected device.
- Build for `arm64-v8a`: `./scripts/build.ps1 -Abi arm64-v8a -Tasks
  assembleDebug,assembleDebugAndroidTest,testDebugUnitTest,lintDebug`.
- `./scripts/device-smoke.ps1`, `node scripts/capture-device.mjs`, and
  `node scripts/read-ui-verification.mjs` use the saved device configuration.
- The saved `DeviceForegroundListener` is needed on this ROM: direct launches
  from a background instrumentation process timed out, while shell launches
  succeeded. It only brings the test app to the foreground before each test.
- Use the existing app-scoped tests and keep results in `.tools/`. Preserve the
  phone's existing app data. Do not clear data to make a failing test pass.
- Local JDK 21, SDK 35, Gradle 8.9, and ARM64 runtime assets are already staged
  under `.tools/`. The pinned Termux PRoot URL returned 404 during setup;
  `.tools/stage-pinned-release.mjs` recovered the exact locked libraries from the
  matching v0.0.9 release and verified all hashes. Do not substitute newer native
  libraries just to get past that download failure.
- Follow the README's isolated fixture workflow for phone-control permission
  tests; ordinary startup testing does not require those permissions.
