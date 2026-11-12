# KanjuNative · 看剧（原生播放器）

用 **Android 原生控件** 直连 `kanju.ai` 的公开接口实现的最小可用播放器。
没有 WebView、没有第三方 SDK、没有广告：UI 全部是 `ListView` / `SeekBar` / `MediaPlayer` / `TextureView` 这类系统控件，
图标用 `Canvas` 手绘，所以 release 包只有 **92 KB**（v1.3 加上预载内核后仍在 100 KB 以内）。

交互走 B 站 / 爱奇艺 / 腾讯视频的路子：**搜索结果先进竖屏详情页选集，点哪一集才进横屏播放**。

> 上一个 WebView 壳版本保留在 `../KanjuLite`（tag `v1-webview`）。

---

## 一、功能

**首页（对齐 Chrome 移动端新标签页）**

- 顶部一条胶囊搜索框：深色填充、无描边、左内嵌放大镜，右侧仅在输入时出现「清除 / 前往」
- 只搜片名/演员，回车或点前往即出结果，结果用 `ListView` 渲染（海报 + 年份地区 + 演员 + 备注）
- 换源偏好：自动优选 / 官方线路 / 4K高清 / 海外线路 / 资源站
- 最近搜索：搜过的关键词以胶囊列出，一点即搜（本地只存 8 条）

**详情页（竖屏选集，v1.2 新增）**

- 顶部海报做背景并向下渐隐，压着封面 + 标题 + 更新状态 + 年份/地区/类型 + 主演
- 主按钮：没有记录时是「播放 第1集」，看过就是「继续观看 第N集 · 12:34」，点了从断点续播
- 选集：5 列网格，最近看的那一集用描边高亮；点任意一集进播放页播那一集
- 简介默认 4 行 + 展开/收起
- 右侧「换源」改线路优先级（真正几十条线路在播放页里逐条换）
- 单集电影自动隐藏选集区；没有播放源时按钮变灰显示「暂无播放源」

**播放页（横屏沉浸）**

| 能力 | 说明 |
| --- | --- |
| 播放 / 暂停 | 中央按钮；**双击画面任意处也能暂停 / 继续**，双击时中间闪一下状态图标 |
| 进度调节 | `SeekBar` 拖动，暂停态也能拖；时间精确到秒 |
| 倍速 | 0.5x / 0.75x / 1x / 1.25x / 1.5x / 2x / 3x，选中即生效并记住 |
| 锁屏 | 隐藏所有控制，只留「已锁定 · 点击解锁」，防误触 |
| 选集 | 6 列网格，当前集高亮；支持上一集 / 下一集按钮（详情页也能选） |
| 换源 | 列出已排序线路（mp4 直链优先，其次 HLS），点一条即切 |
| **预载缓冲（v1.3）** | 本地缓存代理：边下边播 + 提前预读，网络抖动不再直接卡画面，见下文 |
| 跳过片头 | 取站点社区统计的中位数（本剧 90s），一键跳到片头结束 |
| 跳过片尾 | 剩余时长进入片尾区间时出现，点一下直接进下一集 |
| 断点续播 | 按集 token 记录播放位置（哈希做 key，避免同剧共用前缀） |
| 自动处理 | 播完自动下一集、自动跳过片头（可关）；线路失败自动换下一条（22s 看门狗） |
| 片头片尾校正 | 「更多」里可把当前进度设为片头结束 / 片尾起点，按剧记忆 |
| 预载设置 | 「更多」里可开「整集预载」、看本集已预载多少、一键清缓存 |

---

## 二、截图

| 首页 | 搜索结果 |
| --- | --- |
| ![](dist/screenshots/01-home.png) | ![](dist/screenshots/02-results.png) |

| 播放控制 | 选集 |
| --- | --- |
| ![](dist/screenshots/03-player.png) | ![](dist/screenshots/04-episodes.png) |

| 倍速 | 锁屏 |
| --- | --- |
| ![](dist/screenshots/05-speed.png) | ![](dist/screenshots/06-lock.png) |

| 竖屏详情页（选集） | 从详情页续播 |
| --- | --- |
| ![](dist/screenshots/07-detail.png) | ![](dist/screenshots/08-play-from-detail.png) |

