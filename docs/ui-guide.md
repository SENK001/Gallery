# 界面与交互

本文件覆盖除「查看器/视频的内部 GL 绘制」（见 [GL 自绘渲染](gl-rendering.md)）、「地图 SDK 初始化细节」与「外部调用契约」（见 [外部调用 API](external-api.md)）之外的全部界面行为：页面结构、insets 分配、手势与状态切换、系统交互（分享/收藏/导航）、多选/选图/添加到/自建相册，以及各页面的限制。

## 1. 页面结构与导航

```
PrivacyActivity（LAUNCHER，全屏同意页）
    └─同意→ MainActivity
              ├─ ViewPager2（isUserInputEnabled = false，只由胶囊导航切页）
              │    ├─ index 0  PhotosFragment（照片，View 宫格）
              │    └─ index 1  AlbumsFragment（相册，RecyclerView）
              ├─ 权限覆盖层 permission_view（未授权时代替 pager 显示）
              ├─ 标题栏 toolbar + 状态栏底条 status_bar_bg + 渐变遮罩 toolbar_scrim
              └─ 底部悬浮胶囊导航 bottom_nav（视口两页共用一个）
PhotosFragment / AlbumsFragment
    ├─相册点击→ AlbumDetailActivity（同一套 View 宫格，标题为相册名）
    └─缩略图点击→ ViewerActivity（查看器）
                       ├─上划/点「详情」→ 详情面板（基本信息 / 地图卡片 / EXIF）
                       │        └─地图卡片点击→ PhotoMapActivity
                       └─视频页点击播放图标→ VideoPlayerActivity

多选（照片页 / 相册详情页）：
    ├─标题栏「多选」→ 进入多选；格子右上角选择框；标题「已选择 N 项」；全选
    ├─底部动作栏：分享 / 收藏 / 添加到 / 删除
    └─「添加到」→ AddToAlbumActivity（自建相册 / 相机 / 文件夹相册）

最近删除：
AlbumsFragment「常用 · 最近删除」→ RecentlyDeletedActivity
    ├─点击缩略图 → ViewerActivity（翻页范围即回收站）
    └─多选 → 恢复（createTrashRequest false）/ 彻底删除（createDeleteRequest）；溢出菜单「清空」

外部调用（唯一 exported 入口）：
ExternalEntryActivity → ImagePickerActivity（选图，照片/相册双 tab）
                      → ViewerActivity（外部大图模式，不读本应用媒体库）
```

胶囊导航的选中态：`nav_photos` / `nav_albums` 的 `isSelected`，由 `MainActivity.updateNavSelection(position)` 维护。多选态下胶囊导航隐藏——**进入多选时先播放导航下滑隐藏（180ms），完全隐藏后再展开多选菜单**；退出时菜单先收起，再滑入导航。

## 2. 首启与隐私同意（`PrivacyActivity`）

- **全屏页**而不是弹窗；结构对齐系统应用首启同意页：应用标识 + 欢迎说明 + 权限逐条说明 + 「隐私」标识 + 可点政策链接 + 底部「退出 / 同意」。
- 已同意（`PrivacyPrefs.isAgreed`）→ 直接转 `MainActivity` 并 `finish()`，用户无感知。
- 「同意」→ `PrivacyPrefs.setAgreed` + 转主界面；「退出」/返回键 → `finishAffinity()`，不进入主界面、不记录同意。
- 政策正文：统一对话框 `GalleryDialog`（`dialog_privacy_policy` 仅作可滚动内容区），标题/圆角/关闭按钮由组件提供；正文区高约屏高 62%，超长由内部 `ScrollView` 滚动。
- 同意状态持久化在 `PrivacyPrefs`（`gallery_privacy` SharedPreferences）；`reset()` 仅供调试。**未同意前不初始化任何三方 SDK、不进主界面**（`MainActivity` 是 `exported=false`，无法被外部绕过同意页调起）。
- 外部调用同样受此闸门约束：`ExternalEntryActivity` 未同意时 Toast 提示并返回 `RESULT_CANCELED`，不读取媒体。

## 3. 权限流程（`MainActivity`）

- 申请：`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED`、`ACCESS_MEDIA_LOCATION`（`requestPermissions()`）。「已授权」判定：三者（图像 / 视频 / 部分选择）任一 granted 即算通过。
- 未授权：`permission_view` 覆盖层显示，`pager` 与 `bottom_nav` 隐藏；覆盖层给出一条说明与一个按钮。
- 拒绝两次后再点按钮 → 跳系统「应用详情」设置页（`pendingSettingsCheck` 标记，`onResume` 时重新检查）。
- **授权成功后的两个动作**：`BaiduMapSdk.ensureInitialized(this)` 预热 + `refreshFragments()` 触发首刷。
- 视图层级注意：`permission_view` 与 pager 同层、声明在 pager 之后，天然盖在上面。
- 外部调用入口（`ExternalEntryActivity`）在 PICK_IMAGE 前同样校验/申请媒体权限；VIEW_IMAGE 不强制本应用媒体权限（只依赖调用方对 URI 的临时授权）。

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

