# 界面与交互

本文件覆盖除「GL 渲染内部实现」（见 [GL 自绘渲染](gl-rendering.md)）与「地图 SDK 初始化细节」之外的全部界面行为：页面结构、insets 分配、手势与状态切换、系统交互（分享/收藏/导航）以及各页面的限制。

## 1. 页面结构与导航

```
PrivacyActivity（LAUNCHER，全屏同意页）
    └─同意→ MainActivity
              ├─ ViewPager2（isUserInputEnabled = false，只由胶囊导航切页）
              │    ├─ index 0  PhotosFragment（照片，GL 宫格）
              │    └─ index 1  AlbumsFragment（相册，RecyclerView）
              ├─ 权限覆盖层 permission_view（未授权时代替 pager 显示）
              ├─ 标题栏 toolbar + 状态栏底条 status_bar_bg + 渐变遮罩 toolbar_scrim
              └─ 底部悬浮胶囊导航 bottom_nav（视口两页共用一个）
PhotosFragment / AlbumsFragment
    ├─相册点击→ AlbumDetailActivity（GL 宫格，标题为相册名）
    └─缩略图点击→ ViewerActivity（查看器）
                       ├─上划/点「详情」→ 详情面板（基本信息 / 地图卡片 / EXIF）
                       │        └─地图卡片点击→ PhotoMapActivity
                       └─视频页点击播放图标→ VideoPlayerActivity
```

胶囊导航的选中态：`nav_photos` / `nav_albums` 的 `isSelected`，由 `MainActivity.updateNavSelection(position)` 维护。

## 2. 首启与隐私同意（`PrivacyActivity`）

- **全屏页**而不是弹窗；结构对齐系统应用首启同意页：应用标识 + 欢迎说明 + 权限逐条说明 + 「隐私」标识 + 可点政策链接 + 底部「退出 / 同意」。
- 已同意（`PrivacyPrefs.isAgreed`）→ 直接转 `MainActivity` 并 `finish()`，用户无感知。
- 「同意」→ `PrivacyPrefs.setAgreed` + 转主界面；「退出」/返回键 → `finishAffinity()`，不进入主界面、不记录同意。
- 政策正文：`AlertDialog` + `dialog_privacy_policy` 布局，宽 90% 屏、高 80% 屏，正文超长由内部 `ScrollView` 滚动。
- 同意状态持久化在 `PrivacyPrefs`（`gallery_privacy` SharedPreferences）；`reset()` 仅供调试。**未同意前不初始化任何三方 SDK、不进主界面**（`MainActivity` 是 `exported=false`，无法被外部绕过同意页调起）。

## 3. 权限流程（`MainActivity`）

- 申请：`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED`、`ACCESS_MEDIA_LOCATION`（`requestPermissions()`）。「已授权」判定：三者（图像 / 视频 / 部分选择）任一 granted 即算通过。
- 未授权：`permission_view` 覆盖层显示，`pager` 与 `bottom_nav` 隐藏；覆盖层给出一条说明与一个按钮。
- 拒绝两次后再点按钮 → 跳系统「应用详情」设置页（`pendingSettingsCheck` 标记，`onResume` 时重新检查）。
- **授权成功后的两个动作**：`BaiduMapSdk.ensureInitialized(this)` 预热（SDK 鉴权异步，提前初始化可避免首次进入详情页时逆地理编码返回 `PERMISSION_UNFINISHED`）+ `refreshFragments()` 触发首刷。
- 视图层级注意：`permission_view` 与 pager 同层、声明在 pager 之后，天然盖在上面。

## 4. 主界面（`MainActivity`）

### 4.1 结构（`activity_main.xml`）

`pager` **铺满整个窗口**（含状态栏与系统导航栏区域），其余元素全部是覆盖层：`status_bar_bg`、`toolbar_scrim`、`toolbar`、`bottom_nav`。`toolbar` 与 `toolbar_scrim` 的绘制顺序：

