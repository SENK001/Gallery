# 架构总览

Android 相册应用（`com.senk.gallery`，应用名「相册」）。本文件给出模块划分、数据流、界面地图与跨模块的关键约束；各主题的展开见对应文档。

## 1. 技术栈与版本

| 项 | 值 |
|---|---|
| 包名 / 版本 | `com.senk.gallery` / `versionName 1.0.0.0`（`versionCode 10000000`） |
| SDK | `minSdk 36` / `targetSdk 36` / `compileSdk 37.2`（Android 16+） |
| 构建 | AGP **9.2.1**（内置 Kotlin 支持，无 `kotlin.android` 插件）、Gradle wrapper **9.4.1**、daemon JDK **21**（toolchain 自动下载）、configuration cache 开启、Java 11 兼容级别 |
| UI 技术 | androidx/material（RecyclerView、ViewPager2、MaterialToolbar、MaterialCardView 胶囊导航）+ **三处 GL 自绘**（宫格/查看器/视频）+ 百度地图 `TextureMapView` |
| 依赖版本 | 见 `gradle/libs.versions.toml`；关键项：material 1.14.0、appcompat 1.8.0、core-ktx 1.18.0、recyclerview 1.4.0、viewpager2 1.0.0、lifecycle 2.11.0、coroutines 1.10.2、exifinterface 1.3.7、百度 `com.baidu.lbsyun` 8.2.0（三件套） |

构建、签名、ABI 与地图 AK 的完整说明见 [构建与发布](build-and-release.md)。

## 2. 模块与依赖

```
main (:main, 应用壳)                        namespace com.senk.gallery
 └─ api── main-ui                            namespace com.senk.gallery.ui
           ├─ api── core-data               namespace com.senk.gallery.data
           ├─ api── common-util             namespace com.senk.gallery.util
           └─ api── baidu map/search/util（Maven）
```

| 模块 | 职责 | 关键内容 |
|---|---|---|
| `main` | 应用壳、打包入口 | `PrivacyActivity`（LAUNCHER）、`MainActivity`（exported=false）、`MainPagerAdapter`、`GalleryApp`、权限流程、底部胶囊导航、AK/签名的 `build.gradle` 注入 |
| `main-ui` | 全部业务界面与 GL 组件 | `PhotosFragment` / `AlbumsFragment` / `AlbumDetailActivity` / `ViewerActivity` / `VideoPlayerActivity` / `PhotoMapActivity`；`ui/gl/*`；主题（`ThemeUtils`、`values*/styles.xml`）；`BaiduMapSdk` 等地图支撑 |
| `core-data` | 数据层（见 [数据层](data-layer.md)） | `GalleryProvider`（Manifest 注册，exported=false）、`GalleryContract`、`MediaStoreFetcher`、`AlbumResolver`、`ExifFetcher`、`XmpParser`、`GalleryCursorReader`、entity/pojo |
| `common-util` | 无业务依赖的小工具 | `BitmapUtils`、`DisplayUtils`、`DateFormats`、`FileSizeUtils`、`CoordConverter`、`SystemBarUtils` |

依赖方向是**单向**的：`main → main-ui → {core-data, common-util}`。`core-data` 与 `common-util` 互不依赖、也不引用界面代码；`main-ui` 通过 `api` 对外暴露后两者（`main` 直接使用 `MediaItem`、`GalleryContract`、`DisplayUtils` 等不需要额外声明）。

各模块 Manifest 要点：

- `main`：`PrivacyActivity` 为 LAUNCHER 且 `exported=true`；`MainActivity` `exported=false`（杜绝绕过同意页直接调起）；媒体/位置权限声明（`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED`、`ACCESS_MEDIA_LOCATION`）与百度 AK 的 `meta-data`（值来自 `manifestPlaceholders.baiduMapApiKey`）。
- `main-ui`：四个 Activity（均 `exported=false`），`VideoPlayerActivity` 声明 `configChanges`（横竖屏不重建）；`<queries>` 声明高德/百度/腾讯/Google 地图包名（供导航检测）。
- `core-data`：`GalleryProvider`（`authorities=com.senk.gallery.data.provider`，`exported=false`）。

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
└─────────┬─────────────────────────────────┘
          │
      MediaStore（系统媒体库）
