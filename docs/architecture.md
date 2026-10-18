# 架构总览

Android 相册应用（`com.senk.gallery`，应用名「相册」）。本文件给出模块划分、数据流、界面地图与跨模块的关键约束；各主题的展开见对应文档。

## 1. 技术栈与版本

| 项 | 值 |
|---|---|
| 包名 / 版本 | `com.senk.gallery` / `versionName 1.0.0.0`（`versionCode 10000000`） |
| SDK | `minSdk 36` / `targetSdk 36` / `compileSdk 37.2`（Android 16+） |
| 构建 | AGP **9.2.1**（内置 Kotlin 支持，无 `kotlin.android` 插件）、Gradle wrapper **9.4.1**、daemon JDK **21**（toolchain 自动下载）、configuration cache 开启、Java 11 兼容级别 |
| UI 技术 | androidx/material（RecyclerView 宫格、ViewPager2、MaterialToolbar、MaterialCardView 胶囊导航、TabLayout）+ **两处 GL 自绘**（查看器/视频）+ 百度地图 `TextureMapView` |
| 依赖版本 | 见 `gradle/libs.versions.toml`；关键项：material 1.14.0、appcompat 1.8.0、core-ktx 1.18.0、recyclerview 1.4.0、viewpager2 1.0.0、lifecycle 2.11.0、coroutines 1.10.2、exifinterface 1.3.7、百度 `com.baidu.lbsyun` 8.2.0（三件套） |

构建、签名、ABI 与地图 AK 的完整说明见 [构建与发布](build-and-release.md)。

## 2. 模块与依赖

```
main (:main, 应用壳)                        namespace com.senk.gallery
 ├─ api── main-ui                            namespace com.senk.gallery.ui
 │         ├─ api── core-data               namespace com.senk.gallery.data
 │         ├─ api── common-util             namespace com.senk.gallery.util
 │         ├─ api── external-api             namespace com.senk.gallery.external
 │         └─ api── baidu map/search/util（Maven）
 └─ implementation── external-api
```

| 模块 | 职责 | 关键内容 |
|---|---|---|
| `main` | 应用壳、打包入口、**唯一外部入口** | `PrivacyActivity`（LAUNCHER）、`MainActivity`（exported=false）、`ExternalEntryActivity`（exported=true，外部选图/大图路由）、`MainPagerAdapter`、`GalleryApp`、权限流程、底部胶囊导航、AK/签名的 `build.gradle` 注入；`src/debug` 有外部调用测试壳 `ExternalClientDebugActivity`（release 不含） |
| `main-ui` | 全部业务界面与 GL 组件 | `PhotosFragment` / `AlbumsFragment` / `AlbumDetailActivity` / `ViewerActivity` / `VideoPlayerActivity` / `PhotoMapActivity`；`ui/grid/*`（View 宫格、分页、缩略图缓存）、`ui/selection/*`（多选状态与动作）、`ui/picker/ImagePickerActivity`（选图页）、`ui/addto/AddToAlbumActivity`（添加到目标选择）；`ui/gl/*`（查看器/视频自绘）；主题（`ThemeUtils`、`values*/styles.xml`）、`BaiduMapSdk` 等地图支撑 |
| `core-data` | 数据层（见 [数据层](data-layer.md)） | `GalleryProvider`（Manifest 注册，exported=false）、`GalleryContract`、`MediaStoreFetcher`、`AlbumResolver`、`ExifFetcher`、`XmpParser`、`GalleryCursorReader`、`CustomAlbumStore`（自建相册持久化）、`FavoriteStore` + `FavoriteDb`（自建收藏库，**SQLite**）、`MediaWriter`（复制写入）、`ExternalMediaProvider`（选图结果代理）；entity/pojo |
| `common-util` | 无业务依赖的小工具 | `BitmapUtils`、`DisplayUtils`、`DateFormats`、`FileSizeUtils`、`CoordConverter`、`SystemBarUtils` |
| `external-api` | 外部调用**契约**（无实现、无依赖） | `GalleryExternal`：`PICK_IMAGE` / `VIEW_IMAGE` 两个 action、入参/结果 extra、`pickImageIntent()` / `viewImageIntent()` 构造；供 `main`、`main-ui` 与第三方集成方共同引用 |

依赖方向是**单向**的：`main → main-ui → {core-data, common-util, external-api}`，`main → external-api`。`core-data` 与 `common-util` 互不依赖、也不引用界面代码；`external-api` 是唯一的"契约叶子模块"（只依赖 Android SDK）。`main-ui` 通过 `api` 对外暴露后三者（`main` 直接使用 `MediaItem`、`GalleryContract`、`DisplayUtils`、`GalleryExternal` 等不需要额外声明）。

各模块 Manifest 要点：

