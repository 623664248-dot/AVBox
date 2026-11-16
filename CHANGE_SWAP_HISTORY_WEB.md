# v1.6.4 「记录」与「网页」入口互换

用户确认的互换：底部导航里的「记录」与首页右上角胶囊组里的「网页」（🌐）对调位置。
改完后：底部导航第 2 个槽位是「网页」（动作型，点击直达网页入口页），右上角胶囊组
中间按钮是「记录」（点击去历史记录页）。⋮ 和 🔍 不动，其他 tab 顺序/行为不变，
导航总数仍是 5 个。

> 编译状态：**未编译**。沙箱无 JDK 21 / SDK 37，只能静态检查（见文末）。

## 互换前的位置（事实来源）

- 底部导航由 `MainScreen.kt` 的 `AppTab` 枚举驱动，共用一份定义，三个导航面
  （`ShortNavigationBar`、`FloatingNavBar` 玻璃栏、`NavigationRail`）都从它生成条目：
  pager 4 页 HOME / RECORDS / FOLLOWING / SETTINGS，另在中间插入「直播」动作项，
  视觉顺序为 首页 · 记录 · 直播 · 追剧 · 我的（共 5 个）。
- 「记录」tab 的目的地：pager 第 1 页 `RecordsPage`（含历史/收藏两段），导航调用是
  `MainContent` 的 `selectTab(AppTab.RECORDS.ordinal)`（按 `navAnimationEnabled`
  走 animateScrollToPage / scrollToPage）。
- 「网页」🌐：只在 `HomePage.kt` 顶栏 actions 胶囊组里（⋮ 与 🔍 之间），
  `Icons.Filled.Language` 图标，点击 `HtmlWebActivity.start(context)`。
  grep 确认其他页面顶栏没有这组按钮，无需同步处理。

## 改动文件

| 文件 | 改动 |
| --- | --- |
| `ui/page/MainScreen.kt` | 导航槽位模型 + 三个导航面改按槽位渲染 + FloatingNavBar 新接线 + HomePage 传入 onOpenRecords |
| `ui/page/HomePage.kt` | 新增 `onOpenRecords` 参数；🌐 按钮换成记录按钮；删无用 import |
| `ui/navbar/FloatingNavBar.kt` | 单动作项泛化为多动作槽（新增 `NavActionItem`） |
| `ui/navbar/NavMetrics.kt` | `slotIndexOfTab` / `tabIndexOfSlot` 改为动作槽集合版 |
| `res/drawable/ic_tab_web.xml` | 新增：网页图标（与 ic_tab_* 同风格的 Material Symbols 地球路径，复用 ic_pref_language 的 pathData） |
| `VERSION` | v1.6.3 → v1.6.4 |

## 实现方式

### 槽位模型（MainScreen）

pager 与 `AppTab` **不动**：仍是 4 页，RECORDS 仍是第 1 页 —— 这样记录页的
目的地与导航调用保持字面不变。导航条渲染另用一份条目表：

- `navTabs = [HOME, FOLLOWING, SETTINGS]`（条里实际渲染的 tab）
- 动作槽常量：`NAV_WEB_ACTION_SLOT = 1`（记录原来的槽位）、`NAV_LIVE_ACTION_SLOT = 2`
  （直播原来的槽位，`navLiveHidden` 时不存在）
- 槽位 ↔ tab 下标换算统一走 `NavMetrics.tabIndexOfSlot(slot, navActionSlots)`，
  三个导航面共用同一套映射：

| 槽位 | 内容 |
| --- | --- |
| 0 | 首页（tab 0） |
| 1 | **网页（动作）** |
| 2 | 直播（动作，隐藏时此槽由追剧顺延） |
| 3 | 追剧（tab 1） |
| 4 | 我的（tab 2） |

### 网页槽位（动作型）

- 三个导航面在槽位 1 都渲染 `selected = false` 的动作条目，点击走 MainScreen 的
  `openWeb = { HtmlWebActivity.start(context) }` —— 与原顶栏 🌐 完全相同的入口逻辑，
  不切换 tab 选中态、不产生空白页。
- 图标统一用新 drawable `R.drawable.ic_tab_web`（三个导航面的条目图标本来就都走
  painterResource；顶栏原来用的 `Icons.Filled.Language` 矢量与它同为地球 glyph）。
- 文案 `R.string.html_web_entry`（「网页」/ Web / 網頁，三语已齐）：玻璃栏与
  NavigationRail 携带在条目 label 上（沿用原直播动作项的呈现方式：玻璃栏仅选中时
  显示 label、动作项永不选中故不显示；Rail 常显；ShortNavigationBar 动作项 label
  为 null，与原直播项一致），ShortNavigationBar 里同时用作 contentDescription。