- 相册页的标题栏由代码铺一层不透明 `gallery_surface_chrome`（内容不会从标题栏下透出）；照片页保持透明以透出渐变/宫格。
- 宫格第一行静止时位于标题栏下方，由 `PhotosFragment` 的 `setContentInsets(top, …)` 保证；上划后才允许缩略图滚入标题栏区域。
- 菜单图标（多选/全选）与导航图标颜色跟随同一三态（`ThemeUtils.iconOnSurface / iconOnScrim`）。
- 切回相册页 / 照片页时重置 `gridScrolledPast = false`。
- 系统栏图标明暗经 `ThemeUtils.applySystemBarAppearance`，`MainActivity` 与 `AlbumDetailActivity` 同一套逻辑。

### 4.4 多选与标题栏（`MainActivity`）

- 标题栏菜单（`menu_main.xml`）：普通态 `[多选]`（`ic_multi_select`）；多选态 `[全选]`（`ic_select_all`，全部已选时文案/无障碍描述切为 **取消全选**）。点击分别调 `PhotosFragment.enterSelectionMode()` / `toggleSelectAll()`。
- 多选态：标题 =「已选择 N 项」、导航图标 = 关闭（点击退出）、隐藏底部胶囊导航；返回键优先退出多选；退出后恢复应用标题与胶囊导航。照片页进入多选时**胶囊导航先下滑隐藏，隐藏完成后才播放多选菜单「圆 → 胶囊」展开动画**（`MainActivity.hideBottomNavAnimated` → `PhotosFragment.showSelectionActionBarAnimated`）；退出时顺序相反（菜单收缩完成后 `showBottomNavAnimated` 滑入导航）。
- 菜单/标题/导航图标的颜色跟随 4.3 的三态。

### 4.5 宫格回调与 Fragment 懒创建

- `PhotosFragment.onGridScroll` 由 MainActivity 设置，驱动 4.3 的标题栏切换（回调在主线程）。
- `PhotosFragment.onSelectionStateChanged(active, count)` 由 MainActivity 设置，驱动多选标题栏/菜单/胶囊导航。
- **ViewPager2 的 Fragment 是懒创建的**：`setAdapter` 之后 Fragment 并不立刻存在；切页回来时 Fragment/视图也可能重建。MainActivity 通过 `registerFragmentLifecycleCallbacks` 在 `onFragmentViewCreated`/`onFragmentResumed` 时绑定回调，并在 `refreshFragments()` 与 `onPageSelected` 兜底（真机实测：只靠 onCreate/onPageSelected 会丢回调，切页后上划标题栏不再变遮罩）。
- **切页/返回后必须同步真实滚动状态**：RecyclerView 会保留宫格滚动位置，而切页本身不会产生滚动事件；MainActivity 在回到照片页（`onPageSelected`）与 Fragment resume 时调用 `PhotosFragment.syncGridScrollState()`，用 `MediaGridView.currentScrollY()` 的真实值纠正标题栏三态。否则会出现「上划后切页返回，标题黑字、遮罩消失、状态栏不透明」而宫格实际仍是滚动态的错位。

## 5. 宫格（`MediaGridView` + `MediaGridAdapter`，照片页/相册详情页/选图页共用）

- **一格一个 View**：`RecyclerView`（`MediaGridView`）+ `item_media_cell.xml`（正方形格子：`ImageView` centerCrop 缩略图、视频时长角标、动态照片角标、右上角选择框、选中描边/压暗）。**原 GL 自绘宫格已删除**，因此 `uiautomator dump` 能看到每格节点。
- **无障碍**：每格根 View 可聚焦/可点击，`contentDescription` =「照片/视频，拍摄时间，已选择/未选择」；多选态通过 `AccessibilityDelegate` 暴露 CheckBox 语义（`checkable/checked`），TalkBack 能读出选中状态；多选按钮、全选、动作按钮均有中文 `contentDescription`。
- **缩略图加载**：`ImageViewThumbLoader` 带 LRU Bitmap 缓存（约堆内存 1/8）+ `tag` 防复用错位；`loadThumbnail(uri, Size)`，格子边长 = 屏宽/4。
- **铺满式 insets**：`MediaGridView.setContentInsets(top, bottom)` 内部转成 padding 并保持 `clipToPadding=false`：
  - 照片页：top = 状态栏 + 标题栏；bottom = 胶囊导航占位 `72dp` + 系统导航栏；**多选时** bottom = 动作栏 `64dp` + 系统导航栏。
  - 相册详情页：top 同上；bottom = 系统导航栏（无胶囊），多选时加动作栏高度。
- **滚动/分页**：接近底部 2 行时 `onLoadMore`（宿主用 `MediaPager` 拉下一页）；`onScrollChanged` 驱动标题栏 40px 死区；数据刷新（`ContentObserver`）回顶重载。
- **空态**：宫格适配器内单独一行 TextView（`gallery_empty`），不依赖外部布局。
- 点击缩略图 → `ViewerActivity(baseUri, position)`；长按 → 进入多选并选中该格。

### 5.1 多选（`SelectionState` + `SelectionActionBar` + `SelectionActions`）

