# 两条更新线

## Harness

官方 npm 版本目录 → 用户选版本 → 检查所选镜像有无目标版本 → 独立候选目录 → 官方依赖锁 → 镜像只下载锁定字节 → 安装配套 pnpm → 隔离启动 / HTTP 就绪测试 → 原子写入 active / previous → 手动启动。

- 版本目录只从 `registry.npmjs.org/@deepseek-ai%2fdsh` 获取。缓存保存在本机，每天检查；手动检查间隔 30 秒。重试不叠加到几十分钟。
- `npm install --package-lock-only` 从官方源确定依赖，验证包来源、版本与完整性；`npm ci` 按锁从所选源下载。安装脚本禁用。共享 npm 缓存复用已有包，不声称每次都是纯增量下载。
- npm 安装最多 30 分钟，候选 Web 服务测试最多 120 秒。监听实际就绪端口，不假设固定端口。日志中的登录 token 脱敏。
- 首次安装 Ubuntu 的 Git、Python 和证书依赖时，ARM64 使用 `ubuntu-ports`，x86_64 使用 `ubuntu`。国内源为 USTC，索引/下载失败仅回退官方源一次，共用 15 分钟下载预算，本地安装上限 10 分钟；本地 dpkg 失败不通过换源重复安装。仅取 main/universe 二进制索引，跳过翻译。保留 Ubuntu 官方密钥环及严格 TLS 校验，基础环境缺少 CA 包时使用 Android 默认信任管理器提供的公开 CA 证书引导 HTTPS。私有 apt 源文件仅供安装器使用，不覆盖用户原有 apt 配置或关闭签名验证。
- 配套 pnpm 同样先在官方源生成依赖锁，再由所选源下载锁定包。镜像不是可信元数据来源；官方版本元数据仍需可访问。
- 下载阶段若明确报告 `dpkg was interrupted`，仅尝试一次 `dpkg --configure -a`，最多 10 分钟；成功后在原镜像继续下载，不重新获取索引。恢复耗时不占下载预算。恢复失败、包管理锁、权限不足或磁盘写入错误直接报本地状态错误，不换源、不删锁或清空 dpkg 数据库。
- 运行日志位于 App 私有 `files/logs/`，分三段滚动保留，每段最多 1 MiB。界面只显示近期片段，导出读取保留的全部段。日志写入前和导出时均脱敏常见凭据，不收集聊天数据库、配置文件或项目文件；任意命令可能输出其他私人内容，分享前需自行检查。
- 候选目录为 rootfs 内 `/opt/harness-slots/…`。基础 rootfs 不随 Harness 更新替换；`/workspace`、`/root/用户项目` 与 App 私有 profile 保留。
- 基础环境解压不调用 Android 普通应用被禁止的硬链接操作；归档硬链接复制为普通文件并保留源文件权限。复制仅接受本次归档内的普通文件，计入解压大小上限，符号链接仍在最后创建。安装失败后可重试，不需要清除 App 数据。
- PRoot 启用 `--link2symlink` 和 `-L`，兼容 dpkg 状态备份及运行命令新建的硬链接。`PROOT_L2S_DIR` 固定为 rootfs 内的 `.l2s` 实际目录；这是运行环境持久数据，不能作为缓存清理或单独删除。APK 覆盖升级、重启及 Harness 槽位切换均保留它。项目文件浏览器不开放此目录，仍拒绝跟随宿主符号链接。
- Android PRoot 通过应用 code cache 中的库名别名找到 APK 原生库，不使用 `LD_PRELOAD`；Ubuntu 子进程不再尝试加载 Android 库。别名随 APK 安装路径刷新，不修改原生库字节、rootfs 或项目文件。
- Harness 启动、页面就绪、进程退出码、停止请求及启动超时写入持久日志。导出测试使用独立临时日志，不向实际运行历史写入填充数据；不自动清除已有历史日志。
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

1. 更新 `app/build.gradle` 的版本名与递增 `versionCode`，更新 CHANGELOG。`0.0.1` 的内部序号是 2，以覆盖早期私有测试包；公开 `0.0.3` 使用 5，以覆盖同名本地测试包的序号 4；公开 `0.0.8` 使用 11，以覆盖同名本地测试包的序号 10。
2. 使用保存在仓库外 / 忽略目录内的长期签名密钥构建两种架构的 release APK。不可公开密钥、密码或 `local.properties`。
3. `node scripts/verify-apk.mjs arm64-v8a release` 与 `x86_64 release` 验证架构与打包内容；用 SDK `apksigner verify` 验签。
4. `node scripts/prepare-sources.mjs` 生成原生工具对应源码附件。Node/Ubuntu 不应出现在 APK 里。
5. `node scripts/release-manifest.mjs <version> <versionCode>` 生成 APK、更新清单和校验文件。检查 release 文件清单；不得上传 debug、测试夹具或用户文件。
6. 创建草稿 Release `v<version>`，上传两个 APK、`android-update.json`、`SHA256SUMS.txt` 与 `native-corresponding-source.tar.gz`；确认一致后公开。没有添加自动推 tag 发布工作流。

构建命令示例：

```powershell
./scripts/build-release.ps1 -Abi arm64-v8a -SigningConfig <本地签名JSON> -VersionName 0.0.8 -VersionCode 11
./scripts/build-release.ps1 -Abi x86_64 -SigningConfig <本地签名JSON> -VersionName 0.0.8 -VersionCode 11
```

签名 JSON 字段：`keystore`（绝对路径）、`storePassword`、`keyAlias`、`keyPassword`。脚本仅通过当前进程环境传给 Gradle，不把密码写进源码。妥善离线备份密钥；丢失或更换签名会破坏覆盖安装和内置更新兼容性。其他贡献者需使用自己的密钥、包名与更新源，不能向原项目用户分发换签名的更新。

构建脚本按 ABI 隔离 Gradle 项目缓存，并立即将 release APK 和元数据保存到 `dist/staging/<ABI>/`。验证和生成更新清单均读取该目录，避免切换构建架构时 Gradle 清理上一架构的产物。
