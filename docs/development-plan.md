# Gallery 开发计划

## 0. 现状与约束

- 模块：`main`(壳) / `main-ui` / `core-data` / `common-util`
- AGP 9.2.1（自带 Kotlin 支持，无需额外插件），minSdk 36 / compileSdk 36.1，targetSdk 36
- 不引入任何三方 UI 组件；缩略图宫格、大图查看、视频播放全部基于 `GLSurfaceView` 自绘
- 现有 bean 为 Java 空类（继承链 `MediaObject -> MediaSet/MediaItem -> pojo/*`），新增逻辑使用 Kotlin
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
  `mediaType(图/视频) / dateTaken / width / height / duration / orientation / isFavorite / isTrashed / isPending / isMotionPhoto / isPanorama / bucketId / bucketName / relativePath / ownerPackage / exif(懒加载)`，提供 `fromCursor()`
- `entity/MediaSet extends MediaObject`：已有 `List<MediaItem> data`，补 `coverUri / count / albumType`
- `pojo` 补全并新增：
  - 已有：`LocalImage`、`LocalVideo`、`AllItemAlbum`(全部)、`CameraAlbum`、`VideoAlbum`、`LivePhotoAlbum`
  - 新增：`FavoriteAlbum`(收藏)、`PanoramaAlbum`(全景)、`FolderAlbum`(Pictures 子目录)、`ThirdPartyAlbum`(QQ/微信)、`ExifData`

### 1.2 Provider（ContentProvider + 数据抓取）

```
com.senk.gallery.data.provider
├─ GalleryContract.kt      authority/URI/列常量/MIME，供 UI 引用
├─ GalleryProvider.kt      UriMatcher 分发 query/update/call，MediaStore 变更 -> notifyChange
├─ MediaStoreFetcher.kt    查 MediaStore.Files（排除 pending/trashed），分页 limit/offset，date_taken 倒序
├─ AlbumResolver.kt        组装“常用/更多”相册、封面、数量、去重、排序
├─ ExifFetcher.kt          ExifInterface 读 URI，补齐 MediaStore 缺失字段
└─ XmpParser.kt            解析 MediaStore.XMP 列（回退 Exif TAG_XMP）判定动态照片/全景
```

URI 设计（cursor 列即 UI 数据接口）：

- `content://com.senk.gallery.data.provider/media?limit&offset` — 照片 Tab（图+视频倒序）
- `.../media/{id}`、`.../media/{id}/exif`（详情）、`update .../media/{id}`（收藏）
- `.../albums?category=common|more` — 相册列表
- `.../albums/{type}/items` — 相册内容
  （type：all/camera/video/live/favorite/panorama/folder/{bucketId}/app/{pkg}）

### 1.3 相册归类规则

- 常用（固定项）：全部、相机（DCIM/Camera）、视频、截屏、录屏、动态照片（XMP `MotionPhoto/MicroVideo`）、收藏（`IS_FAVORITE`）、全景（XMP `GPano:ProjectionType`，兜底宽高比 >2 且相机来源）
  - 截屏：图片媒体且 `Pictures/Screenshots`、`DCIM/Screenshots` 等目录，或 `Screenshot_`/`截屏` 等文件名
  - 录屏：视频媒体且 `ScreenRecorder`、`Screen recordings` 等目录，或 `Screenrecorder`/`录屏` 等文件名
  - 空相册自动隐藏
- 更多：
  - 三方应用：`OWNER_PACKAGE_NAME` 命中 `com.tencent.mm/com.tencent.mobileqq` 等 + bucket 名称兜底
  - Pictures 目录：`RELATIVE_PATH LIKE 'Pictures/%'` 按 bucket 分组，与三方相册按 bucket 去重
- EXIF 作为补充数据源：dateTaken/尺寸/方向缺失时回填，并供详情页展示
- GPS 需 `ACCESS_MEDIA_LOCATION` 权限才不脱敏

## 2. common-util