- `SelectionState` 是**与 View 无关**的状态机：只保存媒体 id（`LinkedHashSet`，保留点选顺序）、`isActive`、`maxCount`，监听器回调刷新宫格/宿主 UI；分页刷新后旧 `MediaItem` 被替换也不会错位（按 id 匹配）。
- `MediaGridView` 在多选态内自行处理点击 toggle；宿主只监听 `onSelectionChanged`，更新标题/动作栏/底部 inset。
- 进入方式：标题栏「多选」按钮，或长按格子（进入时选中该格）。退出：关闭图标、返回键、或用户把已选取消到 0 项（自动退出；**「取消全选」批量清空除外**，保持多选态）。
- **滑动选择（刷选）**：多选态下在格子上按下并**横向拖动**，手指划过的格子按起点格子的状态批量**选中/取消**（起点未选 → 整段为选中刷选；起点已选 → 整段为取消刷选）；可跨行斜向拖动，快速滑动时按半格步长插值命中、不漏格；手指靠近上下边缘会自动滚动以便连续刷选。**纵向拖动仍是正常滚动列表**：首个超过 `touchSlop` 的位移方向决定本次手势类型（横 = 刷选、纵 = 滚动），锁定后不再切换，避免边滚边误选。实现见 `MediaGridView.onInterceptTouchEvent` / `onTouchEvent`；上限/Toast 逻辑与点击选择一致（一次刷选最多提示一次「已达最大选择数」）。
- **长按进入多选后不松手继续拖动**：直接进入刷选（长按已表达选择意图，**方向不再区分**，固定为批量选中；起点格子已由长按选中，不会被误判为取消刷选）。松手后再开始新手势则恢复上面的方向判定规则。
- **底部动作栏（悬浮胶囊）** `view_selection_action_bar.xml`：4 个动作项（分享/收藏/添加到/删除，图标+中文，深色模式跟随主题），每项固定 72dp 宽；整体是**居中悬浮在底部的胶囊**（圆角 = 高度一半 32dp，底部外边距 = 系统导航栏 + 16dp），不占满整行、不贴屏幕边缘。**进入多选时从底部中央的 64dp 圆横向展开成 304dp 胶囊**（宽度动画 260ms + 内容延迟 80ms 淡入），退出时反向收缩后隐藏；动画在 `SelectionActionBar.showAnimated()/hideAnimated()` 中实现。动作：
  - **分享**：单张 `ACTION_SEND`、多张 `ACTION_SEND_MULTIPLE` + `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`。
  - **收藏**：写入**应用内自建收藏库**（Provider → `FavoriteStore`，不修改系统媒体库），因此没有系统授权弹窗、批量收藏不会失败；全部已收藏 → 取消收藏，否则全部收藏。
  - **添加到**：启动 `AddToAlbumActivity`（见 §12），返回 `RESULT_OK` 后退出多选。
  - **删除**：`MediaStore.createTrashRequest`（**必须用 MediaStore 真实媒体 URI**，不能传本应用 Provider URI，否则抛 `IllegalArgumentException: All requested items must be referenced by specific ID`）→ 系统「移到最近删除」确认 → 授权后退出多选并刷新。
- **未选择任何项时禁用菜单**：多选态下 4 个动作置灰（38% 透明度、`enabled=false`）并禁用点击，**不弹 Toast / 不弹系统框**；选择至少 1 项后恢复。实现：`SelectionActionBar.setActionsEnabled(enabled)`，宿主在 `onSelectionChanged` 里传 `state.isActive && state.count > 0`（照片页同时挂到宿主的菜单顺序动画上；选择 0 时胶囊展开过程中也保持置灰观感）。禁用仍保留 `clickable=true`，让触摸被胶囊消费、不会穿透到下面的宫格误选。
- 「全选」是**切换按钮**：选中**全部条目**（不止当前已加载的一页）——点击时会先自动把剩余分页全部加载完（`GridPageController.toggleSelectAll` → `loadAllPages`），再一次性选中；冷启动未滑动时也能选满整个数据源（如 248 项而不是首屏 200 项）。全部已选时按钮文案变为「取消全选」，**再次点击清空已选并保持多选态**（不会因 count 归零自动退出；`toggleSelectAll` 期间用 `suppressAutoExitOnZero` 抑制自动退出）。取消全选后再点单个格子/关闭按钮仍按原逻辑退出。加载期间重复点击菜单会被忽略。
- 多选时宫格底部预留切换为「动作栏高度 64dp + 系统导航栏」，胶囊悬浮其上但滚动到列表末尾时最后一行不会被遮挡。
- **与底部导航的先后顺序（照片页）**：进入多选时 `MainActivity` 先把底部胶囊导航下滑隐藏（180ms，`translationY` + 透明度动画，动画结束回调），隐藏完成后才调用 `PhotosFragment.showSelectionActionBarAnimated()` 播放多选菜单展开；退出多选时先调用 `hideSelectionActionBarAnimated { showBottomNavAnimated() }` 让菜单收起后再滑入导航。快速来回切换时两段动画均可被取消并从中途状态衔接。

## 6. 照片页（`PhotosFragment`）

- 4 列 View 宫格，数据源 `content://…/media`（全量图片+视频按拍摄时间倒序），`PAGE_SIZE = 200`；`GridPageController` 负责分页、`ContentObserver` 变化回顶重载、`mediaGrid.setItems/appendItems`。
- insets 见 §5；宫格铺满窗口，标题栏透明覆盖其上。
- 点击缩略图 → `ViewerActivity`，`baseUri = GalleryContract.MEDIA_URI`、`position = 当前列表下标`。
- 多选时底部显示动作栏；动作完成后退出多选并刷新。
- 授权成功（`MainActivity.refreshFragments`）或媒体库变化时 `loadPage(reset = true)`。