| 预载缓存（v1.3） |
| --- |
| ![](dist/screenshots/09-preload.png) |

---

## 三、构建

```bash
# JDK 17 + Android SDK 35
./gradlew assembleRelease     # 产出 app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug
```

release 用 debug 签名（`signingConfig signingConfigs.debug`），可直接侧载，但不能上架。

产物已复制到 `dist/`：

- `KanjuNative-1.3.0-release.apk`（92 KB）
- `KanjuNative-1.3.0-debug.apk`（200 KB，含调试信息）

### 版本

| tag | 内容 |
| --- | --- |
| `v1-webview` | 最早的 WebView 壳 |
| `v1.1.0-native` | 全原生控件重写（首页/搜索/播放） |
| `v1.2.0-detail` | 竖屏详情页 + 选集、首页对齐 Chrome |
| `v1.3.0-preload` | 预载缓冲（本地缓存代理）+ 双击暂停 |

---

## 四、实现要点

### 1. 接口签名

站点 `/v1/*` 需要四个头：`x-ai-movie-timestamp`、`x-ai-movie-nonce`、`x-ai-movie-signature`、
`x-ai-movie-client-name`（另外还有 client/build/protocol-version 三个固定值）。

签名算法（在站点打包产物 `movie-card-runtime-*.js` 里找到）：

```
signature = hex( HMAC_SHA256( key, METHOD + "\n" + pathAndQuery + "\n" + timestamp + "\n" + nonce ) )
```

`Api.java` 里用 `Mac.getInstance("HmacSHA256")` 原样复现，纯 Java，无依赖。

### 2. 链路

```
POST /v1/threads                                    → thread_id
GET  /v1/browse/catalog?q=&page=&limit=20           → cards[]（搜索）
GET  /v1/catalog/{variantId}/detail                 → 简介 + playback_skip_settings_preset
GET  /v1/catalog/{variantId}/episodes?limit=48      → episodes[].token
GET  /v1/playback/resolve/{token}?view=compact      → line_options[]（几十条线路）
POST /v1/playback/resolve-line（body={ticket}）      → 真实 mp4 / m3u8 地址
```

- `kanju.ai` 本身可以直接当 API 域名，不必跟着播放域名漂移
- 线路里有伪线路（`.jpg/.png`），`Api.order()` 会剔除，并按 mp4 → m3u8 排序
- 片头片尾来自 detail 的 `playback_skip_settings_preset`（`source=community_median`）

### 3. 为什么是 TextureView

`SurfaceView` 在模拟器上不出画，`adb screencap` 也抓不到画面；换成
`FrameLayout + TextureView + MediaPlayer` 后正常。`PlayerView` 自己按 `videoW/videoH` 做居中 letterbox。

### 4. 底部面板不用 Dialog

横屏下 `Dialog` 的独立窗口首帧会把内容量成 0 宽（表现为空白面板）。`Sheet` 改成直接叠加在
`android.R.id.content` 上的浮层，宽度永远等于真实屏幕宽度。

### 5. 详情页与播放页的分工

`DetailActivity` 只负责「看 / 选集」，不放视频；`PlayerActivity` 只负责播放，通过
`EXTRA_EPISODE` 接收要播第几集（缺省才回退到「上次那一集」）。这样返回键语义很干净：
播放页按返回就退回详情页，转屏交给底栏的全屏按钮，不再出现「按返回先竖过来」的割裂感。