- `main`：`PrivacyActivity` 为 LAUNCHER 且 `exported=true`；`MainActivity` `exported=false`（杜绝绕过同意页直接调起）；`ExternalEntryActivity` `exported=true`，`PICK_IMAGE` 与 `VIEW_IMAGE` **分成两个 intent-filter**（后者声明 `content`/`file` scheme + host + mimeType，见 §7）；媒体/位置权限声明（`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED`、`ACCESS_MEDIA_LOCATION`）与百度 AK 的 `meta-data`。
- `main-ui`：各 Activity 均 `exported=false`（`ImagePickerActivity` / `AddToAlbumActivity` 由外部路由或内部调用启动）；`VideoPlayerActivity` 与选图/添加到页声明 `configChanges`；`<queries>` 声明高德/百度/腾讯/Google 地图包名（供导航检测）。
- `core-data`：`GalleryProvider`（`authorities=com.senk.gallery.data.provider`，`exported=false`）；`ExternalMediaProvider`（`authorities=com.senk.gallery.external`，`exported=false` + `grantUriPermissions=true`，只允许拿到临时授权的调用方按 id 读取单条媒体）。

## 3. 数据流

```
┌─────────── UI（main / main-ui）───────────┐
│ Fragment/Activity  ── MediaQuery(协程) ──┐ │
│         ↑ ContentObserver（根 URI）       │ │
└─────────┼─────────────────────────────────┘
          │ ContentResolver.query / update
┌─────────┴──────── core-data ──────────────┐
│ GalleryProvider（UriMatcher 分发）         │
│   → MediaStoreFetcher / AlbumResolver /   │
│     ExifFetcher                           │
│ 监听了 MediaStore 变化 → notifyChange(根URI) │
│                                           │
│ CustomAlbumStore（自建相册逻辑集合）        │
│ FavoriteStore（自建收藏库）                 │
│ MediaWriter（复制到目录，MediaStore 写入）  │
│ ExternalMediaProvider（对外代理，只读）     │
└─────────┬─────────────────────────────────┘
          │
      MediaStore（系统媒体库）
```

- UI **只经 ContentResolver** 读媒体数据；查询封装在 `MediaQuery` / `GridPageController`（挂 `Dispatchers.IO`，异常时返回空列表并记日志）。**没有 ViewModel / Repository 层**，状态直接存在 View/Activity/Fragment 里。
- **例外（有意设计）**：自建相册是应用私有的"逻辑集合"，成员与名称存在 `CustomAlbumStore`（SharedPreferences + 版本化 JSON），UI 通过该 store API 读写；**它不经过 ContentResolver，也不复制媒体文件**。收藏同理：`FavoriteStore` 是应用私有收藏库（**SQLite** `gallery_favorites.db`），Provider 查询时用其覆盖 `is_favorite`、写入时写它（**不修改系统媒体库**），见 [数据层 §6.2](data-layer.md)。相册内容查询仍由 Provider 读取 MediaStore。
- 媒体文件本身（解码、`loadThumbnail`、播放）由 UI 直接用 `MediaItem.uri` 访问 MediaStore URI，不经过 Provider。
- 对外选图结果通过 `ExternalMediaProvider` 暴露：调用方拿到的是本应用 authority 下的代理 URI，由 `ExternalEntryActivity` 对调用方做临时授权（见 [外部调用 API](external-api.md)）。
- 数据刷新靠 CONTENT_URI 的变更通知驱动（Provider 把 MediaStore 的变更转成根 URI 通知 + 自建相册/复制写入后显式 notifyChange），见 [数据层 §4](data-layer.md)。

## 4. 界面地图

```
PrivacyActivity（LAUNCHER，首启同意页，全屏页）
   └─(同意)→ MainActivity
                ├─ ViewPager2
                │   ├─ PhotosFragment   照片页：View 宫格（铺满式）+ 多选动作栏
                │   └─ AlbumsFragment   相册页：常用 / 我的相册 / 更多
                ├─ 底部悬浮胶囊导航（MaterialCardView，覆盖层）
                ├─ AlbumDetailActivity  相册内容（同一套 View 宫格 + 多选）
                │     └→ ViewerActivity（GL 查看器）
                └─ PhotosFragment ──点击缩略图──→ ViewerActivity
                                                    ├─ 沉浸模式（隐藏覆盖层与系统栏）
                                                    ├─ 上划详情面板（信息 / 地图卡片 / EXIF）
                                                    │     └─点击地图卡片→ PhotoMapActivity（全屏地图 + 导航）
                                                    └─点击视频播放图标→ VideoPlayerActivity

ExternalEntryActivity（exported=true，透明窗口，外部唯一入口）
   ├─ PICK_IMAGE → ImagePickerActivity（照片 / 相册双 tab，单选/多选）
   │                 └─结果：代理 URI + 临时授权 → 回传调用方
   └─ VIEW_IMAGE → ViewerActivity 外部大图模式（不读本应用媒体库）

PhotosFragment / AlbumDetailActivity 多选动作栏
   ├─ 分享 / 收藏 / 删除（系统授权）
   └─ 添加到 → AddToAlbumActivity（自建相册 / 相机 / 文件夹相册）
```

- `ViewerActivity` 的左右翻页以「进入时的相册内容列表」为数据源，翻到边界即止；外部大图模式用调用方传入的 URI 列表，不查询媒体库。
- `VideoPlayerActivity` 可被查看器的视频条目打开；播放器内无「下一个视频」概念。
- `PhotoMapActivity` 只能从查看器详情面板的地图卡片进入（EXIF 含定位时）。
- 权限流程与隐私同意状态见 [界面与交互](ui-guide.md)；隐私同意状态由 `PrivacyPrefs`（`main-ui`）在进程内缓存并持久化到 SharedPreferences。

