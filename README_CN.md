# CLIProxyAPI Android GUI（Material You + 内置 GeckoView）

这是基于随包附带的 CLIProxyAPI Go 源码和原 Termux/Android 构建工程整理出的 **原生 Android Studio 图形壳**。目标是把 CLIProxyAPI 直接安装成普通 APK 使用，不依赖 Termux，也不依赖设备的 Android System WebView / Chrome WebView provider。

## 设计目标

- **完整浏览器引擎随 APK 分发**：使用 Mozilla GeckoView (`geckoview-omni`)；Android 项目中没有 `android.webkit.WebView`。
- **浏览器资料与系统浏览器隔离**：Gecko 使用应用私有数据目录，并固定 `contextId=cliproxy-browser`；Cookie、LocalStorage、缓存和登录态都属于本 App 的 Gecko profile。
- **同屏 GUI + WebUI**：主 Activity 同时显示 CLIProxyAPI 配置区与 GeckoView WebUI 子窗体。宽屏/平板为左右双栏，窄屏/竖屏为上下双栏。
- **真正的浏览器全屏**：全屏时整个浏览器卡片会从分栏槽位重新挂载到 Activity 顶层全屏容器，配置区/应用 Toolbar/系统栏都会让位，浏览器实际扩展到整个应用窗口；退出时再无损放回原分栏。
- **不依赖 Termux**：Go 核心用 NDK/Bionic 交叉编译成 Android ELF，并作为 `jniLibs/<abi>/libcliproxyapi.so` 随 APK 安装。App 从 `nativeLibraryDir` 直接启动它。
- **后台常驻策略**：独立 `:cpa` 前台 Service、`START_STICKY`、`specialUse` FGS、可选 CPU WakeLock / Wi-Fi Lock、异常退出自动拉起、可选开机启动、忽略电池优化入口。
- **Android 15+ / 16 KB 页兼容**：沿用原移植工程的 NDK/Bionic 路线；NDK r27 及更早显式加入 16 KB ELF page-alignment flags，NDK r28+（例如 r30）使用工具链默认的 16 KB 对齐。

## 项目结构

```text
CLIProxyAPI-Android-GUI/
├─ CLIProxyAPI/                  # 你上传的 Go CLIProxyAPI 完整源码
├─ android-app/                  # Android Studio 工程
│  ├─ app/src/main/java/io/github/cliproxy/android/
│  │  ├─ MainActivity.java       # Material You 控制台 + GeckoView 分栏/全屏
│  │  ├─ GeckoController.java    # 内置 Gecko runtime/session 与内部导航
│  │  ├─ CpaService.java         # 前台 Service + Go 子进程 + watchdog
│  │  ├─ ConfigRepository.java   # v8 YAML GUI 映射，保留未知节点
│  │  ├─ AppPaths.java           # 私有工作目录/default config/WebUI 资源
│  │  ├─ BatteryPolicy.java      # 电池优化入口
│  │  └─ BootReceiver.java       # 可选开机恢复
│  └─ app/src/main/assets/cpa/
│     ├─ management.html         # 构建时由 fetch-webui 脚本替换为官方最新 WebUI
│     └─ config.example.yaml
├─ scripts/
│  ├─ build-go.ps1 / .sh         # Go -> Android/Bionic arm64+x86_64
│  ├─ fetch-webui.ps1 / .sh      # 拉取官方 Management Center management.html
│  └─ bootstrap-gradle-wrapper.* # 首次补齐 wrapper jar
├─ reference/                    # 你上传的原 Termux/Android 移植工程，仅作参考
├─ build-all.ps1                 # Windows 一键构建 APK
└─ build-all.sh                  # Linux/macOS 一键构建 APK
```

## GUI 已直接覆盖的配置

配置面板不是只有端口和 API key。目前原生 GUI 直接覆盖：