详情页顶部的沉浸式背景有个坑：API 30+ 调用 `setDecorFitsSystemWindows(false)` 之后，
状态栏图标会被系统改回深色（深色海报上直接看不见）。这里只用老的
`SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 且**不清除** `LIGHT_STATUS_BAR`，图标才能保持白色。

### 6. 图标

`Ui.Icon` 是一个 24x24 网格的 `Canvas` 画笔，`onDraw` 里先扣掉 padding 再按 `min(w,h)/24` 缩放——
不扣 padding 的话 `iconButton(icon=34, pad=20)` 的播放箭头会被放大到 74dp（之前「图标过大」就是这个原因）。

### 7. 预载：为什么得自己写一个本地缓存代理

`MediaPlayer` 的缓冲量不可调，HLS 里那句 `buffer low, pausing...` 就是网速短暂掉到码率以下被卡住。
一集 45 分钟、几百片 m3u8 的片子，只要有一片晚到 3 秒，画面就停 3 秒——这正是「偶尔卡顿」的来源。

`CacheProxy` 在 `127.0.0.1` 上起了一个只服务本机的极简 HTTP 服务，MediaPlayer 播的是本地地址：

```
GET /hls/<key>.m3u8?u=<源清单>   → 抓清单，把分片地址改写成 /seg/<key>/<n>
GET /seg/<key>/<n>               → 已落盘直接读文件，没落盘等预载
GET /v/<key>                     → mp4 单文件：区间预读 + 按 Range 供给
```

几个踩过的坑都固化在实现里：

- **分片序号按地址固定分配**（只增不减）：清单被重复拉取时序号不能漂移，否则播放器手里的旧序号
  会指到别的分片，直接解码报错（`err=-1010`）
- **顺序预取管道**：当前片之后 8 片由一条串行线程按顺序下，慢网下不会自己挤兑自己
- **起播缓冲**：清单返回前先把前 4 片下完（最多等 9 秒），起播那一下也不会卡
- **窗口不推翻**：请求落在缓存窗口外（例如播放器探到还没下载的位置）一律直连透传，
  绝不 `setLength(0)` 重置——否则正在发送的那条连接会被截断成 `unexpected end of stream`
- **兜底**：预载没起来 / 源站拒绝 → 这一片直连；整条预载通道出问题 → `onError` 里同线路改直连重来一次
- 退到后台 `setActive(false)` 立刻停预载；缓存目录 1.2GB 上限，按最久没用的删

同一台模拟器、同一集 45 分钟视频的对照（`adb logcat` 里数 `buffer low`）：

| | 直连（v1.2） | 预载（v1.3） |
| --- | --- | --- |
| 80 秒内卡顿次数 | 4 次，每次停 5–11 秒 | 0 次（仅起播那次） |

### 8. 双击暂停

手势用 `GestureDetector` 挂在 `root` 的 `OnTouchListener` 上（子控件按钮照常优先响应）。
单击与双击互斥：`onSingleTapUp` 里不立刻处理，而是 `postDelayed(..., getDoubleTapTimeout())`，
双击时把这个任务撤掉，所以双击不会先闪一下控制栏。反馈图标只在控制栏隐藏时出现，
否则会和中间那个播放按钮叠在一起。

---

## 五、已知限制

- 只做了手机竖屏（首页 / 详情）+ 横屏播放，没有平板布局
- 线路可播性依赖站点，资源站那几十条经常返回 `invalid_public_playback_id`，靠自动换源兜底
- 字幕/音轨切换、投屏、下载未实现
- release 未混淆（`minifyEnabled false`），方便对照日志

---

## 六、目录

```
app/src/main/java/com/kanju/player/
  Api.java          签名 + 全部 HTTP 接口 + 线路排序
  Models.java       Movie / Detail / Episode / Line / Playback
  HomeActivity.java 首页：Chrome 式搜索框 + ListView + 最近搜索 + 换源
  DetailActivity.java 竖屏详情：海报头图 + 继续观看 + 选集网格 + 简介 + 换源
  PlayerActivity.java 播放页：控制栏 / 选集 / 换源 / 倍速 / 锁屏 / 跳片头片尾 / 双击暂停
  CacheProxy.java   预载内核：本机 HTTP 缓存服务（mp4 区间预读 + HLS 清单改写与分片预取）
  PlayerView.java   TextureView + MediaPlayer 封装
  Sheet.java        底部面板（叠在内容层上的浮层）
  Ui.java           色板、圆角、20 个手绘图标、开关
  Store.java        SharedPreferences：偏好 / 倍速 / 断点 / 最近一集 / 片头片尾
  ImgLoader.java    海报：内存 LruCache + 磁盘缓存 + 采样
  PosterView.java   圆角海报
```
