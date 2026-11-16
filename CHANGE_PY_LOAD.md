# v1.14.0 — 视频 py 站点加载慢 / 无限转圈

背景（手机实测，2026-11-16）：配置 ss.json 有 823 个站点，全是 type 3、api 都是
`./py/xxx.py` 形式，无 jar。18XX[py] 完全正常（分类/封面/播放都行），说明路径
解析没问题；91社区[py]（多步 token 登录的重型爬虫）、AVTOP10[py]（本地代理
服务 + 多站源）进站转圈 25 秒以上出不来。125AV[py] 的「暂无内容」不是 App
问题（它自己的爬虫用 urllib 会被站方断连接），已单独修 py 文件交付，不在本版。

## 1. sort / list 爬虫调用超时 15 秒 → 30 秒（独立常量）

位置：
- `sourcedata/SortLoader.kt`：新增 `companion object` 的
  `SPIDER_TIMEOUT_MS = 30_000L`；`sortFromSpider()` 的 `BoundedCall.call`
  改用它（原 `sourceBean.playTimeoutSeconds() * 1000L`）。
- `sourcedata/ListLoader.kt`：新增同名 `companion object` 常量；
  `listFromSpider()`（categoryContent）与 `homeRecList()`（homeVideoContent）
  的 `BoundedCall.call` 改用它。

旧行为：两个调用都走 `SourceBean.playTimeoutSeconds()`，默认 15 秒
（`SourceBean.kt:117`，`timeout` 未设时返回 15）；重型爬虫 15 秒内跑不完
就被 `BoundedCall` 掐断，返回 null → 加载失败。
新行为：两个调用各用独立的 30 秒超时；`playTimeoutSeconds()` 的默认值
**没有改**，`DetailLoader` / `PlayLoader` 的超时**没有动**——播放链路
保持现状。`sortFromExtendedApi` / `listFromExtendedApi` 里给
`SourceHelper.getFixUrl` 传的 `playTimeoutSeconds()` 也没动（那是 extend
取数，不是爬虫调用）。

## 2. 去掉 homeContent 失败后的静默自动重试

位置：`ui/page/HomeViewModel.kt` 的 `onSortResult()`，`loadFailed` 分支。

旧行为：`loadFailed` 时若 `sortRetried` 为 false，先静默 `getSort` 再跑
一次，等于再等一个完整超时（15 秒旧值下最长 30 秒转圈，30 秒新值下
最长 60 秒）才进失败态。
新行为：`loadFailed` 直接进现有失败态（`_sortLoadFailed = true`，
`_rec` / `_partitions` / `_sorts` / `_allSorts` 清空，`sortsLoaded = true`），
页面显示加载失败 + 手动重试（复用已有 `retrySort()` 路径）。失败日志
`echo--sort-failed` 保留。

**不动**：空分类结果（`visible` 为空且无 `loadFailed`）的 2 秒延迟重试
保持原样（仍由 `sortRetried` + `loadGeneration` 守卫）。

## 3. watchdog 对齐到 35 秒 + 迟到结果丢弃

位置：`ui/page/HomeViewModel.kt`。

- `companion object` 新增 `HOME_LOAD_WATCHDOG_MS = 35_000L`；
  `armWatchdog()` 的 `delay(20_000)` 改为 `delay(HOME_LOAD_WATCHDOG_MS)`。
  旧行为：watchdog 20 秒就把还在跑的分区翻成 Error，比 30 秒的爬虫调用
  还早 10 秒。新行为：35 秒，比调用超时多 5 秒余量，watchdog 只在调用
  真正超时/卡住后才接管。
- 新增 `private var homeLoadTimedOut = false`：`onHomeLoadTimeout()` 真正
  把 rec/分区翻成 Error 时置 true；`loadHome()` 与 `retrySort()` 里清
  false。`onSortResult()` 在现有的 sourceKey  stale 检查之后加一道：
  `homeLoadTimedOut` 为 true 时直接丢弃（日志 `echo--sort-late-drop`），
  不重建分区、不把 Error 页面翻回 Loading。用户从 Error 态手动重试，
  `retrySort()` 会先清掉这个标志。
- 迟到的 **列表** 结果不在本版处理范围：`requestPartition` 已有
  generation 检查；迟到的列表成功仍会把对应分区从 Error 翻成 Ready
  （给用户内容，不翻回 Loading），与任务要求一致。

## 4. 分类缓存复用：有 classes 就直接用

位置：`sourcedata/SortLoader.kt` 的 `getSort()` 与 `cacheSort()`。

旧行为：读缓存时只在缓存的 `videoList` 非空时才提前返回；纯分类结果
（有 classes、无首页推荐视频）每次进站都要重跑 `homeContent`。
新行为：读缓存条件放宽为 `classes.sortList` 非空 **或** `videoList`
非空（后者与旧行为一致）；命中后照常 `attachSortSource` +
`postSortResult`，列表仍按现流程逐分区加载。

**配套的写侧改动（否则读侧放宽是死代码）**：`cacheSort()` 原先在
`!hasHomeRecVideos(sortXml)` 时直接 return，纯分类结果根本进不了缓存。
现改为 `!hasHomeRecVideos && !hasSortClasses` 时才 return，新增
`hasSortClasses()`（`classes.sortList` 非空即 true）。原有的
`shouldBypassSortCache`（首页 + 豆瓣源不缓存）与 `hasActionSort`
不缓存逻辑一律保留。

## 30 秒仍不够时的表现

- sort 调用 30 秒超时 → `BoundedCall` 返回 null → `postSortFailure` →
  `loadFailed` → 立即显示加载失败 + 手动重试，**不再自动重试、不再
  无限转圈**。上限约 30 秒见失败态。
- list 调用 30 秒超时 → 该分区 `listResult` 为 null → `applyPartitionResult`
  仍有一次该分区的重试（`listRetried` 守卫），再失败则该分区进 Error
  态 + 分区级手动重试；其余分区不受影响。
- watchdog 35 秒是最后一道：任何仍在 Loading 的 rec/分区翻成 Error，
  并发出 `home_load_failed` / `home_load_partial_timeout` 事件。
- 仍不够的爬虫（如登录链路本身超过 30 秒）需要站点侧优化或进一步放宽，
  不在本版继续加码。

## 改动文件

- `app/src/main/java/com/github/avbox/osc/sourcedata/SortLoader.kt`
- `app/src/main/java/com/github/avbox/osc/sourcedata/ListLoader.kt`
- `app/src/main/java/com/github/avbox/osc/ui/page/HomeViewModel.kt`
- `VERSION` → v1.14.0
- `CHANGE_PY_LOAD.md`（本文件）
