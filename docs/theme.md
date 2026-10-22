# 主题与颜色规范

全应用颜色管理的三层规则：

1. **颜色只在 `colors.xml` 定义**，且浅色（`values/`）与深色（`values-night/`）**成对**；
2. **XML 布局里不写颜色或 `@color/...`**，统一在 `styles.xml` 定义语义 style 挂到控件上；
3. **Kotlin/Java 取色只走 `ThemeUtils`**，不直接 `ContextCompat.getColor`，更不写字面量（`Color.WHITE` 等）——「有意恒定」的颜色除外（见 §6）。

深/浅色跟随系统（无应用内切换），`values-night` 资源自动生效。校验脚本见 §7。

## 1. 文件清单

| 文件 | 内容 |
|---|---|
| `main/src/main/res/values/themes.xml` | `Base.Theme.Gallery`（`Theme.Material3.Light.NoActionBar`）、`Theme.Gallery`、`Theme.Gallery.Privacy` |
| `main/src/main/res/values-night/themes.xml` | `Base.Theme.Gallery` 的 Dark 父类覆盖（`Theme.Material3.Dark.NoActionBar`） |
| `main-ui/src/main/res/values/colors.xml` + `values-night/colors.xml` | 通用语义色板（文字/容器/图标/品牌/媒体层/权限页） |
| `main/src/main/res/values/colors.xml` + `values-night/colors.xml` | main 模块自己的色（胶囊导航、权限提示层）；通用色经资源合并直接引用，不重复定义 |
| `main-ui/…/values/styles.xml` | `Widget.Gallery.*` / `TextAppearance.Gallery.*` 样式（main-ui 的全部控件样式） |
| `main/…/values/styles.xml` | main 模块样式（权限页、胶囊导航、隐私页） |
| `main/src/main/res/color/nav_item_tint.xml`、`nav_item_text.xml` | 胶囊导航图标/文字的 ColorStateList（选中 → `gallery_nav_selected`） |
| `main-ui/…/theme/ThemeUtils.kt` | 代码取色唯一入口 + 明暗判断 + 系统栏图标 |

主题要点：

- 所有 Activity 都使用 `Theme.Gallery`；深色父类**显式**写在 `values-night/themes.xml`（不依赖 `DayNight` 自动切换），避免将来有人改浅色父类时影响深色分支——深色模式下 Material 组件（按钮/卡片/对话框/工具条）必须按 Dark 解析 `?attr/colorSurface` 等，否则会与 `values-night` 色板打架、对话框保持浅色。
- `Theme.Gallery.Privacy`：`windowBackground` 设为透明——白色只由布局根节点画一次（避免主题背景与布局背景重复绘制），状态栏保持可见、深色图标，与系统应用首启页一致。

## 2. 色板（main-ui）

「浅 / 深」两列对应 `values/` 与 `values-night/`。`media_*` 两列相同是**有意**的（见 §6）。

