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