```

- UI **只经 ContentResolver** 读数据；查询封装在 `MediaQuery`（挂 `Dispatchers.IO`，异常时返回空列表并记日志）。**没有 ViewModel / Repository 层**，状态直接存在 View/Activity/Fragment 里。
- 媒体文件本身（解码、`loadThumbnail`、播放）由 UI 直接用 `MediaItem.uri` 访问 MediaStore URI，不经过 Provider。
- 数据刷新靠 CONTENT_URI 的变更通知驱动（Provider 把 MediaStore 的变更转成根 URI 通知），见 [数据层 §3.4](data-layer.md)。

## 4. 界面地图

```
PrivacyActivity（LAUNCHER，首启同意页，全屏页）
   └─(同意)→ MainActivity
                ├─ ViewPager2
                │   ├─ PhotosFragment   照片页：GL 宫格（铺满式）
                │   └─ AlbumsFragment   相册页：常用网格 + 更多列表
                ├─ 底部悬浮胶囊导航（MaterialCardView，覆盖层）
                ├─ AlbumDetailActivity  相册内容（GL 宫格，与照片页同一版式）
                │     └→ ViewerActivity
                └─ PhotosFragment ──点击缩略图──→ ViewerActivity
                                                    ├─ 沉浸模式（隐藏覆盖层与系统栏）
                                                    ├─ 上划详情面板（信息 / 地图卡片 / EXIF）
                                                    │     └─点击地图卡片→ PhotoMapActivity（全屏地图 + 导航）
                                                    └─点击视频播放图标→ VideoPlayerActivity
```

- `ViewerActivity` 的左右翻页以「进入时的相册内容列表」为数据源，翻到边界即止。
- `VideoPlayerActivity` 可被查看器的视频条目打开；播放器内无「下一个视频」概念。
- `PhotoMapActivity` 只能从查看器详情面板的地图卡片进入（EXIF 含定位时）。
- 权限流程与隐私同意状态见 [界面与交互](ui-guide.md)；隐私同意状态由 `PrivacyPrefs`（`main-ui`）在进程内缓存并持久化到 SharedPreferences。

## 5. GL 自绘的范围

| 自绘（`GLSurfaceView`） | 说明 | 文档 |
|---|---|---|
| 缩略图宫格 | `GlThumbnailGridView` + `GlGridRenderer` + `GridGeometry` | [GL 自绘渲染](gl-rendering.md) |
| 大图查看器 | `GlImageViewer` + `GlImagePagerRenderer`（含动态照片叠加） | 同上 |
| 视频渲染 | `GlVideoView` + `VideoPlayManager`（OES 纹理） | 同上 |

**不属于自绘**的界面元素：标题栏、胶囊导航、相册列表、详情面板、播放器控制栏、隐私页、地图（百度 `TextureMapView`）——它们都是常规 View，叠加在 GL surface 的窗口层之上。

## 6. common-util 工具清单

| 工具 | 用途 |
|---|---|
| `BitmapUtils.decodeSampled(openStream, maxWidth, maxHeight)` | 采样解码，解码结果不超过给定尺寸 |
| `DisplayUtils` | `dp2px` / `px2dp` / `screenWidth` / `screenHeight` / `density` |
| `DateFormats` | 中文日期时间、时长格式化；`SimpleDateFormat` 按线程各持一份（进程级单例会被主线程与解码线程并发调用） |
| `FileSizeUtils.format` | 字节数 → `B / KB / MB / GB / TB` |
| `CoordConverter.wgs84ToGcj02` | WGS-84 → GCJ-02（导航 scheme 用；含单元测试） |
| `SystemBarUtils.applyLightBackgroundAppearance(activity, lightBackground)` | 状态栏/导航栏图标明暗 |

## 7. 关键约束清单（跨模块，改动前对照）

1. **数据接口**：UI 不直查 MediaStore；Provider 的 URI/列是 UI 的真实契约；`UriMatcher` 注册必须「具体模式在前、通配在后」。[数据层](data-layer.md)
2. **颜色与主题**：颜色只在 `colors.xml` 定义且浅/深成对；XML 不直接写颜色（走 style）；代码取色只走 `ThemeUtils`。[主题与颜色规范](theme.md)
3. **透明标题栏**：它"透出"的是 页面背景 → GL 清屏色 的叠层，改观感必须三层一起改；渐变遮罩必须**声明在 toolbar 之前**。[界面与交互](ui-guide.md)
4. **GL 通用**：默认 z-order、不给 GL View 设背景、纹理只在 GL 线程创建/删除、GL 回调切主线程。[GL 自绘渲染](gl-rendering.md)
5. **宫格几何**：`topInset`（平移）/ `bottomInset`（加长）/ `bottomPadding`（底部留白）语义不同；可见行按真实坐标求交。[GL 自绘渲染](gl-rendering.md)
6. **查看器/播放器尺寸恒定**：GL View 恒 `match_parent`，fit 与留白由渲染器画。[GL 自绘渲染](gl-rendering.md)
7. **百度 SDK**：初始化唯一入口 `BaiduMapSdk.ensureInitialized()`（内部先校验隐私同意），任何地图/逆地理编码路径前调用。[界面与交互](ui-guide.md)
8. **隐私合规**：未同意政策不进入主界面、不初始化三方 SDK；`MainActivity` 不可被外部直接调起。[界面与交互](ui-guide.md)
