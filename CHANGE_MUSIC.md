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

## v1.9.0 音源导入修复 + 沉浸歌词 + 后台播放

### 音源导入不显示 / 切换不生效（根因修复）

真机录屏实证：导入用户音源脚本提示成功，但音源弹窗里永远只有
内置两个、切换也存不住、findDef 回退到默认脚本。根因在 KV 层：
KVDecoder.decode 在 defaultValue==null 时走 KVKeySpec 注册表查
类型，未注册的 key 直接返回 null。音乐代码有两处用无默认值方式
读 KV，于是永远读到 null：

- music/LxUserScripts.kt 的 readMetas()：KV.get(KV_USER_SCRIPTS)
  改为 KV.get(KV_USER_SCRIPTS, "")，空串按「无已注册脚本」处理。
- ui/page/MusicViewModel.kt 的 savedScriptId()：
  KV.get(KV_SCRIPT_ID) 改为 KV.get(KV_SCRIPT_ID, "")，空串按
  未保存处理。

带 String 默认值时 resolveType 直接用 String 类型、parse 原样
返回，往返成立。全局复查 music/、ui/music/、MusicPage、
MusicViewModel，无其它无默认值 KV.get 调用。另外
music/LxBridge.kt 的 awaitInited 默认超时 15s 提到 30s：
jsjiami 混淆脚本在 QuickJS 里解码慢，原超时下容易误判初始化
失败（该类脚本已在 Node 洛雪宿主模拟中验证可正常 inited）。

### 播放器沉浸歌词（ui/music/LxMusicPlayerScreen.kt）

点封面进入沉浸模式：封面图全屏铺满（ContentScale.Crop）作
背景，上覆深色竖向渐变遮罩；歌词居中大字号显示（当前行
headlineSmall 白色高亮，其余 titleMedium 半透明白），复用现有
lyricLines 数据与 MusicLrc 解析，不新造管线。沉浸模式保留最小
控制（上一首 / 播放暂停 / 下一首，白色图标）与缓冲指示；点
背景任意处或系统返回键退出，回普通布局。普通布局结构未动，
仅封面 Box 增加点击入口。

### 后台播放（切后台 / 锁屏继续播 + 系统媒体通知）

播放器由 Activity 持有改为服务持有：

- 依赖：gradle/libs.versions.toml 新增 media3-session
  （androidx.media3，version.ref = "media3"，与 exoplayer 同
  版本）；app/build.gradle.kts 加 implementation(libs.media3.session)。
- 新增 music/LxPlaybackService.kt：MediaSessionService，内部
  建 ExoPlayer（音乐 AudioAttributes）与 MediaSession；通知用
  media3 自带的 DefaultMediaNotification.Provider（服务默认
   provider），应用图标 + MediaItem 的 MediaMetadata（标题 /
  歌手 / 封面 artworkUri）自动呈现，锁屏 / 通知栏可播放暂停；
  任务被划掉且处于暂停时 stopSelf，已播完不残留服务。
- ui/music/LxMusicPlayerActivity.kt 改用 MediaController 连接
  服务：「先经 LxBridge.getMusicUrl 解析直链再播」的流程保留在
  UI 层，解析后把带 metadata 的 MediaItem 交给 controller；
  播放状态 / 进度 / 缓冲从 controller 的 Player.Listener 与
  ticker 观察回 LxPlayerState；上一首 / 下一首 / 播完自动连播
  语义不变，只是执行对象换成 controller；controller 未连接时
  playAt 先挂起（pendingPlay），连接成功后自动补播；Activity
  销毁只断开 controller，不停服务，后台继续播放。
- AndroidManifest 注册 LxPlaybackService（exported=true，
  intent-filter MediaSessionService + MediaButtonReceiver，
  foregroundServiceType="mediaPlayback"）。权限
  FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK /
  POST_NOTIFICATIONS 本就已声明；播放页 init 时经
  PermissionHelper.requestNotificationIfNeeded 请求通知权限，
  拿不到不阻断播放。
- 已知限制：服务里只持当前一首 MediaItem，系统通知 / 锁屏上
  只有播放暂停（上一首 / 下一首在 App 内），与原单曲解析模型
  一致；影视播放器（ui.MusicPlayerActivity、player 包）未动。

## v1.9.1 悬浮音源按钮

- 音乐页第一行的「音源」TextButton 删掉，平台 chips 的 LazyRow
  改为占满整行宽度；原内容 Column 包进根 Box，Box 沿用同一套
  contentPadding / 状态栏 inset / 水平 16dp，所以悬浮按钮的活动
  范围就是原内容区，不会压到底部导航、也进不了状态栏。
- 新增 MusicSourceFab（MusicPage.kt 内私有 Composable）：胶囊形
  半透明深色底 + 白字 + 6dp 阴影，叠在根 Box 最上层（弹窗在独立
  窗口，层级仍在它之下）。默认停在内容区右下角（16dp 边距）。
- 拖动 / 点击判定：单个 pointerInput + awaitEachGesture 自行跟
  踪位移——detectDragGestures 对未过 touch slop 的轻点不回调，
  无法区分点击与取消，故不用它。按下后累计位移 < 8dp 判为点击
  （打开原有音源弹窗，并回滚几 px 的手指漂移），≥ 8dp 为拖动，
  跟手移动且每步按根 Box 实测尺寸 clamp；取消则回滚。手势只
  consume 按钮自身区域的事件，不挡底下榜单网格滚动与点击；
  scriptBusy 时点击不打开弹窗（与原 TextButton 的 disabled 一致），
  拖动不受影响。
- 位置持久化：松手时以 "x,y"（px）写 KV 的 music_source_fab_pos，
  读取带 "" 默认值（避开无默认值读未注册 key 恒 null 的坑）；
  页面切走切回、杀 App 重开都恢复上次位置，恢复时同样 clamp。
- 弹窗、MusicViewModel、LxBridge、播放器、MusicApi 均未动。

## v1.9.2 播放器回退

- 现象：v1.9.0 把 LxMusicPlayerActivity 改成 MediaController +
  LxPlaybackService（MediaSessionService）后，真机出现「点歌曲立
  刻闪退」——内置星海音源 + 酷狗榜单歌曲可复现，播放页都来不及
  显示。Kotlin 层逐行查过没找到必崩点，疑在 media3 会话服务 /
  默认通知一侧。
- 处理：先回退恢复播放。LxMusicPlayerActivity 整体恢复为
  v1.8.1 的 Activity 内 ExoPlayer 实现（该写法 v1.7.1 起真机验
  证能播）；它与当前 LxMusicPlayerScreen 的回调签名逐个对齐，
  v1.9.0 的点封面沉浸歌词保留可用。
- AndroidManifest 删除 .music.LxPlaybackService 的 <service>
  注册块；LxPlaybackService.kt 文件保留（未注册即 inert，类注
  释已标明停用），gradle 的 media3-session 依赖保留不动。
- 后台播放暂时没有（退出播放页即停，与 v1.8.1 行为一致），后
  续单独重做服务化时再恢复。
- v1.9.0 / v1.9.1 的其余成果保留不动：KV 两处 "" 默认值修复
  （音源导入名单与选择持久化）、awaitInited 超时 30s、沉浸歌
  词、悬浮音源按钮。

## v1.9.2 构建失败修复（版本号不变）

- v1.9.1 与 v1.9.2 前两次构建均死于 `:app:compileReleaseKotlin`，
  报错集中在 MusicPage.kt 悬浮按钮手势代码（构建日志实证）。
- 根因：项目 Compose 为 BOM 2026.09.00 / UI 1.12.1，手势代码
  漏 import `foundation.gestures.awaitEachGesture` 与
  `awaitFirstDown`；且 `changedToCanceled()` 在 1.12.1 中根本不
  存在（已对官方 AAR 字节码逐项核对）。`positionChange()` 是
  `ui.input.pointer` 包的扩展函数，import 必须保留。
- 修复：补上两个 gestures import 与 positionChange import，
  删除 changedToCanceled 分支——取消手势与抬手走同一结束路径。

## v1.10.0 播放页 / 音乐页 RikkaHub 式界面 + 音效切换

- 新增共享外观设置（ui/music/MusicAppearance.kt）：自定义背景图
  （OpenDocument 选图后复制到 filesDir/music_bg/ 持久保存）、模糊
  半径 0–40（默认 18）、黑色遮罩不透明度 0–90%（默认 55%）、音效
  模式，全部经 KV 持久化（读值一律传 "" 默认，沿用 v1.9.0 教训），
  状态走 Compose 快照状态，Sheet 里拖动滑杆页面当场预览。
- 播放页（LxMusicPlayerScreen）：根布局改 Box，底层 MusicBackground
  （自定义图优先，否则当前封面，full-bleed Crop + blur + 黑色遮罩），
  封面卡 / 歌词区 / 进度控制区改白色低 alpha 毛玻璃 Surface，文字与
  图标改白色系；顶栏队列按钮左侧加调色板按钮打开外观 Sheet（背景
  + 音效两节）。点封面沉浸歌词、队列、上下首等行为不变。
- 音效（LxMusicPlayerActivity）：android.media.audiofx 的
  Equalizer / BassBoost / Virtualizer 挂 ExoPlayer 的 audio session，
  player 备好（STATE_READY / prepare 后）与 session 变化时重挂，选
  择在 Sheet 里点下即由 Activity 重新应用；预设名运行时从设备
  Equalizer 枚举（numberOfPresets / getPresetName）。模式存 KV 字
  符串：off / preset:<index> / bass / surround（低音、环绕 strength
  均 700/1000）。全部 try/catch 包裹，设备不支持 audiofx 时静默降
  级，不影响播放。Manifest 补 MODIFY_AUDIO_SETTINGS（普通权限）。
- 音乐页（MusicPage）：根布局外包一层 Box，设了自定义背景图时底
  层铺 MusicBackground（同 blur + 遮罩），榜单卡片 / 歌曲行 / 搜
  索框改半透 Surface 让背景透出，未设置时外观与之前完全一致；
  [搜索|排行榜] 行右端加调色板图标按钮打开外观 Sheet（仅背景节）。
  悬浮音源按钮的位置 / 拖动 / 点击逻辑未动。
- 已知限制：Modifier.blur 需 API 31+ 才有效果，低版本自动 no-op
  （不引第三方库）；EQ 依赖设备 audiofx 支持，部分设备可能无预设
  或效果创建失败，此时音效节只剩原声可选、播放不受影响；背景图
  每次重选会替换旧图（旧文件自动清理）。

## v1.10.0 构建失败修复（版本号不变）

- 首次构建死于 `:app:compileReleaseKotlin`，仅 2 处报错，均在
  LxMusicPlayerActivity 的音效代码：`BassBoost`/`Virtualizer`
  没有 `getStrength()`（只有 `setStrength(short)` 与
  `getRoundedStrength()`），Kotlin 无法合成 `strength` 属性。
- 修复：两处改为显式 `setStrength(EQ_EFFECT_STRENGTH)` 调用
  （已对 android.jar 字节码核对；`strengthSupported` 属性合法，
  编译器亦未报错）。

## v1.10.0 二次构建失败修复（版本号不变）

- 修复版构建仍死于 `:app:compileReleaseKotlin`：MusicAppearance
  的四个 `var ... private set` 属性生成的 JVM setter 与手写的
  `setBgPath/setBlurDp/setScrimPercent/setEqMode` 函数签名冲突
  （Platform declaration clash，日志实证，共 8 处）。
- 修复：四个函数改名为 `updateBgPath/updateBlurDp/
  updateScrimPercent/updateEqMode`，同文件内 5 处调用同步改名；
  无其他文件引用这些函数。

## v1.11.0 酷狗式播放页 + 循环 / 收藏 / 下载

### 播放页排版（ui/music/LxMusicPlayerScreen.kt 重排）

参照酷狗播放页改为自上而下的单列布局：

- 顶栏只剩返回（左）与调色板按钮（右，打开原有外观 + 音效 Sheet）；
  播放队列按钮下移到控制行右端。
- 背景：未设自定义背景图时改为封面取色的纯色深色背景（见下），
  底部叠一道渐变压暗保可读性；已设自定义背景图时仍用 v1.10.0 的
  MusicBackground（模糊 + 遮罩），优先级更高。文字保持白色系。
- 大封面：圆角正方形（BoxWithConstraints 取区域内最大正方形），
  点封面仍进沉浸歌词；其下是加粗歌名、歌手名、一行小标签 chip
  （平台名 + 已知音质如 320K；音质未知时不显示，不仿造 VIP/DTS
  等标签）。
- 两行歌词（当前行白字 + 下一行半透），点歌词区同样进沉浸歌词；
  原来的整列歌词区与三块毛玻璃卡片移除（沉浸歌词不变）。
- 操作行：收藏（爱心）与下载两键居中分布；进度条改为细条 Slider
  与当前 / 总时长同行；控制行 = 循环模式（左）+ 上一首 / 播放暂停
  / 下一首（中）+ 播放队列（右）。

### 封面取色

项目无 androidx.palette 依赖，取色手写：Activity 在切歌时用 Coil
（SingletonImageLoader）以 48×48、allowHardware(false) 拉封面
bitmap，遍历像素求平均色（跳过近透明像素），各通道 ×0.5 压暗后
写入 LxPlayerState.bgColor；全程 try/catch，失败或无封面时页面用
深蓝灰兜底色 0xFF262833。取色结果只跟当前歌曲走，切歌自动重取。

### 循环模式（LxLoopMode，四档）

列表循环 → 单曲循环 → 随机播放 → 顺序播放，按钮点按轮换并
toast 当前模式名，KV `lx_loop_mode` 持久化（读取带 "" 默认值）。
与现有队列推进的衔接（队列推进仍统一走 Activity.playAt）：

- 列表循环：沿用原行为，STATE_ENDED 时 playAt(index+1) 绕回。
- 单曲循环：exo.repeatMode = REPEAT_MODE_ONE，播放器内部循环，
  不触发自动切歌；切到其它模式即恢复 REPEAT_MODE_OFF。
- 随机播放：自动连播与手动「下一首」都在切歌时取随机下标
  （队列 >1 首时避开当前曲）。
- 顺序播放：自动连播在队尾停止（不绕回）；手动下一首仍可翻页。

### 收藏（music/LxFavorites.kt）

收藏列表以 MusicTrack JSON 数组存 KV `lx_favorites`（读写带 ""
默认），身份键为 source+songmid（无 id 时回退歌名+歌手），存的
字段与播放队列一致，收藏里点歌可直接播放。播放页爱心切换当前
歌曲收藏（已收藏填充粉红，未收藏描边白），进页 / 切歌时按当前
歌曲刷新状态。音乐页模式行新增第三个 chip「我的收藏」：
FAVORITES 模式列出收藏歌曲，点歌播放（队列 = 收藏列表），空时
显示「暂无收藏」；每次切入该模式都从 KV 重读，播放页里的增删
回到音乐页即生效。

### 下载

播放页下载按钮把当前歌曲音频存入设备音乐库：优先用播放已解析
的直链（LxPlayerState.currentUrl），未解析完则先走 LxBridge
解析再下。文件名「歌手 - 歌名.<扩展名>」（扩展名从 URL 判，
判不出用 mp3；非法字符清洗）。OkHttp 流式写入、后台协程执行，
进度（百分比，总长未知时只转圈）回 LxPlayerState，按钮在下载
中禁用防重复点，完成 / 失败均 toast。落盘策略：API 29+ 用
MediaStore 写 Music/AVBox（RELATIVE_PATH，IS_PENDING 完成后
清零，失败删条目），无需任何权限；API 29 以下写
getExternalFilesDir(Music)，完成 toast 带完整路径。