- Server：bind host、port、trusted proxies/CIDR、LAN discovery、commercial mode。
- Access / management：client API keys、management secret、remote management、WebUI route、WebUI auto-update、Management Center GitHub repo。
- Routing：round-robin / weighted-round-robin / fill-first、session affinity、TTL、subagent 继承、force model prefix。
- Retry / cooldown：request retry rounds、max retry credentials、max retry interval、disable cooling、persist cooldown、transient-error cooldown。
- Requests：全局 HTTP/SOCKS proxy、passthrough headers、non-stream keepalive、stream keepalive、bootstrap retries。
- Observability：debug、file logging、request log、usage statistics、日志总量上限、错误日志文件数。
- Android：开机启动、Go 进程异常自动重启、CPU WakeLock、Wi-Fi Lock、电池优化豁免入口。

另有 **Advanced · edit complete YAML**，直接编辑完整 `config.yaml`。GUI 保存时采用 map 级更新，未映射到控件的上游字段仍会保留；SnakeYAML 重新输出时注释/格式可能会被规范化。如果你非常在意原文件注释布局，请用完整 YAML 编辑器直接保存文本。

## Management secret 的处理

CLIProxyAPI 会在首次启动时把明文 `management.secret-key` 改写成 bcrypt hash。这会导致普通 GUI 下次启动只能看到 hash，无法知道真实登录密码。

这个 Android 壳会在生成/通过 GUI 设置明文 management secret 时，把明文副本保存在 **应用私有 SharedPreferences**；Go 核心仍只在 YAML 中持久化 bcrypt hash。这样重开 App 后 GUI 仍能显示真实密码。若你在 WebUI/外部工具直接改了 management secret，GUI 里保存的明文副本可能不再匹配，此时在 GUI 中重新输入新密码即可。

## WebUI 行为

- 内嵌页面默认地址：`http://127.0.0.1:<port>/management.html`
- 服务端口在线后自动加载。
- GeckoView 现在是一个真正的**多标签浏览器容器**：每个标签对应独立 `GeckoSession`，所有标签共享同一个 App 私有 Gecko storage context。
- `target=_blank` / `window.open()` 会创建新的 App 内标签页；OAuth 登录可在新标签完成，再关闭/切回原 WebUI 标签，不会跳系统浏览器。
- 浏览器区包含标签栏、关闭标签、新建标签、地址栏、Go、后退、前进、刷新、回 WebUI 首页以及全屏/退出全屏。地址栏可直接打开普通 HTTP/HTTPS 网站。
- 子窗体与全屏之间只移动现有 browser card，不重建当前 `GeckoSession`，因此网页状态与登录流程不会因为切换布局而丢失。
- 正常模式使用 WindowInsets 避让状态栏、刘海/挖孔和导航栏；全屏模式才隐藏系统栏，并允许侧滑临时唤出。
- 本工程 **没有 System WebView 依赖**；不要把 GeckoView 替换成 `android.webkit.WebView`，否则会失去你的 OEM 一致性目标。

## 后台保活边界

能做的 Android 端策略已经尽量补齐：

1. API 核心运行在独立 `:cpa` 前台 Service；关闭/旋转 GUI 不停止服务。
2. Service 是 `START_STICKY`，被系统回收后可请求恢复。
3. Native Go 进程由 watchdog 监视，异常退出默认 2 秒后重新拉起。
4. 可选 `PARTIAL_WAKE_LOCK`（默认开）防止 CPU 深睡导致长期 API 任务断掉。
5. 可选 high-performance Wi-Fi lock（默认关，耗电更高）。
6. 可请求从 Android 电池优化中豁免。
7. 可选 `BOOT_COMPLETED` 自动启动。
8. `android:stopWithTask=false`，从最近任务划掉 UI 不等于停止 CPA。

**Android 的“强制停止”是系统级边界：用户在设置里点 Force stop 后，应用不能合法绕过它自行复活。** 一些 OEM 还有额外“自启动/后台运行/省电”白名单，这部分没有统一 API，仍可能需要用户在该 OEM 的系统设置里允许后台运行。

## Windows 构建（推荐）

### 前置条件

- Android Studio / Android SDK **Platform 37**（仅用于 `compileSdk`；`targetSdk` 仍为 36，`minSdk` 为 26（GeckoView 156 的最低要求））
- JDK 17（Android Studio 自带 JBR 也可）
- Android NDK **27.2.12479018 (r27c)**；脚本找不到时会尝试使用 SDK 内已安装的最新 NDK
- Go **1.26+**（这份 CLIProxyAPI 的 `go.mod` 要求）
- 首次构建需要网络下载 Gradle/Android/Maven/GeckoView 依赖，并从官方 Management Center release 获取 `management.html`


