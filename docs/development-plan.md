# Gallery 开发计划

## 0. 现状与约束

- 模块：`main`(壳) / `main-ui` / `core-data` / `common-util`
- AGP 9.2.1（内置 Kotlin 支持 `built_in_kotlinc`，确实无需 `org.jetbrains.kotlin.android` 插件），minSdk 36 / ~~compileSdk 36.1~~ **compileSdk 37.2**（`main/build.gradle`：`compileSdk { version = release(37) { minorApiLevel = 2 } }`），targetSdk 36（2026-09-26 真机复测更正）
- 自绘范围是**宫格 / 大图查看 / 视频渲染**三处（`GLSurfaceView` + shader）；其余界面使用 androidx/material（`MaterialToolbar`、`RecyclerView`、`ViewPager2`、`MaterialCardView` 胶囊导航）与百度地图 `TextureMapView`。~~不引入任何三方 UI 组件~~ 的表述不准确，见 §5 依赖
- ~~现有 bean 为 Java 空类~~ → bean 已补全字段与访问器（`MediaItem` 含 `mediaType / dateTaken / 宽高 / duration / orientation / isFavorite / isTrashed / isPending / isMotionPhoto / isPanorama / bucketId / bucketName / relativePath / ownerPackage / exif`）；继承链仍为 `MediaObject -> MediaSet/MediaItem -> pojo/*`，业务逻辑使用 Kotlin（2026-09-26 更正）
- 依赖统一选用 Gradle 本地缓存中已有版本，保证离线可编译：
  - `androidx.exifinterface 1.3.7`
  - `androidx.recyclerview 1.4.0`
  - `androidx.viewpager2 1.0.0`
  - `androidx.lifecycle:lifecycle-runtime-ktx 2.11.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android 1.10.2`
- Provider 指真正的 Android `ContentProvider`（exported=false，同进程），UI 通过 `ContentResolver` 取数

## 1. core-data（数据核心）

### 1.1 补全 Java bean（保持现有继承关系）

- `entity/MediaObject`：`id / uri / name / mimeType / size / dateModified`
- `entity/MediaItem extends MediaObject`：
  `mediaType(图/视频) / dateTaken / width / height / duration / orientation / isFavorite / isTrashed / isPending / isMotionPhoto / isPanorama / bucketId / bucketName / relativePath / ownerPackage / exif`。**`fromCursor()` 未实现**：cursor → `MediaItem` 有两个入口（2026-09-26 核对）——Provider 侧 `MediaStoreFetcher.readItem(cursor)`（读 MediaStore 游标），UI 侧 `GalleryCursorReader.readMedia(cursor)` / `readMediaList(cursor)`（读 Provider 输出的 22 列 MatrixCursor）
- `entity/MediaSet extends MediaObject`：已有 `List<MediaItem> data`，补 `coverUri / count / albumType`
- `pojo` 补全并新增：
  - 已有：`LocalImage`、`LocalVideo`、`AllItemAlbum`(全部)、`CameraAlbum`、`VideoAlbum`、`LivePhotoAlbum`
  - 新增：`FavoriteAlbum`(收藏)、`PanoramaAlbum`(全景)、`FolderAlbum`(Pictures 子目录)、`ThirdPartyAlbum`(QQ/微信)、`ExifData`

### 1.2 Provider（ContentProvider + 数据抓取）

```
com.senk.gallery.data.provider
├─ GalleryContract.kt      authority/URI/列常量/MIME，供 UI 引用
├─ GalleryProvider.kt      UriMatcher 分发 query/update/getType，MediaStore 变更 -> notifyChange（`insert`/`delete` 抛 UnsupportedOperationException；`call()` **未实现**）
├─ MediaStoreFetcher.kt    查 MediaStore.Files（排除 pending/trashed），分页 limit/offset，`COALESCE(datetaken, date_modified*1000) DESC, _id DESC` 倒序（稳定排序键见 §10）
├─ AlbumResolver.kt        组装“常用/更多”相册、封面、数量、去重、排序
├─ ExifFetcher.kt          ExifInterface 读 URI，供详情页 EXIF 展示（~~补齐 MediaStore 缺失字段~~ **未做回填**，见 §1.3）
└─ XmpParser.kt            解析 MediaStore.XMP 列判定动态照片/全景；~~回退 Exif TAG_XMP~~ **未实现**（`XMP` 列为空即判定为非动态照片/非全景）
```

URI 设计（cursor 列即 UI 数据接口）：

- `content://com.senk.gallery.data.provider/media?limit&offset` — 照片 Tab（图+视频倒序）
- `.../media/{id}`、`.../media/{id}/exif`（详情）、`update .../media/{id}`（收藏）
- `.../albums?category=common|more` — 相册列表
- `.../albums/{type}/items` — 相册内容
  （type：all/camera/video/live/favorite/panorama/folder/{bucketId}/app/{pkg}）

### 1.3 相册归类规则

