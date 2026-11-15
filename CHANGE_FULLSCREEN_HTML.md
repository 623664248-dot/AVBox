# v1.6.2 — [HTML] 站默认全屏 + 返回链 + 长按胶囊刷新

背景：v1.6.1 真机验证 [HTML] 站（homePage 站点）页面已能正常显示。本轮按用户录屏里的对比诉求改交互，共三处。未编译（沙箱无 JDK 21 / SDK 37），仅静态检查：改动段落逐段通读、grep 引用完整性、四文件括号/圆括号配平（剥离字符串/注释后计数一致）。本仓库副本无 `.tools/` 目录，门禁脚本未跑（与前几轮一致）。未动 git、未 commit。

## 状态真源（新增）

`ui/page/HomeViewModel.kt`：

- 新增只读状态 `homeWebFullScreen: StateFlow<Boolean>`（`private val _homeWebFullScreen`，符合 UDF 约束）。
- `loadHome()` 里：解析出的 homePage URL **与当前值不同**时才更新全屏状态——homePage 站激活 → `true`（默认全屏）；普通站 → `false`。同一站点的重载（配置刷新、订阅重载）URL 不变 → 保留用户当前的全屏选择，不会把已退出全屏的用户又弹回全屏。
- 新增 `exitHomeWebFullScreen()`：仅置 `false`。

## 1. [HTML] 站默认全屏

- `ui/page/HomePage.kt` — `HomePage()`：`homePageTarget != null && webFullScreen` 时**不组合 `AppTopBarScaffold`**，直接 `key(url) { HtmlWebView(fillMaxSize) }`，网页占满整个内容区；否则走原 scaffold（内部 homePage 分支保持 v1.6.1 的顶栏下布局，普通站布局与顶栏行为零改动）。
- `ui/page/MainScreen.kt` — `MainContent()`：`webFullScreenActive = homeWebFullScreen && pagerState.targetPage == HOME`（只在首页 tab 生效，滑到其他 tab 时导航自动恢复）。生效时：
  - Scaffold `bottomBar`（ShortNavigationBar）不渲染；
  - 液态玻璃 `FloatingNavBar` + 底部 scrim 整块不渲染；
  - 横屏/平板 `NavigationRail` 不渲染；
  - `pageContentPadding` 变为 `PaddingValues(0.dp)`，首页不再为导航留白（非玻璃模式下 Scaffold 的 innerPadding 随 bottomBar 消失自动归零）。
- 系统状态栏未做任何改动（不隐藏、不改样式），与现状一致。

## 2. 返回回到资源站点

返回链（全屏态下，自上而下）：

1. **WebView 历史后退**：`HomePage()` 新增 `BackHandler(enabled = homePageTarget != null && webFullScreen)`：`webHolder.webView.canGoBack()` → `goBack()`。
2. **历史到根 → 退出全屏**：`canGoBack() == false`（或 WebView 尚未就绪）→ `vm.exitHomeWebFullScreen()`，恢复顶栏+底栏、回到带栏的站点页（仍是当前 [HTML] 站）。这一下返回**不经过** `MainScreen` 的双击退出计数（`lastBackTime` 不动）。
3. **退出全屏后**：HomePage 的 handler 停用，落回原有逻辑——`MainScreen` 的 `BackHandler`「再按一次返回键退出」+ toast，与普通站完全一致。

优先级依据：HomePage 在 `MainContent` 的 `BackHandler` 之后组合（pager 内容），其启用的 handler 后注册、先执行——与本仓库其他页面（FollowingPage/RecordsPage 的编辑态 BackHandler、BottomSheet 的 BackHandler）同一模式。独立「网页」入口 `HtmlWebScreen` 的返回行为未动。

为让 handler 够得到 WebView：`ui/page/HtmlWebPage.kt` 的 `HtmlWebHolder` 与 holder 版 `HtmlWebView(url, holder, ...)` 由 `private` 改为公开（同包内 HomePage 共用一个 `remember { HtmlWebHolder() }`，全屏/带栏两种布局都传同一个 holder，handler 永远指向活着的那个 WebView）。

## 3. 长按左上角站点胶囊刷新

`ui/page/HomePage.kt` 顶栏站点胶囊：`.clickable` 改为 `.combinedClickable`（文件头 `@file:OptIn` 增加 `ExperimentalFoundationApi`，与仓库内 SettingsGroup/PressableCard 的用法一致）：

- 普通点击不变：`refreshSubscribes()` + 打开站点选择底单；
- **长按 → `vm.refreshConfig()`**，与底单条目长按完全同一条路径（`AppBootstrap.onApiUrlChanged()` 重载配置 → Ready 后 `loadHome()` → Toast「已刷新」）。
- 底单条目长按保留未动；顶栏右侧三个按钮（设置/网页/搜索）仍是普通 `clickable`，无冲突（长按判定只挂在胶囊自身的 combinedClickable 上）。

## 改动文件

- `app/src/main/java/com/github/avbox/osc/ui/page/HomeViewModel.kt`（全屏状态 + loadHome 联动 + exit 函数）
- `app/src/main/java/com/github/avbox/osc/ui/page/HomePage.kt`（全屏分支、BackHandler、胶囊 combinedClickable、两处 HtmlWebView 传 holder）
- `app/src/main/java/com/github/avbox/osc/ui/page/MainScreen.kt`（底栏/浮动栏/侧栏/内容留白的总开关）
- `app/src/main/java/com/github/avbox/osc/ui/page/HtmlWebPage.kt`（HtmlWebHolder 与 holder 版 HtmlWebView 公开）
- `VERSION` → `v1.6.2`

## 最终行为速览

- 切到 [HTML] 站：立即全屏（无顶栏、无底栏），网页全区显示。
- 全屏内点链接跳转：返回先在网页内后退，逐级退回。
- 退到网页根页再返回：退出全屏，回到带顶栏/底栏的站点页；再返回走原「再按一次退出」。
- 长按左上角站点名（带栏状态下）：重载配置并刷新，弹「已刷新」；点按仍是打开站点列表。

## 已知限制

- **退出全屏会重建一次 WebView**：全屏与带栏是两个组合分支，退出时 WebView 被销毁重建并重新加载 homePage 根 URL。因退出只发生在历史根（canGoBack 为 false），重建后落点就是站点首页，视觉上无跳变；但页面内的 JS 运行时状态（如已展开的菜单）会重置。
- **退出全屏对当前站是粘性的**：退出后长按刷新、重选同一站（底单里点已选中站无动作）都不会再进全屏；切到别的站再切回来（URL 变化）会重新默认全屏。
- 全屏态下 pager 仍可横滑切 tab；滑离首页 tab 后导航栏与留白自动恢复（`webFullScreenActive` 带 tab 条件），HomePage 的 BackHandler 仍按状态启用——若用户在其他 tab 按返回，会先把（不可见的）首页退出全屏，再按一次才走退出 App 逻辑。与仓库其他页面的状态型 BackHandler 行为一致，未做生命周期门控。
- 全屏 WebView 顶到状态栏之下（沿用全 App 的 edge-to-edge 现状，状态栏本身不隐藏）；页面自身若没有顶部留白，其标题栏可能与状态栏图标重叠——这是网页自身的适配问题，与独立「网页」入口的表现一致。
