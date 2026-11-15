# FongMi WebHome `window.fm` 原生桥（v1.6.0）

让 WebHome 单页应用（如用户的 AV.html 聚合页）在 AVBox 内置 WebView 里真正跑起来并播放。
未编译（沙箱无 JDK 21/SDK 37），静态检查完成；未动 git、未 commit。

## 桥面清单（从 AV_7_6mm2.html 逐个 grep `fm\.` 抠出，与实现对齐）

页面检测：`isNative(){ return !!(window.fm && window.fm.req) }`；启动时 `whenFm()` 等
`fmsdk` 事件（超时 2500ms）后才 boot。

| 成员 | 页面侧签名 / 期望 | 实现状态 |
|---|---|---|
| `fm.req(url, opt)` | Promise → `{ok, status, body, error}`（页面用 ok/status/body/error；error 在 HTTP 错误时须是 `"HTTP <status>"` 形式，其诊断层靠它正则抠状态码做 CF 重试）。opt：method 小写 get/post、headers、data+postType('form')/body、responseType('text')、timeout（秒）、credentials | ✅ 已实现：走 `HttpClients.defaultClient`（App 现有 OkHttp 栈，跟随重定向、自定义 DNS/代理与 App 一致），按 opt.timeout 派生 callTimeout（钳制 3–120s）；POST 支持 form 对象/字符串、json、raw body；恒 resolve 结果对象（网络失败为 status 0），body 上限 8MB |
| `fm.res(url, {headers})` | **同步**返回可直接当 img.src 的 URL 字符串 | ⚠️ 原样返回（见受限项） |
| `fm.cache.get(key, ns)` | Promise → 字符串（缺失为 ''） | ✅ SharedPreferences（文件 `fm_webhome_cache`，键 `ns:key`） |
| `fm.cache.set(key, value, ns)` | Promise | ✅ 同上 |
| `fm.play(url, title, opt)` | Promise，opt `{pic, wallPic, format:'hls'/'mp4', headers}`；reject 时页面回退嗅探 | ✅ 打开原生播放器（见下），启动成功 resolve、失败 reject |
| `fm.vodInline(obj)` | Promise，obj `{vod_id, vod_name, vod_pic, wallPic, vod_play_from, mark, headers, episodes:[{name, url, resolve, pageUrl}]}`；页面首选路径 | ✅ 已实现，含 resolver 回调（见下） |
| `fm.pan.play({type:'http', url, title, pic, wallPic})` | Promise；页面解析不出直链时的嗅探兜底 | ❌ 安全 stub：恒 reject（见受限项） |

## 注入方式

- 仓库没有 androidx.webkit 依赖（版本目录无 webkit 制品、全仓无 WebViewCompat 用法），
  故未用 `addDocumentStartJavaScript`，采用兜底方案：共享 WebView 的
  `WebViewClient.onPageStarted` 里 `evaluateJavascript(SHIM_JS)`，`onPageFinished` 再补注一次
  （shim 幂等：`if (window.fm) return`）。页面 whenFm 有 2500ms 等待窗口，onPageStarted 注入足够及时。
- shim 定义 `window.fm` 后派发 `fmsdk` 事件。异步模型：shim 用自增 id + Promise 注册表把
  `fm.*` 包装成 `__fmNative.invoke(id, method, argsJson)`；原生完成后在主线程
  `evaluateJavascript("window.__fmResolve(id, <json>)")` 回填 resolve/reject。
  `fm.res` 走同步 `@JavascriptInterface` 方法（页面同步使用其返回值）。
- 宿主逻辑全部放在新文件 `ui/FmBridge.kt`（无 Composable，符合「Composable 只渲染」约束；
  HtmlWebPage.kt 只新增 attach/detach 与注入三处调用，未引入 api/base/data/sourcedata/net 导入）。
  桥对象只持有 WebView 的 WeakReference，`AndroidView.onRelease` 先 `FmBridge.detach()` 再 destroy，
  页面销毁后的回调会被 destroyed 标志安全丢弃。

## 播放如何接 AVBox 播放器

复用 App 内置的 **push_agent 直播通道**（不是新造播放器）：

- 打开 `DetailActivity`，extras：`sourceKey="push_agent"`、`id=<播放地址>`、`title`、`picture`，
  新增 `autoPlay=true`。
- 详情侧：`DetailLoader` 对 push_agent（合成 SourceBean type=-1）本地合成单集详情，不走任何爬虫；
  播放侧：`PlayLoader` 对 push 兜底直接回 `parse=0` 直链结果，不需要源站 playerContent。