## 7. 相册页（`AlbumsFragment` + `AlbumListAdapter`）

- `RecyclerView` + `GridLayoutManager(spanCount = 3)`：
  - `Row.Header` 占满 3 格（`SPAN_FULL`）；
  - 常用相册为宫格卡（`item_album_grid`，每行 3 个，封面 `ShapeableImageView`，`ImageViewThumbLoader.load(cover, coverUri)` 默认 256px）；
  - 我的相册/更多相册为整行卡（`item_album_row`，占满 3 格，封面 160px）；`Row.Action` 用于「新建相册」动作行（整行 + 图标）。
- 分组顺序：`常用` → `我的相册`（自建，含「新建相册」动作行）→ `更多`；全空时显示 `empty_view`。
- 自建相册：可以**空相册**形式显示（系统/目录空相册仍然隐藏）；点击进入详情（`AlbumDetailActivity.start` 用 custom URI）；长按弹「删除相册（不会删除其中照片）」确认后仅删逻辑集合。
- **新建相册流程**：点「新建相册」→ 名称对话框 → 创建成功后**立即进入选图页**（`ImagePickerActivity`，多选、不限数量、标题「选择照片和视频」，照片/视频都可以选）→ 选中项加入新相册并直接进入该相册详情页（Toast「已添加 N 项到相册」）；用户在选图页取消时保留刚创建的空相册并刷新列表（之后可再添加或删除）。
- **最近删除**：相册页**最底部的独立入口行**（「更多」分组之后），**不显示封面**——只有图标 + 名称 + 条数；仅回收站非空时显示。点击进入专用的最近删除页（见 §9），不进入相册详情。数据来自 `CATEGORY_RECENTLY_DELETED`（`AlbumResolver` 单独返回 0/1 行，不混入「常用」分组）。
- 顶部内边距 = `paddingTop` 基础值 + 状态栏 + `actionBarSize`（用 padding 而不是 margin，保留滚动手感；布局 `clipToPadding=false`）；标题栏不透明（见 4.3）。
- 点击相册 → `AlbumDetailActivity.start(context, album)`，由 `AlbumInfo.albumType` 决定内容 URI（`AlbumResolver.albumItemsUri`）：`folder → folderAlbumItemsUri(bucketId)`、`third_party → appAlbumItemsUri(ownerPackage)`、`custom → customAlbumItemsUri(albumId)`、其余 → `albumItemsUri(type)`；标题 = 相册名。

## 8. 相册详情页（`AlbumDetailActivity`）

与照片页同一套「铺满式」结构（`activity_album_detail.xml`）：宫格铺满窗口，`status_bar_bg`、`toolbar_scrim`、`toolbar` 为覆盖层（遮罩同样声明在 toolbar 之前）。

- insets：top = 状态栏 + `actionBarSize`；bottom = **系统导航栏高度**（本页没有胶囊导航，不要再加 72dp 占位）；多选时 bottom = 动作栏 64dp + 导航栏。
- 上划切换与照片页相同的 40px 死区：静止 → 主题色标题栏 + 主题色标题/返回键；上划 → 遮罩接管、标题/返回键/菜单图标转白、`status_bar_bg` 隐藏。
- 多选：菜单用代码创建（main-ui 不新增 menu 资源）——`[多选]` / `[全选]`（切换语义：全部已选时变「取消全选」，再次点击清空并保持多选态）；返回键先退出多选；底部动作栏同 §5.1。
- **自建相册的「添加」**：标题栏最左侧常驻 `[添加 +]` 菜单（仅 `album_type=custom` 显示，系统/目录/三方相册没有该按钮）。点击进入选图页（多选、不限数量、标题「选择照片和视频」），并把**当前相册已有成员预选**（`ImagePickerActivity` 的 `EXTRA_PRESELECTED_ALBUM_URI`）。确认后按「选中集合 = 相册成员」更新：新勾选加入、取消勾选移除（`CustomAlbumStore.addItems/removeItems` diff 写入），被移除的恰好是封面时清空封面交给成员兜底；Toast「已更新相册（新增 X，移除 Y）」，无变化时提示「没有变化」。
- 数据：`EXTRA_BASE_URI`（相册内容 URI）+ `EXTRA_TITLE` + `EXTRA_ALBUM_TYPE` + `EXTRA_ALBUM_ID`（后两者供自建相册添加流程使用）；`PAGE_SIZE = 200` 分页；`ContentObserver` 变化时重载回顶。
- 点击缩略图 → `ViewerActivity(baseUri, position)`（翻页范围就是本相册）。

## 9. 最近删除页（`RecentlyDeletedActivity`）