### 图标

循环 / 单曲复用仓库已有 ic_repeat / ic_repeat_one；随机、顺序、
收藏（填充 / 描边）、下载为新增 res/drawable 矢量图标
（ic_shuffle / ic_play_order / ic_favorite / ic_favorite_border /
ic_download，24dp viewport 的 Material 路径，与 ic_check 同款
式），播放页全部图标统一走 painterResource，不新增 material
图标符号依赖。音质 chip 的数据来自 LxBridge 新增的
lastResolvedQuality()（记录最近一次 getMusicUrl 实际命中的
音质，320k 失败回退 128k 时如实显示 128K）。

### 已知限制

- 取色是整幅封面的平均色，封面本身过亮 / 过杂时背景观感一般，
  但已统一压暗一半，明暗极端时仍可改用自定义背景图。
- 随机模式不记播放历史，「上一首」仍是队列上一首。
- 下载不做断点续传，同一首重复下载会产生同名文件（MediaStore
  自动改名）；下载完成状态只记当前歌曲，切歌即重置。
- 收藏列表在音乐页切入「我的收藏」时刷新，停留在该模式时从
  播放页返回不会自动刷新（切一下模式即可）。

## v1.11.0 首次构建失败修复（版本号不变）

- 构建死于 `:app:compileReleaseKotlin`，仅 1 处错误：
  LxMusicPlayerActivity 取色代码用了 Coil 2 的
  `ImageRequest.Builder.allowHardware(false)`，本项目是 Coil 3.6.3，
  该方法不存在。
- 修复：删掉 allowHardware 调用；拿到 bitmap 后若 config 为
  HARDWARE（API 26+），先 copy 到 ARGB_8888 软件位图再 getPixels
  （copy/Config.HARDWARE/getPixels 均已对 android.jar 核实）。

## v1.12.0 沉浸歌词纯色背景 + 音效加强

### 沉浸歌词背景改纯色 + 点按返回

真机反馈：点歌词进全屏沉浸歌词后，背景是封面图平铺，不想要图；
在歌词界面点击要能回到播放界面。

- ImmersiveLyrics 背景层去掉封面图与渐变罩，改为纯色：优先
  LxPlayerState.bgColor（与播放页相同的封面取色），为 null 时用
  播放页兜底深色 0xFF262833。即使外观里设了自定义背景图，沉浸
  歌词也只用这个纯色。播放页本身的背景逻辑不动。
- 点按返回：根容器与歌词列表（LazyColumn，及无歌词时的空态区）
  都加了无波纹 clickable（新辅助 clickableNoRipple）。歌词文本
  本身不消费点击，滚动手势会消费掉点按，所以滚动跟随不受影响；
  上一首 / 播放 / 下一首按钮自己消费点击，不会误退。BackHandler
  退出保留，退出后播放进度与歌词行不变。

### 音效加强（都在 LxMusicPlayerActivity 的 EQ 体系内）

真机反馈：原 EQ 预设 + BassBoost/Virtualizer 效果不明显。

- BassBoost / Virtualizer 的 strength 从 700 提到最大值 1000。
- 新增三条内置强曲线（eqMode = curve:bass / curve:vocal /
  curve:bright）：不用设备预设，直接对 Equalizer 逐 band
  setBandLevel。曲线以 5 点锚点定义（权重 -1..1，乘以设备
  bandLevelRange 的正上限并 clamp 到 range 内），按设备实际
  band 数线性插值；每 band 独立 try/catch。
  - 重低音 bass：[1.0, 0.75, 0.25, -0.1, -0.25]
  - 人声 vocal：[-0.35, 0.25, 1.0, 0.7, 0.0]
  - 明亮 bright：[-0.25, -0.05, 0.2, 0.65, 1.0]
- 新增「自定义」模式（eqMode = custom）：外观 Sheet 音效区选中
  自定义后，下方出现逐 band 滑杆（band 数与 mB 范围由 Activity
  在 ensureEffects 建 Equalizer 时发布到 LxPlayerState 的
  eqBandCount / eqBandMinMb / eqBandMaxMb，当前值镜像在
  eqCustomLevels）。滑杆值存 KV `music_eq_custom_levels`（逗号
  分隔，读取带 "" 默认），拖动即写 KV 并经
  onEqCustomLevelsChanged 回调 Activity 立即重应用；存储 band
  数少于设备 band 数时，多余 band 置 0。
- Sheet 音效区顺序：原声 → 设备预设 → 重低音 → 人声 → 明亮 →
  低音增强 → 环绕 → 自定义。音乐页的 Sheet 只有背景区，不受
  影响。
- Equalizer 创建失败 / 设备不支持 audiofx 时，曲线与自定义
  静默无效，播放不受影响（沿用全 try/catch 风格）。

### 已知限制

- 沉浸歌词纯色取的是整幅封面平均色压暗值，与播放页一致；封面
  本身过杂时颜色观感一般，但与播放页保持统一。
- 曲线与自定义都依赖设备 Equalizer 的 band 数与范围：band 越
  少曲线越粗略；个别设备 Equalizer 增益上限低（如 ±5dB）时
  效果仍会弱于软件 DTS，这类设备上 BassBoost（已拉满 1000）
  通常更明显。
- 自定义滑杆只在 Equalizer 已建好（开始播放后）时出现；未播放
  时选自定义不显示滑杆，开始播放后重开 Sheet 即出现。

## v1.13.0 py 音乐爬虫作为音源

音乐页音源体系新增第三类源：base.spider 格式的 py 音乐爬虫，与 JS
（LX）音源并列，复用视频侧同一套 pyramid/Chaquopy 运行时，不另起
Python 引擎。

### 接入方式

- 音源对话框新增「py 音乐站点」分区：6 个内置爬虫（assets/
  py_sources/，英文 slug 文件名，脚本头注释保留原文件名）+ 用户
  导入项；下方新增「导入 py 音源」按钮。内置对照：
  fy_musicbox=_____36_kjcj.py（音乐盒·丢丢喵）、kuwo_youyou=
  ___37_akjv.py（小心儿悠悠·酷我）、netease_music=_______39_
  h97s.py（网易云音乐）、netease_ddm1=_____40_c4ym.py、
  netease_ddm2=______41_g2mw.py（网易云·丢丢喵两版）、
  kugou_5sing=sing___42_b4va.py（5sing·丢丢喵）。同批的
  _____38_np7v.py 是影视爬虫（枫叶影院），未放入音乐。
- 存储照 LX 用户脚本模式（music/PyMusicSources.kt）：内置 asset
  首次使用时释放到 filesDir/py_sources/（大小变化时重释放）；
  用户导入复制到 filesDir/py_music_sources/，元数据 JSON 数组存
  KV `py_music_sources`（读取带 "" 默认）。可切换、可删除；导入
  校验失败 toast 原因，初始化（homeContent）失败不切换、停留在
  上一个可用源并 toast 原因。选择持久化沿用 KV
  `lx_music_script_id`，py 源存 "py:<slug>"，启动时自动恢复爬虫
  模式，恢复失败回退 JS 默认源。
- 爬虫模式（选中 py 源后）：第一行 chips = homeContent 的 class
  分类（横滚），列表走 categoryContent 分页（滚动加载更多，追加
  去重）；搜索框走 searchContent（带「返回分类」出口）；
  [搜索|排行榜|我的收藏] 模式 chips 隐藏，原生模式的状态（平台、
  榜单、搜索结果）不被爬虫模式污染，切回 JS 源完整恢复。列表行
  复用现有行组件：vod_pic 封面、vod_name、vod_remarks 作歌手行。

### 运行时复用点

- music/PyMusicBridge.kt 构造最小 SourceBean（type=3，key=
  "pymusic_<slug>"，ext=""，api="file://" + 爬虫文件绝对路径），
  经 ApiConfig.get().getCSP(bean) 进入视频侧同一分发：
  SpiderLoader 按 api 含 ".py" 分发到 pyLoader（pyramid
  PythonLoader/PythonSpider），file:// 在 downloadPlugin 里直用
  本地路径，无需完整站点配置、无下载。pyLoader 按 key 缓存实例，
  重复调用复用已加载模块；视频配置切换 clear 后下次调用自动重建。
- 调用线程模型与视频侧一致：Dispatchers.IO + BoundedCall 超时
  包裹（首次 45s 含 Python 冷启动，其余 25s）+ SpiderReaper.track。
- 结果按 TVBox 标准 JSON 解析：home/category/search 的 class/
  list、detail 的 vod_play_from/vod_play_url、player 的
  parse/jx/url/header。vod 映射为 MusicTrack（source="py:<slug>"，
  songmid=vod_id，singer=vod_remarks）。

### 播放

- 点歌先走 detailContent：只有 1 个剧集直接播，队列=当前列表，
  每首轮到时各自 detail+playerContent 懒解析；多剧集（专辑/歌单）
  先弹选集层，队列=该 vod 的剧集列表。MusicTrack 新增 pyFlag/
  pyEpId 两字段（JSON 往返兼容，收藏 key 带 pyEpId 区分同 vod
  剧集）。
- 播放复用 LxMusicPlayerActivity（队列/循环/收藏/下载/音效/沉浸
  歌词不变）：py 歌曲经 PyMusicBridge.resolve 解析；playerContent
  返回 parse=1/jx=1（或 video://）时不硬播，提示「该源需要解析，
  暂不支持」；返回的 header 通过播放器共享的
  DefaultHttpDataSource.Factory 默认请求属性带入播放，下载时
  同样带 header（已解析 URL 优先复用）。
- 歌词：py 歌曲不取平台歌词，歌词区留空，播放页与沉浸歌词均
  显示既有「暂无歌词」。

### Python 依赖核查

- 6 个脚本的 import 只有 requests、bs4、Crypto 与标准库（39 号
  另用 urllib3）。pyramid/build.gradle.kts 的 Chaquopy pip 清单
  已含 requests、beautifulsoup4、pycryptodome；本次只新增显式
  install("urllib3")（本为 requests 传递依赖，显式声明防丢失），
  未删任何依赖。无清单外无法补的包。

### 已知限制

- py 源歌曲无歌词（爬虫不提供），不做平台歌词硬套。
- parse 型（parse=1/jx=1）与 proxy:// 播放地址不支持，分别给出
  提示/报错，不硬播。
- py 运行时与视频侧共用：视频配置切换会清掉音乐爬虫实例缓存
  （下次调用自动重建，首次会再付一次加载耗时）；pyLoader 的
  recentPyKey 为全局单值，音乐与视频 py 站点的 proxy 调用互相
  覆盖——音乐桥不使用 proxy 调用，不受影响。
- 39 号脚本（netease_music）自带写死的外置缓存目录
  /storage/emulated/0/Download/...，无存储权限时其缓存写入可能
  失败，属脚本自身行为，本版未改脚本逻辑（仅加原名注释）。

## v1.13.1 py 播放修复与来源 chip 显示名

### 来源 chip 显示名（用户点名）

- v1.13.0 播放页来源 chip 直接渲染 MusicTrack.source，py 歌曲露出
  原始 id「py:netease_music」。改为展示名传递：LxMusicPlayerActivity
  在 playAt 设置当前歌曲时算好展示名，写入 LxPlayerState.sourceLabel，
  LxMusicPlayerScreen 只读状态渲染（UDF），旧的屏内 lxSourceLabel
  映射函数删除。py 歌曲用 PyMusicSources 注册表的显示名（如
  「网易云音乐」「小心儿悠悠（酷我）」），注册项已被删除时兜底新
  字符串 music_py_fallback「py 音源」（四语齐全）；JS 音源歌曲仍是
  平台名（酷狗/酷我/QQ音乐/网易云/咪咕），音质 chip 不变。内部
  MusicTrack.source 不动，仍是 "py:<slug>"。
- 其余展示面核查：队列对话框与音乐页列表行（含收藏列表）只显示
  歌名/歌手，LxFavorites 里的 source 只是内部 key，无原始 id 露出，
  无需改。

### py 调用全局串行化（问题二根因判断）

- 实测症状：homeContent、搜索、detail 选集都通，但点歌的
  playerContent 一直转圈到 25s 超时（《布拉格广场》），后一首
  《老街》弹出「爬虫加载失败或无响应」；小心儿悠悠点歌手的
  categoryContent 也长时间无响应报同样错误。
- 判断：页面（MusicViewModel）与播放器（LxMusicPlayerActivity）
  经 pyLoader 按 key 拿到的是同一个缓存 Spider 实例，多线程
  并发进入同一个 Chaquopy 解释器会互相卡死——单发调用正常、
  并发才挂，与症状吻合，是最可能的根因。
- 修法：PyMusicBridge 加一把全局 Mutex（serialized 包裹层），
  home/category/search/detailEpisodes/resolve 五个入口的「取
  Spider + 调用 + 解析」全程串行。Mutex 不可重入，resolve 内部
  改走不加锁的 detailEpisodesLocked，避免自死锁。调用仍在
  Dispatchers.IO + BoundedCall 内执行，UI 线程不堵。

### 超时放宽

- playerContent：25s → 60s。解析型爬虫逐个轮询备用接口
  （netease_music 内置多个备用解析地址），25s 经常不够一轮。
- detailContent / categoryContent / searchContent：25s → 45s。
- homeContent（首次，含 Python 冷启动）：保持 45s。
- 报错路径不变：超时后 BoundedCall 返回 null → PyException
  「爬虫加载失败或无响应」。播放页的失败处理是 v1.13.0 既有
  逻辑（本版核查确认、未重构）：resolve 抛错后 catch 里置
  state.buffering=false，封面转圈停止，同时 errorText 显示原因
  并弹 Toast，不会无限转圈。

### 已知限制（沿用与新增）

- BoundedCall 超时靠中断其工作线程，个别卡在原生层的 Python
  调用可能在后台残留一小段时间；串行锁在超时抛错后即释放，
  后续调用可继续排队进入，不会因一次超时永久堵死。
- 其余沿用 v1.13.0：py 歌曲无歌词；parse 型与 proxy:// 地址
  不支持；py 运行时与视频侧共用。

## v1.13.2 音乐页顶部布局：搜索行置顶 + 动态音符

按真机截图反馈调整音乐页顶部（只动 ui/page/MusicPage.kt）：

- 搜索行置顶：页面第一行改为 [动态音符] [搜索框] [外观设置]。
  搜索框由原来藏在 JS 搜索模式内容区 / PySpiderContent 内部挪到
  顶行，JS 与 py 模式共用；查询状态（页面 rememberSaveable 的
  query）、IME 搜索动作、自绘胶囊样式（MusicSearchField 本体）
  全部不变，只是换了所在的行。搜索目标按模式分发：py 模式走
  vm.pySearch；JS 模式若当前不在搜索模式，先 selectMode(SEARCH)
  再 vm.search，保证结果可见（vm.search 本身不切模式，原先搜索
  框只在搜索模式内出现、不存在这个问题）。
