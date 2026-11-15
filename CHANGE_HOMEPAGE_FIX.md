# homePage 站点不生效修复（v1.5.1）

现象：v1.5.0 真机上选中 AV[HTML]、禁片天堂h[HTML] 站，首页显示 AVBox 原生
「加载失败，请检查网络 / 重试」，WebView 分支从未出现。

**未编译**：沙箱无 JDK 21 / SDK 37，仅静态检查（逐段通读真实链路 + grep）。
未动 git、未 commit；未回退任何已有改动。

## 根因

`app/src/main/java/com/github/avbox/osc/api/ConfigParser.kt` 的 `parseSites()`。

AVBox 的站点解析**不是 Gson 按字段名映射**，而是 `parseSites()` 逐字段手工赋值
（key/name/type/api/searchable/quickSearch/changeable/filterable/playUrl/ext/jar/
playerType/categories/timeout/click/style/icon/hide/indexs/danmaku/header）。
上一轮只给 `bean/SourceBean.kt` 加了 `homePage` 字段，却没有在这个手工解析器里
加对应的读取行。结果：任何配置解析出的每个站点，`SourceBean.homePage` 恒为 null。

下游因此全线失效（代码本身都没写错，只是永远拿不到值）：

- `ApiConfig.resolveHomePage(bean)` 第一行读 `bean.homePage` → 恒空 → 恒返回 null；
- `HomeViewModel.loadHome()` 的 homePage 短路分支永不触发，照旧走 `getSort`；
- `SortLoader.getSort` / `SearchLoader` / `WarmQueue` 里的 `hasHomePage()` 守卫同样
  永不触发，type=3 + `api:"csp_AV"` 的占位爬虫被正常初始化 → 找不到爬虫 → 报错 →
  首页落到 `HomeGridLayout` 的错误态，就是截图里的「加载失败，请检查网络 / 重试」。

逐段核查排除的其他嫌疑：

- 仓库只有一个 `osc.bean.SourceBean`，不存在 catvod 侧的第二个 bean；
  全仓没有用 Gson 反序列化 SourceBean 的地方。
- 站点切换无 clone/转换：`ApiConfig.parseJson` 把 `parseSites` 的实例原样放进
  `sourceBeanList`，`setSourceBean`/`homeSourceBean()`/`switchSourceBeanList()`
  传递的都是同一实例，字段不会在中途丢失。
- `HomeViewModel.loadHome()` 的判断与 `HomePage.kt` 的 UI 分支
  （`homePageTarget != null` 在网格/错误分支之前）经通读确认无误。
- `PythonLoader.setConfig` 也读 "sites"，但它只把原始 JSONObject 按 api 存进
  Python 爬虫运行时的 siteMap，不构造 SourceBean、不喂 UI，与本问题无关，未动。
- `fixContentPath` 的预处理不会吃掉 homePage：它把正文里所有 `./` 替换为按配置
  地址解析出的绝对前缀。对本地配置（file:// → clan://localhost → 本地文件服务
  地址）与远程 http 配置，`"homePage":"./AV.html"` 在进 `parseSites` 前已经变成
  配置同目录下的绝对 URL，字段本身完好，只是上一轮没人读它。

## 修复

只改一个文件、一行（`ConfigParser.kt` 第 104 行，与 ext/jar 等字符串字段同款写法）：

```kotlin
sb.homePage = DefaultConfig.safeJsonString(obj, "homePage", "")
```

修复后链路（逐段复核）：

1. JSON 文本 →（ConfigLoader.fetchConfigAsync：clanContentFix + fixContentPath
   把 `./AV.html`  absolutize；缓存里存的也是处理后的正文，缓存恢复路径一致）
   → `ApiConfig.parseJson` → `ConfigParser.parseSites` 现在读出 homePage；
2. bean → `ApiConfig.homeSourceBean()`（同一实例）→
   `HomeViewModel.loadHome()` → `resolveHomePage()`：绝对地址原样返回，
   纯相对值（如 `AV.html`、`sub/x.html`）按 KV 里的配置地址解析；
3. VM 状态 `homePageUrl` 非空 → `HomePage` 渲染共享 `HtmlWebView` 全屏分支，
   不调 getSort、不初始化占位爬虫。

对普通站的影响：无 `homePage` 字段时 `safeJsonString` 返回 `""`，
`hasHomePage()` 为 false、`resolveHomePage()` 返回 null，type 0/1/3/4 全部走
原有流程，一行新赋值不改变任何既有字段。

另按要求把仓库根 `VERSION` 从 `v1.5.0` 改为 `v1.5.1`。

## 为什么上一轮静态检查没发现

上一轮报告写的是「SourceBean 新增 homePage 字段（Gson 按字段名解析）」——
这个前提是想当然的：只 grep 了 `homePage` 在新代码里的链路
（bean 字段 → resolveHomePage → VM 状态 → UI 分支），这条链每一环都真实存在、
也都写对了，所以 grep 看起来「完整」。但没人去读 `parseSites()` 的函数体，
没发现 AVBox 的站点解析是手工逐字段赋值、根本不经过 Gson 字段映射，
新字段压根没有入口。教训：给 bean 加字段时，必须同时确认构造它的解析器
（尤其是手工赋值的解析器）真的写了这个字段。