| 名称 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `gallery_text_primary` | `#DE000000` | `#E6FFFFFF` | 标题、列表主文案 |
| `gallery_text_secondary` | `#8A000000` | `#99FFFFFF` | 说明、副标题 |
| `gallery_text_on_scrim` | `#FFFFFF` | `#FFFFFF` | 深色遮罩/媒体层上的文字（上划标题栏、视频控件） |
| `gallery_surface_chrome` | `#FFFFFF` | `#FF1C1B1F` | chrome 底色：标题栏、状态栏底条、各页根布局 |
| `gallery_surface_content` | `#FFFFFF` | `#FF1C1B1F` | 内容底色：全屏页面背景、查看器 GL 清屏色 |
| `gallery_surface_sheet` | `#FFF2F3F5` | `#FF2A2A2E` | 查看器详情面板 / 地图卡片 |
| `gallery_surface_placeholder` | `#FFEEEEEE` | `#FF2A2A2E` | 缩略图占位 |
| `gallery_surface_nav_card` | `#F0FFFFFF` | `#F02A2A2E` | 胶囊导航卡片（半透明，透出缩略图） |
| `gallery_icon_on_surface` | `#DE000000` | `#E6FFFFFF` | 工具条/按钮图标 |
| `gallery_icon_on_scrim` | `#FFFFFF` | `#FFFFFF` | 遮罩/媒体层上的图标 |
| `gallery_divider_on_surface` | `#33000000` | `#66FFFFFF` | 详情面板把手 |
| `gallery_nav_item_selected_bg` | `#1A000000` | `#33FFFFFF` | 导航选中态底 |
| `gallery_brand_primary` | `#FF3482FF` | `#FF6EA8FF` | 品牌主色（选中、主按钮） |
| `gallery_brand_on_primary` | `#FFFFFF` | `#FF10233F` | 主色之上的内容 |
| `gallery_brand_neutral` | `#FFF2F2F2` | `#FF2A2A2E` | 中性按钮底 |
| `gallery_brand_danger` | `#FFB3261E` | `#FFF2B8B5` | 危险操作主色（删除/清空确认按钮） |
| `gallery_brand_on_danger` | `#FFFFFF` | `#FF601410` | 危险色按钮上的文字/图标 |
| `gallery_media_scrim` | `#99000000` | 同浅色 | 播放器/查看器深色遮罩 |
| `gallery_media_control_bg` | `#66000000` | 同浅色 | 视频控件圆钮底 |
| `gallery_media_on_scrim` | `#FFFFFF` | 同浅色 | 媒体层图标/文字/进度条 |
| `gallery_media_progress_track` | `#66FFFFFF` | 同浅色 | 进度条轨道 |
| `gallery_media_placeholder_light` | `#FFEEEEEE` | 同浅色 | 查看器「亮底」观看占位 |
| `gallery_media_placeholder_dark` | `#FF222222` | 同浅色 | 查看器「深底」观看占位 |
| `gallery_perm_icon_bg` | `#FFF0F0F0` | `#FF2A2A2E` | 权限图标圆底 |
| `gallery_perm_fade` | `#1F000000` | `#1FFFFFFF` | 隐私页底部渐隐提示 |

**main 模块**（`main/src/main/res/values/colors.xml`）：

| 名称 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `gallery_nav_selected` | `@color/gallery_brand_primary` | 同 | 导航选中态图标/文字 |
| `gallery_nav_icon_unselected` | `#E6000000` | `#99FFFFFF` | 导航未选中图标 |
| `gallery_nav_text_unselected` | `#99000000` | `#99FFFFFF` | 导航未选中文字 |
| `gallery_permission_surface` | `@color/gallery_surface_content` | 同 | 未授权提示层底色 |

胶囊导航的图标/文字经 ColorStateList（`res/color/nav_item_tint.xml`、`nav_item_text.xml`）：`state_selected` → `gallery_nav_selected`，否则未选中色。两个状态色本身都在色板里，因此深色自动适配。

## 3. 样式约定（styles.xml）

- **命名空间补齐**：`styles.xml` 顶部有三个 `parent=""` 的中间样式（`Widget.Gallery` / `TextAppearance.Gallery` / `ShapeAppearance.Gallery`）。Android 点号命名隐含继承——`Widget.Gallery.Toolbar` 会被当作 `Widget.Gallery` 的子样式，而框架没有 `Widget` 基础样式，缺了补齐就会 AAPT 报 `resource style/Widget not found`。加新命名空间时照抄这个结构。
- 命名：控件/容器用 `Widget.Gallery.<控件>`，纯文本语义用 `TextAppearance.Gallery.<层级>`。

**工具条有三类，别混用**：

| 样式 | 背景 | 用在哪 |
|---|---|---|
| `Widget.Gallery.Toolbar` | **透明**（透出下层 chrome 底色） | 仅「标题栏作为覆盖层叠在铺满内容之上」的页面：照片页、相册详情页（上划时换渐变遮罩） |
| `Widget.Gallery.Toolbar.Surface` | 不透明内容底色 | 其余页面：查看器、地图页等（内容不铺到标题栏下，必须自己有不透明底，否则透出内容或 GL 清屏色） |
| `Widget.Gallery.Toolbar.Media` | 恒定半透明深底 + 白字/白图标 | 视频播放器（压在任意视频画面上） |

