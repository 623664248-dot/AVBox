# v1.7.0 音乐版：底部「追剧」换成「音乐」（LX 音源引擎 + 原生音乐页）

## 做了什么

- 底部导航第三个 tab 由「追剧」改为「音乐」：`AppTab.FOLLOWING` 的标签/图标
  换成 `tab_music` / `ic_tab_music`（枚举与 pager 页数不变），该页渲染由
  `FollowingPage` 换成新的 `MusicPage`。`FollowingPage.kt` 按约定保留未删，
  只是不再被渲染。
- 新增原生音乐页（搜索 + 热搜 + 平台切换 + 结果列表 + 分页加载），点歌进入
  独立播放器（封面、进度拖动、上下首、播放队列、歌词滚动高亮）。
- 内置两个洛雪（LX Music）自定义音源脚本（assets/lx_sources/），音乐页右上角
  「音源」可切换，选择持久化（KV `lx_music_script_id`）：
  - **默认：星海音乐源 v2.3.0**（`xinghai.js`）——Torres 在 PeekPro 里实际在用的
    那个；接口完全标准（只用 `EVENT_NAMES/request/on/send`），注册
    wy/tx/kw/kg/mg 五个平台的 musicUrl。
  - 备选：长青SVIP音源 v1.2.0（`changqing.js`）。
- 和洛雪本身一致的分工：**App 负责搜索（Kotlin 直调各平台公开接口），脚本
  只负责把歌曲解析成播放链接**。

## 新增文件

| 文件 | 作用 |
| --- | --- |
| `music/MusicTrack.kt` | 歌曲模型 + LX musicInfo 构造 + 队列 JSON 序列化 |
| `music/MusicApi.kt` | kg/kw/wy/tx 搜索、酷狗热搜、四平台歌词（平台公开接口） |
| `music/LxBridge.kt` | QuickJS 上的洛雪桥（见下） |
| `ui/page/MusicViewModel.kt` | 音乐页状态：桥生命周期、搜索、分页、脚本切换 |
| `ui/page/MusicPage.kt` | 音乐页 UI（只渲染 VM 状态） |
| `ui/music/LxMusicPlayerActivity.kt` | 独立播放器 Activity（自有 ExoPlayer） |
| `ui/music/LxMusicPlayerScreen.kt` | 播放器 UI |
| `ui/music/LxPlayerState.kt` | 播放器状态容器（同 MusicPlayerState 形态） |
| `res/drawable/ic_tab_music.xml` | 音符图标（Material music_note 按仓库 960+translateY 约定生成） |
| `assets/lx_sources/xinghai.js` / `changqing.js` | 两个内置音源脚本 |

改动：`MainScreen.kt`（tab 标签/图标/渲染分支 + 注释）、`AndroidManifest.xml`
（注册 LxMusicPlayerActivity）、四套 strings（values / values-en /
values-b+zh+Hant / values-zh-rHK）新增 tab_music 与 music_* 文案、VERSION →
v1.7.0。

为什么新建播放器而不是复用已有的 `ui.MusicPlayerActivity`：那个播放器从
PlaybackService 的会话里取 VodInfo，绑死在 VOD 的 Detail/PlayLoader 管线，
接不了外部队列。本版播放器自带 ExoPlayer 实例，只吃「队列 JSON + 起始下标」，
切歌时逐首走 LxBridge 解析链接。歌词解析复用了已有的 `MusicLrc.parse` /
`LyricLine`。

## LxBridge（洛雪桥）设计

沿用仓库 drpy 的 QuickJS 模式（参考 catvod JsSpider/Global/Async）：

- 单线程 ScheduledExecutor 独占 QuickJS context，所有 JS 调用都在该线程；
  OkHttp 回调先 post 回该线程再碰 JS；Promise 用 then/catch + 
  CompletableDeferred 等待（等待发生在协程线程，不占 JS 线程，不会死锁）。
- 注入 `globalThis.lx`：`version='2.2.0'`、`env='mobile'`、`EVENT_NAMES`、
  `currentScriptInfo`（从脚本头部 `@name/@description/@version/@author`
  注释解析——聚合类脚本会逐字校验这四个字段）、`send('inited')` 捕获 sources
  注册表、`on('request')` 保存 handler、`request(url, options, callback)`
  走 OkHttp 并按洛雪签名 `callback(err, {statusCode, headers, body}, body)`
  回调（body 是 JSON 时自动解析成对象，和洛雪行为一致；响应头同时给原名和
  小写名两份键）。