- 常用（固定项）：全部、相机（DCIM/Camera）、视频、截屏、录屏、动态照片（XMP 含 `MotionPhoto`/`MicroVideo` 等标记）、收藏（`IS_FAVORITE`）、全景（XMP 含 `GPano:*` 任一标记；~~兜底宽高比 >2 且相机来源~~ 实际兜底是 `XmpParser.isPanoramaBySize`：`width >= 6000 && width >= height * 3`，不含相机来源判断）
  - 截屏：图片媒体且 `Pictures/Screenshots`、`DCIM/Screenshots` 等目录，或 `Screenshot_`/`截屏` 等文件名
  - 录屏：视频媒体且 `ScreenRecorder`、`Screen recordings` 等目录，或 `Screenrecorder`/`录屏` 等文件名
  - 空相册自动隐藏
- 更多：
  - 三方应用：`OWNER_PACKAGE_NAME` 命中 `com.tencent.mm/com.tencent.mobileqq` 等 + bucket 名称兜底
  - Pictures 目录：`RELATIVE_PATH LIKE 'Pictures/%'` 按 bucket 分组，与三方相册按 bucket 去重
- EXIF 作为辅助数据源：`ExifFetcher` 仅服务详情页的 `.../media/{id}/exif` 查询（只读展示）；~~dateTaken/尺寸/方向缺失时回填~~ **未实现** —— `MediaItem.exif` 目前无任何写入方（死字段），`readItem()` 中 dateTaken 仅回退 `date_modified`，宽高/方向仍取自 MediaStore（2026-09-26 核对）
- GPS 需 `ACCESS_MEDIA_LOCATION` 权限才不脱敏

## 2. common-util

`BitmapUtils`（采样解码/方向）、`DisplayUtils`（dp/px/屏幕尺寸）、`DateFormats`、`FileSizeUtils`。`DateFormats` 的 `SimpleDateFormat` 已改为 `ThreadLocal`（进程级单例会被主线程与解码线程并发调用，`SimpleDateFormat` 非线程安全）。

## 3. main-ui（宫格 / 查看器 / 视频渲染为 GL 自绘，其余用 androidx/material）

### 3.1 GL 组件（`ui/gl`）

| 组件 | 实现 |
|---|---|
| `GlThumbnailGridView` | GLSurfaceView + `OverScroller` 自绘宫格，RENDERMODE_WHEN_DIRTY；4 列方形单元格居中裁剪；视频时长/动态照片角标用位图纹理绘制；Executor 池 + LRU 纹理缓存；`GlGridRenderer` 只对**可见行（firstRow..lastRow）**发缩略图请求 —— ~~可见优先/离开取消~~ **未实现**（无优先级队列；`ThumbnailLoader` 仅在 shutdown 时批量 cancel，滚出屏幕的请求仍会跑完并进缓存）；点击回调 |
| `GlImageViewer` | GLSurfaceView 自绘左右翻页（拖动跟手 + fling），前后页纹理预加载；`ImageDecoder` 目标尺寸采样（受 GL_MAX_TEXTURE_SIZE 限制）—— ~~EXIF 方向矩阵~~ **无矩阵代码**：`ImageDecoder`（API 28+）解码时已自动套用 EXIF 方向，故不再手写方向变换；双击/双指缩放；方向锁横纵手势（横向翻页/纵向上划 detail 面板）；单击回调切换沉浸；视频页显示封面+播放按钮 |
| `GlVideoView` | GLSurfaceView 渲染（播放逻辑全部委托 `VideoPlayManager`），aspect-fit（尺寸/朝向直接采用 MediaPlayer 旋转后的显示尺寸）；默认 z-order（`setZOrderOnTop(false)`，控制层窗口视图需盖在其上）；生命周期管理 —— ~~音频焦点处理~~ **未实现**（仅 `setAudioAttributes(USAGE_MEDIA/CONTENT_TYPE_MOVIE)`，未 `requestAudioFocus`） |
| `VideoPlayManager` | MediaPlayer + SurfaceTexture(OES) 播放状态机封装：`VideoSource.File`（独立文件）/ `VideoSource.Embedded`（文件尾部内嵌 MP4 + 长度）；`attachTexture` 在 GL 线程绑定 OES 纹理（GL 上下文重建后自动换绑并重建播放器）；start/pause/seekTo/stop/release、prepared/duration/position/尺寸与帧到达回调；播放器页与查看器动态照片共用 |
| `EmbeddedVideoDataSource` | `MediaDataSource` 实现：按 `[fileSize - videoLength, fileSize)` 范围用 `Os.pread` 读取（不拷贝视频数据），供动态照片内嵌视频 `setDataSource` |
| 公共 | `GlUtils`（shader/纹理）、`MediaTextureStore`（LRU 纹理缓存；~~`TexturePool`~~ 不存在此类型）、`ThumbnailLoader` |

### 3.2 主界面与查看器