- **入口**：相册页**最底部**（「更多」分组之后）的「最近删除」行——无封面，只有图标 + 名称 + 条数；只有系统回收站非空时才显示。
- **数据**：Provider 的 `content://…/albums/recently_deleted`（`AlbumResolver` 的 `TRASH_SELECTION`）。查询必须带 `MediaStore.QUERY_ARG_MATCH_TRASHED = MATCH_ONLY` —— MediaProvider 默认会过滤掉 trashed 条目，仅靠 SQL 的 `is_trashed = 1` 取不到。
- **页面结构**：不透明标题栏 + View 宫格 + 多选底部动作栏；复用 `MediaGridView` / `GridPageController` / `SelectionState`，与照片页/相册详情页同一套宫格与多选交互。
- **交互**：
  - 点击缩略图 → 查看器（baseUri 为最近删除列表，翻页范围即回收站）；
  - 长按或标题栏「多选」进入多选 → 标题「已选择 N 项」、全选（切换语义，再次点击取消全选）；底部动作栏 **恢复 / 彻底删除**（未选择任何项时置灰禁用、点击无响应且不弹提示，≥1 项后恢复）；
  - 标题栏溢出菜单 **清空**：先二次确认（显示条数），再彻底删除回收站全部条目；
  - **恢复** = `MediaStore.createTrashRequest(uris, false)`、**彻底删除** = `MediaStore.createDeleteRequest(uris)`，两者都会由系统弹出「允许修改/删除」确认框；
  - 成功后退出多选并重新加载；回收站清空后返回相册页，入口自动消失。
- 30 天自动清理由系统 MediaStore 负责，应用不做定时任务；选图页的相册 tab 不展示「最近删除」（回收站不是选图来源）。

## 10. 选图页（`ImagePickerActivity`）

可被外部调用（经 `ExternalEntryActivity`）或内部复用；`exported=false`、`configChanges`（旋转不重建、选择保留）。

- 结构：顶部 `MaterialToolbar`（关闭/返回 + 标题）+ `TabLayout`（照片 / 相册）+ 内容容器 + 多选确认栏。
- 入参（`GalleryExternal` 常量）：`EXTRA_ALLOW_MULTIPLE`、`EXTRA_MAX_COUNT`（默认 9）、`EXTRA_TITLE`；companion `intent(context, allowMultiple, maxCount, title)`。内部额外支持 `preselectedAlbumUri`（`EXTRA_PRESELECTED_ALBUM_URI`）：打开时按该相册 URI 一次取回全部成员并全部勾选（id 进 `SelectionState`、实例进 `selectedItems`，未加载到的分页成员也会计入结果）。
- **照片 tab**：复用 `MediaGridView` + `MediaPager`（全量媒体，200/页分页）；点击/多选语义见 §5。
- **相册 tab**：复用 `AlbumListAdapter` + `MediaQuery`，分组「常用 / 我的相册 / 更多」；点相册在同一 Activity 内进入内容视图（toolbar 变返回、标题 = 相册名、内容源换成该相册），返回回相册列表。**分类选** = 先选相册分类再选图。「最近删除」是回收站入口、不是可选图来源，在选图页的相册 tab 中被过滤掉。
- **选择语义**：单选模式点击一张立即返回；多选模式点击 toggle（跨 tab/相册保留已选），底部「已选择 N 项」+「完成」（空选禁用）；超 `maxCount` 时 Toast 且不选中。
- **调用方**：外部经 `ExternalEntryActivity` 进入（单选/多选由 extras 指定，默认上限 9）；内部「相册页新建相册」以多选 + 不限数量（`ImagePickerActivity.MAX_COUNT_UNLIMITED`）进入，标题「选择照片和视频」；内部「相册详情页 → 添加」同样多选不限数量，并通过 `preselectedAlbumUri` 预选该相册已有成员（确认后宿主按 diff 增删成员）。
- **结果契约**（内部，供外部路由消费）：`data` = 第一张原始 MediaStore URI；`ClipData` = 全部原始 URI；`EXTRA_SELECTED_URIS`（ArrayList<String>）、`EXTRA_SELECTED_IDS`（LongArray）、`EXTRA_SELECTED_COUNT`（Int）；关闭/返回 = `RESULT_CANCELED`。外部路由再把这些 id 映射成代理 URI 并授权回传调用方（见 §11 与 [外部调用 API](external-api.md)）。
- 无障碍：tab、格子、关闭/返回、完成均有 `contentDescription`；空态文案（暂无照片/暂无相册/该相册暂无内容）。

## 11. 外部调用入口（`ExternalEntryActivity`）

- 透明窗口、`exported=true`、不进入最近任务列表；是第三方使用「选图 / 看大图」的**唯一入口**，内部页面全部 `exported=false`。
- 流程：校验 action → 校验隐私同意（未同意 Toast + `RESULT_CANCELED`，不读媒体/不初始化 SDK）→ PICK_IMAGE 校验/申请媒体权限 → 路由到选图页；选图结果把媒体 id 映射为**代理 URI**（`content://com.senk.gallery.external/media/{id}`），对调用方包名逐个 `grantUriPermission(READ)`，再 `setResult` 回传（单选 `data`、多选 `ClipData` + extras + `FLAG_GRANT_READ_URI_PERMISSION`）。
- VIEW_IMAGE：合并 `data` 与 `EXTRA_VIEW_URIS`，过滤只支持 content/file，启动 `ViewerActivity` 外部模式；**不立即 finish**（保持本页存活以维持调用方授予的 URI 临时授权），查看器返回后再结束。
- 关键节点用 `Log.i("GalleryExternal", ...)` 打日志，便于 `adb logcat` 验证。
- 契约、结果与授权语义的完整说明见 [外部调用 API](external-api.md)。

## 12. 「添加到」与自建相册（`AddToAlbumActivity`）