- 动态音符：搜索框左侧放 24dp 音符图标（复用 res/drawable/
  ic_tab_music 的矢量图形，tint 用主题 primary），不用 emoji
  文本。用 rememberInfiniteTransition 做轻柔循环动画（与
  ui/components/Skeleton.kt 的 shimmer 同一套 Compose 动画
  API）：上下浮动 ±3dp + 旋转 ±8°，600ms 单程、Reverse 往返，
  全周期 1.2s，经 graphicsLayer 应用，只作用于这一个小图标。
- 外观按钮归位：调色板按钮从模式行右端（py 模式下是单独一行
  靠右）移到顶行搜索框后面，点击仍打开既有外观 Sheet
  （showEqSection=false，内容不变）。模式行在 py 模式下整行
  不再渲染（原来只剩调色板时才出现），JS 模式下只剩三个模式
  chip。
- 其余不动：平台/分类 chips 行顺延为第二行（补 top 8dp 间距），
  模式行为第三行；音源悬浮按钮、自定义背景图（含搜索框既有
  的半透明处理）逻辑不变；JS 搜索内容区去掉内嵌搜索框后结果
  Box 直接占满内容区；PySpiderContent 去掉搜索框与对应参数，
  「返回分类」行保留（顶部间距 4dp→8dp）。

## v1.13.3 py 歌词匹配 + 定时关闭 + 后台播放（服务优先/本地兜底）

本版含 v1.13.2 的音乐页顶行布局（未单独发版），并在此之上做三件事。

### py 歌曲歌词匹配（music/LyricMatcher.kt，新增）

- py 歌曲不再直接放弃歌词：playAt 的歌词协程与原生歌曲统一——原生
  走 MusicApi.lyric(本平台)，py 走 LyricMatcher.matchLyric，拿到
  LRC 后同一条 MusicLrc.parse → state.lyricLines 展示路径。播放
  先行、歌词后到自动刷新，匹配失败保持「暂无歌词」，全程不阻塞
  播放（铁律）。
- 匹配策略：先网易（wy）后 QQ（tx），全部复用 MusicApi.search /
  MusicApi.lyric，不另写 HTTP。搜索关键词 = 清洗后的歌名 + 第一
  歌手。
- 歌名清洗（titleCandidates，按可信度排序、最多 3 个）：
  ①《》内标题（"周深《小美满》"→"小美满"）；②" - "拆分后非歌手
  侧（歌手侧判定：含 / 或 、，或与清洗后的歌手 token 互为包含）；
  ③另一侧；④去括号的整名。
- 歌手清洗（cleanSingers）：按 /、,，&;；与空白切分，剥尾部垃圾
  后缀（歌曲/音乐/合集/专辑/精选，如"周深歌曲"→"周深"），去重。
- 挑选规则（pickMatch）：候选名与目标名归一化（小写、去空白与
  标点）后必须全等，或去尾部括号标签（"（Live）"等）后全等；
  同名候选里优先歌手有交集的；无歌手信息时接受纯歌名命中。宁可
  无词，不配错词。

### 定时关闭

- 播放页顶栏在调色板按钮旁新增时钟按钮（ic_sleep_timer），点击
  用既有 AVBoxOptionSheet 选：取消定时 / 15 / 30 / 45 / 60 分钟。
- 状态进 LxPlayerState：sleepTimerEndAtMs（墙钟截止，0=关）+
  sleepMinutesLeft（剩余整分钟）。Screen 只渲染：定时中顶栏按钮
  变主题色、按钮旁小字显示「还剩 N 分钟」，Sheet 标题也带剩余
  时间。
- 执行点在 Activity 既有 400ms ticker 里（checkSleepTimer）：到点
  对当前生效的 Player（服务 controller 或本地 ExoPlayer）调用
  pause 并 toast「定时时间到，已暂停播放」。截止时间存在 state
  里，跨切歌保持；播放页销毁时 ticker 取消，定时随之取消。

### 后台播放重做（双模式 + 降级兜底）

- LxPlaybackService 重写并重新注册（Manifest：foregroundServiceType
  ="mediaPlayback"，exported=true + MediaSessionService intent-
  filter，即 v1.9.2 摘掉的那段按 media3 规范形式恢复）。服务持有
  ExoPlayer + MediaSession（setCallback + setSessionActivity），
  MediaSessionService 默认通知（DefaultMediaNotification.Provider）
  展示 MediaItem 元数据里的标题/歌手/封面，通知栏/锁屏只有
  播放/暂停（队列在 Activity 侧单曲交接，不做上下首）。通知点击
  经 session activity 回到播放页（用 ComponentName 全限定名指向
  播放器 Activity，music 包不 import ui.*，不新增包级循环）。
- Activity 改为「服务优先 + 本地兜底」：init 时并行发起
  MediaController 连接（buildAsync，3s 超时）；第一次 startPlayback
  等待连接结果（≤3.5s）后锁定本会话模式（SERVICE/LOCAL）。连接
  失败/超时、或后续发现 controller 已断开（isConnected 检查）、
  或 controller 调用抛异常 → degradeToLocal：释放 controller、
  本会话不再重试，之后全部走 v1.13.1 的本地播放器路径。整个连接
  与启动过程 try/catch 包裹，任何异常只降级不崩溃——最差情况等
  于 v1.13.1 现状。
- 队列/循环/随机/顺序/上下首/进度/seek/下载逻辑全部改走 Player
  接口（controller 本身即 Player）：playerListener 抽为共享监听
  同时挂本地与 controller（只有锁定目标有媒体，空闲侧不发事件）；
  ticker 读 activePlayer()；循环模式在连接建立与模式锁定时各同步
  一次 repeatMode。MediaItem 改为带 MediaMetadata（标题/歌手/
  专辑/封面 Uri）构建，本地模式行为不变。
- header 传递（服务侧）：MediaItem 不带 HTTP header，改用自定义
  SessionCommand「…music.SET_HEADERS」：Activity 在 setMediaItem
  前把当前曲 headers（键名列表 + "hv:<键>" 值）推给服务，服务
  写入自己那份 DefaultHttpDataSource.Factory 的默认请求属性
  （与本地模式 v1.13.0 的办法等价）；推送有 2s 超时、失败不阻塞
  播放。
- EQ 两种模式行为：本地模式与旧版一致（exo.audioSessionId 直
  接建 AudioFX）。服务模式下 MediaController 不给 session id，
  改用自定义命令「…music.GET_AUDIO_SESSION_ID」向服务取
  （SessionResult.extras 回传），取到后照常建 AudioFX——音频会话
  是系统级的，Activity 进程建的效果器作用于服务播放器的输出；
  取不到（命令失败/超时）时 EQ 在服务模式下静默不生效，外观
  Sheet 照常打开，播放不受影响。session id 在服务播放器 lifetime
  内稳定，切歌不重取（id 变化时 ensureEffects 仍会重建效果器）。
- 通知点开恢复：start()/init() 把队列 JSON 与当前 index 记在
  companion 进程内记忆里；无队列 extra 的启动（通知点开且旧实例
  已销毁）按记忆恢复队列与当前曲，不再直接 finish。
- 崩溃留痕：music/MusicCrashLog.kt 给播放器 Activity 与播放服务
  所在进程装默认未捕获异常处理器，把堆栈追加写到
  filesDir/music_crash.log（>256KB 时重建）后委托原处理器，不吞
  异常、不改默认行为；同进程只装一次。

### 音乐页迷你播放条

- 进程级共享状态 music/NowPlayingState.kt（StateFlow，只读投影）：
  一条 Info 记录（trackKey/标题/歌手/封面/来源名/isPlaying/canNext/
  serviceBacked/队列 JSON/队列 index）。三个写者：播放器 Activity
  在 startPlayback 交接前发布完整记录并由其播放监听同步 isPlaying；
  LxPlaybackService 的 player 监听维护它（转曲时按 MediaItem 元
  数据重发、保留队列/来源名/canNext 等富集字段，isPlaying 只翻
  serviceBacked 的记录，服务 onDestroy 时清掉自己那条）；迷你条
  自己在服务模式切歌成功时重发。
- MediaItem 统一由 NowPlayingState.mediaItemFor 构建：mediaId =
  歌曲 key（与 LxFavorites.keyOf 同公式），附标题/歌手/封面元
  数据。播放器页与迷你条共用。
- 显示条件：MusicPage 订阅 StateFlow，有记录才显示；服务模式还
  要求页面自己的轻量 MediaController 连上同一 session（连接
  try/catch、失败静默——连不上就不显示，绝不影响音乐页）；本地
  模式有记录即显示。位置在内容区底部、底部导航之上（内容 Box
  在小条可见时补 64dp 底部 padding，列表不被遮挡）；音源 FAB
  逻辑与位置不动，小条在其下层。
- 按钮：播放/暂停与关闭走页面 controller（服务模式）或
  NowPlayingState.localControls（本地模式，由 Activity 在锁定
  LOCAL 时注册、销毁时注销）；下一首服务模式由页面侧完成——按
  KV 里的循环模式算下一 index（与播放器页规则一致：顺序模式到
  队尾不可进、随机模式避开当前曲），用与播放器页相同的
  LxBridge/PyMusicBridge 解析 + SET_HEADERS 自定义命令 + 发布新
  记录 + setMediaItem，解析失败只 toast、当前歌曲继续播。关闭 =
  controller.stop()（本地模式走 localControls.stop）+ 清空共享
  记录 + 页面 controller 随记录消失自动断开。
- 点条身回播放页（附着）：LxMusicPlayerActivity.startAttached 只
  带 EXTRA_ATTACH，不带队列、不覆盖进程内记住的队列。init 恢复
  记住的队列后由 tryAttach 判断：共享记录存在、serviceBacked、
  且其 trackKey 能在队列里找到，才走附着——页面按该曲目布置
  （歌词/封面色/收藏照常加载），bindAttachedSession 等连接锁定
  SERVICE 后再核对 controller 当前 MediaItem 的 mediaId 与记录
  key 一致才真正绑定（进度/时长由既有 ticker 读 controller，
  播放状态直接采信 controller），不一致或连不上就回退普通
  playAt 重播，绝不出现假绑定黑页。本地模式记录不附着（点条身
  按普通启动走记住队列重播，与通知点击行为一致）。
- 本地模式的行为差异：本地播放只存在于播放器页存活期间，记录
  随页面销毁清除，所以小条在本地模式下实际只在「页面未销毁但
  不在前台」的边缘场景可用；服务模式才是小条的主路径。

### API 依据（media3 1.11.1 官方 sources jar 逐项核对）

- MediaController.buildAsync / isConnected / sendCustomCommand
  （返回 ListenableFuture<SessionResult>）、MediaSession.Builder
  .setCallback / setSessionActivity、MediaSession.Callback
  .onCustomCommand(session, controller, customCommand, args)、
  SessionCommand(String, Bundle).customAction、SessionResult
  .RESULT_SUCCESS / RESULT_ERROR_NOT_SUPPORTED / extras 字段、
  MediaSessionService 经 onGetSession 返回的会话由框架自动
  addSession 并挂默认通知管理（DefaultMediaNotificationProvider）
  ——均在 1.11.1 sources 中确认存在。media3-session 依赖
  app/build.gradle.kts 本就在（libs.media3.session），未动。

### 已知限制

- 通知栏/锁屏只有播放/暂停，没有上一首/下一首（队列仍是
  Activity 侧单曲交接，与 v1.9.0 的限制相同）。
- 服务模式下若服务连接在会话中途死亡（同进程，实际只可能随
  整个 App 进程死亡发生），下一次点歌/切歌时降级本地播放器，
  当前曲不会自动续播，需用户再点一次播放。
- EQ 在服务模式依赖自定义命令回传 session id；个别设备命令
  往返失败时 EQ 静默失效（见上）。
- py 歌词匹配依赖平台搜索可用性与歌名清洗质量：同名不同曲、
  爬虫歌名严重变形时可能匹配不到（保持无歌词）或极少数配错，
  挑选规则已按「宁缺勿错」收紧。

## v1.15.0 唱片音源按钮 + 内置音源撤下

### 顶行：唱片按钮取代音符与悬浮按钮

- 顶行左侧原 AnimatedMusicNote（上下浮动的音符图标）换成
  VinylSourceButton：Canvas 自绘黑胶唱片——黑盘、三圈纹路
  （同心圆细环）、一段浅色弧光、主题色中心标 + 偏心小白点、
  中心孔；不用 emoji、不用外部图片。点击它打开与原悬浮按钮
  同一个音源选择对话框。
- 旋转条件：订阅 NowPlayingState 的 isPlaying——有歌在播时匀速
  旋转（360°/6 秒，线性），暂停/无播放时静止。角度用
  LaunchedEffect + withFrameMillis 逐帧累积（只在播放时累加），
  播放状态切换时角度不清零、不跳变。
- 唱片按钮与右侧外观（调色板）按钮统一为 TopRoundButton：40dp
  圆形、1dp 浅色描边、半透明底（自定义背景图时沿用页面惯例
  surface 0.45 alpha，否则 surfaceVariant 0.55 alpha）。顶行两端
  按钮等宽（40dp）+ 等距 8dp 间隔，中间的搜索栏因此居中；搜索栏
  本体（胶囊样式、IME 搜索、背景 alpha）未动。
- 悬浮音源按钮 MusicSourceFab 整体移除，连同拖动手势与位置
  持久化代码；KV 里已存的 music_source_fab_pos 不删除、只是不再
  读取。迷你播放条与内容区 padding 不受影响。

### 内置音源全部撤下

- JS 侧：LxBridge.SCRIPTS 常量删除，星海（xinghai）、全豆要
  （quandouyao）不再注册为可选/默认源。MusicViewModel 的
  allDefs() 只剩用户导入脚本，findDef 改为可空。
- py 侧：PyMusicSources.BUILTINS 删除，6 个内置 py 源不再释放、
  不再出现在可选列表；loadDefs() 只返回用户导入，resolveFile()
  简化为按 filePath 取文件（签名不变，PyMusicBridge 调用点不动）。