### Android 工具链版本矩阵

当前 GeckoView 156 和它的 AndroidX 依赖要求 `compileSdk 37`。因此工程使用：

```text
compileSdk = 37
targetSdk  = 36
minSdk     = 26
AGP        = 9.1.1
Gradle     = 9.3.1
```

`compileSdk 37` 只决定编译时可见的 Android API，不会把最低系统版本抬到 Android 17；真正决定最低可安装版本的是 `minSdk 26`（Android 8.0）。当前 GeckoView 156 自身要求 API 26，因此不能安全地用 `tools:overrideLibrary` 强行保留 API 24。保留 `targetSdk 36` 是为了在当前阶段不主动 opt-in Android 17 的 target-specific 行为变化。

### 一键构建真实 Android 手机用 APK

在项目根目录 PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -Arm64Only
```

输出：

```text
dist\CLIProxyAPI-Android-debug.apk
```

这是可直接 sideload 的 debug-signed APK：

```powershell
adb install -r .\dist\CLIProxyAPI-Android-debug.apk
```

如果还需要 x86_64 Android Emulator：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-all.ps1
```

### Android Studio 内构建

1. 先运行 `scripts\fetch-webui.ps1`。
2. 运行 `scripts\build-go.ps1 -Abis arm64-v8a`（真机）或默认同时构建 arm64/x86_64。
3. 用 Android Studio 打开 `android-app/`。
4. 等 Gradle 同步完成，直接 Run，或 `Build > Build APK(s)`。

## Linux/macOS

```bash
./build-all.sh
```

macOS Apple Silicon 使用 NDK 的 macOS toolchain 时可能依赖 Rosetta；如果你的 NDK 已提供原生 host 目录，可以直接在 `scripts/build-go.sh` 调整 `HOST`。

## 为什么 native core 文件名是 `libcliproxyapi.so`

它实际上是 Go 编译出的 Android PIE/ELF 可执行程序，不是 JNI API。使用 `lib*.so` 名称是为了让 APK/PackageManager 将它作为 native library 解包进只读、可执行的 `nativeLibraryDir`。Android 新版本不允许随便从应用可写数据目录执行任意下载的 ELF；这种打包方式也避免了首次运行再复制可执行文件的脆弱方案。

## 16 KB page-size

对 NDK r27 及更早版本，脚本在 Go external linker 中加入：

```text
-Wl,-z,max-page-size=16384,-z,common-page-size=16384
```

这是为了覆盖 Android 15 起的 16 KB page-size 设备。NDK r28+ 默认已按 16 KB 对齐（你当前使用的 r30 属于此类），新版 Go 构建脚本不再额外添加这两条 flags。

## 当前源码包的验证状态

已做的本地静态验证：

- Android Manifest 与全部 XML resources 可解析。
- Java 所引用的 `R.id.*` 均存在于布局。
- Shell 构建脚本均通过 `bash -n`。
- Android App 源码中没有 `android.webkit` / System WebView 引用。
- GeckoView API 使用方式已按当前 GeckoView Javadoc 核对。
- Go Android 编译参数来自你上传的 Termux 移植工程的已验证 Bionic/cgo 路线，并增加 16 KB 对齐。

当前源码已针对你反馈的 Windows 构建链问题做了累计修复。由于本交付环境没有与你本机完全相同的 Android SDK/Gradle 缓存，最终 APK 仍应以你本机 `assembleDebug` 的实际结果为准；构建脚本会在任何阶段失败时立即停止，不会发布陈旧 APK。

## 上游更新建议

以后升级 CLIProxyAPI 时，优先直接替换 `CLIProxyAPI/` 目录，然后先运行 Go Android build。只要上游仍保留 `cmd/server`、`-config` 参数和 v8 YAML，大多数时候 Android 壳无需改 Go 代码。新增配置项如果暂时没有 GUI 控件，可立即通过完整 YAML 编辑器/WebUI 使用，再按需要补进 `ConfigRepository` 和 Material 控件。