- 入口：多选动作栏「添加到」，传入选中媒体 id（`AddToAlbumActivity.intent(context, LongArray)`）；`exported=false`、`configChanges`。
- 页面：`MaterialToolbar`（返回 + 标题「添加到」）+ 分组列表：
  1. **自建相册**：首行「新建相册」（名称对话框，创建后立即把所选加入并返回）；已有自建相册点击 **逻辑加入**（不复制文件，Toast「已添加到「名称」」）。
  2. **文件夹相册**：先「相机」（目标 `DCIM/Camera/`），再 provider `category=more` 中的目录相册（副标题「N 项 · 复制到该文件夹」）；点击执行 `MediaWriter.copyToFolder`（进度对话框、防重复点击），完成后回到来源页并 `RESULT_OK` + `EXTRA_ADDED_COUNT`。
- 复制写入：按 mime 选 `Images`/`Video` 集合，`RELATIVE_PATH` + `DISPLAY_NAME` 去重 + `IS_PENDING` 流程，64KB 流式复制（大视频不 OOM/ANR）；失败/取消清理 pending 行并提示。
- 自建相册是**应用内逻辑集合**（`CustomAlbumStore`）：可新建/删除（相册页），不复制、不移动媒体；删除相册不会删除照片。重命名/封面/相册内移除的 store API 已具备但 UI 暂未提供（见 [backlog](backlog.md)）。

## 13. 查看器（`ViewerActivity` + `GlImageViewer`）

### 12.1 数据加载

- 首次：取 `limit = initialPosition + 200` 的第一段，定位到 `initialPosition`；`endReached = 结果数 < limit`。
- 翻页进入尾部 `LOAD_MORE_THRESHOLD = 20` 内时加载下一页（从 `items.size` 偏移取 200）。
- 击穿边界：翻页到列表两端即止（查看器不知道「下一个相册」）。
- **外部大图模式**：intent 带 `GalleryExternal.EXTRA_VIEW_URIS` 时，直接用调用方给的 URI 列表构造合成 `MediaItem`（负数 id，不查本应用 Provider）交给 `setItems`；隐藏收藏/详情（EXIF/地图依赖本应用媒体库 id），禁用上划详情手势，分享可用；标题 = `EXTRA_TITLE`/文件名，副标题 = 位置/总数。

### 12.2 顶栏 / 底栏

- 内部模式顶栏：
  - **大标题 = 拍摄日期**（`DateFormats.formatDate`，如「2026年10月19日」），不再带时间；
  - **副标题 = 拍摄时间 +（有定位时）行政区划**：`08:49` 或 `08:49 · 重庆市 渝北区`（`gallery_viewer_subtitle_time_location` / `gallery_viewer_region_format`）。定位来自 Provider 的 EXIF 坐标 → 百度逆地理编码的省/市/区（`LocationAddressResolver.resolveAddress`），直辖市的 province=city 会去重后取「市 区」，普通省份取「省 市」；无定位/无 AK/无网络时只显示时间，异步解析结果按 media id 缓存并做 token 校验防翻页错位；
  - 标题栏右侧菜单：**详情**（图标 `ic_info`，打开详情面板）——原先在底栏的详情按钮移到了这里。
- 底栏三个按钮：分享（`ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`，`createChooser`）、收藏（写入应用内自建收藏库，见 12.4）、**删除**（移到最近删除，见 12.6）。
- **外部大图模式**：没有本应用媒体库 id 与拍摄时间，标题仍用调用方 title/文件名、副标题为「当前张 / 总数」；收藏/详情/删除入口全部隐藏，只保留分享。

### 12.6 删除（移到最近删除）

- 底栏「删除」与多选删除同一套语义：`MediaStore.createTrashRequest(uris, true)` 由系统弹「允许相册将此照片移到最近删除吗」确认，不直接物理删除。
- 确认成功后：从查看器内存列表移除该条 → `viewer.setItems(...)` 触发 `onPageChanged` 刷新标题/副标题/详情，停留在下一张；列表删空则 `finish()`；同时 `FavoriteStore.markTrashed(id, true)`，收藏相册随 Provider 通知刷新。
- 外部模式不提供删除（URI 不属于本应用媒体库）。

### 12.3 沉浸模式与手势

- 单击 ≈ 切换沉浸：隐藏系统栏 + `toolbar` / `bottomBar`，窗口与根视图背景变 `ThemeUtils.mediaScrim`；退出沉浸时窗口背景恢复 `surfaceContent`。渲染器的观看底色同步切换（`viewer.setLightBackground(!immersive)`）。
- **沉浸收放动画**：进入沉浸时**标题栏向上滑出、底部菜单向下滑出**并同步淡出（200ms，`ViewerActivity.animateChromeHidden(true)`），结束后才置 `GONE`；退出沉浸时标题栏从上方、底部菜单从下方滑回原位并淡入。动画中途再次单击会从当前位移/透明度继续（不跳变），面板手势接管两栏时由 `setSheetProgress` 取消动画并恢复栏状态；`onDestroy` 取消未完成的动画。
- 查看器内部手势：双击缩放（2.5× / 复位）、双指缩放（1×–5×）、放大态拖动平移、非放大时横滑翻页、上划拉起详情面板；单击落在视频页播放图标内 → 进播放器，否则切换沉浸。
- **沉浸模式与详情面板（2026-10-20 修复）**：真机上沉浸态直接展开的面板不参与窗口合成（看不见）却仍接收触摸，点地图会误跳转。因此沉浸期间 `GlImageViewer.sheetTouchEnabled = false`，垂直上划改为 `onSheetUnavailableSwipeUp` → 先 `toggleImmersive()` 退出沉浸、再 `openSheet()` 展开面板——**看得见的面板才可交互**。进入沉浸前若面板还开着，`closeSheetImmediately()` 无动画复位，避免残留不可见面板。
- 动态照片：长按播放内嵌视频、松手/翻页/退后台停止。