- assets 里的 lx_sources/*.js 与 py_sources/*.py 文件保留在 APK
  里（push-files 删不了文件），只是再没有任何代码引用它们。

### 默认选择与无源空状态（新规则）

- 启动顺序：记住的音源（lx_music_script_id）还存在就用它（py
  走 activatePy、JS 走 initBridge）；不存在或加载失败时，按对话
  框列表顺序（JS 用户源在前、py 用户源在后）试第一个能启动的
  源（startFirstAvailable，跳过刚失败的记住源）。
- 删除正在用的源后同样走 startFirstAvailable 兜底；无源可兜时
  清空平台列表与记住值，进入空状态，不再指向已删除的 id。
- 一个用户源都没有时（noSource 状态）：不初始化桥、不报错，
  音乐页内容区居中显示「暂无音源，点左上角唱片按钮添加」
  （music_no_source，四语）。此状态下搜索被 ViewModel 的空源
  守卫挡下（toast 音源未就绪）、排行榜返回空列表，不会 NPE
  或一直转圈。导入第一个源后自动切换过去、空状态解除。
- savedScriptId() 不再回退到任何内置源：记住值无效时返回 ""。

## v1.15.1 — 后台播放服务连接加固 + 列表底部留白

### 背景

v1.13.3 的后台播放真机（荣耀）从未接上：播放时通知栏无 AVBox
媒体通知、退播放页即停、音乐页无迷你条。静态审计确认服务本体、
清单注册、连接代码都没写错；最可疑的是连接预算太紧——播放页打开
才冷启服务、只等 3 秒（SERVICE_CONNECT_TIMEOUT_MS=3000 /
WAIT=3500），而 QuickJS/Python 初始化同时抢资源，超时后
degradeToLocal 本会话永不重试，于是每次都落成本地播放。

### 进程级预热连接（music/MusicServiceConnection.kt，新）

- object 单例持有共享 MediaController 连接（构建 future + 已连成
  的 controller），用 applicationContext 构建，不泄漏 Activity。
- ensureConnecting(context)：已连成直接返回；构建中不重复建；
  构建失败或 controller 后续断开（MediaController.Listener 的
  onDisconnected + 读取时 isConnected 复查）都会清掉状态，下次
  调用重新建。
- MusicPage 首次组合时用 LaunchedEffect 调 ensureConnecting
  预热（只加这一处，唱片/FAB/源逻辑未动）；播放页 connectService
  改为优先采用共享连接：在飞的构建用新的 10 秒预算等待；什么都
  没有时经 ensureConnecting 发起（构建即归共享持有）。
- 持有/释放语义：共享 controller 由单例持有，播放页只
  addListener/removeListener 自己 playerListener，onDestroy 与
  degrade 时都不 release；Activity 自有 controller 的分支保留
  release（现行路径下 controller 恒为共享）。

### 预算与会话内重试

- SERVICE_CONNECT_TIMEOUT_MS 3000→10000，
  SERVICE_CONNECT_WAIT_MS 3500→10000。
- 已因连接失败降级本地后，播放页存活期间每 5 秒重试
  connectService、最多 6 次（lifecycleScope，销毁即停）。重试
  失败只写日志、不再走 degradeToLocal（记录转换不重复 thrash）；
  重试成功只置 serviceReadyForNext，不打断本地正在播的歌——
  下一次 startPlayback 时 awaitActivePlayer 优先锁 SERVICE、
  localControls 置 null，新记录经 startPlayback 原有发布路径
  自然回到 serviceBacked；切到服务的那一首会先 stop 本地播放器
  防两首重叠。

### 显式拉起服务与通知权限

- 每 Activity 首次把歌交给服务时（startPlayback 的 controller
  分支内、setMediaItem 之前），try/catch 调
  ContextCompat.startForegroundService 拉起 LxPlaybackService，
  失败静默并写日志。只在服务即将收到曲目时调，避免降级模式下
  服务无内容可播却被 startForegroundService 的前台时限反噬。
- 通知权限：init 里已有
  PermissionHelper.requestNotificationIfNeeded(this)（v1.9.0 起），
  本版核对保留，不阻塞播放。

### 诊断日志

- MusicCrashLog 新增 log(context, msg)：带时间戳一行，同时写
  filesDir/music_crash.log 与 getExternalFilesDir(null)/
  music_crash.log（external 为 null 跳过；超 256KB 沿用删除重建）。
  后者位于 Android/data/com.github.avbox.osc/files/，可直接拷出。
- 事件点（克制）：共享连接开始/成功耗时/失败；播放页 connect
  开始/成功耗时/超时/异常（区分 retry）；degrade 及原因
  （connect-timeout / connect-exception / controller-dropped /
  controller-call-failed）；重试第几次/成功；awaitActivePlayer
  锁定结果（SERVICE/LOCAL，含重试切换）；startForegroundService
  结果；服务侧 onCreate / onGetSession。

### 音乐页列表底部留白（Torres 反馈：榜单卡片被悬浮栏压住）

- 查证：悬浮栏遮挡高度 = 导航栏 inset + MARGIN 12 + 栏高 64
  （NavMetrics.FLOATING_OVERLAY_DP=76）；MainScreen 在
  liquidGlass 分支给 pageContentPadding.bottom 恰为 inset+76，
  已全额预留，故滚到底最后一行不会被永久切掉。但音乐页只有
  榜单网格自带 bottom=16dp，搜索结果 / py 列表 / 收藏 / 榜单
  歌曲四个 LazyColumn 没有任何底部 contentPadding，最后一行
  底边与悬浮栏顶边 0dp 重合，再叠 MainScreen 底部 140dp 渐变
  scrim，观感即「被压住」。
- 改法（只动 MusicPage）：四个 LazyColumn 补
  contentPadding bottom = 16dp，与榜单网格对齐。76dp 部分已由
  pageContentPadding 给过，不重复计算；迷你条的 +64dp 是内容
  Box 的 padding，与容器 contentPadding 正交叠加、不冲突。
  rail 模式（侧栏）与普通底栏模式下这 16dp 只是列表末尾多一点
  空白，无副作用。MainScreen 的 scrim 本身未动（全局设计）。

### 真机验证点

1. 冷启动 App → 进音乐页 → 点歌：通知栏应出现 AVBox 媒体通知
   （歌名 + 暂停键），荣耀灵动岛胶囊应显示播放状态。
2. 播放中按 Home / 锁屏：音乐继续；回音乐页底部迷你条出现，
   暂停/下一首可用，点迷你条回播放页不重头播。
3. 退播放页再切歌、自动连播：仍走服务（通知不消失）。
4. 榜单/搜索/py 列表/收藏滚到底：最后一行/排完整露在悬浮栏
   上方，有一小段间隔。
5. 若仍无通知/不能后台：把 Android/data/com.github.avbox.osc/
   files/music_crash.log 发回，按 connect/degrade 行定位。

## v1.15.2 py 歌手分类平铺网格 + 分类再点回顶部

Torres 对比 OK影视（同一爱听 py 爬虫当普通站点打开）指出：
OK影视里「歌手」分类是平铺网格，音乐页 py 模式却渲染成歌曲式
列表行。本版只动 MusicPage / MusicViewModel，两项：

### 歌手分类改网格

- 判定（页面层由 VM 状态派生，PySpiderContent 只渲染）：py 模式、
  非搜索状态（pySearchActive 为假）、当前选中分类名包含「歌手」
  （歌手 / 歌手分类 / 歌手大全等）时，pyItems 改用
  LazyVerticalGrid 渲染；其余分类、py 搜索结果、JS 模式保持
  列表行不变，收藏模式与 py 无关、未动。
- 网格：3 列，格间距横竖 12dp、contentPadding top 8 / bottom 16，
  与榜单网格惯例一致。每格 = 圆形封面（AsyncImage + CircleShape，
  取 track.img，与列表行同一字段）+ 居中一行名字（超长省略）。
- 点击格子与点击列表行共用 onPyTrackClick：多集 vod 走既有选集
  弹层、单曲直接播，队列仍是当前列表，未另写一套。
- 加载更多与列表行同一触发：index 进入末尾 5 个时调
  pyLoadMore()；loadingMore 时网格底部跨整行显示转圈；首屏
  loading 与空状态沿用原有分支。

### 分类 chip 再点一次回顶部并刷新

- selectPyClass：已选中同一分类且非搜索状态时，不再直接
  return，改为 loadPyList(reset = true) 从第一页重新加载
  （搜索状态下点当前分类的原有路径不变；JS 模式的平台 chip
  不在本项范围）。
- MusicPage：PySpiderContent 为行/格两个容器各持一个
  LazyListState / LazyGridState，LaunchedEffect 以 _pyLoading
  由假变真为触发，对当前可见容器 scrollToItem(0)；loadMore 走
  _pyLoadingMore，不会误滚。JS 模式的列表未动。

### 真机验证点

1. 爱听音乐「歌手」：平铺 3 列圆形头像 + 名字；点某个歌手能进
   他的歌曲列表/选集并播放。
2. 其他分类（TOP榜单/新歌榜/电台等）与 py 搜索结果仍是列表行。
3. 在任意长列表滑到中间，再点一次当前选中的分类 chip：列表
   回到顶部并从第一页刷新。

## v1.15.3 唱片按钮常转（空闲慢转 + 播放加速）

Torres 反馈：左上角唱片只有播放时才转，没点歌时全程静止，
看起来像个静态图标。改为唱片一直动态旋转。

- VinylSourceButton 的逐帧循环（仍是原来那一个 LaunchedEffect
  + withFrameMillis，角度仍存 remember、切换不跳变）：
  空闲（未播放/无记录）目标速率 360°/20000ms（18°/s），播放时
  目标速率保持原值 360°/6000ms（60°/s）。
- 平滑过渡：当前速率以固定斜率向目标速率线性趋近，全程摆幅
  （60−18°/s）约 600ms 走完——起播时像唱片提速，暂停后缓缓
  回到慢转，无突变。
- 按钮的点击行为（开音源对话框）、外观（黑盘/纹路/弧光/中心
  标/TopRoundButton 外壳）、语义描述均未改；未引入新的动画
  状态，循环只存在于音乐页顶行的这一个按钮。

### 真机验证点

1. 进音乐页不点歌：左上角唱片也缓慢转动，不是静止图标。
2. 点歌播放后：唱片转速明显加快（约 0.6 秒内提速到位）。
3. 暂停后：转速缓缓回落到慢转，而不是立刻停住。

## v1.15.4 — 后台播放连不上：根因定位与修复

Torres 于 2026-11-17 发来 music_crash.log，日志给出决定性证据（v1.15.3，10:03–10:05）：

```
svc: shared connect start
svc: shared connect start failed IllegalArgumentException   ← 1–2ms 内必现
player: connect timeout after 2ms (retry=false)
player: degrade to local (connect-timeout)
```

即连接不是超时、更不是服务慢，而是**在构造连接令牌时同步抛异常**，服务根本没有被拉起过（日志中无 svc: service onCreate / onGetSession）。对照 media3-session 1.11.1 源码：`SessionToken(Context, ComponentName)` 构造器用 `queryIntentServices(Intent("androidx.media.session.MediaSessionService").setPackage(本包名), GET_META_DATA)` 解析目标服务，三种接口都查不到时抛 `IllegalArgumentException("Failed to resolve SessionToken for ...")`。已核对发布 APK 的合并清单：LxPlaybackService 声明完整（exported=true、mediaPlayback、两个 MediaSessionService action 齐全），源码侧亦无任何禁用该组件的代码——即清单没问题，是测试机（荣耀/MagicOS）的包管理查询在该调用下返回了空结果。此根因同时解释 v1.9.0 点歌闪退（当时同一构造在 Activity 里无降级兜底、异常直接崩溃）与 v1.13.3 起的「静默降级本地播放」（无通知、无迷你条、无灵动岛）；此前「3 秒超时太短」的判断是错的。

修复（本版）：

1. `AndroidManifest.xml` 的既有 `<queries>` 块中显式声明对 `androidx.media.session.MediaSessionService` 与 `android.media.session.MediaSessionService` 两个 action 的可见性，使上述包管理查询能命中本应用的播放服务。
2. `MusicServiceConnection` 的两处失败日志补上异常 message（此前只记类名）：shared connect start failed 与 shared connect failed（后者取 ExecutionException 的 cause）。若本修复无效，新日志会直接给出异常原文以便下一步判断。

### 真机验证点

1. 点一首歌播放：应能正常出声（不回归）。
2. 播放中按 Home 退到桌面：歌继续播，通知栏出现 AVBox 播放通知（歌名/歌手/封面，可暂停），荣耀灵动岛/控制中心媒体卡显示正在播放。
3. 回到音乐页：底部出现迷你播放条，暂停/下一首/关闭可用，点条身回播放页且不重头播。
4. 若仍不行：再发一次 music_crash.log——新版日志带异常原文（预期能看到 "svc: shared connected" 则说明已连上）。

## v1.16.0 — 后台播放重构：弃用 media3 会话，改同进程绑定服务

Torres 于 2026-11-17 10:43 反馈 v1.15.4「还是不行」——`<queries>` 可见性
声明没有改变荣耀/MagicOS 上 `queryIntentServices(MediaSessionService,
本包)` 返回空结果的事实，SessionToken 构造依旧抛
IllegalArgumentException。结论：media3 的 MediaSessionService /
MediaController 路线在这台设备上走不通，本版整体替换，不再有任何
SessionToken / MediaController 参与音乐后台播放。

### 新架构（同进程 LocalBinder + 框架 MediaSession + 手工通知）

- `music/LxPlaybackService.kt` 重写为普通 `android.app.Service`：
  内部仍持一个 ExoPlayer（同一套 DefaultHttpDataSource.Factory +
  音乐 AudioAttributes + handleAudioFocus）；新增 `LocalBinder`
  直接交出服务对象，服务对外暴露 player、`setHeaders(map)`（写入
  HTTP 工厂默认请求属性，与本地播放器同一机制）、`audioSessionId`。
  旧的两个自定义 SessionCommand（SET_HEADERS /
  GET_AUDIO_SESSION_ID）随会话层一起删除，改为同进程直调。
- 锁屏/控制中心：服务内建框架 `android.media.session.MediaSession`
  （tag "AVBoxMusic"，onCreate 即 setActive(true)），Callback 把
  onPlay/onPause/onSeekTo/onStop 映射到 ExoPlayer，onSkipToNext/
  onSkipToPrevious 转发给可注册的 `NextPrevHandler`（队列在
  Activity/迷你条手里，服务不持队列；无人注册时上下首为空操作）。
  PlaybackState 与 MediaMetadata（标题/歌手/专辑/封面 Uri 字符串）
  由 Player.Listener 实时同步。
- 前台通知手工构建：`Notification.Builder` + 本应用通知渠道
  `avbox_music_playback`（IMPORTANCE_LOW，新字符串
  music_notification_channel 四语齐全）；`Notification.MediaStyle`
  绑定框架 session 的 token，compact 显示 0/1/2 三键；动作为
  上一首 / 播放暂停（图标随 isPlaying 切换）/ 下一首，
  PendingIntent 以显式 action 意图回送服务、onStartCommand 分发
  （FLAG_UPDATE_CURRENT | IMMUTABLE、独立 requestCode）。
  startForeground 在 onCreate 用初始通知先行调用、onStartCommand
  每次再保一次，后续状态变化在已前台时用 notify 刷新（布尔标记
  把关）；onStartCommand 返回 START_STICKY。通知点击经
  setSessionActivity 回播放页（仍用 ComponentName 全限定名，
  music 包不 import ui.*）。
- `music/MusicServiceConnection.kt` 重写：object 持有
  ServiceConnection + @Volatile 服务引用 + 在飞的
  CompletableDeferred；`ensureConnecting` 用 applicationContext
  bindService(BIND_AUTO_CREATE)，幂等；`awaitConnected(timeoutMs)`
  等绑定完成返回服务（或 null）；`connectedService()` /
  `connectedPlayer()` 同步取；`startForegroundService(context)`
  封装 ContextCompat.startForegroundService + try/catch + 日志；
  onServiceDisconnected 清引用待重绑。日志前缀统一改 `svc2:`，
  与旧日志区分（service onCreate / bound / startForeground ok /
  shared bind start / shared bound / shared disconnected 等）。
- NowPlayingState 的 Info/publish API 不变，serviceBacked 语义
  改为「绑定服务在播」；服务的镜像监听语义与旧版一致（只维护
  serviceBacked 记录、转曲保留富集字段、onDestroy 清自己记录）。

### 播放页（LxMusicPlayerActivity）适配

- MediaController 字段换成 boundService + servicePlayer（服务
  ExoPlayer 即 Player，共享 playerListener/ticker/队列/循环逻辑
  原样复用）；connectService 走 ensureConnecting + awaitConnected
  （10s 预算不变）；degradeToLocal 与 6 次/5s 重试循环保留，重试
  改为重新尝试绑定，成功仍只置 serviceReadyForNext、下一首才切
  服务；切服务时先 stop 本地播放器防重叠（原有逻辑）。
- headers 改直调 boundService.setHeaders；EQ 的服务侧 session id
  改直读 boundService.audioSessionId（同进程，无命令往返、无异步
  fetch）；startForegroundService 改走 MusicServiceConnection
  助手（每 Activity 一次不变）。
- 通知/锁屏上下首：Activity 连接成功时把自己的
  NextPrevHandler（next=playNextManual、prev=playAt(index-1)）
  注册到服务，degrade/onDestroy 时若仍是自己注册的则注销。
- attach 流程不变，只是「服务正在播的歌」改为比对绑定服务
  player 的 currentMediaItem.mediaId 与记录 trackKey。
- onDestroy 只 removeListener + 注销 handler：绑定归
  MusicServiceConnection（applicationContext）持有，不 unbind、
  不 release 服务播放器（serviceIsShared 语义）。

### 音乐页迷你条（MusicPage）适配

- 删除页面自己的 MediaController/SessionToken 连接；条显隐 =
  有 nowPlaying 记录且（本地模式 localControls 在位，或服务模式
  绑定已建立——LaunchedEffect 里 ensureConnecting +
  awaitConnected(10s) 置位 serviceBound）。
- 服务模式按钮直接驱动 MusicServiceConnection.connectedPlayer()
  （play/pause/stop）；下一首的页面侧解析逻辑不变（LxBridge/
  PyMusicBridge 解析 + 发布记录 + setMediaItem），headers 改直调
  connectedService()?.setHeaders，playBarTrack 参数由
  MediaController 改为 Player。页面打开时的预热
  ensureConnecting 调用保留（现在是绑定预热）。

### 清单与依赖

- AndroidManifest：LxPlaybackService 改为 exported=false、删除
  MediaSessionService intent-filter、保留
  foregroundServiceType="mediaPlayback"；v1.15.4 的 `<queries>`
  块保留（无害）。media3-session 依赖保留不删（按 AGENTS.md
  §6.3 的裁剪谨慎原则，未用依赖比误删安全）。

### 已知限制

- 通知为纯文本（歌名/歌手 + 上一首/播放暂停/下一首），不带封面
  图：服务不做图片下载，避免再引入一条网络依赖；锁屏/控制中心
  的媒体卡由框架 MediaSession 驱动，元数据含封面 Uri 字符串，
  系统侧是否取图由荣耀系统决定。
- 上下首依赖队列所有者注册的 NextPrevHandler：播放页销毁后
  （只剩服务与迷你条时）通知/锁屏的上下首无人处理为空操作，
  迷你条的下一首仍可用（页面侧解析）；播放/暂停/进度不受影响。
- 绑定与播放器同进程：进程被系统杀死时后台播放仍会停（与旧版
  一致）；START_STICKY + 前台服务只能降低被杀概率。

### 真机验证点

1. 点一首歌播放：应能正常出声（不回归）。
2. 播放中按 Home 退到桌面：歌继续播，通知栏出现 AVBox 播放通知
   （歌名/歌手，可暂停/上一首/下一首），荣耀灵动岛/控制中心媒体
   卡显示正在播放。
3. 回到音乐页：底部迷你播放条出现且不闪退，暂停/下一首/关闭
   可用，点条身回播放页且不重头播。
4. 若仍不行：再发一次 music_crash.log——新版日志前缀是 svc2:，
   预期能看到 "svc2: shared bound" 与 "svc2: startForeground ok"。

## v1.16.1 — 通知/媒体卡显示歌曲封面

- Torres 反馈：通知栏媒体卡与灵动岛显示的是 AVBox 的 App 图标，
  不是歌曲封面。原因是 v1.16.0 的 LxPlaybackService 只把封面 Uri
  字符串写进框架 MediaSession 元数据，从不下载位图。
- 本版只改 LxPlaybackService：照视频侧 PlaybackService 的
  updateArtwork 模式，服务内用 Coil 异步加载当前歌曲封面
  （512×512），以 artworkUrl 相等为防陈旧/防循环守卫；加载完成
  后把位图写入框架 MediaSession 元数据的 ALBUM_ART 与
  DISPLAY_ICON，并设为通知的 large icon，再走一遍同步刷新。
- 封面未加载到、或加载失败时，回退到原来的 App 图标（即
  v1.16.0 的行为）；封面加载全程 try/catch，失败不影响播放
  与文字通知。

### 真机验证点

1. 点一首有封面的歌播放，按 Home：通知栏媒体卡左侧应显示歌曲
   封面（可能晚一两秒出现），不再是 App 图标。
2. 桌面灵动岛胶囊/锁屏媒体卡同样显示封面。
3. 切歌后封面跟着换成新歌的封面。

## v1.17.0 — 唱片按钮换动态图 + 导入后回主界面 + 加载提速

Torres 录屏与反馈（2026-11-17）三点：左上角唱片要换成他给的
动态图；添加脚本/py 文件后还停在音源弹窗，要手动关；文件加载
慢——录屏里打开音乐页后，记住的 py 源（爱听音乐）冷启动期间，
页面在 JS 模式的「暂无榜单」上停了约 13 秒。

### 1. 唱片按钮 = 动态 GIF

- 顶部左键 VinylSourceButton 在 API 28+ 改为显示 Torres 给的
  霓虹唱片动图（256×256、6 帧）：AndroidView + ImageView，
  ImageDecoder 解码得到 AnimatedImageDrawable 后 start()，
  CENTER_CROP 并裁成圆形；视图离屏时在 onRelease 里 stop()。
  解码失败只留空壳，不崩页。
- GIF 的入库形式：本仓库的 GitHub 推送通道只接受 UTF-8 文本
  文件，二进制 GIF 传不上去，所以 GIF 以 base64 文本资源
  res/raw/vinyl_neon.b64 入库（无换行）。运行时首次用到时，
  VinylGifDisc 把它 Base64 解码写入 cacheDir/vinyl_neon.gif
  （文件缺失或长度与解码字节不一致时才写），再用
  ImageDecoder.createSource(文件) 解码；任何失败都走原来的
  解码失败路径，只留空壳。
- API < 28 没有 ImageDecoder，保留原来的自绘旋转唱片
  （VinylCanvasDisc，空闲 20 秒/圈、播放 6 秒/圈的逻辑不变）。
  按钮尺寸、点击开音源弹窗、playing 参数签名都不变。

### 2. 导入后关弹窗回主界面

- MusicPage 的两个导入回调（JS importLauncher、py
  pyImportLauncher）在调用 vm.importScript / vm.importPyScript
  之后立即把 scriptDialogOpen 置 false：弹窗关闭，用户直接
  回到音乐主界面。导入与激活仍在 ViewModel 里异步进行，
  「已导入 / 加载失败 / 音源已就绪」提示走页面级已有的
  messages 收集（LaunchedEffect(vm)），关弹窗不丢任何提示。
  取消选择文件（uri 为 null）时弹窗保持打开。ViewModel 的
  导入语义不变（自动试新源、失败回退旧源）。

### 3. 加载提速与体感

- 空状态门控：ChartsContent 新增 initializing 入参
  （scriptBusy || pyLoading；scriptBusy 从 VM 创建起为 true，
  initBridge / activatePy 全程持有，覆盖 py 冷启动窗口）。
  榜单为空、且仍在初始化时，显示居中转圈 + 「音源脚本加载
  中…」，不再先闪「暂无榜单」；图表歌曲的空状态同理。初始化
  结束后若确实没有榜单（如酷我），仍显示「暂无榜单」。
- PyMusicBridge 进程级内存缓存：homeContent 按源 slug 缓存，
  categoryContent 只缓存第 1 页（键为 slug+分类 id）；搜索、
  详情、playerContent 与第 2 页起不缓存。缓存读写都在桥的
  串行 Mutex 内，普通 HashMap 即可。源被删除（及导入，防御性）
  时经 MusicViewModel 调用 clearCacheFor(slug) 失效。
- 超时值不变。

### 已知说明

- Python 运行时首次冷启动仍要几秒（进程内第一次加载爬虫的
  固有成本）；缓存只让重复进音乐页、来回切源变秒开。
- GIF 按钮动画需 API 28+；更老的系统继续用自绘旋转唱片。

### 真机验证点

1. 音乐页左上角是动态的霓虹唱片图，点它仍打开音源弹窗。
2. 在弹窗里导入一个 JS 或 py 文件：选完文件弹窗自动关、回到
   音乐主界面，导入结果的提示（已导入/已就绪/失败）照常出现。
3. 杀掉 App 重开进音乐页：记住的 py 源加载期间显示转圈+
   「音源脚本加载中…」，不再出现「暂无榜单」；加载完直接是
   该源的分类。退出音乐页再进来、或切走再切回该 py 源，
   分类与第一页列表应秒出（走缓存）。

## v1.17.1 — 动图铺满按钮 + 歌手分类判定放宽 + 停掉首屏连环翻页

Torres 试 v1.17.0 录屏后的三点反馈，全部在 MusicPage.kt。

### 1. 左上角动图铺满按钮

- API 28+ 的 GIF 路径（VinylGifDisc）由 30dp 改为 40dp，与
  TopRoundButton 外壳同尺寸、CircleShape 裁剪，霓虹图直接铺满
  整个圆形按钮，不再露一圈外壳底色。按钮外径仍 40dp（与右侧
  外观按钮对称），点击与无障碍描述不变；API 28 以下的自绘
  唱片回退保持原样。

### 2. 歌手分类不再只认「歌手」二字

- 新增顶层私有 helper isSingerClassName，PySpiderContent 的
  singerGrid 调用点改用它判定。命中任一即按歌手网格（3 列圆
  头像）渲染：名字含「歌手」或「艺人」；去空格后恰为 华语男 /
  华语女 / 华语组合 / 日韩 / 欧美 / 内地男 / 内地女 / 港台男 /
  港台女 / 男歌手 / 女歌手 之一；或匹配正则
  ^(华语|内地|港台|大陆|日韩|日本|韩国|欧美)(男|女|组合|乐队|团体)$。
- 起因：Torres 导入的酷我 py 源把歌手分类按地区+性别命名
  （华语男 / 华语女 / 华语组合 / 日韩），旧判定只认「歌手」
  二字，这些分类被渲染成了歌曲式列表行。
- 歌曲/歌单类分类（听歌模式 / 发现 / 新歌榜 / 电台 等）不命中
  以上任何一条，仍是列表。网格的点击（onPyTrackClick）、
  加载更多与搜索行为均未改。

### 3. 停掉首屏连环翻页

- PySpiderContent 的列表与歌手网格两处 itemsIndexed 加载更多
  触发，除原有的 index >= items.size - 5 外，再要求容器已
  离开顶部（firstVisibleItemIndex > 0 或
  firstVisibleItemScrollOffset > 0）。旧行为下第一页条目少
  （如只有 9 条、全部可见）时，组合阶段每个尾部条目都会触发
  一次 onLoadMore，用户还在看第一页，App 已连环请求第 2、
  3…页，白白压慢爬虫与页面。MusicViewModel.pyLoadMore 自身
  的守卫未动，列表底部的 loadingMore 转圈项未动。

### 已知说明

- 点一首歌之后等详情/播放地址的那段时间，是 py 源自己的
  服务器响应时间，本版没有改动；本版去掉的是 App 自己连环
  翻页造成的额外等待，以及按钮上那圈外壳底色。

### 真机验证点

1. 音乐页左上角霓虹图铺满整个圆形按钮，没有一圈边；点它仍
   打开音源弹窗。
2. 切到酷我 py 源：华语男 / 华语女 / 华语组合 / 日韩 等分类
   显示为 3 列圆形头像网格，不再是列表；爱听音乐的「歌手」
   分类仍是网格；新歌榜、电台等分类仍是列表行。
3. 进一个条目较少的分类停在第一页不动：不应再自动连环加载
   后续页（底部不该自己冒出翻页转圈）；手动滑到底附近时
   加载更多照常触发。

## v1.17.2 — 榜单卡片文字裁切 + 酷我榜单入口隐藏 + 播放进度条改圆点

### 1. 榜单卡片最后一行文字被裁一半

- 现象（Torres 截图）：排行榜 2 列网格里，个别卡片的最后一行
  文字（如网易云「飙升榜」的更新提示「刚刚更新」）下半截被切
  掉，同排相邻卡片却完整，且被裁的卡片量出来还略矮一截。
  原因是卡片高度随内容变化（榜名 1~2 行、有无更新提示），
  网格行高测量时可变高度的卡片会被压掉最后一行。
- 改法：MusicChartCard 改为统一高度——榜名 Text 固定预留
  两行 bodyMedium 的高度（lineHeight × 2，lineHeight 未定义
  时兜底 40dp），更新提示块改为恒定渲染（提示为空也占位）
  并固定预留一行 bodySmall 高度（兜底 16dp），卡片 Column
  末尾再加 8dp 底部间距。QQ 榜（无提示）与网易云/QQ 榜
  （有提示）的卡片高度从此完全一致，不再有可变高度可被裁。
  封面、圆角、自定义背景时的半透明底色与点击行为未改。

### 2. 排行榜模式隐藏酷我平台

- 酷我的榜单接口拒绝未签名请求（v1.8.0 已确认），排行榜
  模式下酷我永远是「暂无榜单」，只会让用户困惑。
- 改法：仅在排行榜（CHARTS）模式下，平台 chips 行过滤掉
  kw；搜索模式照常显示酷我（搜索可用，未动 MusicApi）。
  若切进排行榜模式时当前选中的正是酷我，MusicPage 的
  LaunchedEffect 会走既有 selectSource 路径自动回退到第一
  个其余平台并加载其榜单。

### 3. 播放页进度条改圆点滑块

- 现象（Torres 截图圈出）：material3 默认滑块是一根 44dp
  高的白色竖条，与这行细进度条格格不入。
- 改法：ProgressRow 的 Slider 传入自定义 thumb 与 track：
  thumb 为 14dp 白色圆点（带轻微阴影）；track 手绘 4dp
  圆角条——整条为白色 35% 透明，已播部分为纯白，按
  SliderState 的 value/valueRange 计算比例。两侧时间标签、
  seekFraction 的拖动/松手 seek 行为与原配色参数未改。

- 构建修复（首个 v1.17.2 云端构建失败，run 44030832490）：
  本项目的 material3（1.5.0-alpha29）里，thumb/track 插槽
  只存在于 state 型的 Slider 重载上，value 型的重载没有
  这两个参数，编译直接报「No parameter with name 'thumb'」。
  ProgressRow 改用 SliderState：remember 一个 trackRange
  为 0f..1f 的 SliderState，未拖动时用 SideEffect 把它与
  播放进度同步（拖动时 onValueChange 驱动 seekFraction，
  同步目标即 state 自身的值，不打架）；track 因值域是
  0f..1f，st.value 即进度比例。VERSION 不变，仍为 v1.17.2。
  二次构建（run 44035352217）仍失败：该版本 SliderState 的
  区间参数名是 trackRange 而非 valueRange（已查 material3
  1.5.0-alpha29 官方 sources jar 核实），构造调用改用
  trackRange 后重推。

## v1.18.0 — 顶栏多功能菜单（外观/收藏/播放记录）+ 播放历史 + 音源统一导入

### 1. 右上角多功能按钮

- 顶栏右端的调色盘按钮换成多功能按钮（新图标
  `ic_music_more`：2x2 圆角方块网格，与被替换的
  `ic_settings_theme` 同色填充），仍是同一个 TopRoundButton
  槽位（40dp、搜索栏右端）。点开是一个 AVBoxBottomSheet
  菜单，三行图标+文字：
  - 外观设置：打开原有的外观设置 sheet（与调色盘旧行为完全
    一致，页面里只剩菜单这一处打开点）；
  - 歌曲收藏：见第 2 点；
  - 播放记录：见第 3 点。
- 左侧唱片按钮、搜索栏、其余顶栏元素未动。

### 2. 歌曲收藏在 py 模式下也可达

- 原生模式下收藏是模式行里的第三个 chip（MusicMode.FAVORITES），
  py 源激活时模式行隐藏，收藏列表无从进入。本版给
  MusicViewModel 加了 `listOverlay` 状态（null / FAVORITES /
  HISTORY）与 open/close 函数：菜单点「歌曲收藏」时，原生
  模式仍走既有 selectMode(FAVORITES)；py 模式置
  overlay=FAVORITES，MusicPage 在内容区改为渲染 overlay
  列表（标题栏 + 关闭按钮 + 与原生收藏相同的行与点播行为：
  整表为队列点歌），不再渲染 PySpiderContent。系统返回键
  也可关闭 overlay。

### 3. 播放记录（新功能）

- 新增 `music/LxPlayHistory.kt`，与 LxFavorites 同样的存储
  方式：KV 键 `lx_play_history`（读写均带 "" 默认值），
  MusicTrack 的 JSON 数组，最近播放在最前，按与收藏相同
  的键（source|songmid，py 剧集再加 pyEpId）去重，上限
  100 条。API：all / record / clear。
- 记录点在 LxMusicPlayerActivity.playAt()：每次真正开始播
  一首就记一次（JS 与 py 歌曲一视同仁，含切歌与自动连播）。
  attach 路径（tryAttach 成功绑定既有会话）不经过 playAt，
  不会重复记录。
- 菜单「播放记录」在两种模式下都走 overlay=HISTORY（不加
  第四个模式 chip）。列表与收藏 overlay 共用渲染：点行以
  整表为队列播放；标题栏带「清空」（清完列表即空）与关闭
  按钮；空态文案「暂无播放记录」。
- 已知限制：迷你播放条 / 播放服务侧的下一首不经过 playAt
  （页面侧 service next 直接换歌），这类切歌不会被记录；
  播放页内的切歌与连播都经过 playAt，记录完整。

### 4. 音源统一导入

- 音源对话框原来有两个导入入口（「导入音源脚本」给 JS、
  「导入 py 音源」给 py），本版合并为一个「导入音源」：
  一个文件选择器（mime 取两边旧列表的并集，仍以 */* 兜底）。