- `utils` 由 JS prelude 提供：buffer（utf8/hex/base64 互转、from/alloc/
  concat/bufToString）、crypto.md5 与 crypto.aesEncrypt/aesDecrypt（经两个
  小的原生绑定 `__md5` / `__aes` 走 MessageDigest / javax.crypto，CBC/ECB +
  PKCS5）。另提供全局 setTimeout/setInterval/clearTimeout/clearInterval
  （GD 类脚本靠 setTimeout 延迟发 inited）和 console（转 AppLog）。
- 调用 handler 传 `{source, action:'musicUrl', info:{type, musicInfo}}`，
  默认音质 320k，失败自动回退 128k；脚本的错误信息透传到 UI toast，不静默。
- 脚本初始化失败/超时（15 秒）同样透传 toast。

## musicInfo 字段依据

字段模型照洛雪桌面版 musicSdk 各平台 musicSearch.js 的 filterData 输出，
并用可读脚本交叉验证（IKUN：`musicInfo.hash ?? musicInfo.songmid`；GD：
`musicInfo.songmid || musicInfo.id`；星海：`hash ?? songmid ?? id`，且备用
API 用 name/singer 搜歌）：

| 平台 | songmid | hash | albumId | 其它 |
| --- | --- | --- | --- | --- |
| kg | audio_id | FileHash（另带 320hash/sqhash 进 types） | album_id | 封面取 trans_param.union_cover（{size}→240） |
| kw | MUSICRID 去 `MUSIC_` 前缀 | — | ALBUMID | 封面 web_albumpic_short 拼 img4.kuwo.cn 前缀 |
| tx | mid | — | album.mid | albumMid=album.mid、strMediaMid=file.media_mid |
| wy | id | — | album.id | 时长 ms→s |

songmid 按洛雪惯例：kg/kw/wy 传数字、tx 传字符串。musicInfo 还带
name/singer/albumName/interval(mm:ss)/img/types/typeUrl 等洛雪标准字段。

## 沙盒验证（静态 + 冒烟，非真机）

沙盒工具链编不了本工程（AGP/SDK 版本高于本地），本地只做了静态自查
（import、资源引用、空安全、括号平衡）。另外用 node 搭了一个与 LxBridge
语义一致的冒烟环境实测：

- prelude 的 md5/AES-CBC 往返/base64/hex 全部正确；两脚本语法检查通过。
- 星海 v2.3.0：成功 send inited，注册 wy/tx/kw/kg/mg 五平台（其服务器检查
  在沙盒网络下走降级路径仍正常 inited）。
- 搜索接口实测：kg（mobilecdn v3，http）、kw（search.kuwo.cn）、tx
  （client_search_cp）返回结构与解析代码逐项对上；kg 热搜、四平台歌词接口
  （网易 lyric、QQ base64 歌词、酷狗 lyrics 搜索+下载）均实测可用。
- **网易搜索**在沙盒出口返回了加密载荷（`{"result":"<hex>"}`，网易对不可信
  出口的反爬），解析会安全返回空列表不崩；中国手机网络下通常是正常 JSON，
  这一点未经真机验证。

## 已知限制

- **长青 v1.2.0 自带版本熔断**：冒烟实测它连上更新服务器后发现新版 1.3.0，
  按其逻辑「发现新版本，脚本将不会初始化」，永远不会 send inited——切到长青
  会等 15 秒后 toast 初始化超时。它仍按约定放进 assets 作备选，但实际不可用；
  默认的星海不受影响。
- 星海的 inited 平台列表来自它自己的动态稳定源接口；其服务器不可达时走
  降级（仍 inited，主 API 只映射 wy/kw，其余平台走备用 API）。平台 chips 以
  inited 实际注册为准。
- 星海 musicUrl 在沙盒实测未取到链接（GDAPI 未返回音频地址、备用 API 返回
  410），沙盒出口被这些服务区别对待的可能性大；**播放全链路未经真机验证**，
  这是本版最大的未验证点。
- utils.crypto 只覆盖 md5 + AES（CBC/ECB）；用到 zlib、RSA 或其它
  utils API 的第三方脚本不在 v1 支持内（两个内置脚本都用不到）。