### 12.4 收藏（应用内自建库）

```
点收藏 → contentResolver.update(mediaUri(id), is_favorite)
   → GalleryProvider.update → FavoriteStore.setFavorite(id, favorite) → notifyChange(根 URI)
   → 本地 item.isFavorite 改写 + 爱心图标刷新；收藏相册/列表随根 URI 通知刷新
```

- 收藏**不再写系统媒体库**、也不再走 `RecoverableSecurityException` 系统授权弹窗（2026-10-19 起）：此前批量收藏需要用户逐次授权、部分设备直接失败。
- 收藏数据保存在本应用（`FavoriteStore`，SQLite `gallery_favorites.db`）；系统相册/其它应用看不到本应用的收藏，旧版本写进 MediaStore 的收藏标记不导入；开发期换方案，旧 SharedPreferences 收藏数据不兼容。

### 12.5 详情面板（`details_sheet`）

- 高度 = 屏高 × 0.52（`SHEET_HEIGHT_RATIO`），从底部平移进出（`translationY`）；拖拽由查看器上划手势驱动，`sheetProgress ∈ [0,1]`。
- 松手结算：`progress > 0.4` 或向上速度超过阈值 → 展开；否则收起；动画 200ms `Decelerate`。
- 面板展开时顶/底栏按 `1 - progress` 同步淡出（仅非沉浸态）；返回键优先收面板。
- 内容分区（`loadViewerInfo`，IO 线程 + token 防错页）：
  - 基本信息：名称 / 类型 / 拍摄时间 / 大小 / 尺寸 /（视频）时长 / 路径 / 来源应用 /（动态照片）(全景)标记；
  - 地图卡片（EXIF 有定位时）：`sheet_map_container` 内嵌 `TextureMapView`（160dp，禁用手势），标记 `MapMarkers.dot`，缩放 16；地址经 `LocationAddressResolver` 逆地理编码；
  - EXIF 区：品牌 / 型号 / 镜头 / 光圈 / 曝光 / ISO / 焦距 / 等效焦距 / EXIF 时间 / 软件。
- 地图卡片整体盖了 `sheet_map_overlay`：点卡片 → `PhotoMapActivity`。点击有双保险：仅 `!immersive && sheetProgress > 0.01`（面板确实展开且可见）时才响应，防止点到不可见面板里的地图。
- 面板的打开入口是标题栏右侧「详情」菜单（底栏已不再放详情按钮）；底栏「删除」见 12.6。
- 地图相关卡点：`TextureMapView` 构造**先于授权初始化会 NPE 崩溃**，因此 `ensureSheetMap()` 先调 `BaiduMapSdk.ensureInitialized()`，失败时隐藏地图卡片，不崩溃；坐标系 BD09LL。

## 14. 视频播放器（`VideoPlayerActivity` + `GlVideoView`）

- 窗口背景透明（`ColorDrawable(TRANSPARENT)` + `setDecorFitsSystemWindows(false)` + 隐藏系统栏）：半透明控制栏 `#99000000` 才能透出下层视频。
- 控制：播放/暂停、进度条（拖动时暂停跟随，松手 seek）、横竖屏切换按钮、标题栏返回。
- 自动隐藏：播放中 5s（`AUTO_HIDE_DELAY`）隐藏 `toolbar` + `controls`；暂停 / 播放结束 / 拖动进度条时保持显示；点视频区切换显隐。
  - **暂停瞬间 `isPlaying()` 仍是 true、恢复瞬间仍是 false**（`start/pause` 是主线程 post 异步生效），因此 toggle 的两个分支都用 `handler.post { scheduleAutoHide() }`。
- 播放中 `FLAG_KEEP_SCREEN_ON`；`onPause` 暂停播放并清 keep-screen-on；进度每 500ms 刷新。
- 横竖屏切换不重建（Manifest `configChanges`）；`onConfigurationChanged` 只刷新旋转按钮图标。
- 外部调用传入的视频 URI 也可播放（同一 UID，调用方授予的临时授权在查看链路期间有效）。

## 15. 地图页（`PhotoMapActivity`）

- **`BaiduMapSdk.ensureInitialized()` 必须早于 `setContentView`**（布局内含 `TextureMapView`）。
- 内容：全屏地图（缩放 16、标记点）+ 底部卡片（地址 + 「导航」按钮）。
- 地址：进入时若未携带地址则现场逆地理编码；导航：`NavigationHelper.navigationApps()` 检测已安装的地图应用（高德 / 百度 / 腾讯 / Google），弹 `GalleryDialog` 列表选择后 `startActivity`，找不到时 `Toast`。
- 各导航 App 的 scheme 与坐标系：高德 `androidamap://navi`（GCJ-02）、百度 `baidumap://map/navi`（BD09LL）、腾讯 `qqmap://map/routeplan`（GCJ-02）、Google `google.navigation:`（原始 WGS-84）。