- 遮罩是普通 `View`、toolbar 的 View elevation 为 0（`Widget.Gallery.Toolbar` 里的 elevation 是 Material 属性），`FrameLayout` 按声明顺序绘制；
- 因此 **`toolbar_scrim` 必须声明在 `toolbar` 之前**，遮罩才能垫在标题文字下面。顺序颠倒的现场表现：上划后标题与背景同为同一灰度（真机实测同为 115），文字「消失」。[主题与颜色规范](theme.md) 有对应资源清单。

### 4.2 insets 分配（`applyOverlayInsets`）

根布局不吃 inset，inset 只分给覆盖层：

| 元素 | 分配 |
|---|---|
| `status_bar_bg` | 高度 = 状态栏高度 |
| `toolbar` | `marginTop` = 状态栏高度（左右 = 系统手势区）；**不能用 paddingTop**——会把 toolbar 撑高成「状态栏 + actionBarSize」，标题在整块里居中后落到状态栏与内容交界处 |
| `toolbar_scrim` | 高度 = 状态栏 + `actionBarSize` |
| `bottom_nav` | `marginBottom` = 基础 16dp + 手势条高度 |

改 `layoutParams` 后**必须重新赋值**触发重新布局（直接改可变对象不生效，真机实测踩过：遮罩高度不更新）。

### 4.3 标题栏三态（`applyChrome` / `applyToolbarAppearance`）

标题栏背景**始终透明**，视觉上「透出」的是页面底色（`@id/main` 的 `gallery_surface_chrome`，深色模式由 values-night 自动变深）。状态切换：

| 场景 | 遮罩 | 标题/图标颜色 | 状态栏图标 |
|---|---|---|---|
| 相册页（始终） | 隐藏 | `ThemeUtils.textPrimary`（跟随主题） | 按 chrome 底色 |
| 照片页 · 静止（`scrollY ≤ 40px` 死区） | 隐藏 | `ThemeUtils.textPrimary` | 按 chrome 底色 |
| 照片页 · 上划（`scrollY > 40px`） | 显示 | `ThemeUtils.textOnScrim`（白） | 按 `mediaScrim`（深色） |

- 宫格第一行静止时位于标题栏下方，由 `PhotosFragment` 的 `topInset` 保证；上划后才允许缩略图滚入标题栏区域。
- 切回相册页 / 照片页时重置 `gridScrolledPast = false`。
- 系统栏图标明暗经 `ThemeUtils.applySystemBarAppearance`，`MainActivity` 与 `AlbumDetailActivity` 同一套逻辑。

### 4.4 宫格回调与 Fragment 懒创建

- `PhotosFragment.onGridScroll` 由 MainActivity 设置，驱动 4.3 的标题栏切换（回调在 GL 线程产生，View 层已切主线程）。
- **ViewPager2 的 Fragment 是懒创建的**：`setAdapter` 之后 Fragment 并不立刻存在，只在 `onCreate` 挂回调会漏掉照片页（真机实测回调恒为 null）。`refreshFragments()` 在授权后调用，并在首帧后 `pager.post { attachGridScrollListener() }` 兜底；`onPageSelected` 时也补挂一次。

## 5. 照片页（`PhotosFragment` + `GlThumbnailGridView`）

- 4 列宫格、数据源 `content://…/media`（全量图片+视频按拍摄时间倒序），`PAGE_SIZE = 200`；接近底部（GL 渲染器阈值）触发下一页；`ContentObserver` 监听到变化时从第一页重载（宫格回顶）。
- **insets**（`applyInsets`）：
  - `topInset` = 状态栏 + `actionBarSize` → 第一行完整落在标题栏下方；
  - `bottomPadding` = 胶囊导航占位 `72dp` + 系统导航栏高度 → 滚到底时最后一行完整停在两者上方。
  - `CAPSULE_AREA_DP = 72f` 是「胶囊卡片高 + 下边距」的实测合计（本机 density 3.25 下 234px），**不含**系统导航栏，导航栏由 inset 另加。
  - 必须走 `setOnApplyWindowInsetsListener`：`onViewCreated` 时视图未 attach，直接读 root insets 恒为 0（真机实测）。