- 播放器无通知栏/锁屏控制、无后台播放服务（切后台会随 Activity 生命周期
  暂停）；无排行榜/歌单/我的页、无脚本导入 UI、无黑胶转盘动画（均为 v1 
  明确不做）。
- 酷我歌词走 newlyric.lrc 直链，部分歌曲取不到时显示「暂无歌词」。

## v1.7.1 修复（真机反馈：点歌不出声）

- 根因：内置的星海 v2.3.0 已过期——它的备用接口被服务端废弃（返回
  code 410「此接口已废弃，需提供 name 和 songmid 参数」），主接口
  GDAPI 在稳定源列表不含 kg 时也走不通，所以所有歌都解析失败。
- 换内置脚本：xinghai.js 升级为官网最新星海 v3.2.19（新接口契约
  name+songmid）；已用 Node 搭洛雪宿主冒烟实测：inited 注册
  wy/tx/kg/kw/mg/qs 六平台，kg《发如雪》成功解析出酷狗 CDN mp3 直链。
- 备选脚本由长青SVIP v1.2.0（已被作者远程熔断）换成全豆要-聚合音源
  v10.0（同样冒烟实测可 inited 且能解析出 kg 播放链接）。旧的
  changqing.js 资源文件因推送工具不能删文件而留在 assets 里，
  SCRIPTS 已不再引用，不会被加载。
- 平台 chips 只显示能原生搜索的平台（kg/kw/tx/wy）：脚本还注册了
  mg/qs 等无搜索实现的平台，显示出来只会搜出空列表。
- MusicPage 顶部加状态栏 inset，修搜索框压到状态栏的问题。

## v1.7.2 自选音源（像洛雪一样自己换音源）

- 音乐页「音源」对话框底部新增「导入音源脚本」：系统文件选择器
  （OpenDocument，mime 放宽到 text/javascript、application/javascript、
  text/* 并带 */* 兜底）选手机里的 .js 文件即可导入，导入成功后自动
  切换到新音源试运行。
- 存储：脚本复制到 app 私有目录 files/lx_user_sources/<id>.js，元数据
  （id/显示名/版本/文件名）以 JSON 数组存 KV 的 lx_user_scripts 键；
  新增 music/LxUserScripts.kt 负责文件与元数据，LxBridge.ScriptDef
  增加可空 filePath/version，loadScript 按 asset/file 两种来源读脚本，
  头部解析（@name/@version）、inited、解析播放链接的流程完全复用。