## 16. 通用小部件

| 组件 | 行为 | 备注 |
|---|---|---|
| `ImageViewThumbLoader` | 4 线程 `loadThumbnail` + LRU Bitmap 缓存（约堆内存 1/8，按 uri+尺寸档位）+ `imageView.tag` 校验防列表复用错位；加载前先铺主题占位色 | 宫格与相册封面共用 |
| `SelectionState` | 多选状态机（id 集合 / active / maxCount / 监听器） | 照片页、相册详情、选图页共用 |
| `SelectionActions` | 分享/收藏/添加到/删除的共享实现；需要 ActivityResult 的三处由宿主注入启动器 | 照片页与相册详情共用 |
| `GridPageController` | 分页加载 + `ContentObserver` 回顶刷新 + `setItems/appendItems` 提交 + 索引/已选集合 | 照片页与相册详情共用 |
| `ToolbarScrimDrawable` | 顶部约 55% 黑（`0x8C`）→ 全透明单段线性渐变；高度由调用方给（状态栏 + 标题栏） | 首页与相册详情共用 |
| `MapMarkers.dot` | 白底圆 + 蓝色圆点（`0xFF2A6BF2`），18dp 起，进程内缓存 | 查看器卡片与地图页共用 |
| `MainPagerAdapter` | 2 页固定 Fragment；pager 禁用户滑动，只由胶囊导航切 | |
| `GalleryDialog` | 应用统一自定义对话框：28dp 圆角卡片；按钮为胶囊（取消 = 中性 tonal、确认 = 品牌主色实心、危险操作 = danger 色），与首启隐私页按钮同款；支持标题/正文、输入（带校验错误）、自定义内容、列表、无按钮（进度） | 替代 `MaterialAlertDialogBuilder`；见下方「统一对话框」 |

### 统一对话框（`GalleryDialog`）

- 代码：`main-ui/.../ui/dialog/GalleryDialog.kt`；样式：`values/styles_dialog.xml`（`Theme.Gallery.Dialog`、`TextAppearance.Gallery.Dialog*`、`Widget.Gallery.DialogButton*`、`Widget.Gallery.DialogInput`）。
- 决策原因：Material 默认弹窗是文字按钮，与隐私页「胶囊按钮」风格不一致；各页面手写布局又会造成圆角/字号/配色漂移。统一为一个 `Dialog` 子类 + 自定义布局。
- 调用方式：
  ```kotlin
  GalleryDialog.builder(context)
      .setTitle("删除相册")
      .setMessage("确定删除「xxx」吗？")
      .setNegativeButton("取消")
      .setPositiveButton("删除", destructive = true) { /* 确认动作；回调后自动 dismiss */ }
      .show()
  ```
- 变体：`setInput(hint, initial, validate)`（输入型；`validate` 返回错误文案时显示在输入框下方且不关闭，相册命名用）、`setContent(view)`（自定义内容，尊重视图自身宽高；隐私政策正文用）、`setItems(items) {}`（列表项，如地图导航应用选择）、无按钮时用于不可交互的进度弹窗（`setCancelable(false)`，添加到页复制进度用）。
- 危险操作（相册删除、最近删除「清空」）确认按钮使用 `gallery_brand_danger` / `gallery_brand_on_danger`（浅色深红底白字、深色浅红底深红字）。
- 宽度 = 屏宽 88%（上限 560dp）；输入型弹窗 show 后自动聚焦并请求 IME（延迟 120ms 等窗口焦点）；内容区外层内边距统一 24dp，调用方内容视图不要再重复加边距。
- 已迁移：相册新建/重命名输入、相册删除确认、最近删除「清空」确认、地图导航应用选择、隐私政策正文、添加到页复制进度。

**遮罩/渐变的验证约定**（`ToolbarScrimDrawable` 类注释）：怀疑「顶部空白/压黑」类问题时，先把 `START_ALPHA` 临时改为 `0x00` 重建——能直接看到未压暗的缩略图 ⇒ 布局正确、只是观感问题；仍纯白 ⇒ 查宫格 `setContentInsets` / 覆盖层 insets。不屏蔽时的辅助判据：同一高度取**多个 x**，取值互不相同才说明有图像内容透出。

## 17. 已知限制（详见 [backlog](backlog.md)）

- 查看器缩放后滑出工作集再回来，纹理是降分辨率版本（放大超过约 1.2× 属放大）；解码上限是屏幕尺寸。
- 查看器翻页数据源是「进入时的列表」，期间媒体库变化不会在查看器内刷新。
- 视频播放无音频焦点管理；播放结束停在结尾画面，不自动重播。
- 详情面板的地图地址、EXIF 均为进入面板时单次加载，翻页后自动重取（有 token 防错页）。
- 外部大图模式不提供收藏/详情，只做浏览、分享与视频播放；`file://` 受 Android 7+ 跨应用限制。
- 收藏保存在应用内自建库：系统相册不显示、旧系统收藏不导入、卸载/清数据后丢失；多选「全选」会先加载全部条目再选中（覆盖整个数据源）；「添加到相机/文件夹相册」是复制（会产生重复文件），自建相册是逻辑集合（不复制）。