## 5. GL 自绘的范围

| 自绘（`GLSurfaceView`） | 说明 | 文档 |
|---|---|---|
| 大图查看器 | `GlImageViewer` + `GlImagePagerRenderer`（含动态照片叠加） | [GL 自绘渲染](gl-rendering.md) |
| 视频渲染 | `GlVideoView` + `VideoPlayManager`（OES 纹理） | 同上 |

**缩略图宫格已改为普通 View**：`MediaGridView`（RecyclerView 子类）+ `MediaGridAdapter` + 每格 View（缩略图、时长/动态照片角标、多选选择框）——为的是无障碍（每格可被 TalkBack 聚焦读出）与标准滚动手感。原 GL 宫格（`GlThumbnailGridView` / `GlGridRenderer` / `GridGeometry`）已删除。

**不属于自绘**的界面元素：宫格、标题栏、胶囊导航、相册列表、多选动作栏、选图页、添加到页、详情面板、播放器控制栏、隐私页、地图（百度 `TextureMapView`）——它们都是常规 View，叠加在 GL surface 的窗口层之上。

## 6. common-util 工具清单

| 工具 | 用途 |
|---|---|
| `BitmapUtils.decodeSampled(openStream, maxWidth, maxHeight)` | 采样解码，解码结果不超过给定尺寸 |
| `DisplayUtils` | `dp2px` / `px2dp` / `screenWidth` / `screenHeight` / `density` |
| `DateFormats` | 中文日期时间、时长格式化；`SimpleDateFormat` 按线程各持一份 |
| `FileSizeUtils.format` | 字节数 → `B / KB / MB / GB / TB` |
| `CoordConverter.wgs84ToGcj02` | WGS-84 → GCJ-02（导航 scheme 用；含单元测试） |
| `SystemBarUtils.applyLightBackgroundAppearance(activity, lightBackground)` | 状态栏/导航栏图标明暗 |

## 7. 关键约束清单（跨模块，改动前对照）

1. **数据接口**：UI 不直查 MediaStore；Provider 的 URI/列是 UI 的真实契约；`UriMatcher` 注册必须「具体模式在前、通配在后」（`albums/folder/*`、`albums/app/*`、`albums/custom/*` 都要在 `albums/*` 之前）。[数据层](data-layer.md)
2. **自建相册与收藏语义**：自建相册 = 应用内逻辑集合（`CustomAlbumStore`，不复制文件）；收藏 = 应用内自建库（`FavoriteStore`，不写系统媒体库）；「添加到」对相机/文件夹相册 = 物理复制（`MediaWriter`），对自建相册 = 逻辑加入。改这块前先读 [数据层 §6](data-layer.md)。
3. **颜色与主题**：颜色只在 `colors.xml` 定义且浅/深成对；XML 不直接写颜色（走 style）；代码取色只走 `ThemeUtils`。[主题与颜色规范](theme.md)
4. **透明标题栏**：它"透出"的是 页面背景 → 宫格底色 的叠层；渐变遮罩必须**声明在 toolbar 之前**；照片页标题栏透明，**相册页标题栏必须不透明**（内容不能从标题栏下透出）。[界面与交互](ui-guide.md)
5. **View 宫格（替代原 GL 宫格）**：铺满式布局用 `RecyclerView` 的 `padding + clipToPadding=false`；top padding = 状态栏 + 标题栏，bottom padding = 胶囊导航占位/导航栏（多选时换成动作栏高度 + 导航栏）；滚动回调用 `MediaGridView.onScrollChanged` 驱动 40px 死区的标题栏渐变。[界面与交互](ui-guide.md)
6. **查看器/播放器尺寸恒定**：GL View 恒 `match_parent`，fit 与留白由渲染器画。[GL 自绘渲染](gl-rendering.md)
7. **百度 SDK**：初始化唯一入口 `BaiduMapSdk.ensureInitialized()`（内部先校验隐私同意），任何地图/逆地理编码路径前调用。
8. **隐私合规**：未同意政策不进入主界面、不初始化三方 SDK；`MainActivity` 不可被外部直接调起。
9. **外部调用**：`ExternalEntryActivity` 是唯一的 `exported=true` 入口，负责隐私同意与权限校验；`VIEW_IMAGE` 的 intent-filter 必须声明 `scheme(content/file) + host + mimeType`，否则带 data 的隐式 Intent 无法匹配（已在 Manifest 中配置，改动时不要合并回 PICK_IMAGE 的 filter）；选图结果只返回本应用代理 URI，并按调用方逐个临时授权。[外部调用 API](external-api.md)
10. **多选状态**：`SelectionState` 只保存媒体 id（不持有 `MediaItem`），分页/刷新后按 id 匹配；照片页、相册详情页、选图页共用；`MediaGridView` 在多选态内自行 toggle，宿主只监听 `onSelectionChanged`。