- `MainActivity`（main 模块）：MaterialToolbar + ViewPager2（照片 / 相册），Tab 切换由**底部悬浮胶囊导航栏**驱动：MaterialCardView 磨砂白底，条目图标在上、文字在下，选中项图标与文字为蓝色并整体带半透明灰色大圆角衬底（衬底包住图标和文字，可透出下层内容，与导航栏背景同一类半透明效果），样式参考高德地图底部栏；宫格区域与胶囊不重叠
- `PhotosFragment`：GL 宫格（不分组），滑到底分页加载
- `AlbumsFragment`：RecyclerView 多类型 — “常用”3 列网格 + “更多”列表（小封面/名称/数量），section 标题
- `AlbumDetailActivity`：相册内容 GL 宫格 + 分页
- `ViewerActivity`：白底 + 标题栏（日期/序号）+ 底部功能菜单（分享/收藏/详情）；单击内容 -> 黑底沉浸（隐藏系统栏+工具栏+底栏），再单击恢复；左右滑切换，滑到视频显示封面 + GL 绘制的播放图标，**单击播放图标**进入播放器（点击其他区域与照片一致进入沉浸模式，命中测试用渲染器记录的图标矩形）
  - GL 视图恒定铺满整个内容区（`match_parent`，整个会话内尺寸不变）：照片按当前页宽高比 fit 绘制在视口中央，留白由 GL 清屏按模式绘制成与窗口背景相同的颜色（正常白 #FFFFFF / 沉浸黑），视觉上与窗口留白无差别
  - 大图纹理：解码最长边 = min(GL_MAX_TEXTURE_SIZE, 视口最长边)（约一屏分辨率），纹理缓存 96MB（可容纳 当前页 + 前后各一页 的工作集）；放大超过约 1.2x 为纹理放大（后续可做按需高分解码）
  - 标题栏/底部菜单为覆盖层（FrameLayout，不占布局空间）：照片按全屏 fit，进出沉浸只隐藏/显示覆盖层与系统栏，内容区尺寸不变（像素级零跳变）；覆盖层通过系统栏 insets 调整 padding
  - **长按动态照片播放**（`item.isMotionPhoto && item.motionVideoLength > 0`）：对文件尾部内嵌 MP4 起播，视频帧 center-fit 叠加绘制在当前页照片上；松手/拖动/翻页/`onPause` 停止并恢复静图；错误时 log 并回退静图（MIUI 长按弹出的系统识别气泡为系统全局行为，无法在 App 内抑制）
  - **上划详情面板**（替代原详情弹窗）：从底部上划跟手拖出半屏（52% 屏高）面板，顶栏/底栏随展开进度淡出；内容自上而下 = 基础信息（名称/类型/拍摄时间/大小/尺寸/路径/来源应用）→ 地图定位卡片（EXIF 含定位时：百度地图瓦片 + 蓝点标记 + 逆地理编码地址，隐藏缩放/比例尺控件，点击整卡进入 `PhotoMapActivity`）→ EXIF；无定位则不显示地图卡片；展开时翻页保持展开并按 item 刷新内容（防串页）；大图单击/返回键收起（返回键优先关面板）—— ~~把手拖动收起~~ **未实现**：`sheet_handle` 只存在于 `activity_viewer.xml`，无任何 Kotlin 绑定
  - **定位与导航**：地址解析经 `LocationAddressResolver` 用百度地图 SDK 逆地理编码（WGS-84→BD09LL，含缓存）；**SDK 唯一初始化点是 `BaiduMapSdk.ensureInitialized(context)`**（进程内单例，负责 `setAgreePrivacy` + `setCoordType(BD09LL)` + `initialize`），由 `ViewerActivity.ensureSheetMap()` / `PhotoMapActivity.onCreate()` / `LocationAddressResolver` 按需调用，并在权限授予后（`MainActivity.updatePermissionState()`）预热鉴权；~~初始化在 `GalleryApp.onCreate`~~ **已移除**（原因见 §10）；AK 经 `main` 的 `AndroidManifest.xml` meta-data `com.baidu.lbsapi.API_KEY`（值为 `manifestPlaceholders.baiduMapApiKey`，来自 `local.properties` 的 `BAIDU_MAP_API_KEY`）注入，SDK AAR 位于 `libs/baidumap`，so 由 `main` 的 `jniLibs.srcDir` 打包；界面不展示经纬度（与照片查看一致，只给地址）
  - **`PhotoMapActivity`**：全屏百度地图（标题 = 照片文件名）+ 底部地址卡 + 「开始导航」（未安装任何导航应用时隐藏）；`NavigationHelper` 检测已安装应用（`main-ui` manifest `<queries>` 声明 4 个包）弹窗选择后 scheme 直达：高德 `androidamap://navi`（GCJ-02）/ 百度 `baidumap://map/navi`（BD09LL）/ 腾讯 `qqmap://map/routeplan` 路线页（GCJ-02）/ Google `google.navigation`（WGS-84）；GCJ-02 由 `common-util` 的 `CoordConverter.wgs84ToGcj02` 转换（含单测）