### Windows: `invalid value "$ldflags" for flag -ldflags`

早期打包版本的 `scripts/build-go.ps1` 使用了 PowerShell 对 native command 不够稳妥的 `-ldflags=$ldflags` 写法。在部分 Windows PowerShell 环境下，这会把 `$ldflags` 字面量直接交给 `go build`。当前版本改为参数数组传递（`-ldflags`, `$ldflags`），已规避该问题。

同时，NDK r28 及以上默认生成 16 KB 对齐 ELF；脚本只会在 r27 及以下附加显式 page-size linker flags。因此自动回退到 NDK r30 是受支持的。

## Windows 额外修复（2026-10-06）：`'java' is not recognized` / Gradle 失败仍复制 APK

这表示 **Go 原生核心已经成功**，但 Windows Shell 找不到 Java 启动 Gradle。
新版 `build-all.ps1` 会优先使用 `JAVA_HOME` 或 Android Studio 默认安装路径内的 `jbr`（自带 JDK），再查 PATH；找不到会直接给出设置说明，不再继续编译。
同时修正 `android-app/gradlew.bat` 的退出码传递，保证 Gradle 失败时主构建脚本立即停止，而且不会复制上一次构建留下的 APK。

若 Android Studio 安装在非默认位置，可在 PowerShell 手动设置：

```powershell
$env:JAVA_HOME = 'D:\Apps\Android Studio\jbr'  # 替换为你自己的真实路径
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

如果之前已看到 `Native core build complete.`，**不用重新编译 Go，也不用重新下载 WebUI**：

```powershell
.\build-all.ps1 -Arm64Only -SkipGo -SkipWebUi
```

以后正常一键完整构建仍使用 `-Arm64Only`。若 Gradle 后续还有依赖下载或编译问题，新版构建会打印实际失败原因，不会误报成功。

## v0.3.0 GUI 改进

- 新增中/英文界面切换：右上角 `中文` / `EN` 可即时切换，使用 Android/AppCompat 应用级 Locale；同时声明 Android 13+ App language 配置。
- 修复软键盘遮挡：Edge-to-edge 模式显式处理 IME Insets，配置面板在键盘弹出时会保留可视区域，并在输入框获得焦点时自动滚动到可编辑位置；全屏 GeckoView 也会随 IME 缩放。
- 浏览器新增“数据 / Data”管理入口：支持清空缓存、清空全部 Cookie/站点存储、清空全部 Gecko 浏览器数据。
- 新增“隔离账号标签页”：每个隔离账号使用独立 GeckoView `contextId`，Cookie/localStorage 与主账号以及其他隔离账号互不共享；OAuth `window.open/target=_blank` 新标签继承发起页容器，因此登录流程仍在同一账号环境内完成。关闭某个隔离账号最后一个标签页后，该隔离容器会自动清理。
- 主账号容器继续持久化，适合日常 WebUI/OAuth；需要切换其他账号时无需清空主账号，可直接新建隔离账号标签页。

注意：GeckoView 的公开 `StorageController` 提供清理 Cookie/站点数据/缓存及按 `contextId` 隔离/清理的能力，但不提供类似桌面 Firefox 开发者工具那样逐条枚举并编辑 Cookie 的通用 UI API，因此本项目实现的是账号容器与数据清理管理，而不是 Cookie 值编辑器。


## v0.3.1 浏览器账号入口修复

- 修复 v0.3.0 “数据”对话框同时使用 message/list 导致部分 Material 主题下操作列表不可见的问题。
- 浏览器工具栏新增“账号+ / A+”按钮，可一键创建新的 GeckoView 隔离账号容器。
- “数据 / Data”对话框改为显式 Material 按钮列表：新建隔离账号、清当前容器、清缓存、清全部 Cookie/站点数据、清全部 Gecko 数据均始终可见。

## GitHub Actions 自动跟踪上游构建

如需让 fork 自动检查 CLIProxyAPI Core 与 Management Center 上游 Release、自动构建并发布 ARM64 APK，请参阅 [`.github/ANDROID_AUTOBUILD_CN.md`](.github/ANDROID_AUTOBUILD_CN.md)。