- 宫格 surface 底色 = `ThemeUtils.surfaceContent`（否则深色模式标题栏区域露白）；缩略图/角标细节见 [GL 自绘渲染](gl-rendering.md)。
- 点击缩略图 → `ViewerActivity`，`baseUri = GalleryContract.MEDIA_URI`、`position = 当前列表下标`。

## 6. 相册页（`AlbumsFragment` + `AlbumListAdapter`）

- `RecyclerView` + `GridLayoutManager(spanCount = 3)`：
  - `Row.Header` 占满 3 格（`SPAN_FULL`）；
  - 常用相册为宫格卡（`item_album_grid`，每行 3 个，封面 `ShapeableImageView`，`ImageViewThumbLoader.load(cover, coverUri)` 默认 256px）；
  - 更多相册为整行卡（`item_album_row`，占满 3 格，封面 160px）。
- 分组顺序：`gallery_section_common`（常用）在前，`gallery_section_more`（更多）在后；两组都空时显示 `empty_view`。
- 顶部内边距 = `paddingTop` 基础值 + 状态栏 + `actionBarSize`（用 padding 而不是 margin，保留滚动手感；布局 `clipToPadding=false`）；**相册页保持原样的不透明白底标题栏**（不参与 4.3 的上划切换）。
- 点击相册 → `AlbumDetailActivity.start(context, album)`，由 `AlbumInfo.albumType` 决定内容 URI：`folder → folderAlbumItemsUri(bucketId)`、`third_party → appAlbumItemsUri(ownerPackage)`、其余 → `albumItemsUri(type)`；标题 = 相册名。
- 空相册已在数据层隐藏（见 [数据层](data-layer.md)），UI 只是展示。

## 7. 相册详情页（`AlbumDetailActivity`）

与照片页同一套「铺满式」结构（`activity_album_detail.xml`）：宫格铺满窗口，`status_bar_bg`、`toolbar_scrim`、`toolbar` 为覆盖层（遮罩同样声明在 toolbar 之前）。

- insets：`topInset` = 状态栏 + `actionBarSize`；`bottomPadding` = **系统导航栏高度**（本页没有胶囊导航，不要再加 72dp 占位）。
- 上划切换与照片页相同的 40px 死区：静止 → 主题色标题栏 + 主题色标题/返回键；上划 → 遮罩接管、标题/返回键转白、`status_bar_bg` 隐藏。
- 数据：`EXTRA_BASE_URI`（相册内容 URI）+ `EXTRA_TITLE`；`PAGE_SIZE = 200` 分页；`ContentObserver` 变化时重载回顶。
- 点击缩略图 → `ViewerActivity(baseUri, position)`（翻页范围就是本相册）。

## 8. 查看器（`ViewerActivity` + `GlImageViewer`）

### 8.1 数据加载

- 首次：取 `limit = initialPosition + 200` 的第一段，定位到 `initialPosition`；`endReached = 结果数 < limit`。
- 翻页进入尾部 `LOAD_MORE_THRESHOLD = 20` 内时加载下一页（从 `items.size` 偏移取 200）。
- 击穿边界：翻页到列表两端即止（查看器不知道「下一个相册」）。

### 8.2 顶栏 / 底栏

- 顶栏：标题 = 拍摄时间（`DateFormats.formatDateTime`），副标题 = 「当前张 / 总数」（`gallery_position_format`，如 `3 / 223`）。
- 底栏三个按钮：分享（`ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`，`createChooser`）、收藏（写回 MediaStore，见 8.4）、详情（打开面板）。

### 8.3 沉浸模式与手势