- `VideoPlayerActivity`：GlVideoView 播放（MediaPlayer + OES，硬解优先）
  - 标题栏/底部控制条为**半透明覆盖层**（`#99000000`，不占布局空间，GL 恒定 `match_parent`）：窗口背景设为透明（`window.setBackgroundDrawable(ColorDrawable(TRANSPARENT))`）才能透过半透明栏看到下层视频；insets 只调整覆盖层 padding
  - **系统栏始终隐藏**：进入即 `hide(systemBars)`（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`），`onResume` 重新隐藏；无「显示系统栏」的切换
  - 标题栏显示**文件名**（`ViewerActivity` 传 `item.name`）
  - 覆盖层自动隐藏：**播放中无操作 5s** 后隐藏标题栏/底栏；点视频区立即切显示/隐藏；点播放按钮、拖动进度条、横屏按钮都会重置计时；**暂停/播放完成时保持显示不自动隐藏**
  - 播放时 `FLAG_KEEP_SCREEN_ON`（不锁屏、不降亮），暂停/播放完成/`onPause` 清除
  - 底部栏：进度条位于栏上缘（通栏），下方一行 左→右 = 播放/暂停、`当前 / 总时长`（`gallery_time_format`）、横屏切换（最右，图标随朝向切 `ic_fullscreen`/`ic_fullscreen_exit`）
  - 横屏切换：`requestedOrientation` 在 `SCREEN_ORIENTATION_LANDSCAPE`/`PORTRAIT` 间切换，`onConfigurationChanged` 更新图标（manifest 已声明 configChanges，不重建 Activity）
- 主题调整：查看器固定浅色白底

## 4. main（壳）

- 重写 `activity_main.xml` / `MainActivity.kt`：edge-to-edge、Tab 导航、空态页
- 权限流程：`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` + `READ_MEDIA_VISUAL_USER_SELECTED` + `ACCESS_MEDIA_LOCATION`，支持 Android 14+ 部分授权与“管理/允许全部”入口
- 依赖：`implementation project(':main-ui')`（api 传递 core-data）

## 5. 构建配置

- `libs.versions.toml` 增加上述依赖
- `core-data` 加 exifinterface
- `main-ui` 加 core-data/common-util/recyclerview/lifecycle/coroutines（viewpager2 已从 `main-ui` 移除——它并不使用；改由 `main` 显式声明 `libs.androidx.viewpager2`，避免依赖 material 的传递依赖）
- `main` 加 main-ui
- `core-data` Manifest 注册 GalleryProvider（exported=false）
- `main` 打包配置（2026-09-26 新增/更正）：
  - `ndk { abiFilters 'arm64-v8a' }`：百度地图 SDK 自带 4 个 ABI 的 so，全打包 APK ≈90MB，只留 arm64-v8a 后 debug ≈38.7MB / release ≈35.9MB（体积数据为真机实测，见 §10）
  - `manifestPlaceholders = [baiduMapApiKey: ...]`：AK 从 `local.properties` 的 `BAIDU_MAP_API_KEY` 读取（`local.properties` 已被 `.gitignore` 忽略；模板见仓库根 `local.properties.example`），manifest 中写 `android:value="${baiduMapApiKey}"`，不再明文硬编码；未配置时注入空串，SDK 初始化失败但应用不崩溃

## 6. 实施顺序

1. 依赖与构建配置
2. core-data：bean 补全 + Contract/Provider/Fetcher/XmpParser（含 XMP 解析单元测试）
3. common-util 工具
4. main-ui：GlThumbnailGridView -> 照片 Tab
5. AlbumsFragment（常用/更多）
6. GlImageViewer + ViewerActivity（沉浸/菜单/EXIF 详情）
7. GlVideoView + VideoPlayerActivity
8. main 权限/导航收尾
9. 验证：`gradlew :main:assembleDebug` + 真机/模拟器手测

## 7. 视频解码策略

- 播放链路：`VideoPlayManager` = `MediaPlayer` + `SurfaceTexture(GL_TEXTURE_EXTERNAL_OES)`（`GlVideoView` 委托之），解码帧经 `Surface` 直接送入 GL 渲染，无拷贝、无三方库；动态照片的内嵌视频通过 `EmbeddedVideoDataSource`（`MediaDataSource`，`Os.pread` 读尾部范围）作为 `setDataSource` 源，同样走 MediaPlayer
- 朝向：**旋转由 MediaPlayer 处理**（渲染到 Surface 的帧已按容器元数据旋转，`videoWidth/Height` 也是旋转后的显示尺寸），渲染端直接用该尺寸做 aspect-fit，不再自行读 rotation、不再旋转 UV；UV 采用 GL 常规约定（v=0 在图像底部，即左上顶点取 (0,1)）
- 解码：`MediaPlayer` 底层走 `MediaCodec`，框架通过 `MediaCodecList.findDecoderForFormat` 按列表顺序选择解码器；厂商硬件解码器（如 `c2.qti.*`/`c2.mtk.*`）优先于软件解码器（`c2.android.*`），即**默认硬解优先**
- 回退：当设备无支持该格式/档位的硬件解码器（如个别 10bit HEVC、特殊 profile）时，框架自动回退软件解码，保证可播放性
- 限制：`MediaPlayer` 没有公开 API 可强制指定或排除某个编解码器；`setSurface` + OES 纹理路径本身不会导致软解
- TODO（按需）：若需“强制硬解/禁用软解”的精确控制，改为自建 `MediaCodec` 管线：`MediaExtractor` 选轨 → `MediaCodecList` 按 `isHardwareAccelerated()` 硬解优先选码 → OES `SurfaceTexture` 渲染；音频用 `MediaCodec` + `AudioTrack` 并自行做音画同步，同时暴露 `allowSoftwareDecoder` 开关

## 8. 暂缓项（TODO）

- **自建相册**：目录固定放 `Pictures/<相册名>`；后续实现“新建/重命名/删除相册、添加/移出照片”，写入用 `MediaStore RELATIVE_PATH` 更新或 `createWriteRequest`；Provider 预留 `folder` 类型即目录相册基础
- **最近删除**：查询 `IS_TRASHED=1`，恢复/彻底删除用 `MediaStore.createTrashRequest/createDeleteRequest`，系统 30 天自动清理；UI 入口与 Provider `recently_deleted` 分类暂时隐藏，代码预留
- ~~**发布前必须接入隐私政策弹窗（合规阻塞项）**~~ **已实现（2026-09-26）**：新增首启隐私政策同意页 `PrivacyActivity`（LAUNCHER，`main` 模块）。**UI 为全屏页而非对话框弹窗**，结构与间距按系统应用（MIUI/HyperOS，实测参考「日历」首启页，屏幕尺寸已脱敏）对齐：白底全屏 + 顶部居中应用标识块（80dp 圆角方） + 应用名 + 欢迎说明 + 逐条列出将申请的权限（44dp 浅灰圆角图标底 + 权限名 + 说明） + 底部「隐私」盾牌标识与分割线 + 同意声明（「用户协议与隐私政策」为可点链接，点击弹出可滚动政策正文 `dialog_privacy_policy.xml`） + 底部「退出 / 同意」两枚 56dp 圆角按钮。实测几何对齐：正文左边距 117px vs 系统 102px、权限名 x306 vs 系统 268、y1291 vs 系统 1072、按钮 y2473 vs 系统 2472、按钮尺寸 502x182 vs 系统 532x159。
  同意状态由 `main-ui` 的 `PrivacyPrefs` 持久化（SharedPreferences `gallery_privacy/privacy_agreed`），`BaiduMapSdk.ensureInitialized()` **先校验该状态，未同意直接返回 false 且不做任何 SDK 初始化**。行为：点「同意」→ 记录并进入 `MainActivity`；点「退出」或返回键 → 退出应用不进入主界面；已同意则启动时静默转发。`MainActivity` 已改为 `exported=false`（不可被外部绕过同意页直接调起）。**遗留**：隐私政策正文目前是应用内固定文案，正式上架前需替换为真实生效的政策文本/链接
- **音频焦点**：`VideoPlayManager` 目前只设 `setAudioAttributes`，未 `requestAudioFocus`（其他应用播放时不会自动压低/暂停）；如需车内/后台共存体验再补
- 其他 backlog：搜索、多选分享、编辑

## 9. 风险点

- GL 宫格内存/性能：纹理 LRU + 异步解码；~~离开取消~~ **未实现**；大图 `ImageDecoder` 采样防 OOM
- 动态照片/全景依赖 MediaProvider 抽取的 XMP 列，~~空时回退读文件 XMP~~ **回退未实现**（exifinterface 1.3.7 无 MotionPhoto API，需自行正则解析；当前 `XMP` 列为空即判定为非动态照片/非全景）
- 视频 GL 播放兼容性（HEVC/HDR）不佳时后续降级 MediaCodec 方案
- minSdk 36 设备/模拟器较少：**真机已覆盖**（见下方 2026-09-26 复测），Android 16 模拟器仍待验证

## 10. 设备适配记录（实测）

- `MediaStore.MediaColumns.XMP` 在 MediaProvider 中以 **BLOB** 存储，`Cursor.getString()` 会抛 `Unable to convert BLOB to string` 导致整个查询失败；已改为按列类型读取（BLOB 按 UTF-8 转字符串）
- ~~MediaProvider 严格排序校验：排序串只允许列名 + ASC/DESC（`datetaken` 为正确列名），不支持表达式~~ —— **此记录有误，2026-09-26 真机复测更正**：校验是**逐 token 白名单**，而非“只允许列名 + ASC/DESC”：
  - 非 system 调用者走严格语法路径（`MediaProvider.java:6053-6059`），排序串最终由 `util/SQLiteQueryBuilder.java:843-877` 的 `enforceStrictToken` 逐 token 校验；
  - token 切分规则见 `util/SQLiteTokenizer.java:263-267` 与 `:202-205`：**数字、括号、`*` 不产生 token**；`COALESCE` 命中允许的 SQLite 函数表即放行，`datetaken`/`date_modified` 属允许列名；
  - 真机实测：`COALESCE(datetaken, date_modified * 1000) DESC` **通过**、顺序正确（`content query` 返回 `files` 表全部 1749 行）；真正报错的是把 Kotlin 常量名当列名——`date_taken`、`no_such_column` 均返回 `Invalid token date_taken` / `Invalid token no_such_column`；
  - 稳定排序键（本轮加固，**非本库可复现缺陷**）：SQLite 对 `ORDER BY` 键完全并列的行不保证返回顺序，配合 `limit/offset` 分页理论上可能重复/漏项（本库 216 张图片中有 2 组 `datetaken` 并列）。`SORT_DATE_DESC` 追加单调递增的 `, _id DESC` 作为稳定次级键（与拍摄时间同向）后，并列组内顺序确定为「新在前」。**实测去掉该键后连续 4 次查询结果也完全一致，即本库未复现不稳定**；保留属防御性加固
  - 口径澄清（原记录有误，已更正）：`1749` 是 `MediaStore.Files`（`content://media/external/file`）的**原始行数，含非图片/视频文件**；其中 1525 条 `datetaken` 为 NULL 的**全部是这些非媒体文件**。应用实际展示的媒体条目为 **223 条 = 216 图片 + 7 视频**，其 `datetaken` **全部非 NULL（0 条）**，与相册「全部 223 项」完全吻合。因此 `COALESCE` 的 NULL 回退在本库不参与排序，单列 `datetaken DESC` 即可
