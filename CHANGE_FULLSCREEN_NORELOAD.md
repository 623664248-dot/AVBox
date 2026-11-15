# v1.6.3 — 退出/进入全屏不再重载网页

背景：v1.6.2 的已知限制——homePage 站点退出全屏回到站点页时网页会重载一次根页面。本轮只修这一处，全屏状态机（`HomeViewModel.homeWebFullScreen`）、返回链、长按胶囊刷新均保持 v1.6.2 语义未动。未编译（沙箱无 JDK 21 / SDK 37），仅静态检查：改动段落逐段通读、三文件括号/圆括号配平（剥离字符串/注释后计数一致）、grep 引用完整性。本仓库副本无 `.tools/` 目录，门禁脚本未跑（与前几轮一致）。未动 git、未 commit。

## 根因（v1.6.2 结构）

`HomePage()` 顶层是 `if (homePage 站 && 全屏) { 裸 HtmlWebView } else { AppTopBarScaffold { … } }`：

- 全屏时 WebView 在 HomePage 根部的 `key(url)` 里；
- 带栏时 WebView 在 Scaffold 内容 lambda 的 `when` 分支里，是另一个调用点。

全屏开关一切换，组合树里 WebView 的位置就变了，旧 `AndroidView` 被 dispose（`onRelease` 里 `holder.webView = null` + `destroy()`），新位置重新走 factory 并 `loadUrl(根 URL)`——页面、历史、JS 状态全丢，表现为「退出全屏重载一次根页面」。进入全屏方向同理多一次重载。

## 重构方式

### 1. `ui/components/EdgeToEdgeTopBar.kt` — AppTopBarScaffold 加显隐参数

- 新增参数 `topBarVisible: Boolean = true`（其余 9 个调用方全部走默认值，行为零变化）。
- `topBarVisible == false` 时 Scaffold 的 `topBar` slot 传空 lambda。此时 Scaffold 给出的 top padding 为 0（该 Scaffold 的 `contentWindowInsets` 本就是 `WindowInsets(0,0,0,0)`），内容 lambda 收到的 `topPad` 随之归零。
- slot 内容变化只影响 Scaffold 内部布局测量，**内容 slot 的组合位置不变**。

### 2. `ui/page/HomePage.kt` — WebView 收敛到唯一组合位置

- 删掉顶层全屏 if 分支（含其中独立的 `key(url) { HtmlWebView(fillMaxSize) }` 调用点）。
- `AppTopBarScaffold` 改为无条件只调用一次，传入：
  - `topBarVisible = homePageTarget == null || !webFullScreen`
  - `topScrimEnabled = homePageTarget == null || !webFullScreen`（全屏时 scrim 一并关；其高度本来也随 topPad 归零）
- 内容 lambda 的 `when` 分支与顺序原样保留（pageLoading → 空源 → homePage → 网格），其中 homePage 分支成为全 App 内该 WebView 的**唯一调用点**，仍是 `key(homePageTarget)` + 同一个 `webHolder` + `Modifier.fillMaxSize().padding(top = topPad, bottom = navBottom)`：
  - 全屏：`topPad = 0`（顶栏隐藏）、`navBottom = 0`（MainScreen 全屏时传 `PaddingValues(0)`）→ WebView 占满，与 v1.6.2 全屏视觉一致；
  - 带栏：`topPad` = 状态栏+顶栏高、`navBottom` = 导航预留，与 v1.6.2 带栏视觉一致。
  - `key` 只随站点（URL）变化——切站仍按既有行为新建 WebView 加载新站根页；全屏开关不碰 key。
- `BackHandler` 位置与逻辑未动，仍指向同一 holder 的活 WebView。

### 3. `ui/page/MainScreen.kt` — 不改

复核结论：v1.6.2 的全屏联动在 MainScreen 侧只有两类变化，都不触及 WebView 的组合身份——

- `pageContentPadding` 数值变化（`PaddingValues(0)` ↔ 带导航预留）：经 HomePage 参数最终落到 WebView 的 modifier padding 上，只是同一节点的重新测量/布局；
- Scaffold `bottomBar` slot 与浮动栏/侧栏的显隐：它们是 pager 的兄弟/祖先节点，pager 中 HomePage 的页位置（`beyondViewportPageCount = 3` 常驻组合）不变。