- 单击 ≈ 切换沉浸：隐藏系统栏 + `toolbar` / `bottomBar`，窗口与根视图背景变 `ThemeUtils.mediaScrim`；退出沉浸时窗口背景恢复 `surfaceContent`（根视图被同时重置为不透明白底，位于 GL 画面之下、不可见，见 [主题与颜色规范 §6](theme.md)）。渲染器的观看底色同步切换（`viewer.setLightBackground(!immersive)`）。
- 查看器内部手势：双击缩放（2.5× / 复位）、双指缩放（1×–5×）、放大态拖动平移、非放大时横滑翻页、上划拉起详情面板；单击落在视频页播放图标内 → 进播放器，否则切换沉浸（手势细节见 [GL 自绘渲染](gl-rendering.md)）。
- 动态照片：长按播放内嵌视频、松手/翻页/退后台停止。

### 8.4 收藏写回

```
点收藏 → contentResolver.update(mediaUri(id), is_favorite)
   ├─ 正常返回：本地 item.isFavorite 改写 + 图标刷新
   └─ 抛 RecoverableSecurityException（Android 12+ 需用户授权）
        → startIntentSenderForResult(e.userAction.actionIntent.intentSender, REQUEST_FAVORITE)
        → onActivityResult(RESULT_OK) 再写一次，成功后刷新
```

### 8.5 详情面板（`details_sheet`）

- 高度 = 屏高 × 0.52（`SHEET_HEIGHT_RATIO`），从底部平移进出（`translationY`）；拖拽由查看器上划手势驱动，`sheetProgress ∈ [0,1]`。
- 松手结算：`progress > 0.4` 或向上速度超过阈值 → 展开；否则收起；动画 200ms `Decelerate`。
- 面板展开时顶/底栏按 `1 - progress` 同步淡出（仅非沉浸态）；返回键优先收面板。
- 内容分区（`loadViewerInfo`，IO 线程 + token 防错页）：
  - 基本信息：名称 / 类型 / 拍摄时间 / 大小（`FileSizeUtils`）/ 尺寸 /（视频）时长 / 路径 / 来源应用 /（动态照片）(全景)标记；
  - 地图卡片（EXIF 有定位时）：`sheet_map_container` 内嵌 `TextureMapView`（160dp，禁用手势），标记 `MapMarkers.dot`，缩放 16；地址经 `LocationAddressResolver` 逆地理编码（`gallery_info_address_loading` → 成功显示地址，失败显示「地址解析失败」）；
  - EXIF 区：品牌 / 型号 / 镜头 / 光圈 / 曝光 / ISO / 焦距 / 等效焦距 / EXIF 时间 / 软件。
- 地图卡片整体盖了一个 `sheet_map_overlay`：点卡片 → `PhotoMapActivity`（地图 + 导航）。
- 地图相关卡点：
  - `TextureMapView` 构造**先于授权初始化会 NPE 崩溃**（真机栈：`TextureMapView.<init> → BaiduMap.<init> → JNIInitializer.isUserTest`），因此 `ensureSheetMap()` 先调 `BaiduMapSdk.ensureInitialized()`，失败（未配置 AK 等）时**隐藏地图卡片**，不崩溃；
  - SDK 初始化唯一入口见 [构建与发布](build-and-release.md)；坐标系 BD09LL（`LocationAddressResolver.toBd09`）。

## 9. 视频播放器（`VideoPlayerActivity` + `GlVideoView`）