- 早期“默认 z-order 的 `SurfaceView` 被窗口遮挡”的结论有误：真正原因是给 `GLSurfaceView` 本身调用了 `setBackgroundColor`（View 背景绘制在窗口层，会盖住位于窗口后方的 surface）。实测去掉背景色后默认 z-order 完全可用；大图查看器已改为默认 z-order（不在 GLSurfaceView 上设背景色）：标题栏/底栏以 `FrameLayout` 覆盖层形式布局在 GL 之上，可正常显示并接收触摸。宫格起初保留 `setZOrderOnTop(true)`（自绘内容不依赖窗口覆盖）；后因主页底部悬浮胶囊导航需覆盖在宫格之上（缩略图铺满到底、从胶囊下方滚过，滚到底最后一行停在胶囊上方，底部 `bottomInset` 由内容内边距承担）改为默认 z-order + 渲染器 `glClearColor` 白底（不设 View 背景），窗口层胶囊/标题栏均可覆盖；视频播放器亦改为默认 z-order（半透明覆盖层需默认 z-order 才能透出视频，见下条）
- 由于 GL 面在最上层会遮挡窗口绘制的 View，宫格空态文案改为在 GL 内绘制（纹理化文本），不再使用 `TextView`
- 大图翻页“闪烁一下”根因：早期实现让 `GlImageViewer` 尺寸贴合当前照片（`wrap_content` + `onMeasure`），切到不同尺寸照片时 `SurfaceView` 改变大小——系统在新尺寸下会先把旧缓冲拉伸合成 1~2 帧（抓帧实测：settle 后一帧照片被压扁、下一帧才正确），加上拖动/缩放时的临时扩展也会有同样问题。修复：GL 视图改为恒定 `match_parent`，整个会话内不再改变 surface 尺寸（换页/缩放/进出沉浸均无 resize），照片 fit 与留白全部由渲染器绘制，闪烁消失
- 视频播放“画面颠倒”根因（用合成测试视频定位：无旋转/90° 元数据两种）：①`GlVideoView` 的 UV 映射把 v=0 放在了画面顶部（GL 常规相反），所有视频被上下翻转（无旋转元数据的视频表现为“颠倒”）；②MediaPlayer 渲染到 Surface 时**已按容器元数据旋转**，`videoWidth/Height` 返回旋转后的显示尺寸，我们又把 `MediaMetadataRetriever`/`MediaExtractor` 读到的 rotation 手动转一遍 → 二次旋转。修复：去掉手动旋转与尺寸交换，UV 改为常规约定（左上顶点 (0,1)、左下 (0,0)）；查看器动态照片的视频叠加（`GlImagePagerRenderer.drawVideoQuad`）也曾沿用照片纹理约定（v=0 在画面顶部）导致上下颠倒，已同步改为同一约定
- 新拍/动态照片“一直闪烁”根因：查看器解码最长边上限原为 `视口最长边 * 2`（本机 5544 → 受 GL_MAX_TEXTURE_SIZE 限制实际 4096），单张纹理 2304x4096≈36MB；工作集（当前页+前后页）两张就超过 64MB 纹理缓存 → LRU 每帧互相淘汰，`drawPage` 发现缺纹理又触发解码上传，形成“解码→上传→淘汰→重解码”死循环（logcat 实测 100ms/轮，画面在两页纹理间逐帧交替）。修复：解码上限改为 `min(GL_MAX_TEXTURE_SIZE, 视口最长边)`（纹理约 17~23MB），缓存提升到 96MB，工作集可完整驻留，循环消失（稳定性实测 0/3120 帧差异）
- 视频播放器覆盖层要“半透明 + 看得见视频”，窗口（decorView/主题背景）必须设为透明：默认 z-order 下 surface 位于窗口**之后**，窗口不透明背景会把半透明栏合成成不透明黑，视频透不过来；透明窗口 + `#99000000` 栏即可看到下层视频（横屏实测有效）
- 播放器「暂停后栏仍会自动隐藏 / 再点播放无效」根因：`VideoPlayManager.start()/pause()` 均为主线程 post（异步生效），而 `togglePlayback` 同步读 `isPlaying()` 并调用 `setBarsVisible(true)`→`scheduleAutoHide()`——暂停瞬间 `isPlaying()` 仍是 true，会重新排上 5s 自动隐藏（栏在暂停后 5s 消失，之后的“播放”点击落在视频区只切换了栏显隐）；恢复瞬间 `isPlaying()` 仍是 false，自动隐藏计时器又排不上。修复：播放/暂停两个分支都用 `handler.post { scheduleAutoHide() }`，在状态生效后再排计时器
- `GlVideoView` 封装到 `VideoPlayManager` 后播放器黑屏根因：`attachTexture`（GL 表面晚于 `setSource` 到达，播放器页正是此顺序；`setSource` 时无 surface 不建播放器）里原实现仅当 `mediaPlayer != null` 才调用 `createPlayerIfPossible()` → 播放器永不创建。修复：`attachTexture` 中总是调用 `createPlayerIfPossible()`（仅当已有播放器时先 `releasePlayer()`）；查看器动态照片是 surface 先于 source 的相反顺序，不受影响
- `UriMatcher` 的子节点按**注册顺序**匹配：先注册的 `albums/*` 通配节点会遮挡后注册的 `albums/folder/*`、`albums/app/*`，导致多段 URI 返回 `NO_MATCH`；注册顺序必须“具体模式在前、通配在后”。**已验证**（`GalleryProvider.kt` 中 `CODE_FOLDER_ITEMS`/`CODE_APP_ITEMS` 先于 `CODE_ALBUM_ITEMS` 注册，2026-09-26 真机复测：相册详情/目录相册/三方相册均正常取数）
- `owner_package_name` 对非 owner 应用不可见（读取为 null），三方应用相册不能按该列过滤；改为用 bucket 名映射分组后按 `bucket_id IN (...)` 查询（读取到的 owner 为空时才回退 `owner_package_name = ?`）
- 百度地图 SDK 初始化（2026-09-26 真机复现并修复；~~鉴权需在发请求前完成，改为 `GalleryApp.onCreate` 启动即初始化~~ 的旧结论已废弃）：
  - **唯一初始化点**是 `main-ui` 的 `BaiduMapSdk.ensureInitialized(context)`（幂等，内部 `setAgreePrivacy` + `setCoordType(BD09LL)` + `initialize`，失败只记日志不抛异常）；`ViewerActivity.ensureSheetMap()`、`PhotoMapActivity.onCreate()`、`LocationAddressResolver` 全部改为调用它
  - **鉴权是异步的**：`initialize()` 返回时 SDK 往往还没拿到 authtoken，此时立刻逆地理编码会返回 `PERMISSION_UNFINISHED`（旧记录表现为 `get authtoken failed` / `mContext is null`）。因此必须在**用户交互点提前预热**：`MainActivity.updatePermissionState()` 在媒体权限已授予时调用 `BaiduMapSdk.ensureInitialized(this)`，用户真正上划出地图卡片时鉴权通常已就绪；首次失败属可重试状态，不是崩溃
  - 从 `GalleryApp.onCreate` 移走的两个原因：① 在用户同意隐私政策前就 `setAgreePrivacy(true)`，有合规风险（见 §8）；② `initialize` 一旦在 `Application` 完成，`SDKInitializer.isInitialized()` 即为 true，之后 `setCoordType(BD09LL)` 永不生效，SDK 退回 GCJ-02 却接收 BD09LL 坐标 → 地图与逆地理编码整体偏移。初始化点唯一化后两个问题都消失
  - 崩溃记录：`TextureMapView` 在 SDK 未初始化时构造会在 `JNIInitializer$InitOptions` 上抛 `NullPointerException`（详情面板地图卡片默认走到该路径）→ 收敛到 `BaiduMapSdk` 后复测通过
  - 真机逆地理编码结果：`重庆市（地址已脱敏）`（BD09LL 设置生效）
