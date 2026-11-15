# homePage 本地白屏修复 + 长按站点刷新（v1.6.1）

现象（v1.6.0 真机，本地文件配置 瑟瑟.json，同目录有 AV.html、禁片天堂.html）：
选中 AV[HTML]、禁片天堂h[HTML] 站不再报「加载失败」（homePage 已读到、WebView
分支已进入），但 WebView 区域纯白屏，连页面自带的静态页壳都不渲染。

**未编译**：沙箱无 JDK 21 / SDK 37，仅静态检查（逐段通读真实链路 + grep + 括号配平）。
未动 git、未 commit；未回退已有改动（首页网格化、HtmlWeb 入口、homePage 站点、
ConfigParser 读 homePage、window.fm 桥）。VERSION 已改为 v1.6.1。

## 一、白屏根因

链路逐段对齐（本地配置）：

1. 本地配置的 API_URL 是 `clan://localhost/1瑟瑟/瑟瑟.json` 形态
   （`io/LocalConfigHost.kt` 把存储路径转成 clan 地址）。配置经
   `ConfigLoader.fetchConfigAsync` 从本地文件服务
   `http://127.0.0.1:<RemoteServer 端口>/file/1瑟瑟/瑟瑟.json` 拉取——这条路是通的，
   配置能加载、站点能解析，就是证据。
2. `ConfigParser.fixContentPath` 把正文里的 `"./AV.html"` 按配置地址 absolutize：
   先 `file://`→`clan://localhost/`，再 `clanToAddress` 映射到本地文件服务，
   最后 `UriUtil.resolve` 拼目录。结果 homePage 在进 `parseSites` 前已经是
   **`http://127.0.0.1:<端口>/file/1瑟瑟/AV.html`**；`ApiConfig.resolveHomePage`
   见绝对地址原样返回；`HomeViewModel.loadHome` 把它交给首页 WebView `loadUrl`。
   地址本身没错，端口就是配置自己用的那个（`ControlManager.getAddress` 按需自启，
   不存在"服务未就绪"问题）；清单里 cleartext 已放行
   （`usesCleartextTraffic=true` + network_security_config base-config），也不是拦截。
3. **断点在最后一跳的响应头**：`server/RemoteServer.kt` 的 `/file/` 处理对所有文件
   一律以 **`application/octet-stream`** 返回（原代码两处写死）。OkHttp 拉配置不看
   Content-Type 所以没事；但 WebView 主帧导航拿到 octet-stream 时不会当文档渲染，
   只会把它当下载丢给 DownloadListener——`HtmlWebPage` 没设下载监听，于是加载被
   静默吞掉：不报错、不渲染、纯白屏。这也解释了为什么独立「网页」入口选文件能开
   （那是 file:// 直读，WebView 按 .html 扩展名判 MIME），而 homePage 走本地服务就白。

## 二、修复（白屏）

`server/RemoteServer.kt`：

- 新增私有 `mimeTypeFor(path)`：按扩展名给 MIME——html/htm → `text/html; charset=utf-8`，
  css/js/json/txt 带 charset，png/jpg/gif/webp/svg/ico 图片类，mp4/mkv/ts/m3u8/mp3
  媒体类，未知扩展名保持 `application/octet-stream`。
- `/file/` 的两处文件响应（直读文件、SAF 授权流）都改用 `mimeTypeFor(f)`。
  目录列表分支不动。对 OkHttp 消费者（配置、jar、py 下载）无影响，它们不看 Content-Type。
- 远程 http 配置不受影响：homePage 仍按配置目录解析、由对方服务器自己给 MIME，
  解析规则（`resolveHomePage`）一行未动。

## 三、主帧加载失败提示（诊断 UI）

`ui/page/HtmlWebPage.kt`（共享 HtmlWebView 加可选开关，首页开启、独立入口默认关）：

- 公开重载新增 `showLoadError: Boolean = false`；`HomePage` 的 homePage 分支传 `true`。
- WebViewClient 新增 `onReceivedError`：仅主帧（`request.isForMainFrame`）时记录
  失败 URL + 错误描述；`onPageStarted` 时清掉旧错误。
- 另设 DownloadListener 兜住 octet-stream 那类"无错误回调的静默丢弃"：
  主帧 URL 被当下载时同样记为失败（文案 `html_web_unsupported_content`）。
- 出错时内容区以覆盖层显示「页面加载失败」+ URL + 错误描述（小诊断块，不改整体布局），
  不再静默白屏。新增字符串 `html_web_load_failed`、`html_web_unsupported_content`
  （values / values-en / values-b+zh+Hant 三份）。

## 四、长按站点刷新资源（新功能）

- 位置：首页顶栏站点名点开的站点选择底单（`HomePage` 的 source sheet），
  条目是 `SettingsOptionRow`——它本来就用 `combinedClickable` 并支持 `onLongClick`，
  之前没人传。现在传入：长按 → 关底单 → `HomeViewModel.refreshConfig()`。
- `HomeViewModel.refreshConfig()` 复用 App 现有配置重载路径
  `AppBootstrap.onApiUrlChanged()`（与改配置地址同一条路：取消在途加载、
  invalidate 配置、以 forceFresh 重新读取当前配置——本地文件会重新读盘——
  并重载 jar/spider）。重载完成走既有 `Boot.Ready` 收集器自动 `loadHome()`
  刷新当前站点首页；VM 在 Ready 落地时通过新增的只读 `toastEvents` 发
  「已刷新」（`home_source_refreshed`，三份语言），HomePage 收集后 Toast。
- 普通点击切换站点行为不变；长按不触发点击（combinedClickable 语义），
  长按任意站点刷新
...[truncated 789 chars]