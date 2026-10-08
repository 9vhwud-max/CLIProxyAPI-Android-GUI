# Android GUI 自动跟踪直接上游并构建 APK

工作流：`.github/workflows/android-gui-upstream-release.yml`

## 直接上游

本项目现在只把下面这个仓库视为自动更新的**直接上游**：

```text
tsaQB/cliproxyapi-android
```

这是刻意设计的。Android GUI 本身就是基于该项目的 Android/Bionic 移植路线开发的，而 `tsaQB/cliproxyapi-android` 已经负责跟踪更上游的：

- `router-for-me/CLIProxyAPI`
- `router-for-me/models`
- `router-for-me/Cli-Proxy-API-Management-Center`

因此本 workflow **不会再自行判断上述三个项目的“最新版”**。它只认 `tsaQB/cliproxyapi-android` 发布出来的 Release 和该 Release 附带的 `provenance.json`。

这样可以避免 GUI fork 和直接 Android 上游发生版本分叉：tsaQB 选择哪个 Core、models catalog 和 WebUI，本 APK 就跟哪个。

## 自动更新流程

工作流每 4 小时检查一次：

```text
tsaQB/cliproxyapi-android/releases/latest
```

也可以在 GitHub Actions 页面手动 `Run workflow`，通过 `upstream_tag` 指定一个 tsaQB Release tag。

找到新的直接上游 Release 后，workflow 会下载并校验：

```text
cliproxyapi-android-arm64.tar.gz
checksums.txt
provenance.json
```

其中 tarball 已经包含 tsaQB 打包的：

```text
cli-proxy-api
management.html
```

`management.html` 会原样嵌入 APK，不再另行从 Management Center 拉“最新版”。

## 为什么 Core 有时会重新编译

`tsaQB/cliproxyapi-android` 当前 Release workflow 使用 NDK r27c。老版本 NDK 生成的 Android ELF 不一定满足原生 16 KiB page-size 设备要求。

因此本 workflow 会先检查 tsaQB tarball 中 `cli-proxy-api` 的每个 ELF `LOAD` segment：

- 如果 alignment 已经 `>= 0x4000`：**直接使用 tsaQB 发布的 binary，字节不改**。
- 如果还是 4 KiB-only：workflow 会读取 tsaQB Release 自己的 `provenance.json`，取得它明确记录的 Core repository/tag/commit、models repository/ref/commit，然后用 **这些完全相同的版本** + NDK r30 重新生成 16 KiB-compatible binary。

这里的 fallback 不是重新追踪最上游，也不会偷偷换成更新的 Core。直接上游仍然只有 tsaQB；最上游地址仅作为 tsaQB provenance 已经锁死的源代码对象下载位置。

WebUI 无论哪种模式都始终使用 tsaQB Release tarball 中那一份。

## 发布版本规则

Release tag 类似：

```text
android-v0.3.1-tsa-v8.0.16
```

如果 tsaQB 发布修订版：

```text
v8.0.16-r1
```

则会得到：

```text
android-v0.3.1-tsa-v8.0.16-r1
```

如果相同的 GUI shell + tsaQB Release 已经构建过，定时任务会直接跳过。手动运行并勾选 `force` 时，会在 GUI Release 后追加新的 `-rN`。

APK `versionName` 类似：

```text
0.3.1-tsa8.0.16
```

`versionCode` 使用 UTC 分钟生成递增值。

## 构建与校验

最终 APK 仍会检查：

- 只包含 `arm64-v8a` native libraries；
- `libcliproxyapi.so` 存在；
- 内嵌 `management.html` SHA-256 与 tsaQB bundle 一致；
- 所有 ARM64 ELF，包括 GeckoView 和 CPA core，`LOAD` alignment 均至少为 16 KiB；
- release APK 使用 `zipalign -P 16`；
- release APK 签名可验证。

Release 中还会附带：

```text
checksums.txt
provenance.json
release-notes.md
tsaQB-provenance.json
tsaQB-checksums.txt
```

这样既可以追溯 GUI 自己的构建，也可以追溯 tsaQB 当时究竟选中了哪个 Core / models / WebUI。

## APK 签名 Secrets

为了让以后自动生成的 APK 可以覆盖升级已经安装的 APK，需要固定 release keystore。在：

`Settings -> Secrets and variables -> Actions`

添加：

- `ANDROID_RELEASE_KEYSTORE_B64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Windows PowerShell 生成 keystore 示例：

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" `
  -genkeypair -v `
  -keystore cliproxyapi-release.jks `
  -alias cliproxyapi `
  -keyalg RSA -keysize 4096 -validity 10000
```

转 Base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\cliproxyapi-release.jks")) |
  Set-Clipboard
```

请离线保存原始 keystore 和密码，不要提交进 Git。

如果签名 Secrets 不完整，workflow 仍会生成 debug APK 并上传 Actions Artifact，但不会创建正式 GitHub Release。

## Fork 的 Actions 权限

GitHub fork 可能默认禁用 Actions，需要先到 **Actions** 页面启用。

自动创建 Release 还需要：

`Settings -> Actions -> General -> Workflow permissions -> Read and write permissions`

workflow 自身已经把 publish job 的 `contents` 权限提升为 `write`。

## Android GUI 自身升级

修改 GUI 本身并准备发布新壳版本时，请同步修改：

```text
android-app/gradle.properties
cpa.shellVersionName=0.3.1
cpa.shellVersionCode=4
```

例如升级为 `0.3.2 / 5`。这样即使 tsaQB 直接上游版本没变化，也会生成新的组合 Release tag。

### GitHub Hosted Runner 的 Android SDK 安装

本工作流不使用 `android-actions/setup-android@v3`。该 Action 在较新的 Android command-line tools 环境中仍可能调用已经移除的 `sdkmanager tools` 包，从而以 `Failed to find package 'tools'` 失败。

工作流会直接安装 Google command-line tools 16.0（build 12266719），然后使用该 `sdkmanager` 安装 `platforms;android-37.0`、Build Tools、Platform Tools，以及仅在需要 16 KiB 兼容重编 Core 时安装 NDK r30。