- 导入校验（故意宽松）：非空、大小 ≤4MB、含 globalThis.lx /
  EVENT_NAMES / lx.on( / lx.send( / window.lx 任一特征；混淆脚本若把
  特征藏起来，会放行导入但在初始化时以可见错误失败，不会静默坏掉。
  显示名优先取脚本头部 @name，缺失时用原文件名（去扩展名）。
- 切换健壮性：选择只在初始化成功后才写入 KV；切换到坏脚本失败时
  自动重新加载上一个可用脚本并恢复选择；启动时记住的用户脚本若加载
  失败，自动回退内置星海。
- 删除：用户音源条目右侧有删除按钮，删文件+元数据；删的正是当前在用
  的脚本时自动切回内置星海。内置脚本（星海、全豆要）不可删、列表
  不变。
- 限制：同名同版本重复导入会生成新条目（不去重）；脚本文件只从本地
  导入，不支持 URL 在线导入；导入的脚本与内置脚本一样在 QuickJS 里
  运行，其联网行为不受 app 额外限制（与洛雪自定义源一致，风险自担）。

## v1.8.0 分平台排行榜

- 音乐页在搜索行下方新增 [搜索 | 排行榜] 分段切换；排行榜模式下平台
  chips 与搜索模式共用同一套（酷狗/酷我/QQ音乐/网易云），切平台即
  加载该平台榜单。榜单以「封面+榜名+更新提示」列表呈现，点榜单进入
  歌曲列表（复用搜索结果的歌曲行），点歌曲走现有播放器、队列=该榜
  歌曲；顶部返回键和系统返回键都从歌曲列表回榜单列表。状态全部在
  MusicViewModel（mode/charts/chartSongs/selectedChart 及两个
  loading），页面只渲染。
- 数据层：MusicApi 新增 charts(source) / chartSongs(source, id, page)
  与 MusicChart(id/name/cover/updateTip)；榜单歌曲一律取第一页
  50 首，不做分页加载。各平台端点与沙盒实测（原始条数→解析条数）：
  - 酷狗 kg：榜单 GET mobilecdn.kugou.com/api/v3/rank/list
    （data.info，25→25；rankid/rankname/imgurl/update_frequency）；
    歌曲 GET /api/v3/rank/song?rankid=&pagesize=50（单页 50 首，
    榜总 500）。歌曲字段与搜索同构，解析抽成 parseKgItem 共用；榜单
    条目无 singername，歌手回退 authors[].author_name → filename
    前缀。
  - 网易 wy：榜单 GET music.163.com/api/toplist（list，65→65；
    id/name/coverImgUrl/updateFrequency）；歌曲 GET
    /api/playlist/detail?id= 的 result.tracks（100→取前 50）。注意
    tracks 是老结构（artists/album/duration），与 wy 搜索 songs
    同构，parseWyItem 共用。沙盒出口本次实测 toplist 与 detail 都
    返回了明文 JSON；个别出口仍可能拿到加密 {"result":"<hex>"}
    载荷，解析会安全返回空、页面显示空态。
  - QQ tx：榜单 GET c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg
    （data.topList，25→25；id/topTitle/picUrl，无更新提示字段）；
    歌曲 GET fcg_v8_toplist_cp.fcg?topid=&type=top 的
    songlist[].data（300→取前 50），与 tx 搜索 song 结构同构，
    parseTxItem 共用；榜单条目无 file.media_mid，strMediaMid 缺失
    时以 songmid 兜底（解析链里 midurlinfo 也纳入回退链）。
  - 酷我 kw：跳过（切到排行榜模式显示「暂无榜单」空态）。
    www.kuwo.cn 的 bang 接口对未签名请求一律回
    {"success":false,"message":"The request is illegal!"}，带
    kw_token 的 csrf 流程同样被拒；老接口 kbangserver.kuwo.cn
    没有榜单列表端点，其 content 接口按 bang id 探测时，同一 id
    在不同请求间返回互相矛盾的榜名（疑似多后端数据不一致），
    无法可靠建立榜单目录。注：kbangserver 的单榜歌曲接口本身
    可用（musiclist 单页 50 首，id/name/artist/album/albumid/
    song_duration 字段完整），待有可靠目录来源再接。
- 顺带 UI 修复：搜索框改为胶囊形（RoundedCornerShape(50%)、高
  44dp），与搜索/音源按钮垂直居中；播放器页顶部行（返回键、队列
  键）加状态栏 inset，不再与状态栏重叠。
- 已知限制：榜单歌曲只加载第一页 50 首；kw 无榜单；wy 在个别
  网络出口下可能因加密载荷显示空态，需真机再验。

## v1.8.1 音乐页布局

按使用反馈重排音乐页（MusicPage / MusicViewModel）：

- 第一行 = 资源站点：平台 chips 行挪到页面最上方（状态栏 inset
  之下），chips 区横向滚动占满剩余宽度，「音源」按钮固定在这一行
  右端（原来在搜索行里）。
- 第二行 = [搜索 | 排行榜] 分段切换，且默认选中排行榜：
  MusicViewModel 的 mode 初始值改为 CHARTS；启动时桥初始化成功
  （含回退默认脚本）后自动 loadCharts() 一次，切平台/切脚本/切
  模式仍走原有重载路径，不会重复触发。
- 排行榜平铺：榜单目录由列表行改为两列封面网格
  （LazyVerticalGrid，GridCells.Fixed(2)）：方形封面（圆角 12dp）
  + 榜名（最多 2 行）+ 更新提示小字（有才显示）。点卡仍进歌曲
  列表（UI 不变），返回回网格；酷我空态不变。
- 搜索框改自绘胶囊：44dp 固定高的 OutlinedTextField 在真机上把
  占位文字上下裁切（压扁/重影），换成 BorderStroke 描边的
  Surface（CircleShape，高 46dp，内容垂直居中）+ BasicTextField
  （15sp，空时自绘占位，IME 搜索键触发 vm.search），胶囊内左侧
  带 18dp 搜索小图标，取代原外置的 🔍 按钮；搜索行只剩胶囊本体，
  位于搜索模式内容区顶部。搜索逻辑与热搜/结果/分页行为不变。
- 播放器、LxBridge、MusicApi 未动。