`BitmapUtils`（采样解码/方向）、`DisplayUtils`（dp/px/屏幕尺寸）、`DateFormats`、`FileSizeUtils`。

## 3. main-ui（全部自绘 GL）

### 3.1 GL 组件（`ui/gl`）

| 组件 | 实现 |
|---|---|
| `GlThumbnailGridView` | GLSurfaceView + `OverScroller` 自绘宫格，RENDERMODE_WHEN_DIRTY；4 列方形单元格居中裁剪；视频时长/动态照片角标用位图纹理绘制；Executor 池 + LRU 纹理缓存，`loadThumbnail(uri,Size,Signal)` 可见优先/离开取消；点击回调 |
| `GlImageViewer` | GLSurfaceView 自绘左右翻页（拖动跟手 + fling），前后页纹理预加载；`ImageDecoder` 目标尺寸采样（受 GL_MAX_TEXTURE_SIZE 限制）、EXIF 方向矩阵；双击/双指缩放；单击回调切换沉浸；视频页显示封面+播放按钮 |
| `GlVideoView` | GLSurfaceView 渲染（播放逻辑全部委托 `VideoPlayManager`），aspect-fit（尺寸/朝向直接采用 MediaPlayer 旋转后的显示尺寸）；默认 z-order（`setZOrderOnTop(false)`，控制层窗口视图需盖在其上）；生命周期/音频焦点处理 |
| `VideoPlayManager` | MediaPlayer + SurfaceTexture(OES) 播放状态机封装：`VideoSource.File`（独立文件）/ `VideoSource.Embedded`（文件尾部内嵌 MP4 + 长度）；`attachTexture` 在 GL 线程绑定 OES 纹理（GL 上下文重建后自动换绑并重建播放器）；start/pause/seekTo/stop/release、prepared/duration/position/尺寸与帧到达回调；播放器页与查看器动态照片共用 |
| `EmbeddedVideoDataSource` | `MediaDataSource` 实现：按 `[fileSize - videoLength, fileSize)` 范围用 `Os.pread` 读取（不拷贝视频数据），供动态照片内嵌视频 `setDataSource` |
| 公共 | `GlUtils`（shader/纹理）、`TexturePool`、`ThumbnailLoader` |

### 3.2 主界面与查看器

- `MainActivity`（main 模块）：MaterialToolbar + ViewPager2（照片 / 相册），Tab 切换由**底部悬浮胶囊导航栏**（MaterialCardView + 胶囊选中态）驱动，宫格区域与胶囊不重叠
- `PhotosFragment`：GL 宫格（不分组），滑到底分页加载
- `AlbumsFragment`：RecyclerView 多类型 — “常用”3 列网格 + “更多”列表（小封面/名称/数量），section 标题
- `AlbumDetailActivity`：相册内容 GL 宫格 + 分页
- `ViewerActivity`：白底 + 标题栏（日期/序号）+ 底部功能菜单（分享/收藏/详情）；单击内容 -> 黑底沉浸（隐藏系统栏+工具栏+底栏），再单击恢复；左右滑切换，滑到视频显示封面 + GL 绘制的播放图标，**单击播放图标**进入播放器（点击其他区域与照片一致进入沉浸模式，命中测试用渲染器记录的图标矩形）
  - GL 视图恒定铺满整个内容区（`match_parent`，整个会话内尺寸不变）：照片按当前页宽高比 fit 绘制在视口中央，留白由 GL 清屏按模式绘制成与窗口背景相同的颜色（正常白 #FFFFFF / 沉浸黑），视觉上与窗口留白无差别
  - 大图纹理：解码最长边 = min(GL_MAX_TEXTURE_SIZE, 视口最长边)（约一屏分辨率），纹理缓存 96MB（可容纳 当前页 + 前后各一页 的工作集）；放大超过约 1.2x 为纹理放大（后续可做按需高分解码）
  - 标题栏/底部菜单为覆盖层（FrameLayout，不占布局空间）：照片按全屏 fit，进出沉浸只隐藏/显示覆盖层与系统栏，内容区尺寸不变（像素级零跳变）；覆盖层通过系统栏 insets 调整 padding
  - **长按动态照片播放**（`item.isMotionPhoto && item.motionVideoLength > 0`）：对文件尾部内嵌 MP4 起播，视频帧 center-fit 叠加绘制在当前页照片上；松手/拖动/翻页/`onPause` 停止并恢复静图；错误时 log 并回退静图（MIUI 长按弹出的系统识别气泡为系统全局行为，无法在 App 内抑制）
  - **详情弹窗**：EXIF 含定位时先显示「位置: 解析中…」与坐标，随后经 `LocationAddressResolver` 用百度地图 SDK 逆地理编码（WGS-84→BD09LL）替换为文字地址；初始化在 `GalleryApp.onCreate`（AK 在 `main` 的 `AndroidManifest.xml` meta-data `com.baidu.lbsapi.API_KEY`，SDK AAR 位于 `libs/baidumap`，so 由 `main` 的 `jniLibs.srcDir` 打包）
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
- `main-ui` 加 core-data/common-util/recyclerview/viewpager2/lifecycle/coroutines
- `main` 加 main-ui
- `core-data` Manifest 注册 GalleryProvider（exported=false）

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
- 其他 backlog：搜索、多选分享、编辑

