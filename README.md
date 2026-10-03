# MengLuo DSH Android

基于 DeepSeek Harness 的非官方 Android 客户端。编写代码、运行脚本、管理项目，保留 Harness 的原生界面。

[下载 APK](https://github.com/Coyami-Mengluo/mengluo-dsh-android/releases/latest) · [更新记录](CHANGELOG.md) · [更新机制与发布说明](docs/UPDATES.md) · [Windows 客户端](https://github.com/Coyami-Mengluo/mengluo-dsh-desktop)

## 安装

Android 11 及以上。普通手机下载 `arm64-v8a.apk`，MuMu 等 x86_64 模拟器下载 `x86_64.apk`。打开 App 后选择下载源，在“更新管理”选择 Harness 版本并安装，然后启动；在 Harness 中配置自己的模型 API。首次安装需要联网，建议预留至少 2 GB 空间。

**当前为早期公开版本。** 已有小米 / 红米 ARM64 真机的运行反馈，但不代表所有功能或机型均已验证；新的手机控制功能仍需独立真机测试。MuMu 的转译与 Permissive SELinux 环境不能替代真实手机验证。请先导出重要文件，不保证所有手机、插件、沙箱与浏览器自动化功能可用。

## 功能

![更新管理](docs/screenshots/updates.png)

- App 内可拖动的图标菜单，闲置或拖动松手后贴边半隐藏，只留 24dp 的半透明入口；点击直接打开菜单，拖动时恢复完整显示，保留左右位置与高度。普通使用不需要跨应用悬浮窗权限；可选的手机操作功能另行引导授权，见下节。
- Material 3 卡片、底部菜单与弹窗，跟随系统浅色 / 深色主题。首页、官方页面及悬浮球使用系统导航栏、刘海与键盘的实际安全区；底部菜单避让手势条，不累加空白高度。
- 悬浮菜单提供 50%–200% 的页面缩放与一键重置，保存上次比例；原生菜单与系统导航安全区不随页面缩放。
- Harness 文件列表和聊天文件卡片可交给系统应用打开；“代码文件”也提供同一入口。网页请求先显示原生确认，随后以临时只读 URI 分享选中文件的副本（单文件最多 128 MiB），不开放整个项目或 WebView 文件访问。原文件不变，外部应用编辑副本不会回写项目。旧版或无法确定路径的预览错误提供“从代码文件打开”入口，不猜测同名文件。
- Ubuntu Base / Node 在首次安装时按需下载并核对 SHA-256，PRoot 小型原生工具随包提供。已有基础环境不随壳更新重复下载。
- Harness 官方版本目录、候选安装和启动测试、版本切换及上一版回退；项目、插件和运行程序分别存放。失败不切换到候选版本。
- 客户端自更新：每天检查、同一更新不重复提醒、手动下载与真实进度；校验包名、版本、签名、哈希及架构后交由安卓系统确认安装。完整小 APK 更新，不是二进制差分。
- 首次安装页及图标菜单可选官方源 / 国内 npmmirror、USTC 镜像。Harness 和配套 pnpm 依赖由官方源确定并锁定，镜像只下载对应字节；镜像缺少目标版本时明确报错。基础 Node / Ubuntu 归档使用 npmmirror / USTC 镜像，失败时明确提示并回退到同一版本的官方地址。Ubuntu 安装依赖也跟随选择，下载失败回退官方源，保留 HTTPS 和官方签名校验。GitHub 地址及手动 apt 命令的源配置不变；已运行的 Harness 需重启才能继承新设置。
- 本地启动 / 停止、安装阶段和错误摘要、带停止按钮的前台通知。“运行日志 → 导出日志”通过系统文件选择器保存近期历史记录（最多 3 MiB，含重启前日志），不只导出屏幕上的片段。常见凭据已脱敏，分享前仍需检查私人内容。
- “代码文件”读取 Harness 已保存的项目目录，提供项目切换、文本编辑、系统文件选择器导入/导出；包括默认 `/workspace`、`/root/项目名` 等 App 私有项目，以及授权后的手机公共项目。“导入 / 导出”可导出当前文件夹或整个项目，保存为手机文件夹或 ZIP，保留子目录、空文件夹及隐藏文件。提供进度与取消，导出至新目录，不覆盖已有目录；原项目不搬移，导入导出本身不需要全盘文件权限。文件列表分页显示；二进制和大文件可单独导出，保存或导入不覆盖已发现的并发修改 / 同名文件。
- 首页与悬浮菜单提供“手机文件访问”：仅管理所有文件访问授权，项目位置直接在 Harness 中选择，不再由壳限定一个文件夹。手机存储以原路径接入，支持 `/sdcard`；运行环境、配置与原 `/workspace` 仍在应用内部，不搬动既有项目。
- 本机 Bash/Node 命令入口、手动插件安装和列表；不是 Windows 版完整插件商店，不自动更新插件。
- 首次安装页提供可关闭的运行权限说明；当前官方页面出现明确的 Bash 沙箱不可用信息时，在悬浮图标旁显示可关闭的小气泡，最多自动提醒一次，记录会保留至重启后。可在气泡或运行环境页主动查看切换「完全访问 / Full access」的方法与风险；不会自动修改权限、执行重试或把普通运行错误归为沙箱问题。
- 从 0.0.8 起提供可选的无障碍手机操作，详见下方“手机操作”。不申请 Root；仅在无障碍截图全黑时，另行请求系统屏幕共享授权用于兼容截图。

运行权限不会由客户端自动放开；需要时只说明如何在官方界面选择“完全访问”及其风险。

### 在 Harness 中选择手机项目

打开“手机文件访问 → 授权文件访问”，阅读说明后在系统设置中允许所有文件访问，再回到 Harness 自己选择、新建和切换项目。内部存储通常是 `/storage/emulated/0`，也可以使用 `/sdcard`；可在权限页复制实际路径。壳不再指定唯一项目，不需要为了换项目停止或重启 Harness。修改系统权限时 Android 可能结束 App，请先保存并结束任务。拒绝授权时仍可使用应用内项目与导入导出。

公共路径对应手机原文件，例如 `/storage/emulated/0/Documents/MyProject`；`/storage` 下其他存储卷能否访问取决于系统及设备。系统保护目录不会因授权而获得 Root 权限。旧版壳保存的单目录设置不再作为访问限制，不删除该记录或项目文件；Harness 自己保存的项目路径不变。壳终端从原 `/workspace` 启动，使用 `cd` 进入需要的项目。运行环境、插件和聊天配置仍在应用内部，不做迁移。

手机路径始终接到真实 Android 存储命名空间，由 Android 决定当前可读写范围；不把权限不足的路径替换成 Ubuntu 内的同名目录。所有文件权限撤销后，原公共文件不删除，公共项目可能无法继续访问；“代码文件”会要求重新授权。安装器与候选版本启动测试不接入手机公共存储。

使用内部的解释器执行公共目录中的脚本，例如 `node app.js`、`python3 main.py`、`bash run.sh`；能否运行取决于项目所需依赖。公共存储缺少完整 Linux 文件系统能力，软链接、可执行二进制、部分 npm/pnpm 依赖和编译工具仍可能失败，不能保证所有项目可运行。需要这些能力的项目应使用应用内工作区；不会自动把依赖搬到隐藏位置，也不修改官方 Harness 权限配置。

新建项目的目录选择器默认从手机内部存储（通常为 `/storage/emulated/0`）开始，仍可自行浏览或输入其他路径。此适配只为官方目录浏览的空路径请求提供起点，不修改 Linux `HOME`、配置目录或已有项目；未授权时仍受 Android 文件权限限制。

## 构建

需要 Windows、Node 24、JDK 21。SDK、Gradle、下载缓存和调试密钥保存在工程的 `.tools/`，不修改系统 PATH。先阅读并接受 [Android SDK 许可](https://developer.android.com/studio/terms)。

```powershell
node scripts/prepare-tools.mjs
node scripts/prepare-runtime.mjs
# 使用 .tools/android-sdk/cmdline-tools/19.0/bin/sdkmanager.bat --licenses 接受 SDK 许可
./scripts/build.ps1
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，仅用于开发调试；公开安装包使用非调试 release 构建与维护者长期签名。其他开发者自行生成的调试密钥不能覆盖官方仓库签名的客户端。源代码测试：`./scripts/build.ps1 -Tasks testDebugUnitTest,lintDebug`。发布方法见 [UPDATES.md](docs/UPDATES.md)。

图标原图保存在 `artwork/app-icon.png`；构建自动生成各屏幕密度的桌面、首页和悬浮菜单图标，保持原图与透明背景，不需要额外安装图像处理工具。

ARM64 手机测试包：

```powershell
node scripts/prepare-runtime.mjs arm64-v8a
./scripts/build.ps1 -Abi arm64-v8a
node scripts/verify-apk.mjs arm64-v8a
```

输出为 `app/build-arm64/outputs/apk/debug/app-debug.apk`，与模拟器包分开构建和存放。PRoot 与基础环境清单按架构打包；Ubuntu Base、Node 按清单在首次运行时下载。安装记录包含 ABI，并在启动前检查 ELF 架构。构建与静态校验通过不代表真机可运行。

设备集成测试运行在 App UID 内，检查解压安装、Node 执行代码写文件、npm/pnpm 镜像、Harness HTTP 与前端就绪及停止后保留文件。不消耗模型额度，不替代真实模型工具调用、后台保活或真实手机测试。

`AptSetupDeviceTest` 可通过 instrumentation 参数 `aptFixturePath` 指定已校验的 Ubuntu Base 归档，在全新缓存目录中测试真实依赖安装。测试专用 seccomp 过滤器仅拒绝该子进程树的原生 `link/linkat`：先确认关闭兼容会出现 `status-old: Permission denied`，再确认开启兼容后同一失败数据库可以恢复、软件包可以安装和升级。过滤器按实际 ELF 架构选择，避免模拟器伪装的 `uname` 误导；不改 SELinux、不申请 Root，也不操作用户的包数据库。`UpgradeRetentionTest` 的 `upgradeRetentionPhase=prepare/verify` 可在覆盖安装前后核对运行版本状态、临时项目文件和下载源保留情况。

先构建 `assembleDebug,assembleDebugAndroidTest`，再运行 `./scripts/device-smoke.ps1`。其中界面用例覆盖横竖屏、深色模式及模拟的手势条 / 侧边导航栏 / 键盘安全区，验证重复分发不会叠加留白。MuMu 当前隐藏系统导航栏，因此这不等于手势导航真机实测。`node scripts/read-ui-verification.mjs` 可取回界面测试截图到 `.tools/verification/`。

MuMu 自带的旧 WebView 缺少 `Promise.withResolvers` 和 `AbortSignal.any`。App 仅在当前 Harness 本地 origin 的文档启动阶段补齐这两个缺失接口，已有原生接口不覆盖，不修改官方文件或增加原生权限桥接；兼容脚本测试：`node --test scripts/web-compat.test.mjs`。它不替代浏览器安全更新，也不代表兼容任意老旧 WebView。

## 任务通知与日志高亮

悬浮菜单新增“任务通知”，可开关提醒、打开系统通知设置和发送测试通知。任务结束或需要确认 / 回答问题时显示系统提醒，点击回到 Harness；仅显示固定提示，不显示聊天正文、命令或密钥，也不提供自动确认按钮。Android 13 及以上需允许通知权限，拒绝后不会反复申请。运行日志增加深浅色高亮，不改写存储、复制或导出的日志原文。

适配旁听官方页面已建立的事件连接，不另建客户端、不回应确认请求、不改动官方文件。支持已核对的旧版 `events.host/events.mux` 和新版 `remote.mux` 事件格式；未知格式忽略，不依据聊天文本猜测任务完成。任务结束指已观察到运行转为空闲，不代表生成内容或所有命令验证成功。断线恢复不补发无法确认的历史完成提醒。通知依赖 App 中 Harness 页面连接保持运行；强行停止、页面连接关闭、系统回收或省电限制时不能保证送达，勿扰模式和通知渠道设置可能静音。

适配测试：`node --test scripts/task-events.test.mjs`；本地状态测试：`TaskNoticeStateTest`、`LogLevelTest`；设备回归：`TaskEventsViewTest`、`LogViewTest`。设备通知测试使用独立 loopback WebSocket 和隔离偏好，验证退到后台仍能收到提醒、不会发送确认答复，不调用模型或读取真实会话。

## 安全和限制

### 手机操作

通过官方插件接口附带 `android-phone` 技能和 `phone_*` 工具，不修改官方 Harness 的核心文件。当前面向 Harness 0.2 系列，已用 0.2.0-rc.2 的真实工具注册表、会话接口和技能提供器校验；更早版本仍可正常启动，但不接入这项扩展。

用户直接向 Harness 提出手机操作任务。首次使用在“手机操作权限”亲自开启无障碍和跨应用悬浮窗；返回客户端会检查本应用服务是否实际连接，不只检查总开关。没有日常自动操作开关，授权权限也不会自动恢复已取消的任务。

系统权限开启后，明确的手机任务直接使用普通可启动应用，不再逐个勾选。平时是可拖动、贴边收起的图标；任务期间显示停止药丸，展开 2 秒后自动贴边收起，点击边缘可重新展开并停止，等待确认时保持展开。普通点击、导航、输入、滚动、返回和打开应用自动执行；发送、支付、删除等敏感或不确定的动作及模型提问才在药丸确认。支持“截图 → 图片像素坐标点击 / 长按 / 直线滑动 → 再截图确认”，自动换算图片与屏幕尺寸；每张截图只允许执行一次触屏操作，最长有效 60 秒，检测到应用、窗口、屏幕尺寸、方向或悬浮窗位置变化需重新截图。轮播、文字变化、视图重排和页内滚动不会单独使截图失效；坐标操作不自动追踪移动目标，模型在滚动或明显布局变化后应主动重新截图。长按可设 500–2000 毫秒，滑动可设 100–2000 毫秒；暂不支持多指或先长按再拖动的连续手势。操作路径限于当前普通应用内部，拒绝穿过悬浮窗、系统栏及被其它窗口遮住的位置。原生标签检查不是完整的风险识别器；无法识别标签的坐标需确认（普通原生可滚动区域内的纵向滑动除外），模型也必须为有实际后果的动作请求确认。不支持任意 Intent、密码控件、锁屏或系统授权界面。保留节点输入等方式，不保证所有游戏、自绘控件均可操作。

点击停止立即撤销本次控制，清除待批准动作，并向模型返回 `user_cancelled`；不会撤销之前已执行的动作。权限断开、连接中断、任务结束或最长十分钟到期也会收回授权。同一用户消息不能重新申请已结束的授权。系统仍可能回收进程或隐藏悬浮窗，可用运行通知的“停止手机操作”作为另一入口，不保证永远常驻。

屏幕文字与截图会进入 Harness 对话并可能发送给用户配置的模型 API；明确的手机任务期间不逐张询问截图。Android 11 以上统一使用整屏截图，仅按本应用悬浮窗的实际屏幕位置遮挡（包含阴影），不再用系统报告的其它窗口边界作遮挡。整屏可见的通知、键盘、分屏内容等也可能包含在截图中。遮挡矩形不包含下方画面，挡住目标时需用户拖动悬浮窗。截图前后窗口几何、焦点或权限不一致时拒绝交付；不绕过安全窗口限制。已发送截图按官方附件机制保存，停止任务不删除聊天附件。原生日志不记录屏幕内容、输入或桥接凭据。坐标点击通过 Android 的 [dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)) 执行，系统开启触摸探索或屏幕放大时拒绝猜测坐标映射。

MuMu 中已复现无障碍截图回调成功但原始像素全黑。空图不会生成可点击的截图 ID；这时每次任务最多请求一次 Android [MediaProjection 屏幕共享授权](https://developer.android.com/media/grow/media-projection)，由用户亲自确认，不自动点击系统提示。拒绝后不重复询问，模型可继续使用无障碍节点。授权成功后需重新截图，不重放旧动作。共享期间保留系统前台通知，每次截图才连接图像读取表面，不录制音视频、不保存或复用授权令牌；停止、到期、断连或系统撤销共享时结束采集。系统安全内容仍可能为空或无法截图，兼容通道不绕过这类限制。

该桥接不是对不可信插件的隔离沙箱：同一 App UID 中运行的 Harness、终端和插件仍属于同一信任域。仅运行可信代码。跨应用能力依赖 Android 权限、临时任务授权、停止撤权和原生敏感动作检查；普通动作自动执行，用户应留意模型行为。没有开机自启，不静默申请或开启任何系统权限。

开发检查：`node --test scripts/phone-tools.test.mjs`、`PhoneControlStateTest`、`PhoneHttpTest`。完整设备操作验证必须使用独立测试包、明确授权和专用测试页面，不得拿真实聊天、支付或个人应用做自动化回归。

真实 SDK 回归可在独立目录安装 `@deepseek-ai/dsh-tools`、`@deepseek-ai/dsh-agent`、`@deepseek-ai/dsh-agent-loop`、`@deepseek-ai/dsh-session`、`@deepseek-ai/dsh-session-format-catalog`、`@deepseek-ai/dsh-session-persistence-jsonl`、`@deepseek-ai/dsh-skill-filesystem` 和 `@deepseek-ai/dsh-app-boot`，全部固定 `0.2.0-rc.2`（使用 `--ignore-scripts`）。把 `MENG_LUO_HARNESS_SDK` 指向该目录，再运行 `node --test scripts/phone-sdk.test.mjs`；覆盖真实会话循环与 V4 落盘，未指定目录时跳过，不能当成已完成 SDK 校验。

独立设备包使用 `./scripts/build.ps1 -Tasks assembleDebug,assembleDebugAndroidTest,-I,scripts/phone-probe.init.gradle`，输出到 `.tools/phone-probe/build`，包名为 `ai.mengluo.dsh.android.phoneprobe`。`PhoneProbeTest` 默认只验证拒绝权限及引导界面；完整用例需用户明确允许测试版权限，并传入 `allowPhoneFixture=true`。Android instrumentation 启动会重启目标进程，应在测试开始后的连接等待阶段开启这个独立测试服务和悬浮窗，不能依赖被结束进程的旧连接。测试只操作配套 `Phone Control Fixture` 页，结束后恢复测试前的权限状态。常规测试包会跳过这些设备用例。

PRoot 是用户态兼容工具，**不是可靠的安全隔离边界**；实际权限边界是 Android App UID。用户命令、Harness 和插件能访问本 App 的工作区和模型配置；只运行可信代码，不要运行不可信插件。API 配置不纳入 Android 自动备份。运行日志不应直接公开，插件仍可能输出私密内容。

默认使用 Android 分区存储权限；导入产生独立副本，导出需用户选择目标。访问公共项目使用可选的 `MANAGE_EXTERNAL_STORAGE`，需用户在系统设置中主动授权。**该权限不只覆盖当前项目**：Harness、插件和用户命令可访问 App 获准访问的其他公共文件，项目路径和 PRoot 绑定不是安全沙箱。它不赋予 Root，也不开放其他 App 的私有数据。此权限和 Harness 的「完全访问」模式相互独立；上架 Google Play 时需另行满足广泛存储权限政策。

普通文件编辑器目前只处理不超过 2 MB 的文本，单文件导入上限 32 MB。应用卸载或系统设置中“清除数据”会删除应用内工作区、运行环境和模型配置；手机公共文件夹不会由客户端删除，仍应备份重要代码。

文件夹导出是一次性副本，不是双向同步。单次上限为 2 GiB、50,000 个条目、64 层子目录；符号链接、特殊文件及运行时配置路径会跳过并列出，目录中自己的隐藏文件（如 `.env`）仍会包含，分享前请检查。导出前应暂停修改该项目；检测到文件变化会失败而非宣称副本完整。取消或失败后，手机目标目录可能留下带 `.incomplete` 标记的部分副本，需用户确认后自行删除。Android 11 及以上不允许通过系统目录选择器访问 `Android/data`，可选择 `Documents` 下的自建目录。

仅允许当前 Harness 的确切 loopback 端口留在 WebView；外链由用户确认后交给浏览器。文件打开适配使用仅绑定当前本地 origin、仅主框架可请求的消息接口，网页不能直接读写文件、执行命令或获得文件内容；系统打开必须经过原生确认，不接收外站或嵌入页面请求。停止运行环境会中止当前任务。后台前台服务不等于永久保活，系统仍可能结束进程。

本地框架是否能使用 Bash 沙箱、原生模块、全部插件或构建特定类型的软件，必须逐项测试。不会通过全局关闭 Android 安全机制或默默关闭 Harness 权限检查来宣称兼容。

### 当前已确认的工具限制

MuMu（Android 12、Linux 5.4.32）上的官方 Harness `0.1.7-rc.2` 在模型调用 `workspace-write` Bash 工具时会报告没有可用沙箱后端。配套 Landlock 原生程序已安装，但实际探测显示内核不支持或未启用。单独解压 Ubuntu bubblewrap 做隔离测试后，其启动探测虽然退出 0，“只读”配置却仍可写入测试文件，`workspace-write` 配置也会失败；因此不能仅以探测退出码判断 PRoot 下的隔离有效，也没有把 bubblewrap 作为修复安装进运行环境。

终端中的普通 Bash / Node / Python 命令测试通过，不等于模型的受限 Bash 工具已经适配。当前不打包 Chromium / Playwright，也尚未验证浏览器渲染、截图和依赖这些工具的任务。上述结论只针对当前实测环境；ARM64 真机需独立测试，不自动切换为完全访问权限。

权限提醒只观察当前确切 Harness origin 的顶层页面中已渲染的错误卡片 / 助手回答，忽略输入框、用户消息、隐藏内容和回答中的代码引用。监听按变更合并并限制扫描量；向原生界面只发送固定提示事件，不传出对话内容，也不访问模型、会话文件或设置接口。提醒是基于页面文字的辅助说明，不是内核能力检测；官方 DOM 或措辞变化可能影响识别。「完全访问」会放开 Harness 工作区限制并减少操作确认，命令可能访问本 App 的其他项目、配置和密钥；Android 的应用隔离仍生效，缺少依赖等问题不由此解决。

项目文件回归测试使用 App 缓存内的独立数据，不读取或改写真实聊天与密钥：构建 `assembleDebugAndroidTest` 后运行 `ai.mengluo.dsh.android.ProjectBrowserTest`。它覆盖项目索引、损坏记录回退、符号链接拒绝、文件选择器状态恢复，以及项目列表到编辑器的实际界面链路。代码浏览器不开放运行时和 `/root/.dsh` 配置目录；符号链接目录请在 Harness 中重新选择实际项目路径。

公共目录设备测试需显式启用，普通测试运行时会跳过：在已安装运行环境的独立测试设备上授予所有文件访问，指定 `ExternalWorkspaceTest` 并传入 `-e externalWorkspaceTests true`，验证同一进程跨多个公共项目读写、路径别名、脚本与本地服务、Harness 隔离配置启动及权限界面。`WorkspacePermissionRestartTest` 分别传入 `-e workspacePermissionPhase prepare/denied/verify`；在 prepare 后通过系统或 ADB 撤销权限，运行 denied，再重新授权运行 verify，核对私有项目仍可用、真实手机文件权限与 Android 一致、没有误建私有同名目录。系统撤销权限会结束 App 进程，必须在 instrumentation 之外操作。结束后恢复测试前的权限状态；不要在真实使用中的设备运行权限撤销测试。测试只清理自己创建的临时项目，不读取或更改用户项目和模型配置。

## 第三方运行工具

项目自有源码采用 [MIT](LICENSE)。第三方组件保留各自许可，MIT 不替代 GPL / BSD / Apache 等上游条款。两个 `runtime-lock` 文件记录各架构的下载地址与 SHA-256；Ubuntu 各包许可位于安装环境 `/usr/share/doc/`，Node 许可为 `/opt/node/LICENSE`。

公开 Release 同时提供 `native-corresponding-source.tar.gz`，包含 APK 内 PRoot、libtalloc、libandroid-shmem 的对应源码与固定提交的 Termux 构建脚本 / 补丁。详见 [NOTICE](NOTICE)、[原生组件源码说明](docs/NATIVE-SOURCES.md) 和 APK `assets/notices/`。项目不附带 API 密钥、聊天数据或维护者签名私钥。