- 带 headers 时把 headers 以 PushUrlParser 的 `@Headers=<URLEncoder 编码的 JSON>@` 标记拼进地址，
  PlayLoader 会解出并合并到播放请求头（与投屏推送同一机制，HeaderGuard 过滤非法头）。
- 自动播放：Jump/DetailNavStack.Target/DetailViewModel 新增 `autoPlay` 通道（默认 false，
  仅 fm 桥传入 true）——目标详情首次 Ready 且有集数时一次性触发
  `DetailPlaybackEntry.PlayCapsule`（全屏开播），普通详情打开行为不变。
- `fm.vodInline` 的 `resolve: true`：原生侧在主线程 evaluateJavascript 调用页面注册的
  `window.__fmWebHomeInlineResolver(episode)`，取回 `{url, format, headers}`
  （resolver 的 headers 覆盖 vod 级 headers），再走同一 push 通道；
  resolver 缺失/无结果时退回 episode.url + vod 级 headers（本页 episode.url 本就是已解析的
  playUrl，仍可播）。format（hls/mp4 / MIME）仅作参考，播放器按地址本身识别。

## 改动文件

- 新增 `app/src/main/java/com/github/avbox/osc/ui/FmBridge.kt` —— 桥宿主（shim JS + JavascriptInterface + OkHttp 请求 + 播放启动 + 缓存）。
- `app/src/main/java/com/github/avbox/osc/ui/page/HtmlWebPage.kt` —— 共享 WebView attach 桥、onPageStarted/onPageFinished 注入 shim、onRelease detach。「网页」入口与 homePage 站点共用同一 WebView 工厂，两处同时生效。
- `app/src/main/java/com/github/avbox/osc/ui/page/Jump.kt` —— `jumpToDetail` 增加默认参数 `autoPlay`。
- `app/src/main/java/com/github/avbox/osc/ui/DetailNavStack.kt` —— Target 增加 `autoPlay=false` 字段。
- `app/src/main/java/com/github/avbox/osc/ui/DetailViewModel.kt` —— parseTarget 读 autoPlay；applyTarget 记 pendingAutoPlay；详情首次 Ready 时消费并自动开播。
- `VERSION` —— v1.5.1 → v1.6.0。

## 未实现 / 受限项及对 AV 页面的实际影响

1. **fm.pan.play（嗅探兜底）未实现**：AVBox 的 WebView 嗅探（PlayUrlResolver）深度绑在播放页
   生命周期里，没有独立入口可复用，故恒 reject。影响：页面自己解析不出直链时（其设置里的
   「解析失败回退」开关）救不回来；但本页正常路径是自己用 fm.req 抓页解析出 m3u8 再走
   vodInline/fm.play，不依赖 pan.play，主流程不受影响。
2. **fm.res 原样返回**：本地服务只有蜘蛛用的 `/proxy?do=...`，没有通用「带 headers 取资源」
   的代理端点，故不拼代理 URL。影响：封面图由 WebView 直连图床，不带页面指定的
   Referer/UA；此前无桥时该页在 AVBox 里封面本就能显示（走的就是直连），个别强防盗链图床
   可能裂图，届时再考虑给 RemoteServer 加通用资源代理端点。
3. **vodInline 只播第一集**：push 通道合成详情只有一个集数。本页每次只传 1 集，无影响；
   其它 WebHome 页若传多集，后面的集数暂时选不了。
4. **详情页标题是播放地址**：push 通道合成详情以地址为名（全 TVBox 系推送皆然），
   播放器里显示的标题会是 URL 而不是片名；fm 传的 title 用于打开详情时的初始标题与换源搜索。
5. **fm.req 的 credentials:'include' 不完整**：OkHttp 栈未挂 CookieJar，与 WebView 的
   Cookie 不互通；页面自己在 headers 里显式带的 Cookie 可透传。需要登录态的站点
   （如 Javverse 登录墙）仍要靠页面自身流程。
6. **注入时机**：onPageStarted 注入在极少数缓存页上可能晚于页面首帧脚本；页面自身的
   isNative 是动态判断、whenFm 有 2.5s 窗口，实际影响仅限首帧的 `fm-native` 样式类可能缺失（纯外观）。

## 静态自检

- shim JS 抽出后 `node --check` 通过；Kotlin 侧 6 个分发方法名与 shim 的 call() 一一对应，
  `native.res` 同步方法存在；fm 成员清单与 HTML 的 grep 结果逐项对齐。
- 全部改动文件括号配平；HtmlWebPage.kt 未新增受门禁限制的导入。
- `.tools/` 门禁脚本在本副本不存在（gitignore 本地目录），未能执行，与前几轮一致。
