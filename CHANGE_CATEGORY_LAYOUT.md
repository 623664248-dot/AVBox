# 首页分类布局改版：堆叠分区 → 顶部分类选项卡 + 单分类网格

基于 XiaochangXu/AVBox `main`（commit bfab6085）。只改首页分类呈现，
**没有**动配置/jar 加载链（本地配置加载慢的问题按要求先放放）。

## 改了哪些文件

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/github/avbox/osc/ui/page/HomePage.kt` | 首页不再按布局设置二选一：删掉横版（沉浸）分支的整条 `LazyColumn`（Hero 推荐位 +「全部分类」胶囊行 + 推荐区 + 每个分类一段的 `PartitionSection` 横排），首页统一渲染已有的 `HomeGridLayout`（选项卡 + 网格）；随之删除只服务堆叠布局的 `PartitionSection` / `HomeCategoryChips` / `HomeCapsule`、Hero 背景状态与无用 import。顶栏、站点切换面板、搜索入口、下拉刷新、卡片点击/长按菜单保持原样。 |
| `app/src/main/java/com/github/avbox/osc/ui/page/HomeViewModel.kt` | 数据侧按网格布局语义收敛：`getSort(..., withRec)` 全部改为 `false`（不再为 Hero 拉推荐位）；分类结果里只有当前选中分类进入 Loading 并请求第一页，其余保持 Idle，选中时才由 `ensureLoaded()` 懒加载；`refreshPartitions()` 只刷新当前分类；删除只服务横版切换的 `onLayoutChanged()`（切到横版时批量加载全部分区 + 推荐位）。 |
| `app/src/main/java/com/github/avbox/osc/ui/components/SearchSettingsSheet.kt` | 删除「海报样式：沉浸 / 普通」布局切换器——首页只剩一种布局，这个开关已无作用。搜索设置其余项不变。 |
| `app/src/main/java/com/github/avbox/osc/ui/page/MainScreen.kt` | 底部导航渐变 scrim 原先对横版首页跳过，现首页统一为网格布局，改为与原竖版一致地显示；顺带清理无用引用。 |

未改动：`HomeGridLayout.kt` 本身（选项卡 + 网格 + 分页的实现直接复用）、
`PartitionListActivity`（独立的分类详情页，未受影响）、`HomeSettings.kt`
（布局存储保留兼容，其单元测试不受影响，但首页不再读取它）。

## 新首页流程

1. 进首页 → 先拿分类列表（spider 的 homeContent classes），顶栏下方出现一条
   可横滚的分类选项卡（`SecondaryScrollableTabRow`），默认选中第一个分类。
2. 下方只有**当前选中分类**的一个全页网格（列数、卡片样式与原网格一致，
   分类自带筛选时网格上方仍有筛选 chips / 筛选按钮）。
3. 点另一个分类 → 切换到那个分类的网格；该分类没加载过就从第一页加载，
   已加载过的保留其列表与滚动位置，不会串数据、不会重复从头拉。
4. 网格滚到接近底部（或滚到列表尾的加载项）→ 自动请求下一页并**追加**到
   当前分类列表；`loadMorePartition` 有 state / hasMore / loader.busy 三重
   守卫，不会重复请求同一页；没有下一页时显示「没有更多」。

## 构建验证状态

**未构建（未验证编译）**。原因：本机只有 JDK 17（`~/jdk17`）与 Android SDK
platform 34 / build-tools 34.0.0、无 NDK，而本仓库要求 JDK 21、
compileSdk 37、build-tools 37、NDK 27.2.12479018（Gradle 9.8.2 / AGP 9.5.1），
工具链不满足，未在本机安装。

已做的静态核查：
- 4 个改动文件逐个复读，括号/签名/引用一致；全仓检索确认被删符号
  （`PartitionSection`、`HomeCategoryChips`、`onLayoutChanged`）无残留引用；
- 与原始 tarball 做 `diff -rq`，确认只有上述 4 个文件有改动；
- `HomeGridLayout`、`PartitionListActivity`、`HomeSettings` 及其测试未动。

在装有 JDK 21 + SDK 37 的机器上可用以下命令验证（debug 构建）：
`./gradlew :app:assembleDebug`
