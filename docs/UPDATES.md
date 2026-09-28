# 两条更新线

## Harness

官方 npm 版本目录 → 用户选版本 → 检查所选镜像有无目标版本 → 独立候选目录 → 官方依赖锁 → 镜像只下载锁定字节 → 安装配套 pnpm → 隔离启动 / HTTP 就绪测试 → 原子写入 active / previous → 手动启动。

- 版本目录只从 `registry.npmjs.org/@deepseek-ai%2fdsh` 获取。缓存保存在本机，每天检查；手动检查间隔 30 秒。重试不叠加到几十分钟。
- `npm install --package-lock-only` 从官方源确定依赖，验证包来源、版本与完整性；`npm ci` 按锁从所选源下载。安装脚本禁用。共享 npm 缓存复用已有包，不声称每次都是纯增量下载。
- 安装最多 30 分钟，候选 Web 服务测试最多 120 秒。监听实际就绪端口，不假设固定端口。日志中的登录 token 脱敏。
- 候选目录为 rootfs 内 `/opt/harness-slots/…`。基础 rootfs 不随 Harness 更新替换；`/workspace`、`/root/用户项目` 与 App 私有 profile 保留。
- 配套 pnpm 读取包的 `packageManager`；未声明时使用记录的默认版本。Node 是基础环境版本（当前 24.19.0）；使用 `--engine-strict` 和实际启动测试拒绝不兼容候选。此版不自动升级 Node/Ubuntu，也不承诺兼容官方所有历史/未来版本。
- 原子状态在切换时立即同步落盘，不依赖正常退出 App。旧 schema 2 安装记录直接读取，无需重装。保留上一版运行程序，但不回退官方已修改的用户数据；降级前导出重要文件。
- 失败的候选不会成为 active。当前不自动清理旧/失败槽位，以避免误删；长期反复安装会占用额外空间。

## Android 客户端

固定 GitHub 仓库 Release 中的 `android-update.json` → 根据 APK ABI 选文件 → 下载 → 大小 / SHA-256 / 包名 / 更高 versionCode / versionName / 签名 / ABI 检查 → 原生确认 → 安卓系统安装器。

- 发布源固定为 `Coyami-Mengluo/mengluo-dsh-android`，只允许 HTTPS GitHub Releases 和官方 release asset CDN 重定向。网页下载域名不可达时，回退到同仓库的 GitHub 官方 Release 附件 API；没有第三方 GitHub 代理或跳过证书验证。
- 更新检查不使用 GitHub 搜索 API；不要求用户登录。每天自动检查只在 App 使用期间发生，失败不循环重试，同一更新提醒去重。已有检查结果跨启动保存。
- APK 的签名必须与当前安装相同；不允许降级、换签名、调试 APK 或换包名。原生安装入口不能从 WebView JavaScript 调用。
- 此版是完整 **小 APK 更新**，没有实现二进制差分补丁。Ubuntu/Node 不再随 APK 重复下载；已经安装的运行环境和数据保留。下载中断需重试，会从头下载 APK。
- 系统可能要求允许此 App 安装未知来源应用。只在点击安装更新时引导开启；返回后再次点击确认，不自动安装。
- 不保证 PRoot 内运行的不可信代码无法篡改本 App 私有文件：同一 UID 内没有独立安全边界。仅运行可信插件和命令。

## 手动发布

1. 更新 `app/build.gradle` 的版本名与递增 `versionCode`，更新 CHANGELOG。`0.0.1` 的内部序号是 2，以覆盖早期私有测试包。
2. 使用保存在仓库外 / 忽略目录内的长期签名密钥构建两种架构的 release APK。不可公开密钥、密码或 `local.properties`。
3. `node scripts/verify-apk.mjs arm64-v8a release` 与 `x86_64 release` 验证架构与打包内容；用 SDK `apksigner verify` 验签。
4. `node scripts/prepare-sources.mjs` 生成原生工具对应源码附件。Node/Ubuntu 不应出现在 APK 里。
5. `node scripts/release-manifest.mjs <version> <versionCode>` 生成 APK、更新清单和校验文件。检查 release 文件清单；不得上传 debug、测试夹具或用户文件。
6. 创建草稿 Release `v<version>`，上传两个 APK、`android-update.json`、`SHA256SUMS.txt` 与 `native-corresponding-source.tar.gz`；确认一致后公开。没有添加自动推 tag 发布工作流。

构建命令示例：

```powershell
./scripts/build-release.ps1 -Abi arm64-v8a -SigningConfig <本地签名JSON> -VersionName 0.0.1 -VersionCode 2
./scripts/build-release.ps1 -Abi x86_64 -SigningConfig <本地签名JSON> -VersionName 0.0.1 -VersionCode 2
```

签名 JSON 字段：`keystore`（绝对路径）、`storePassword`、`keyAlias`、`keyPassword`。脚本仅通过当前进程环境传给 Gradle，不把密码写进源码。妥善离线备份密钥；丢失或更换签名会破坏覆盖安装和内置更新兼容性。其他贡献者需使用自己的密钥、包名与更新源，不能向原项目用户分发换签名的更新。