树 elevation 均为 0（`elevation` 是 Material 属性、不是 `android:elevation`）：这是**照片页/详情页里「遮罩必须声明在 toolbar 之前」**的原因——FrameLayout 完全按声明顺序绘制，顺序颠倒会让白字被同一层渐变乘成同灰度（真机实测上划后同为 115）。相关注释同时写在两处布局里。

其余样式分组：

- 容器：`Widget.Gallery.RootSurface`（内容底色，全屏页根布局）、`Widget.Gallery.ChromeSurface`（chrome 底色，标题栏/状态栏底条透出的那层）、`Widget.Gallery.ViewerBar`（查看器栏，不透明内容底）、`Widget.Gallery.ViewerIcon`；
- 媒体层：`Widget.Gallery.MediaControl`（圆钮底）、`MediaControlsBar`（控件条底）、`MediaSeekBar`（进度条恒定白系）、`MediaIcon`；
- 文本：`TextAppearance.Gallery.Title/Body/Caption/OnScrim`；相册：`AlbumName/AlbumCount/AlbumSectionHeader`；main：`PermissionTitle/PermissionMessage/PrivacyTitle/PrivacyBody`；
- 其他：`ShapeAppearance.Gallery.Cover`（封面圆角 8dp）、`Widget.Gallery.NavCard`（胶囊卡片：半透明底 + 34dp 圆角 + 10dp elevation）、`NavIcon` / `NavLabel`（引用 ColorStateList）、`PermissionOverlay`、`PrivacyRoot`；
- 多选动作栏：`Widget.Gallery.SelectionCapsule`（照片页/相册详情页的**悬浮胶囊**：`bg_selection_capsule` 32dp 圆角 + 12dp elevation + 8dp 左右内边距；进入时宽度由 64dp 圆动画到 304dp，由 `SelectionActionBar` 控制）、`Widget.Gallery.SelectionActionBar`（最近删除页的整行两动作栏，仍用不透明底）、`SelectionActionIcon` / `SelectionActionLabel`（图标/文字，两处共用）；
- 对话框（`values/styles_dialog.xml`）：`Theme.Gallery.Dialog`（独立 Dialog 窗口主题：`Theme.Material3.DayNight.Dialog` + 透明窗口 + 品牌色映射）、`TextAppearance.Gallery.DialogTitle/DialogBody/DialogListItem`、`Widget.Gallery.DialogButton(.Primary/.Secondary)`（胶囊按钮，参考隐私页「同意/退出」）、`Widget.Gallery.DialogInput`（圆角 16dp 输入框）；配套 drawable `bg_gallery_dialog`（28dp 圆角 surface 卡）。

形状/圆角资源：查看器面板 `bg_viewer_sheet`、地图卡片 `bg_viewer_map_card`、导航项选中底 `bg_nav_item` 等 drawable 同受本规范约束（颜色引用色板/状态列表）。

## 4. 代码取色（`ThemeUtils`）

所有需要 `@ColorInt` 的地方经 `ThemeUtils`。提供的方法（按分组）：

| 组 | 方法 |
|---|---|
| 文字 | `textPrimary` / `textSecondary` / `textOnScrim` |
| 容器 | `surfaceChrome` / `surfaceContent` / `surfaceSheet` / `surfacePlaceholder` / `surfaceNavCard` |
| 图标 | `iconOnSurface` / `iconOnScrim` |
| 分隔 | `dividerOnSurface` |
| 品牌 | `brandPrimary` / `brandOnPrimary` |
| 媒体层 | `mediaScrim` / `mediaControlBg` / `mediaPlaceholderLight` / `mediaPlaceholderDark` |
| 其他 | `transparent()`、`isNightMode`、`isLightColor`、`applySystemBarAppearance(activity, background)`、`resolve`（底层入口） |