`AndroidView` 的 factory 只在节点首次组合时跑，modifier 与兄弟 slot 变化不会重跑 factory，也不会触发 `onRelease`。故 MainScreen 保持 v1.6.2 原样，普通站点的布局行为零改动。

## 为什么这样能保住 WebView 实例

Compose 里 `AndroidView` 的存活取决于它在组合树中的位置（调用点 + key）恒定：

- 切换前后 WebView 恒在「HomePage → AppTopBarScaffold → Scaffold 内容 → Box → Box → content lambda → when 的 homePage 分支 → key(url)」这一条固定路径上，调用点与 key 都不变 → 同一个 AndroidView 节点被复用，factory 不重跑、没有新的 `loadUrl`、holder 里的 WebView 不被置空。
- 全屏开关改变的只是输入值：Scaffold topBar slot 内容（空/顶栏）、`topPad`、`navBottom`、MainScreen 的底栏与内容 padding——全是布局层输入，只引起重新测量与 WebView 视图尺寸变化（页面按新视口重排，但不重新加载，历史与 JS 状态保留）。
- 返回链落点随之自然成立：全屏内返回先 `goBack()` 走历史；到历史根 `exitHomeWebFullScreen()` 只翻转 VM 状态 → 顶栏/底栏恢复、padding 回位，用户仍停在当前这张根页面上（同一 WebView、同一页），不再有销毁重建和根 URL 重载。
- 进入方向同理：切到 homePage 站时 `loadHome()` 置 `homeWebFullScreen = true`，WebView 在唯一位置上首次组合并加载一次根页，之后顶栏直接以隐藏态出现，没有「先带栏组合、再切全屏分支」的第二次重载。

## 改动文件

- `app/src/main/java/com/github/avbox/osc/ui/components/EdgeToEdgeTopBar.kt`（AppTopBarScaffold 新增 `topBarVisible` 参数，topBar slot 按它显隐）
- `app/src/main/java/com/github/avbox/osc/ui/page/HomePage.kt`（删除全屏 if 分支，Scaffold 恒定调用并传显隐，WebView 收敛到唯一调用点，相关注释更新）
- `VERSION` → `v1.6.3`
- MainScreen.kt / HomeViewModel.kt / HtmlWebPage.kt 未改动。

## 静态自检

- HomePage 中 `HtmlWebView(` 调用点只剩 1 处（grep 确认），且在 `when` 的 homePage 分支内；
- HomePage.kt、EdgeToEdgeTopBar.kt、MainScreen.kt 括号/圆括号配平一致；
- `topBarVisible` 仅 HomePage 传入，其余 AppTopBarScaffold 调用方走默认 `true`；
- `webFullScreen` 在 HomePage 的引用只剩状态收集、BackHandler enabled、topBarVisible/topScrimEnabled 四处，语义与 v1.6.2 一致；
- 未新增 import、未新增 `!!`、未新增 UI 层状态（显隐是 Scaffold 的普通参数，状态真源仍是 VM 的 `homeWebFullScreen`）。

## 已知限制

- 退出全屏的「粘性」与 v1.6.2 相同（VM 行为未动）：同一站点重载/重选不自动回全屏，切站再切回才重新默认全屏——区别只是现在退出不再重载页面。
- WebView 在全屏↔带栏切换时会经历一次尺寸变化（padding 增减），页面会按新视口重排一次布局（可能伴随一帧重绘），这是布局变化不可避免的，但不是重新加载：URL、历史栈、滚动位置之外的页面状态均保留（滚动位置由 WebView 自身按新视口保持）。
- 全屏态下在其他 tab 按返回仍会先退出（不可见的）首页全屏，与 v1.6.2 一致，未做生命周期门控。
- 本轮未编译、未真机验证：需真机确认三点——① 全屏内点进子页面后返回到根、再返回退出全屏，页面不刷新（可用页面内已展开的菜单/播放进度观察）；② 从普通站切到 [HTML] 站只加载一次；③ 普通站点顶栏/玻璃效果与之前一致（AppTopBarScaffold 默认参数路径）。
