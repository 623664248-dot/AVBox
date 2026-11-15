# 通用 HTML 入口：应用内全屏 WebView 打开任意链接 / 本地 .html

在上一轮「首页分类布局改版」（见 `CHANGE_CATEGORY_LAYOUT.md`，4 文件改动已推送
用户 Fork）之上新增，与那轮改动无冲突：本轮只**增量**改了 `HomePage.kt`
（顶栏多一个按钮 + 2 行 import），没有触碰 `HomeViewModel.kt` /
`MainScreen.kt` / `SearchSettingsSheet.kt`，顶部分类 Tab + 单分类网格首页
保持原样。没有动版本号、包名、签名与配置加载逻辑；未做任何 git 操作。

## 新增 / 修改文件

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/github/avbox/osc/ui/HtmlWebActivity.kt`（新增） | 新 Activity，沿用 `ThemeSettingsActivity` 的 `BaseActivity` + `ComposeView` + `AVBoxTheme` 套路，`companion start(context)` 供入口调用，内容为 `HtmlWebScreen`。 |
| `app/src/main/java/com/github/avbox/osc/ui/page/HtmlWebPage.kt`（新增） | 入口界面（输入态 + 浏览态）与 WebView 工厂、链接规范化、本地文件复制缓存的全部实现。 |
| `app/src/main/AndroidManifest.xml`（修改） | 注册 `.ui.HtmlWebActivity`，`exported=false`，带 `configChanges` 与 `windowSoftInputMode="adjustResize"`，与其它二级 Activity 写法一致。 |
| `app/src/main/java/com/github/avbox/osc/ui/page/HomePage.kt`（修改） | 首页顶栏右侧胶囊按钮组里、搜索图标左边新增一个「网页」按钮（`Icons.Filled.Language` 地球图标，40dp 点击区，与相邻按钮同风格），点击 `HtmlWebActivity.start(context)`。 |
| `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-b+zh+Hant/strings.xml`（修改） | 新增 8 个 `html_web_*` 字符串（入口名、标题、输入提示、两个按钮、说明、两个 Toast），三份完整语言文件齐全；界面无硬编码文案。 |

## 功能与流程

- **输入态**：返回箭头 + 标题、链接输入框（预填上次打开的链接，
  存 SharedPreferences `html_web/last_url`）、「打开链接」按钮、
  「选择本地文件」按钮、底部一行说明。
  - 链接规范化：`http://` / `https://` / `file://` / `content://` 原样用；
    以 `/` 开头按本地路径拼 `file://`；其余（裸域名）自动补 `https://`；
    空输入 Toast 提示不跳转。
  - 选文件走 `ActivityResultContracts.OpenDocument`（mime `text/html` + `*/*`），
    选中后在 IO 线程把文件复制到 `cacheDir/html/<原文件名>`（文件名做非法
    字符清洗），再以 `file://` 打开，不依赖 `content://` 的临时授权。
- **浏览态**：顶部 48dp 小工具条（返回 / 刷新 / 当前地址只读展示 / 关闭），
  下面全屏 WebView。关闭回到输入态；系统返回键（`BackHandler`）与工具条
  返回共用同一逻辑：WebView 能后退先后退，否则退回输入态。
  每个新目标地址用 `key(url)` 重建 WebView，`onRelease` 时 stopLoading +
  destroy 并清掉引用。

## WebView 关键设置

- `javaScriptEnabled = true`、`domStorageEnabled = true`、
  `databaseEnabled = true`、`javaScriptCanOpenWindowsAutomatically = true`、
  `loadsImagesAutomatically = true`、`useWideViewPort` +
  `loadWithOverviewMode`、UTF-8 默认编码、`LOAD_DEFAULT` 缓存。
- `mediaPlaybackRequiresUserGesture = false`（视频可自动播放）。
- `allowFileAccess = true`、`allowContentAccess = true`，并与仓库现有嗅探
  WebView（`PlayUrlResolver.configWebViewSys`）一致地打开
  `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`，
  让 `file://` 打开的单文件 App 能读同目录资源、发 XHR。
- Lollipop+ `mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`（同现有嗅探配置）。
- UA：用系统默认 UA（完整的移动 Chrome UA），仅在为空时用
  `WebSettings.getDefaultUserAgent()` 兜底，保证不为空。
- `WebViewClient` 用默认实现：页面内跳转留在 WebView 里，不跳系统浏览器。
- `WebChromeClient`：实现 `onShowFileChooser`（调系统文件选择，
  `StartActivityForResult` + `FileChooserParams.parseResult` 回传，
  取消时回传 null）；`onJsAlert` / `onJsConfirm` 直接 confirm 后返回，
  不弹框阻塞页面。

## 已知限制

- 某些页面从 `file://` 源发起跨域请求时，可能被目标站点的 CORS 策略拦截
  （`Origin: null`）——这是页面/站点的行为，不是 App 的 bug；这类页面改用
  它自己的 http(s) 链接打开即可。已放开的 `allowUniversalAccessFromFileURLs`
  能覆盖本地文件互读，但绕不过服务器端的 CORS。
- 本地文件只复制被选中的那一个文件：若某页面还引用同目录的相对资源
  （图片/脚本），那些资源不在缓存里会加载不到；单文件 App（本次需求的
  形态）不受影响。
- 多窗口（`window.open` 新窗口）未单独支持，弹窗链接按默认在当前
  WebView 内打开；页面自定义下载、摄像头/麦克风等权限请求未做处理。
- SSL 错误按系统默认处理（拒绝），没有像嗅探 WebView 那样放行。

## 构建验证状态

**未编译，静态检查**。原因同上一轮：本机只有 JDK 17 + Android SDK 34、
无 NDK，本仓库要求 JDK 21 / SDK 37 / NDK 27.2.12479018，工具链不满足；
按仓库 `AGENTS.md` 的规矩，小改动不跑测试、未经允许不提交、不跑 release
构建。仓库门禁脚本在本地 `.tools/`（gitignore，本副本里不存在），未能执行。

已做的静态核查：
- 两个新文件逐行通读：import 无多余（删过一处未用的 `size` import）、
  括号与 Compose 写法与仓库现有页面（`ConfigManagePage` /
  `ThemeSettingsActivity` 等）一致；新代码无 `!!`、无硬编码界面文案
  （全部走 `html_web_*` / `common_*` 字符串资源）。
- grep 确认：Manifest 注册的 `.ui.HtmlWebActivity` 与类包名
  `com.github.avbox.osc.ui.HtmlWebActivity` 对得上；`HomePage` 入口调用
  `HtmlWebActivity.start(context)`，图标 `Icons.Filled.Language` 来自已
  引入的 `material-icons-extended` 依赖。
- 上一轮 4 文件复核：`HomeViewModel.kt` / `MainScreen.kt` /
  `SearchSettingsSheet.kt` 本轮未改动（文件时间戳仍为上一轮）；
  `HomePage.kt` 里 `HomeGridLayout` 渲染与顶栏原有按钮（站点胶囊、
  搜索设置、搜索）保持原样，仅在按钮组中插入一个新按钮。

在装有 JDK 21 + SDK 37 的机器上可用以下命令验证（debug 构建）：
`./gradlew :app:assembleDebug`
