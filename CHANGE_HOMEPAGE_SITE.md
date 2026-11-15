# homePage 站点支持（FongMi 式网页首页）

让配置里带 `homePage` 的站点（如 `{"key":"AV","name":"AV🪜 | [HTML]","type":3,"api":"csp_AV","homePage":"./AV.html"}`）
在 AVBox 里可用：选中该站时，首页内容区直接用 WebView 全屏打开 homePage 作为该站首页，
不再去初始化不存在的 `csp_` 占位爬虫、不再报「加载失败」。

**未编译**：沙箱无 JDK 21 / SDK 37，只有静态检查（通读改动、grep 链路、括号配平）。
仓库 `.tools/` 门禁脚本是 gitignore 的本地目录，本副本不存在，未能执行。
未动 git、未 commit；版本号/包名/签名/配置加载主流程未动；上一轮「加载慢」相关代码未动；
前几轮改动（首页网格化、HtmlWebActivity/HtmlWebPage 网页入口、statusBarsPadding、浏览态无工具条）均未回退。

## 改动文件

- `bean/SourceBean.kt` — 新增 `homePage` 字段（沿用现有 `api`/`ext` 的 safeString getter/setter 模式，Gson 按字段名解析）+ `hasHomePage()`。
- `api/ApiConfig.kt` — 新增 `resolveHomePage(sourceBean): String?`（规则见下）；新增 import `androidx.media3.common.util.UriUtil`、`java.util.function.Supplier`。
- `api/WarmQueue.kt` — 搜索爬虫预热跳过 homePage 站点（防 `searchable=1` 的 homePage 站被预热到占位爬虫）。
- `ui/page/HomeViewModel.kt` — 新增只读状态 `homePageUrl: StateFlow<String?>`；`loadHome()` 在常规重置后解析当前站 homePage，非空则直接置 Ready 态并 return（不调 `getSort`、不挂看门狗）；`retrySort()` 对 homePage 站直接 return。
- `ui/page/HomePage.kt` — 内容区 `when` 新增分支：`homePageUrl != null` 时渲染共享 `HtmlWebView`（`fillMaxSize` + top/bottom padding，顶栏/站点切换保留，不渲染分类 Tab 与网格）；切回普通站自动恢复网格首页（走原 loadHome 逻辑）。
- `ui/page/HtmlWebPage.kt` — 新增公开重载 `HtmlWebView(url, modifier)`（自带 holder、按 url `key()` 重建），与原 private 实现共用同一份 WebView 创建/设置代码（JS、DOM storage、database、文件选择回调、JS alert/confirm、`mediaPlaybackRequiresUserGesture=false`、file URL 访问、混合内容、系统 UA 兜底）；`HtmlWebScreen` 行为不变。
- `sourcedata/SortLoader.kt` — `getSort` 对 homePage 站直接回空分类（不初始化占位爬虫），兜住 HomeViewModel 之外的调用方。
- `sourcedata/SearchLoader.kt` — `getSearch` 对 homePage 站直接回空搜索结果，不调占位爬虫、不崩。

## homePage 解析规则（ApiConfig.resolveHomePage）

1. 空白 → 返回 null（不是 homePage 站点）。
2. 已是绝对地址（`http://`、`https://`、`file://`、`content://` 开头）→ 原样返回。
3. 相对地址 → 以当前配置地址（`KV` 的 `API_URL`）为基准，用 media3 `UriUtil.resolve` 解析：
   `./AV.html`、`../x.html`、`AV.html`、`sub/AV.html` 落在配置文件同目录/子目录，`/AV.html` 落到配置主机/文件系统根。
   配置地址是 `clan://` 时先按 `ConfigParser.clanToAddress` 映射到本地文件服务再解析；配置地址无 scheme 时补 `http://` 仅用于解析。
   没有可用配置地址或解析异常 → 返回原始值，交给 WebView 自行尝试。
4. 备注：配置加载时 `ConfigParser.fixContentPath` 已把正文里带引号的 `"./"`、`"../"` 文本预先 absolutize，
   所以 git raw 上的 `./AV.html` 到 bean 时多半已是绝对地址、本函数直接放行；本函数主要兜住无 `./` 的纯相对路径与绕过该处理的缓存恢复路径。

## 首页判断逻辑

`HomeViewModel.loadHome()`（切站 `switchSource`、配置重载都走它）：
重置状态后计算 `resolveHomePage(home)` 并写入 `homePageUrl` 状态 →
非空：`rec=Empty`、`sortsLoaded=true`、`pageLoading=false`，直接返回；
为空：照旧挂看门狗并 `getSort` 走网格首页。
UI 只读 VM 状态渲染（遵守 AGENTS.md 的 Composable 不穿透 api 层约束）。

## 已知限制

- homePage 站点没有原生搜索/分类/详情/播放页：搜索（含全局多源搜索）对它返回空，浏览、搜索、播放都在网页内部完成。
- 首页内嵌 WebView 不接管系统返回键的网页历史后退（返回键走 App 首页原有逻辑）；网页自身的导航控件不受影响。独立「网页」入口（HtmlWebActivity）的返回行为不变。
- WebView 仍是单窗口：页面里 `window.open`/target=_blank 的新窗口行为与上一轮网页入口一致，未单独支持多窗口。
- homePage 指向本地文件时走 `file://`，页面从 file 源发跨域请求是否被目标站 CORS 放行取决于对方站点，与上一轮一致。