- `isLightColor`：sRGB 近似相对亮度，半透明色与 alpha 加权（把「透出下层」考虑进去），阈值与 Material 一致；`applySystemBarAppearance` 据此设置系统栏图标明暗——**系统栏图标切换与底色变化必须同时做**（标题栏上划转深色遮罩时，状态栏图标要同步转浅色）。
- `isNightMode` 取自 `uiMode` 配置位，与 `values-night` 资源限定符同一依据，不会出现判断与生效不一致。
- **新增颜色流程**：`values/colors.xml` 与 `values-night/colors.xml` **同时**加同名条目 → 需要代码取用时在 `ThemeUtils` 加对应方法 → 跑 §7 脚本。

## 5. 渐变遮罩（`ToolbarScrimDrawable`）

- 单段线性渐变：顶部约 55% 黑（alpha `0x8C`）→ 底部全透明；覆盖高度 = 状态栏 + 标题栏。
- 用代码画而不是 `shape` 资源：高度依赖设备状态栏高度，写死会错位。
- 55% 是实测结论：更深的起点（如 80%）会被看成一条生硬的黑带。
- 遮罩类「观感 vs 布局」的排查约定写在类注释里，验证方法见 [界面与交互 §16](ui-guide.md) 与 [验证方法与判据](verification.md)。

## 6. 有意恒定的颜色（不跟随主题）

判据：**画在任意用户内容（照片/视频）之上、背景不可知**，或**表达「观看模式」而非系统深浅色**。这些颜色不接入色板自动切换，但仍在 `colors.xml` 成对占位（值相同），避免散落成字面量：

- 媒体观看层：`gallery_media_scrim / control_bg / on_scrim / progress_track`（播放器与查看器沉浸，媒体场景按惯例恒定深色）。
- 查看器占位：`gallery_media_placeholder_light / _dark` —— 表达的是「当前用亮底还是深底观看」（由 `lightBackground` 驱动），与系统深浅色是**正交**维度；两态都要能在任何系统模式下工作。
- 查看器角标（`BadgeCache` 的播放图标）与 `MapMarkers.dot` 的标记色：画在用户图片之上，恒定色有实际理由（代码注释里也有说明）。`MapMarkers` 用固定蓝色而非品牌色是历史选择。
- `ToolbarScrimDrawable` 的 `0x8C`：见 §5（深度是观感参数，写在与高度同源的代码里）。
- 功能性字面量（不构成色板条目，与主题无关）：`ViewerActivity` 退出沉浸时根视图被重置为 `Color.WHITE`——该层位于 GL 画面之下、不可见（窗口层背景已按主题恢复为 `surfaceContent`）；`PrivacyActivity` 的 `highlightColor = TRANSPARENT`（关闭点击高亮）；`VideoPlayerActivity` 的窗口透明背景（半透明控件栏透出视频的前提，见 [界面与交互 §14](ui-guide.md)）。「把背景交给下层」的场合用 `ThemeUtils.transparent()` 表达。

判断「该不该接主题」的方法：问一句「这个颜色下面会不会出现用户照片/视频，或它是否在表达观看模式」。是 → 恒定；否 → 必须进色板。

## 7. 校验（`scripts/check-color-pairs.py`）

```
python scripts/check-color-pairs.py    # 校验，失败返回非 0
```

脚本对 `main` 与 `main-ui` 两个模块分别检查：

1. `values/colors.xml` 与 `values-night/colors.xml` 的**名字集合**一致——缺 night 定义会在深色下回退浅色值（白块/文字不可见）；night 多出的名字通常是拼写错误；
2. 全仓 `res/`+`java/`（`.xml`/`.kt`/`.java`）里所有 `@color/xxx` 引用都有定义（改名未收尾的兜底）；
3. `res/color/` 下的 ColorStateList 本身不要求 night 版（文件即资源名，计入已定义集合；其内部引用的状态色各自成对即可）。

通过时打印 `OK —— N 个颜色全部成对定义，且无悬空引用。`。改动颜色后必须跑一遍；CI/发布前的完整检查清单见 [验证方法与判据](verification.md)。

## 8. 历史背景

2026-10-06 做过一次全量颜色规范化：「深色模式只改了一半」的根因（图片加载器/GL 占位色写字面量、`main` 模块 9 个颜色全缺 night 版、布局里直接写颜色）与逐条修法记录在 [开发史 §颜色规范化](history.md)。