- 窗口背景透明（`ColorDrawable(TRANSPARENT)` + `setDecorFitsSystemWindows(false)` + 隐藏系统栏）：半透明控制栏 `#99000000` 才能透出下层视频（默认 z-order 下 surface 在窗口之后，窗口不透明会把半透明栏合成成黑）——横屏实测有效。
- 控制：播放/暂停、进度条（拖动时暂停跟随，松手 seek）、横竖屏切换按钮（切 `requestedOrientation`）、标题栏返回。
- 自动隐藏：播放中 5s（`AUTO_HIDE_DELAY`）隐藏 `toolbar` + `controls`；暂停 / 播放结束 / 拖动进度条时保持显示；点视频区切换显隐。
  - **暂停瞬间 `isPlaying()` 仍是 true、恢复瞬间仍是 false**（`start/pause` 是主线程 post 异步生效），因此 toggle 的两个分支都用 `handler.post { scheduleAutoHide() }`，等状态生效后再排计时器；否则会出现「暂停后 5s 栏自己消失 / 恢复后栏不再自动隐藏」。
- 播放中 `FLAG_KEEP_SCREEN_ON`；`onPause` 暂停播放并清 keep-screen-on；进度每 500ms 刷新。
- 横竖屏切换不重建（Manifest `configChanges`）；`onConfigurationChanged` 只刷新旋转按钮图标。
- 轮换/旋转细节与解码策略见 [GL 自绘渲染](gl-rendering.md)。

## 10. 地图页（`PhotoMapActivity`）

- **`BaiduMapSdk.ensureInitialized()` 必须早于 `setContentView`**（布局内含 `TextureMapView`）。
- 内容：全屏地图（缩放 16、标记点）+ 底部卡片（地址 + 「导航」按钮）。
- 地址：进入时若未携带地址则现场逆地理编码；导航：`NavigationHelper.navigationApps()` 检测已安装的地图应用（高德 / 百度 / 腾讯 / Google），弹 `MaterialAlertDialog` 选择后 `startActivity`，找不到时 `Toast`。
- 各导航 App 的 scheme 与坐标系：高德 `androidamap://navi`（GCJ-02）、百度 `baidumap://map/navi`（BD09LL）、腾讯 `qqmap://map/routeplan`（GCJ-02）、Google `google.navigation:`（原始 WGS-84）；坐标转换分别来自 `CoordConverter.wgs84ToGcj02` 与 `LocationAddressResolver.toBd09`。

## 11. 通用小部件

| 组件 | 行为 | 备注 |
|---|---|---|
| `ImageViewThumbLoader` | 4 线程 `loadThumbnail`，默认 256px（行卡 160px）；`imageView.tag` 校验防列表复用错位；加载前先铺主题占位色 | 相册封面用；宫格不用它（走 GL 链路） |
| `ToolbarScrimDrawable` | 顶部约 55% 黑（`0x8C`）→ 全透明单段线性渐变；高度由调用方给（状态栏 + 标题栏） | 首页与相册详情共用；验证约定见下 |
| `MapMarkers.dot` | 白底圆 + 蓝色圆点（`0xFF2A6BF2`，约 0.32 半径），18dp 起，进程内缓存 | 查看器卡片与地图页共用 |
| `MainPagerAdapter` | 2 页固定 Fragment；pager 禁用户滑动，只由胶囊导航切 | |

**遮罩/渐变的验证约定**（`ToolbarScrimDrawable` 类注释）：怀疑「顶部空白/压黑」类问题时，先把 `START_ALPHA` 临时改为 `0x00` 重建——能直接看到未压暗的缩略图 ⇒ 布局正确、只是观感问题；仍纯白 ⇒ 查 `GridGeometry.topInset` 类布局问题。不屏蔽时的辅助判据：同一高度取**多个 x**，取值互不相同才说明有图像内容透出；只取单点不足以区分「被压平」与「真的空白」。

## 12. 已知限制（详见 [backlog](backlog.md)）

- 查看器缩放后滑出工作集再回来，纹理是降分辨率版本（放大超过约 1.2× 属放大）；解码上限是屏幕尺寸。
- 查看器翻页数据源是「进入时的列表」，期间媒体库变化不会在查看器内刷新。
- 视频播放无音频焦点管理；播放结束停在结尾画面，不自动重播。
- 详情面板的地图地址、EXIF 均为进入面板时单次加载，翻页后自动重取（有 token 防错页）。