- MIUI 跨应用跳转确认：首次经 scheme 拉起高德等三方应用时，系统弹出「相册 想要打开 高德地图，是否允许？」（`com.miui.securitycenter` 的 ConfirmStartActivity），点「始终允许」后不再出现；属系统安全行为，应用侧不做绕过

### 2026-09-26 真机复测（Android 16 真机 / Android 16）

- 设备：Android 16 真机（代号已脱敏），Android 16 / SDK 36，HyperOS ROM 版本已脱敏，arm64-v8a only，屏幕尺寸已脱敏；媒体条目 **223 条（216 图片 + 7 视频，`datetaken` 全部非 NULL）**，`MediaStore.Files` 原始行数 1749（含 1525 条非媒体文件，其 `datetaken` 为 NULL）
- 冷启动构建：`gradlew clean` + `--no-build-cache` + 杀 daemon 后 `:main:assembleDebug` 91/91 任务真实执行 53s，BUILD SUCCESSFUL；`:main:assembleRelease` BUILD SUCCESSFUL，产出 `main-release-unsigned.apk` 35.89MB；`ndk.abiFilters 'arm64-v8a'` 生效后 debug APK 由 ≈90.5MB 降至 ≈38.7MB
- 静态检查：`:main:lintDebug` 0 error / 6 warning；`lintVitalRelease` = No issues found
- 单元测试 16 个全绿（`XmpParserTest` 8 + `CoordConverterTest` 4 + 4 个模块的模板 `ExampleUnitTest`）
- 手测清单（均正常、无崩溃）：照片 Tab / 相册 Tab / 相册详情 / 查看器 / 沉浸模式 / 上划详情面板 / EXIF / 地图卡片 / 逆地理编码 / 视频播放（含控制栏、横屏）/ 动态照片长按播放 / 收藏写回
- 收藏写回链路真机验证：点击收藏弹出系统授权 `要允许相册修改这张照片吗？`（`RecoverableSecurityException`），点「允许」后 `is_favorite` 由 `0` → `1`；再次点击可复原为 `0`（同一 URI 授权后不再弹窗），全程无崩溃
- 相册计数：动态照片 12 项、全部 223 项、相机 195 项、视频 7 项、截屏 11 项、微信 8 项、QQ 3 项 —— 证明 `XMP LIKE` 过滤 BLOB 列在真机可用
- 排序表达式：`COALESCE(datetaken, date_modified*1000) DESC` 在真机被接受且顺序正确；追加 `, _id DESC` 后连续两次查询结果完全一致（修复同秒并列导致的跨页不稳定）；`date_taken` / `no_such_column` 均报 `Invalid token`（详见上文对应条目）
- 崩溃修复：详情面板地图卡片曾因 `TextureMapView` 在 SDK 未初始化时构造抛 `NullPointerException`（`JNIInitializer$InitOptions`）→ 已修复并复测通过
- 逆地理编码真机结果：`重庆市（地址已脱敏）`
- **隐私政策同意页真机验证**（`pm clear` 模拟首启）：首启显示同意页（`PrivacyActivity` 为焦点，非 `MainActivity`）；点「退出」退回桌面且**不写入**同意状态，再次启动仍显示同意页；按返回键同样退出且不记录同意；点「用户协议与隐私政策」链接弹出可滚动政策正文；点「同意」写入 `gallery_privacy.xml`（`privacy_agreed=true`）→ 进入系统媒体权限弹窗 → 进入 `MainActivity`；**第二次启动不再显示同意页**（经 `am start` 与桌面图标两种方式验证）；未同意时直接 `am start MainActivity` 也不会初始化百度 SDK（logcat 无 `LBSAuthManager`/`authtoken` 活动，且未崩溃）；同意后再进入地图路径 SDK 正常初始化
- 现场截图与完整报告归档：本机 `verification-reports/`（**已在 `.gitignore` 中排除，不进版本库**）——`2026-09-26-device-verification.md` 含缺陷清单、端到端用例、构建/测试/lint 数据与已知限制；`screenshots/` 含 11 张真机截图（隐私同意页 / 政策正文弹窗 / 同意后主界面 / 第二次启动 / 照片宫格 / 相册常用与更多 / 动态照片 12 项 / 查看器 / 详情面板含地图卡片与地址 / 视频封面与播放图标 / 播放器控制栏）
- 本轮其他改动：`DateFormats` 的 `SimpleDateFormat` 改为 `ThreadLocal`（线程安全）；`AlbumResolver.addAlbumRow` 显式 `Array<Any?>` 消除 Kotlin 交叉类型 reified 警告；`main-ui` 移除未使用的 `viewpager2`（改由 `main` 显式声明）
