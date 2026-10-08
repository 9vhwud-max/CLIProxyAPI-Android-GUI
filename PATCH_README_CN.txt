基于 CLIProxyAPI-Android-GUI-source-browser-reworked.zip 的 v0.3.0 累计界面补丁。

新增：
1. 中/英文应用界面切换（右上角 中文 / EN），Android 13+ 同步应用语言设置；Android 8-12 使用 AppCompat 自动持久化。
2. IME/虚拟键盘适配：edge-to-edge 下显式处理 IME Insets，配置滚动区域会缩到键盘上方，输入框聚焦时自动滚入可见区域；全屏 GeckoView 也会避开 IME。
3. 浏览器“数据 / Data”入口：清空当前账号容器、缓存、全账号站点数据、全部 Gecko 数据。
4. 隔离账号标签页：独立 Gecko contextId，Cookie/localStorage 隔离；OAuth popup 继承发起标签的账号容器；关闭隔离账号最后一个标签页后自动清理。
5. 版本号提升到 0.3.0。

覆盖到工程根目录后可执行：
  .\build-all.ps1 -Arm64Only -SkipGo -SkipWebUi