- 选中文件后由 MusicViewModel.importSource 分发：文件名以
  .py 结尾 → py 源；以 .js 结尾 → JS 源；都没有后缀时读
  文件头 64KB 嗅探内容——含 base.spider / class Spider 等
  爬虫标记 → py；含 LxUserScripts 校验所用的 LX 注册标记
  （globalThis.lx / EVENT_NAMES / lx.on( / lx.send( /
  window.lx）→ JS；两者都不含则只弹「无法识别这个文件」
  并中止，不改任何东西。
- 分发后走的仍是既有的两个导入实现（LxUserScripts /
  PyMusicSources），各自只剩这一条调用路径。v1.17.0 的行为
  保留：导入成功后对话框自动关闭回音乐主页、新源自动尝试
  启用、初始化失败回退到之前的源。

### 5. 新增字符串（×4 语言）

- music_menu_more（更多功能）、music_menu_history（播放
  记录）、music_history_empty（暂无播放记录）、
  music_history_clear（清空）、music_import_entry（导入
  音源）、music_import_unknown（无法识别这个文件）。
  收藏与外观的菜单文案复用既有 music_favorites_title /
  music_appearance，未新增。


## v1.18.1 — py 多集 vod 改全屏选集页

- 背景：py 模式点开多集 vod（歌手 / 专辑 / 歌单，如爱听音乐的
  歌手）时，原来是「选择剧集」弹窗（列表最高 420dp），歌一多
  就得在小窗里翻。Torres 要求点进去就是整页列表。
- 改动：弹窗删除，改为全屏页（与 v1.18.0 的 MusicOverlayList
  同一种内容区分支形态）：顶栏 = 返回按钮（ic_arrow_left）+
  vod 标题 + 歌曲数（新串 music_py_episode_count，×4 语言）；
  下面整页 LazyColumn，行样式与原弹窗一致（集名 bodyLarge、
  上下 10dp、点按播放）。
- 状态仍在 MusicViewModel：PyEpisodePicker 改名 PyEpisodePage
  （_episodePage / episodePage），onPyTrackClick 多集时置位、
  closeEpisodePage 清除；播放路径一字未动——点行仍调
  playEpisode(index)，以整份集列表为队列走 _pendingPyPlay。
  单集 vod 仍直播放。取集期间的加载态不变（分类列表上居中
  转圈 pyDetailLoading），集数据到达才切到全屏页。
- 返回：顶栏返回按钮 + 系统返回键（BackHandler，菜单 overlay
  打开时让位给 overlay 的返回）。底下的分类列表数据全在
  ViewModel 里，返回不重新请求、不重载。
- 滚动位置：分类列表 / 歌手网格的 LazyListState /
  LazyGridState 已上提到 MusicPage 持有（作为参数传入
  PySpiderContent），全屏选集页打开期间 PySpiderContent 离开
  组合也不会丢，从选集页返回时分类列表的滚动位置保持不变。
- 范围：选集流程只存在于 py 模式（episodePage 只在
  onPyTrackClick 里置位），JS 模式未触碰。旧字符串
  music_py_choose_episode 资源保留未删，页面不再引用。


## v1.18.2 — 多功能入口改悬浮展开球

- 背景：v1.18.0 的多功能入口是顶栏右端按钮 + 「更多功能」
  底部弹窗；Torres 对照他发来的参考 app 截图（右下角一颗悬浮
  球，点开向上展开成一列带标签的圆形按钮）问「能不能这种
  方式」，故改成同款悬浮展开球。
- 删除：顶栏右端的多功能按钮撤掉，顶栏恢复为唱片按钮 + 搜索
  框（占满剩余宽度）；「更多功能」底部弹窗与 MusicMenuRow
  一并删除，AVBoxBottomSheet 在本页不再使用（import 同删）。
- 新增 MusicMultiFab（MusicPage.kt 内私有 Composable），挂在
  内容根 Box 最上层（迷你播放条之后），JS / py 两种模式都在：
  收起时是 48dp 深色半透明圆球（沿用 v1.9.1 悬浮按钮的黑
  55% 底 + 6dp 阴影），白色 ic_music_more 图标；收起状态只
  拦截球自身的触摸，不挡页面。
- 展开：点球展开 3 个 44dp 圆形按钮（同款深色底 + 白色图标
  + 深色小标签片）：外观 / 我的收藏 / 播放记录，动作与
  v1.18.0 菜单一字不变（外观 → appearanceOpen = true、收藏
  → vm.openFavorites()、记录 → vm.openHistory()）。球在页面
  下半部向上展开、在上半部向下展开；标签在球靠屏幕边缘的
  反侧，保证不出屏。展开时铺一层透明 scrim（无变暗、无
  涟漪），点 scrim / 点球 / 点任一项都收起，点中某项先收起
  再执行动作。
- 拖动：沿用 v1.9.1 验证过的单循环手势（awaitEachGesture +
  awaitFirstDown + positionChange，累计位移 < 8dp 判点击，
  ≥ 8dp 为拖动且自动收起展开态），范围钳制在内容区实测尺寸
  内（根 Box 新增 onSizeChanged 量尺寸）。位置持久化用新
  KV 键 music_multi_fab_pos（"x,y" px，读带 "" 默认）。
  默认位置：内容区右下角（右边距 16dp），底边距抬高到
  76dp，避开迷你播放条（56dp + 4dp 底距）的条带。
- 字符串未新增：球与三项文案复用 music_menu_more /
  music_appearance / music_favorites_title /
  music_menu_history。

## v1.18.3 — 悬浮球改固定位置

- 背景：v1.18.2 的悬浮球可拖动、位置存 KV；Torres 试后说
  「不要悬浮，固定就行」，故去掉拖动，球固定在原默认位置。
- 删除：MusicMultiFab 的整套拖动逻辑——awaitEachGesture 单
  循环手势、offset 状态、钳制计算、根 Box 的 onSizeChanged
  尺寸测量（pageSize 只服务于球，一并删除）、KV 键
  music_multi_fab_pos 的读写（常量同删，球内不再有任何 KV
  访问）。随之删除的 import：awaitEachGesture /
  awaitFirstDown / pointerInput / positionChange /
  onSizeChanged / IntOffset / IntSize / roundToInt。
- 固定方式：球改为普通 clickable 点击目标（无涟漪，与页内
  其他圆形按钮一致），点按切换展开。位置用根 Box 内的对齐
  实现（全尺寸透明 wrapper + BottomEnd 对齐的 48dp 锚点
  Box），不用绝对偏移：内容区右下角，右边距 16dp，底边距
  76dp 避开迷你播放条条带——即 v1.18.2 的默认位置。
- 展开恒向上（球固定在底部，原上下方向判断删除）：3 项
  （外观 / 我的收藏 / 播放记录，同图标同标签片，标签恒在球
  左侧朝向页内）以 56dp 步进向上排列；透明 scrim、点球再
  点收起、点项先收起再执行原动作，全部不变（三个动作仍各
  只有球这一处调用点）。
- 功能无其他变化：全屏选集页、播放记录、统一导入均未动。

## v1.18.4 — 多功能球回右上角 + 扇形展开 + 展开项渲染修复

- 背景：Torres 实测 v1.18.3 后给两条新指示——「固定在右上角
  原来的完整 然后点一下扇形打开 在点一下关闭」；另外展开态
  三项渲染坏了：图标不可见，只剩灰圆里包着两行标签文字
  （播放/记录、我的/收藏）。
- 渲染 bug 根因：v1.18.3 的展开项是 48dp 锚点 Box 的子节点，
  被父级量测约束压到 48dp 内，标签药丸被挤成近圆形、文字在
  里面折行，图标圆被挤出视野。本版结构性修复：
  - 展开项改为根覆盖层的直接子节点（全尺寸父级，不再受
    小锚点约束）；
  - 每项 = 单个 clickable Row（整行一个点击目标，点标签片
    同样触发动作），内含：标签片（RoundedCornerShape(6dp)
    圆角矩形，Text 设 maxLines = 1 + softWrap = false，
    永不折行）+ 8dp 间隔 + 44dp 固定尺寸 Box（CircleShape
    背景，只装一个居中 Icon）。
- 位置回右上角：顶栏行恢复三段——唱片按钮 + 搜索框
  (weight 1f) + 48dp Spacer 占位（即 v1.18.0 多功能按钮的
  位置，行高随之为 48dp）。球本体仍由页根覆盖层绘制（覆盖
  层与内容 Box 同 padding 坐标系）：TopEnd 对齐 + 顶部
  12dp padding，恰好落在占位上。覆盖层内顺序固定为
  scrim → 扇形项 → 球（最后画、最上层），故展开时 scrim
  不遮球，点球可关闭。
- 扇形展开：以球心为原点，三项圆心偏移为 外观 (-64, 0)
  正左、我的收藏 (-50, +50) 左下斜、播放记录 (0, +66)
  正下（沿弧顺序同 v1.18.0 菜单序）；项用 TopEnd 对齐 +
  offset(dx - 2dp, dy + 14dp) 落位（由球心在覆盖层内
  距右 24dp、距顶 36dp 与圆半径 22dp 推导）。删除
  v1.18.3 的 BottomEnd 底部锚点与向上堆叠逻辑。
- 语义不变：点球开、再点球关、点 scrim 关、点项先收起再
  执行原动作（appearanceOpen = true / vm.openFavorites() /
  vm.openHistory() 仍各只有球这一处调用点）；功能内部
  （外观页、收藏、播放记录、全屏选集、列表滚动状态提升）
  全部未动。

## v1.18.5 — 扇形展开半径放大，展开项不再重叠

- Torres 的 v1.18.4 真机截图：球在右上角、圆圈里图标正常、
  标签片单行显示都对，但扇形半径只有约 64dp，三项挤在一
  起，我的收藏与播放记录的标签片互相压叠。
- 只改扇形几何（MusicMultiFab 的扇形表）：圆心偏移由
  外观 (-64, 0) / 我的收藏 (-50, +50) / 播放记录 (0, +66)
  改为 外观 (-95, 0) / 我的收藏 (-67, +67) /
  播放记录 (0, +105)，弧半径约 95dp；播放记录多放 10dp，
  使其标签片（[−100, −30] × [91, 119]，相对球心）与
  我的收藏圆的底部（最低 y = 89）留 2dp 净距。三组圆心
  距离约 72.6 / 134.4 / 77.0dp，均 > 56dp；三只标签片
  两两不重叠。落位修正量 (dx − 2dp, dy + 14dp) 只由球心
  锚点几何决定，与目标偏移无关，复核后不变。
- 其余全部未动：球的位置与样式、展开/收起语义、三项动作、
  全屏选集、播放记录、统一导入均与 v1.18.4 一致。

## v1.18.6 — 背景图改系统相册选择器（含 v1.18.5 扇形放大）

- Torres 的录屏：外观设置里点「选择背景图片」打开的是系统
  文件选择器（DocumentsUI），落在空文件夹里、要逐层翻存储；
  他要求直接打开相册。
- 改动（ui/music/MusicAppearance.kt 的 MusicAppearanceSheet）：
  启动器由 ActivityResultContracts.OpenDocument 换成
  PickVisualMedia，启动参数为
  PickVisualMediaRequest(ImageOnly)，点「选择背景图片」直接
  打开系统照片选择器（相册网格）。
- 存储方式不变：照片选择器的 Uri 授权是短期的，选中结果仍
  由既有的 copyBackgroundImage 复制进内部存储
  filesDir/music_bg/ 并以绝对路径存 KV，重启后照常显示；
  MusicBackground 加载时对 content:// 开头的旧值兼容
  （直接以 Uri 交给 Coil），其余按本地文件路径加载。
- 恢复默认、模糊/不透明度滑杆、EQ 区均未动；音源导入等其余
  文件选择器（仍需文件选择器）未动。
- 本版同时包含 v1.18.5 的扇形展开半径放大。

## v1.19.0 — 子页面真全屏 + 榜单详情瘦身 + 收藏去掉模式页签

- 背景：Torres 两段录屏——点进榜单歌曲、收藏、播放记录、py
  选集时，页面只替换音乐内容区，底部导航（首页/网页/直播/
  音乐/我的）和主界面痕迹还在，收藏/记录更是像浮层盖在主页
  上。他要的是「真全屏」：只保留页面自己的紧凑页头 + 歌曲
  列表，主界面的东西一概不要；另外去掉主界面的「我的收藏」
  页签（右上角扇形球里已有收藏入口）。
- 机制（壳层标志，与首页 homeWebFullScreen 同一套路，不用
  Dialog 窗口）：MusicViewModel 新增只读 StateFlow
  `subPageOpen`（combine selectedChart / listOverlay /
  episodePage，任一非空即 true）。MainScreen 的 MainContent
  以 activity 级 viewModel() 取得同一个 MusicViewModel 实例
  并 collect 该状态：音乐页签为当前页且 subPageOpen 时，
  `musicFullScreenActive` 成立——Scaffold 底部栏、液态玻璃
  浮动栏（含渐变 scrim）、竖屏 NavigationRail 全部不渲染，
  且 pageContentPadding 归零（与 webFullScreenActive 同一
  分支）。MusicPage 内部则把四个子页面从内容区分枝链移到
  页面根 Box 的最上层渲染：不透明 surfaceContainer 背景 +
  自带 systemBars 窗口 insets + 16dp 横向边距；同时迷你播放
  条与多功能球在子页面打开时不渲染。主内容保持组合在下层，
  返回时滚动位置（含 v1.18.1 提升的 py 列表/网格状态）原样
  保留；既有三个 BackHandler（榜单/浮层/选集）与各页页头
  返回/关闭不变。不用 Dialog 的原因：子页面留在正常组合
  里，壳层藏导航与页内覆盖是同一个状态驱动，不会出现窗口
  层级问题；点歌打开的播放页本来就是独立 Activity
  （LxMusicPlayerActivity.start 走 startActivity），恒在
  一切之上。
- 四个子页面统一待遇：榜单详情（见下）、收藏、播放记录
  （页头保留「清空」）、py 选集页（页头保留歌手名 + 共 N
  首）。收藏/记录从「内容区浮层」（MusicOverlayList 原位置）
  改为根层全屏页，渲染组件本身未动。
- 榜单详情瘦身：详情从 ChartsContent 拆出为独立的
  ChartDetailContent，只保留紧凑页头（返回 + 榜单名）；
  歌曲列表首行新增「播放全部」瘦行（播放图标 + 文案，点按
  等同点第一首歌）。ChartsContent 只剩榜单网格/加载/空态。
  新增字符串 music_play_all（四语言：播放全部 / 播放全部 /
  播放全部 / Play all）。
- 收藏去掉模式页签：模式行只剩 搜索 / 排行榜 两枚 chip；
  MusicMode.FAVORITES 枚举、selectMode 的收藏分支、内容区
  的 FavoritesContent 渲染一并删除。openFavorites() 改为两
  种模式统一走 listOverlay 全屏页（先 loadFavorites 刷新，
  再置 MusicListOverlay.FAVORITES），py 模式原有的浮层收藏
  也并入同一全屏页。

## v1.19.1 — 去掉「搜索 / 排行榜」模式页签行

- 背景：Torres 指着音乐主界面截图说把第三行的「搜索」「排行榜」
  两枚页签去掉，并问会不会影响其他音源。结论先行：不会——这是
  纯 UI 内容切换的改动，音源脚本、桥（LxBridge / PyMusicBridge）
  与各平台接口一概没动；搜索、榜单、解析播放走的还是原来那些
  调用。
- 改动（仅原生 JS 模式；py 模式本来就没有这一行，完全未动）：
  - 删除页签行 composable（两枚 FilterChip）及其专属代码；
    平台 chips 行保留（仍在榜单内容上方作平台切换）。
  - 内容切换改由搜索活动驱动：主界面默认显示排行榜内容
    （MusicMode.CHARTS 本就是 VM 默认值，init 的 loadCharts
    不变）；顶部搜索框提交时仍先 selectMode(SEARCH) 再 search
    ——触发方式与改前一致，结果列表、分页、空态渲染未动。
  - 新增 LaunchedEffect(query, mode, pyActive)：原生模式下
    处于 SEARCH 且 query 被清空（删空输入框）时自动
    selectMode(CHARTS) 回到榜单。
  - 新增一个与既有三个同风格的 BackHandler：SEARCH 模式且
    无任何子页面打开时，系统返回回到榜单；条件与榜单详情/
    浮层/选集的 handler 互斥，子页面优先消费返回。
  - MusicMode 枚举保留为 VM 内部状态（SEARCH / CHARTS），
    selectMode 不再被任何页签 UI 调用，只剩搜索提交、清空
    回榜、返回回榜三处调用点；枚举 KDoc 已注明。
- 酷我榜单行为（v1.17.2）保持原样：判断仍以 mode == CHARTS
  为准——无搜索活动时（默认态）酷我 chip 仍隐藏，且选中平台
  为 kw 时仍自动切换到第一个非 kw 平台并重载榜单，不会停在
  已知为空的酷我榜单上；进入搜索后 kw chip 与酷我搜索照常
  可用。
- 子页面（榜单详情/收藏/记录/py 选集）、右上角扇形球、
  subPageOpen 壳层接线、py 内容分支与提升的滚动状态均未动。
  热门搜索词的空态分支保留在搜索内容里（无页签后不再有独立
  入口，仅作兜底渲染）。

## v1.19.2 — 音源按钮长按刷新 + 歌曲列表「播放全部」胶囊按钮

Torres 两点要求：① 左上角音源（黑胶）按钮加长按刷新站点；
② 歌曲列表加「播放全部」按钮，并按他给的参考图做成列表左上方
的胶囊样式。

- 长按刷新（MusicViewModel.refreshCurrentSource）：py 源先
  PyMusicBridge.clearCacheFor 清掉该源的 home/首页缓存，再
  activatePy 重新激活——homeContent 与首个分类重新向爬虫取数，
  不再回放 v1.17.0 的进程内缓存；爬虫实例本身保留（加载器只有
  全量 clear，会误伤影视侧）。JS 源重新 initBridge 并按当前
  内容重载（有关键词重搜、榜单模式 loadCharts(force = true)
  强制重拉，loadCharts 为此加了 force 参数）。无源时无操作。
  UI 侧 VinylSourceButton/TopRoundButton 加 onLongClick：
  TopRoundButton 在有长按回调时改用 combinedClickable
  （@OptIn ExperimentalFoundationApi，与 SearchScreens 先例
  一致），短按开音源弹窗的行为不变；刷新完成沿用既有的
  「音源已就绪」提示。
- 播放全部：新增共享 PlayAllButton（主色填充胶囊 + 播放图标 +
  music_play_all 文案），三处歌曲列表统一使用：
  - py 分类/搜索歌曲列表（PySpiderContent 列表分支首行）：
    MusicViewModel.pyPlayAll 把当前列表的歌曲行（过滤掉
    artist: 开头的歌手行）整表作为队列从头播，每行在轮到时
    懒解析，与单行点击的队列语义一致；页面在歌手平铺类、
    纯歌手列表时传 null 不显示。
  - py 选集页（歌手/专辑的歌单页）：episodes 超过 2 行时首行
    出胶囊，点即 playEpisode(0)；单曲的 24bit/16bit 两行不显示。
  - 榜单详情：原图标+文字行换成同一胶囊，行为不变
    （onPlaySong(0)）。

# v1.20.0 语音搜歌 / 听歌识曲 / 节奏器 / 封面三样式 / 后台播放开关 / 圆形迷你播放器

## 做了什么

- **语音搜歌**：音乐页搜索栏左侧的搜索图标换成话筒。点话筒走系统
  语音识别（SpeechRecognizer，partial 结果实时填入搜索框），识别
  完成自动按关键词搜索（py 模式走 pySearch，脚本模式走普通搜索）。
  无系统识别服务时回退 ACTION_RECOGNIZE_SPEECH；新增 RECORD_AUDIO
  权限（话筒、识曲、节奏器共用）。
- **听歌识曲**：搜索栏右侧加自绘雷达按钮。点按后录音 10 秒
  （SongRecognizer：AudioRecord 16kHz 单声道 → WAV），POST 到
  ACRCloud `/v1/identify`（HMAC-SHA1 签名，multipart）。密钥
  （Host / Access Key / Access Secret）由用户在首次点按的弹窗里
  填一次，存 KV（lx_acr_*），不经聊天与代码。识别命中后：脚本模式
  自动搜索并播第一首标题命中的歌；py 模式展示该源的搜索结果。
  识别中弹窗带倒计时与雷达脉冲动画，可取消。
- **节奏器**：播放页进度条上方加 FFT 柱状条（28 根，Visualizer
  取播放器 audio session 的 FFT，对数分频 + 快起慢落平滑，镜像
  中线绘制）。无录音权限时自动申请一次；取不到 session 时整条
  不显示，不影响播放。
- **封面更换**：点播放页封面循环三样式——专辑图（原方形封面）→
  黑胶唱片（旋转唱片 + 唱针，播放时转、暂停停针摆出）→ 唱机
  （浅色转盘 + 唱针 + 电源灯）。样式存 KV（music_cover_style）。
  注意：点封面不再进沉浸歌词，沉浸歌词改由点歌词行进入。
- **修复：灵动岛/通知点进播放页不同步**。根因：MediaSession 的
  sessionActivity 是裸 Intent（无 EXTRA_ATTACH、无队列），播放页
  按全新启动走 playAt，其解析任务又被随后的会话接管取消，还把
  CancellationException 的 "Job was cancelled" 当错误 toast 弹出，
  页面停在 00:00 且按钮失效。修法：无队列启动（queue-less）且
  NowPlayingState 有本服务记录时同样走 attach 接管现有播放；
  playAt 的解析对 CancellationException 直接上抛，不再弹 toast、
  不再留下假状态。
- **后台播放开关**（外观面板新增「播放」区，默认开）：开 = 现有
  行为（离开应用继续播；播放器内核的音频焦点本来就处理来电/
  语音消息的暂停与恢复）。关 = 应用整体离开屏幕（AppForeground
  计数归零）即暂停服务与本地两种播放。回前台不自动恢复。
- **圆形迷你播放器**（同区开关，默认关，需悬浮窗权限）：开启后
  离开应用且有歌在播时，屏幕上出现 58dp 悬浮圆盘（封面、旋转、
  角标播放/暂停），盖在其他应用之上；拖动移位（位置记忆），
  点按暂停/播放。由 LxPlaybackService 托管（FloatingMiniPlayer），
  回到应用内自动隐藏（应用内有 mini 条）。首次开启无权限时跳
  系统悬浮窗设置页。

## 新增文件

| 文件 | 作用 |
| --- | --- |
| `music/SongRecognizer.kt` | ACRCloud 识曲：录音 WAV + 签名 + 识别请求 |
| `music/MusicPlayPrefs.kt` | 后台播放 / 圆形迷你播放器两个开关（KV） |
| `music/FloatingMiniPlayer.kt` | 悬浮圆盘（WindowManager overlay，服务托管） |
| `util/AppForeground.kt` | 进程级前后台跟踪（Application 注册计数） |

# v1.20.1 修复：圆形迷你播放器在系统权限列表里找不到

- v1.20.0 漏在 AndroidManifest 声明 SYSTEM_ALERT_WINDOW：未声明的
  应用不会出现在系统「显示在其他应用的上层」列表里，
  Settings.canDrawOverlays 也永远为 false，悬浮圆盘无从开启。
  本版补上该权限声明，其余不变。

# v1.21.0 浮窗单击/双击、去掉听歌识曲、语音搜歌直接播放

## 做了什么

- **圆形迷你播放器手势**（Torres）：单击 = 播放/暂停（经 280ms
  窗口消歧），双击 = 打开播放页（lx_attach 接管当前播放），
  拖动移位不变。
- **去掉听歌识曲**（Torres）：音乐页右上角雷达按钮、ACRCloud
  密钥弹窗、聆听弹窗、ViewModel 识曲状态机全部移除。
  SongRecognizer.kt 留在仓库作死代码（无任何引用），日后要
  恢复只需接回 UI。
- **语音搜歌识别完直接播放**（Torres）：话筒识别出歌名后不再
  只落到结果列表——脚本模式直接播搜索结果第一首；py 模式在
  蜘蛛搜索结果落地后自动点第一首歌曲行（歌手行跳过）。
  手打搜索行为不变，仍是展示列表。

# v1.21.1 唱臂按参考应用重做

## 做了什么

Torres 对照 PeekPro 录屏指出封面样式「还是有点不一样」：差别在
唱臂——旧版唱臂太长（0.72 个封面高），斜劈到唱片中心；参考
应用是短臂，唱头落在唱片右侧。重做 Tonearm：支点移到右上角
（0.86, 0.10），臂长 0.37，白色细臂 + 黑色唱头 + 支点后配重
短杆，播放角 30° / 休息角 10°，唱头落点在唱片半径约 45% 的
右侧。唱机底盘唱片占比 0.88 → 0.90。

# v1.21.2 节奏器修复 + 唱片再对齐参考

## 做了什么

- **节奏器没了**（Torres 录屏）：从列表点歌时，播放交给服务
  播放器，其音频会话在 playAt 调用 ensureVisualizer 时可能还
  没生成（或播放目标事后切换），绑定落空且无人重试，整首歌
  柱子都是平的。修法：播放页 400ms 的 ticker 里持续调用
  ensureVisualizer（幂等、会话一致时直接返回），会话一就绪
  或切换就自动重绑。
- **唱片还是不一样**（对照 PeekPro 再改）：纹路从 4 圈稀疏
  圆环改为细密纹理（0.60–0.975 半径、约 11 圈、低透明度）；
  去掉右侧那道亮色反光弧；中心封面 0.52 → 0.46；唱机底盘
  里唱片占比 0.90 → 0.82，留出底盘边距。

# v1.21.3 节奏器换输出混合流 + 专辑图海报式

## 做了什么

- **节奏器还是没用**（Torres 截图，v1.21.2）：柱子渲染出来了
  但恒平——Visualizer 绑在播放器自己的音频会话上，在这台
  荣耀上该会话的采集始终是静音（从列表点歌走服务播放器）。
  改绑 **session 0（设备输出混合流）**：不管哪个播放器/模式
  在放，听得见的声音就一定在混合流里。创建成败写入
  MusicCrashLog（viz: 前缀），ticker 仍每 400ms 重试。
- **海报还是和 PeekPro 不一样**（Torres 截图）：PeekPro 的
  专辑图是全屏宽海报贴顶 + 同图虚化背景。专辑图样式改成
  同款：背景层换成封面虚化（MusicBackground 现成实现，
  18dp 模糊 + 55% 暗罩，外观里可调），封面以全宽海报画在
  顶部 54%，底部用 alpha 渐变溶进虚化背景；列表区封面槽
  留空（点击切样式不变）。设了自定义背景图时不启用海报
  模式，保持原方图。

# v1.22.0 跨音源自动换源

## 做了什么

Torres 要求：一首歌在某个音源播放失败时自动换别的音源，
不要在一棵树上吊死。播放页新增跨音源救援（startFallback）：

- 触发点：解析失败（爬虫未返回播放地址、脚本解析报错等）
  和播放器中途报错（链接已死）都会触发，不再直接弹错停住。
- 找歌顺序：py 歌曲先试其他 py 音源再试 LX 平台；脚本歌曲
  先试其他平台（kg/kw/wy/tx 原生搜索）再试 py 音源。逐个
  源搜「歌名 歌手」，按歌名（去括号/修饰后比对）+ 歌手
  交集 + 时长接近打分匹配，命中后立即解析，解析成功才算
  数，失败继续下一个源。整体预算 40 秒。
- 换源成功：替补歌曲占入队列表原位置（页面、播放记录、
  上下曲都跟新源走），音源标签更新，弹提示「X 播放失败，
  已自动切换到 Y」。同一首歌试过的源会记下来，反复失败
  时继续往下跳、不回头，全部源都试完才报原来的错。
- 顺带修：歌词加载加了队列槽位身份校验，换源后旧歌的
  歌词不会覆盖新歌。

# v1.23.0 播放设置补齐（对照洛雪设置页）

## 做了什么

Torres 发了洛雪音乐的设置页截图，问能不能加上这些设置。
外观面板「播放」区新增三项（MusicPlayPrefs 持久化）：

- 优先音质：128k / 320k / flac / flac24bit 四档片选，
  解析与下载都按所选音质走（脚本给不了时桥内仍自动降档）；
  py 音源的音质线由爬虫自己决定，不受影响。
- 记住播放进度：开启后播放中每约 5 秒存一次位置，再次播放
  同一首歌（READY 后 seek）从上次位置继续；播完自动清除。
- 其他应用播放声音时自动暂停（音频焦点）：默认开（= 一直
  以来的行为）；关掉后播放器构建时不抢焦点，与其他应用
  同时出声；播放器构建时读取，重启应用后生效。

没加的（洛雪专有或与本应用形态不合）：蓝牙歌词、歌词
翻译/罗马音（歌词管线没有译文数据）、桌面歌词（是整块
新功能，不是设置项）、主题色商店、最大缓存（当前不落盘
缓存）、启动后自动播放等启动行为项。通知栏封面、后台
播放本来就有（v1.16.1 / v1.20.0）。

# v1.24.0 封面高清化

## 做了什么

Torres 反馈封面模糊、要高清。实测各平台封面尺寸（沙盒
直连 CDN 验证）：

- 酷狗：搜索给的是 {size}=240 的 240×240 小图（19KB），
  铺满播放页必然糊；CDN 任意尺寸可取，1080 档是真
  1080×1080（202KB）。parseKgItem 改取 1080。
- QQ：原来拼 R500x500（46KB），R1200x1200 是真
  1200×1200（164KB）。parseTxItem 改 1200。
- 酷我：现 URL 返回的就是 1477×1477 原图（710KB），
  不用改。网易 picUrl 是原图，不动。

另外加了 MusicTrack.hiResCover 显示层改写：播放页的
海报、专辑图、黑胶中心标、虚化背景在加载前把酷狗
stdmusic 尺寸段和 QQ R 尺寸段统一升到高清——已收藏、
历史、队列里存的旧小图 URL 和 py 音源给的小图也能
跟着变清。背景的虚化本身是 PeekPro 样式（故意虚的），
保留；糊的根源是原图太小，已解决。

# v1.25.0 有声书闪退修复 + 切回脚本免重载

## 做了什么

Torres 的两段录屏：① 悦听（有声书 py 源）里点开
《超品相师》——全书 2280 集——点任意一集直接闪退；
② 星海脚本初始化「音源脚本加载中…」转了约 15 秒。

- 闪退根源：打开播放页时整条队列的 JSON 塞在启动
  Intent 里传，2280 集约 800KB，超过 Binder 约 1MB 的
  事务上限，startActivity 抛 TransactionTooLargeException
  直接闪退。修复：队列先记在进程内静态字段（本来就有
  lastQueueJson 恢复链），超过 200K 字符的队列不再进
  Intent，播放页从静态字段恢复；再加一层 try/catch
  兜底重试。普通歌曲队列小，不受影响。
- 加载慢的一半是浪费：从 py 源切回之前用过的 JS 脚本
  时，桥里其实还装着它（py 走独立运行时，从不卸载 JS），
  却每次都全量重新初始化（星海的初始化要查它自己的
  服务器，约 15 秒）。新增 reuseLoadedScript 快路径：
  桥里还是这个脚本就直接复用注册表，秒切。第一次加载
  某个脚本、第一次打开某个分类的耗时是音源/站点本身
  的速度，已有缓存（首页与分类第一页）会让再访秒开。
- 录屏顺带确认 v1.23.0 的点：专辑图海报样式 ✓、节奏器
  在爱听歌曲上真实跳动 ✓（session 0 生效）、黑胶/唱机
  切换 ✓、后台播放与悬浮圆盘 ✓。

# v1.26.0 平铺布局 + 迷你栏上一曲 + 播放页静音

## 做了什么

Torres 录屏对比了影视+ 里同一个喜马拉雅 py 源的封面平铺，
提出三点：

- py 列表可平铺：非歌手分类的列表右上角加布局切换按钮
  （田字格/列表图标），平铺时 3 列 3:4 封面砖 + 标题 +
  作者行，和 TVBox 壳的 vod 海报墙一致；选择记在
  KV lx_py_tile_grid，全局生效、重启保留。歌手分类仍走
  圆形头像平铺（既定规则），不显示切换按钮；播放全部在
  平铺模式下是整行第一项。搜索结果同样可平铺。
- 迷你播放器加上一曲：迷你栏在播放/暂停前加上一曲按钮，
  服务模式走 barPrevIndex（顺序模式到头禁用、循环回绕、
  随机换一首），本地模式走 LocalControls.prev；
  NowPlayingState.Info 新增 canPrev（默认 true），
  各发布点（播放页/服务/迷你栏自切）都按队列位置计算。
- 播放页加静音按钮：收藏/下载一行新增第三个按钮，
  点一下把本地与服务两个播放器的音量置 0，再点恢复；
  图标切成打叉的喇叭（ic_volume_off）。音量是播放器级
  设置，切歌不丢；每次 startPlayback 重新应用，防止
  服务重绑后新建播放器丢状态。

# v1.27.0 平铺去掉播放全部 + 切换按钮悬浮固定

## 做了什么

Torres 的 v1.26.0 实测截图：平铺与迷你栏上一曲都对了，
两点调整：

- 平铺模式去掉播放全部：整行蓝色大条在平铺里取消
  （列表模式保留——他指的是截图里的平铺页）。
- 布局切换按钮改悬浮固定：从内容里的标题行挪出来，
  做成 40dp 圆形悬浮钮（复用页面 TopRoundButton 的
  半透明描边样式），固定在 py 内容区右上角、压在
  列表/平铺之上，滚动不走。歌手分类仍不显示。

# v1.28.0 加载提速：py 磁盘缓存 + 开机预热

## 做了什么

Torres 反馈加载 py 文件和 JS 脚本还是慢。慢的构成：
py 缓存原本只在进程内存里，app 每次重启后第一次进
每个 py 源都要现跑爬虫（还要付 Python 冷启动）；JS
脚本的初始化原本等进音乐页才开始，星海这类要查自己
服务器的脚本必然转十几秒。

- py 磁盘缓存：PyMusicBridge 的首页与分类第一页结果
  落盘到 filesDir/py_music_cache，冷启动直接读盘秒出，
  后台再静默刷新一次（每进程每键最多一次）更新内存与
  磁盘，下次进来就是新数据。长按刷新走 clearCacheFor，
  内存与磁盘一起清，仍是强制现拉。
- 开机预热：新增 MusicWarmStart，App.onCreate 时在
  后台把记住的音源先加载好——JS 脚本提前 init 进桥，
  py 源提前把 homeContent 拉进缓存。进音乐页时往往
  已经就绪。
- LxBridge.loadScript 幂等：桥里已是这个脚本且上下文
  健康时直接跳过重载（预热与页面 init 不再互相重复
  劳动）；手动刷新传 force=true 仍真重载。
- 剩下的硬成本说清楚：某个脚本/源在全新安装后第一次
  加载、某个分类第一次打开，仍是音源与站点本身的速度。

# v1.29.0 播放页按钮下移：定时与外观挪到下面一排

## 做了什么

Torres 看录屏后要求：把播放页右上角的定时（时钟）和
外观（调色盘）按钮也放下面去。

- LxMusicPlayerScreen 顶栏只剩返回箭头。
- 定时与外观按钮移进歌词下方的功能排，与收藏、下载、
  静音并排（一排五个，SpaceEvenly 均分）。
- 定时运行中时，剩余分钟数标签跟在时钟按钮旁一起显示，
  图标仍亮主题色，与原来在顶栏时一致。
- 两个弹窗（定时选择、外观设置）本身不变，只是入口位置
  变了。

# v1.29.1 播放页去掉返回按钮

## 做了什么

紧接 v1.29.0（未单独交付，并入这一版）：Torres 要求把
播放页左上角的返回按钮也去掉。

- LxMusicPlayerScreen 顶栏整行删除，播放页顶部完全留空，
  封面区随之略微上移变大。
- 退出播放页用系统返回键/手势，行为不变（Activity 照常
  finish）；onBack 参数保留未用，仅为不动调用点。

# v1.29.2 py 列表默认平铺，去掉切换按钮

## 做了什么

紧接 v1.29.1（未单独交付，并入这一版）：Torres 要求去掉
py 模式里切换列表/平铺的按钮，默认平铺。

- 删除音乐页右上角的浮动切换按钮（TopRoundButton）。
- PySpiderContent 删掉行列表分支：非歌手分类一律 3 列
  封面平铺；歌手分类仍是圆形头像平铺，不变。
- 连带删掉：VM 的 pyTileGrid 状态与 togglePyTileGrid、
  KV 键 lx_py_tile_grid、pyListState、只为行列表服务的
  pyPlayAll 与列表上的播放全部条（平铺模式自 v1.27.0 起
  本就没有播放全部条；分集页的播放全部不受影响）。

# v1.30.0 播放队列面板照 QQ 音乐的样子补齐

## 做了什么

Torres 发了 QQ 音乐「正在播放」面板的截图，问播放列表
能不能加这种的。原来的队列弹窗只有标题加一列歌名，现
在补上：

- 标题带总数（播放队列 · 36）。
- 工具行：左侧循环模式（图标加文字，点它切换，和底部
  循环按钮同一个动作）；右侧垃圾桶清空队列——保留正在
  播的这一首，其余全清（QQ 是全清；把正在播的歌也清掉
  会让播放器状态悬空，这里取保留）。
- 每行改成 QQ 式单行：歌名加「- 歌手」一行显示，当前
  歌曲主题色加粗，播放时右侧有四根小竖条的律动动画。
- 每行带 ✕ 单首删除：删当前歌曲时自动接播滑进来的下
  一首；删其他歌曲只调整索引。
- 每行右侧拖动手柄：长按拖动，松手落位完成排序（拖动
  过程列表不实时换位，落点按行高换算）。
- 打开弹窗时自动滚到当前歌曲。
- 队列变更由 Activity（队列所有者）统一落账：state、
  lastIndex、lastQueueJson、迷你条的 NowPlayingState 记录
  （queueJson/queueIndex/canNext/canPrev）一起更新。
- 没照搬的：已播歌曲/已播歌单分页（app 里已有播放记录
  入口）、整单下载与加歌单按钮（没有歌单功能）、VIP
  标记（音源数据里没有这个字段）。

# v1.30.1 修 v1.30.0 的编译错误

v1.30.0 构建失败（run #78 日志）：LxMusicPlayerScreen
第 119 行 onMoveQueueItem 的默认值写成 {}，双参数函数
类型 (Int, Int) -> Unit 的空 lambda 必须写明参数，
改成 { _, _ -> }。其余与 v1.30.0 相同。

# v1.31.0 播放队列面板照 QQ 音乐改版

Torres 对照 QQ 音乐「正在播放」面板要求列表显示一致：
1. 顶部改三个分页签（带计数和下划线）：正在播放 /
   已播歌曲 / 歌单。已播歌曲 = 当前队列里排在正在播
   前面的歌（最近播的在最上面），点歌跳播、✕ 删除；
   随机模式下顺序不准，只作参考。
2. 行显示拆「歌名 - 歌手」：源数据里歌名常自带歌手
   （歌手《歌名》、歌手 - 歌名），原样显示再拼歌手会
   重复且把歌名挤成省略号。新 queueDisplayParts 先拆
   出真正的歌名和歌手，歌名 15sp 半粗、歌手 13sp 灰，
   与 QQ 一致的两段式。
3. 歌单（自建）：新 LxPlaylists 存储（KV，存整份
   MusicTrack 列表，模式同 LxPlayHistory）。工具行加
   + 按钮：把当前整个队列存为歌单（同名覆盖）；歌单
   页点任意歌单整表换队列从头播，✕ 删歌单。
   注意这与 QQ 的「已播歌单」（自动记录播过的歌单）
   不同：app 不记录队列来源，做的是可复用的自建歌单。

# v1.32.0 修 v1.31.0 编译错误 + 队列整表下载

v1.31.0 构建失败（run #80 日志，17 个错误同一根源）：
插入 queueDisplayParts 等助手函数时锚在 QueueDialog
签名上，把它原有的 KDoc + @Composable 挤到了助手函数
头上，QueueDialog 本体丢了注解。已把注解移回。

顺带并入 Torres 要的队列下载（QQ 工具行的 ↓）：
工具行加下载按钮，点后确认「下载队列全部 N 首？」，
逐首解析（py 走 PyMusicBridge、LX 走 LxBridge，与单曲
下载同一路径）存到 Music/AVBox；面板内显示进度
done/total，再点一次取消；结束 toast 报成功/失败数，
失败跳过不中断。进度状态在 LxPlayerState.queueDl*。

# v1.32.1 修 v1.32.0 的编译错误

v1.32.0 构建失败（run #81 日志，就一个错误）：
LxMusicPlayerScreen 第 1704 行，(0 until state.index)
.reversed() 返回的是 IntProgression 不是 List，
LazyColumn 的 items() 不接受。改成
(state.index - 1 downTo 0).toList()。其余与
v1.32.0 相同。

# v1.33.0 队列面板继续贴近 QQ + 去掉拖动手柄

Torres 试用 v1.32.1 后对照 QQ 截图反馈：
1. 右侧三道杠（拖动手柄）点着没反应，要求去掉——它
   本来就是长按拖动排序，他不用这个功能。整列删除，
   连带 onMoveQueueItem / moveQueueItem 拖动排序一并
   移除（删除、清空、点播不受影响）。
2. 面板改成 QQ 那种底部抽屉：全宽贴底、顶部圆角，
   点上方灰色区域关闭（原来是居中浮卡）。
3. 正在播的一行改 QQ 绿（#31C27C，含律动竖条），
   页签下划线改黑色；歌名 16sp、行高 60dp，更贴近
   QQ 的行距。

# v1.34.0 队列多选：勾选下载 / 勾选删除

Torres 的录屏显示：队列工具行只能「下载全部 147 首」
或「清空只剩当前一首」，没法挑着来。他要求列表里能
点选下载、点选删除，已播歌曲和歌单也要能点选删除，
并支持多选和全选。

- 工具行右侧加「多选」入口；进入多选模式后工具行变为
  取消 / 已选 N / 全选 / 下载 / 删除。
- 每行左侧出现圆形勾选框（选中为 QQ 绿 + 白勾），
  点整行勾选/取消；多选模式下隐藏每行 ✕ 和点播。
- 正在播放、已播歌曲：可勾选下载（确认后只下所选，
  与整队下载共用 downloadTracks）、勾选删除
  （Activity.removeQueueItems 批量删：删掉当前歌曲
  接播滑入的那首；全选删除时保留当前歌曲，同清空）。
- 歌单页：勾选的是歌单本身，支持全选 + 批量删除
  （无下载）。
- 切换页签自动清空已勾选；下载/删除都有确认弹窗。