## 9. 风险点

- GL 宫格内存/性能：纹理 LRU + 异步解码 + 离开取消；大图 `ImageDecoder` 采样防 OOM
- 动态照片/全景依赖 MediaProvider 抽取的 XMP 列，空时回退读文件 XMP（exifinterface 1.3.7 无 MotionPhoto API，需自行正则解析）
- 视频 GL 播放兼容性（HEVC/HDR）不佳时后续降级 MediaCodec 方案
- minSdk 36 设备/模拟器较少，需准备 Android 16 模拟器验证

## 10. 设备适配记录（实测）

- `MediaStore.MediaColumns.XMP` 在 MediaProvider 中以 **BLOB** 存储，`Cursor.getString()` 会抛 `Unable to convert BLOB to string` 导致整个查询失败；已改为按列类型读取（BLOB 按 UTF-8 转字符串）
- MediaProvider 严格排序校验：排序串只允许列名 + ASC/DESC（`datetaken` 为正确列名），不支持表达式
- 早期“默认 z-order 的 `SurfaceView` 被窗口遮挡”的结论有误：真正原因是给 `GLSurfaceView` 本身调用了 `setBackgroundColor`（View 背景绘制在窗口层，会盖住位于窗口后方的 surface）。实测去掉背景色后默认 z-order 完全可用；大图查看器已改为默认 z-order（不在 GLSurfaceView 上设背景色）：标题栏/底栏以 `FrameLayout` 覆盖层形式布局在 GL 之上，可正常显示并接收触摸。宫格保留 `setZOrderOnTop(true)`（自绘内容不依赖窗口覆盖）；视频播放器亦改为默认 z-order（半透明覆盖层需默认 z-order 才能透出视频，见下条）
- 由于 GL 面在最上层会遮挡窗口绘制的 View，宫格空态文案改为在 GL 内绘制（纹理化文本），不再使用 `TextView`
- 大图翻页“闪烁一下”根因：早期实现让 `GlImageViewer` 尺寸贴合当前照片（`wrap_content` + `onMeasure`），切到不同尺寸照片时 `SurfaceView` 改变大小——系统在新尺寸下会先把旧缓冲拉伸合成 1~2 帧（抓帧实测：settle 后一帧照片被压扁、下一帧才正确），加上拖动/缩放时的临时扩展也会有同样问题。修复：GL 视图改为恒定 `match_parent`，整个会话内不再改变 surface 尺寸（换页/缩放/进出沉浸均无 resize），照片 fit 与留白全部由渲染器绘制，闪烁消失
- 视频播放“画面颠倒”根因（用合成测试视频定位：无旋转/90° 元数据两种）：①`GlVideoView` 的 UV 映射把 v=0 放在了画面顶部（GL 常规相反），所有视频被上下翻转（无旋转元数据的视频表现为“颠倒”）；②MediaPlayer 渲染到 Surface 时**已按容器元数据旋转**，`videoWidth/Height` 返回旋转后的显示尺寸，我们又把 `MediaMetadataRetriever`/`MediaExtractor` 读到的 rotation 手动转一遍 → 二次旋转。修复：去掉手动旋转与尺寸交换，UV 改为常规约定（左上顶点 (0,1)、左下 (0,0)）；查看器动态照片的视频叠加（`GlImagePagerRenderer.drawVideoQuad`）也曾沿用照片纹理约定（v=0 在画面顶部）导致上下颠倒，已同步改为同一约定
- 新拍/动态照片“一直闪烁”根因：查看器解码最长边上限原为 `视口最长边 * 2`（本机 5544 → 受 GL_MAX_TEXTURE_SIZE 限制实际 4096），单张纹理 2304x4096≈36MB；工作集（当前页+前后页）两张就超过 64MB 纹理缓存 → LRU 每帧互相淘汰，`drawPage` 发现缺纹理又触发解码上传，形成“解码→上传→淘汰→重解码”死循环（logcat 实测 100ms/轮，画面在两页纹理间逐帧交替）。修复：解码上限改为 `min(GL_MAX_TEXTURE_SIZE, 视口最长边)`（纹理约 17~23MB），缓存提升到 96MB，工作集可完整驻留，循环消失（稳定性实测 0/3120 帧差异）
- 视频播放器覆盖层要“半透明 + 看得见视频”，窗口（decorView/主题背景）必须设为透明：默认 z-order 下 surface 位于窗口**之后**，窗口不透明背景会把半透明栏合成成不透明黑，视频透不过来；透明窗口 + `#99000000` 栏即可看到下层视频（横屏实测有效）
- 播放器「暂停后栏仍会自动隐藏 / 再点播放无效」根因：`VideoPlayManager.start()/pause()` 均为主线程 post（异步生效），而 `togglePlayback` 同步读 `isPlaying()` 并调用 `setBarsVisible(true)`→`scheduleAutoHide()`——暂停瞬间 `isPlaying()` 仍是 true，会重新排上 5s 自动隐藏（栏在暂停后 5s 消失，之后的“播放”点击落在视频区只切换了栏显隐）；恢复瞬间 `isPlaying()` 仍是 false，自动隐藏计时器又排不上。修复：播放/暂停两个分支都用 `handler.post { scheduleAutoHide() }`，在状态生效后再排计时器
- `GlVideoView` 封装到 `VideoPlayManager` 后播放器黑屏根因：`attachTexture`（GL 表面晚于 `setSource` 到达，播放器页正是此顺序；`setSource` 时无 surface 不建播放器）里原实现仅当 `mediaPlayer != null` 才调用 `createPlayerIfPossible()` → 播放器永不创建。修复：`attachTexture` 中总是调用 `createPlayerIfPossible()`（仅当已有播放器时先 `releasePlayer()`）；查看器动态照片是 surface 先于 source 的相反顺序，不受影响
- `UriMatcher` 的子节点按**注册顺序**匹配：先注册的 `albums/*` 通配节点会遮挡后注册的 `albums/folder/*`、`albums/app/*`，导致多段 URI 返回 `NO_MATCH`；注册顺序必须“具体模式在前、通配在后”
- `owner_package_name` 对非 owner 应用不可见（读取为 null），三方应用相册不能按该列过滤；改为用 bucket 名映射分组后按 `bucket_id IN (...)` 查询（读取到的 owner 为空时才回退 `owner_package_name = ?`）
- 百度地图 SDK 鉴权需在发请求前完成（官方建议在 Application 子类初始化）：惰性初始化（打开详情时才 `SDKInitializer.initialize`）会因鉴权未完成报 `get authtoken failed`、`mContext is null` 且逆地理编码直接失败；改为 `GalleryApp.onCreate` 启动即初始化后正常返回地址