### 记录按钮（顶栏）

- `HomePage` 新增参数 `onOpenRecords: () -> Unit = {}`（默认空实现，其他调用点不受影响；
  仓库内 HomePage 只有 MainScreen 一个调用点）。
- 顶栏胶囊组中间的 Box：图标换成记录 tab 原来的图标 `R.drawable.ic_tab_records`，
  contentDescription 用 `R.string.tab_records`（「记录」），点击调 `onOpenRecords()`。
- MainScreen 传入 `onOpenRecords = { selectTab(AppTab.RECORDS.ordinal) }` —— 与原记录
  tab 逐字相同的目的地（pager 第 1 页 RecordsPage）和导航调用（含动画开关行为）。
- HomePage 内 `Icons.Filled.Language`、`HtmlWebActivity` 的引用与 import 已清零。

### FloatingNavBar 泛化

- 新增 `data class NavActionItem(slot, item: GlassTabItem, onClick)`；`FloatingNavBar`
  参数由 `actionItem: GlassTabItem? + onActionClick: () -> Unit` 改为
  `actionItems: List<NavActionItem>`（唯一调用点就是 MainScreen，已同步）。
- 玻璃栏的 `selectedTabIndex` 改传「当前 pager 页对应的条目下标」
  （`navTabs.indexOfFirst { it.ordinal == pagerState.targetPage }`），`onTabSelected`
  收条目下标再映射回 pager 页。记录页不在条目表里 → 下标为 -1：
  `NavMetrics.slotIndexOfTab(-1, …)` 原样返回 -1，`DampedDragAnimation.animateToValue`
  会把目标 coerce 进值域（滑块停靠第 0 槽），且没有任何条目渲染为选中态。
- `NavMetrics.slotIndexOfTab/tabIndexOfSlot` 改为 `Set<Int>` 动作槽版；单动作集合时
  与旧公式逐项等价（含「拖拽停在动作槽上会落到下一个 tab」这个旧有行为）。
  旧的 `actionSlotFor(tabCount)` 公式保留在 NavMetrics 里（当前无调用方，留作文档化的
  中心槽规则，未删以免动公共工具面）。

### 全屏联动

homePage 站全屏时隐藏所有导航面的逻辑（`webFullScreenActive`）未动；全屏隐藏的是
整条导航，槽位模型不影响它。顶栏记录按钮在全屏时随顶栏一起隐藏，与原 🌐 一致。

## 已知限制

- 记录页（pager 第 1 页）仍可通过左右滑动 pager 到达（它仍是 pager 的一页）；
  到达后底部导航没有任何条目高亮（玻璃栏滑块停靠第 0 槽但首页不高亮、
  ShortNavigationBar / Rail 无选中项）。这是「记录不是 tab」后的预期表现。
- 玻璃栏里把滑块拖到「网页」槽位上松手，不会打开网页（沿用旧行为：落到下一个
  tab，即追剧页）；点按网页槽位才打开。直播槽同理（与旧版一致）。
- 图标观察（供确认）：代码里记录 tab 的图标 `ic_tab_records` 是「书签+星」形，
  而「带时钟的日历」形图标是追剧 tab 的 `ic_tab_following`。本轮按任务定义
  （记录 tab / 历史记录页）使用 `ic_tab_records` 作为顶栏记录按钮图标；
  若用户指的是日历时钟那个形，需要换的是图标资源而非目的地。
- 未编译（沙箱无 JDK 21 / SDK 37），云构建结果为准。

## 静态自检

- grep：`HomePage.kt` 中 `Filled.Language` / `HtmlWebActivity` 零残留（旧 🌐 顶栏入口
  已清除）；`html_web_entry` 全仓只剩 MainScreen 网页动作项一处使用；
  `FloatingNavBar` 旧参数 `actionItem` / `onActionClick` 零残留；
  `AppTab.RECORDS` 引用完整（枚举、pager 分支 `RecordsPage`、顶栏 `onOpenRecords`），
  无悬空引用。
- 四个改动的 .kt 文件括号/圆括号/方括号配平均为 0。
- 改动段逐段通读：槽位映射在 直播显示/隐藏 两种配置下分别验算
  （5 槽：0 首页 1 网页 2 直播 3 追剧 4 我的；4 槽：0 首页 1 网页 2 追剧 3 我的），
  `slotIndexOfTab` / `tabIndexOfSlot` 互逆关系成立。
