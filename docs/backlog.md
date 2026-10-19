# 暂缓项与已知限制

本文件是「未实现 / 未覆盖 / 明确不做」的**唯一收口**。计划做功能时从这里取；记录新限制时加到这里，不要再散落到其它文档。

## 1. 功能待办

| 项 | 内容与依据 | 备注 |
|---|---|---|
| **自建相册增强** | `CustomAlbumStore` 已有 `rename`、`setCover` 与成员增删 API；UI 已支持新建/删除/加入/从相册详情「添加」页移除成员（2026-10-20）；**重命名、手动指定封面仍未做** | 另：成员是逻辑 id，系统删照片后不主动 prune（相册内容查询会跳过，`item_count` 可能偏大） |
| **音频焦点** | `VideoPlayManager` 目前只设 `setAudioAttributes(USAGE_MEDIA/CONTENT_TYPE_MOVIE)`，未 `requestAudioFocus`：其它应用播放时不会自动压低/暂停 | 车载/后台共存场景需要 |
| **XMP 文件回退** | XMP 列为空时**没有**回退读文件内嵌 XMP（exifinterface 1.3.7 无 MotionPhoto API，需自行正则解析）：XMP 空 ⇒ 判为非动态照片/非全景（全景另有尺寸兜底） | 影响特殊相机写库不全的场景 |
| **查看器按需高分解码** | 解码上限为 `min(GL_MAX_TEXTURE_SIZE, 视口最长边)`，放大超过约 1.2× 属于纹理放大 | 需要高倍放大看细节时做 |
| **详情面板把手拖动收起** | `sheet_handle` 只存在于布局，无 Kotlin 绑定；收起目前靠下划内容区/单击/返回键 | 小交互补齐 |
| **缩略图请求优先级与离开取消** | View 宫格的缩略图加载（`ImageViewThumbLoader`）有 LRU 缓存与 tag 防复用，但**没有优先级队列**；滚出屏幕的请求仍会跑完并进缓存（仅以 tag 校验结果是否仍适用） | 大数据量下的优化项 |
| **选图页媒体类型过滤** | 选图页目前同时展示图片+视频（与照片页一致），外部调用不能只请求图片或只请求视频（`GalleryExternal` 无 mediaType extra） | 外部集成常见需求 |
| **外部调用增强** | 当前返回临时授权的代理 URI，不保证长期可读；不支持批量查询/目录列举/缩略图；不支持 http(s) | 见 [外部调用 API](external-api.md) §9 |
| **MediaCodec 自建管线** | 若需「强制硬解/禁用软解」精确控制：`MediaExtractor` 选轨 → `MediaCodecList.isHardwareAccelerated()` 选码 → OES 渲染 + `MediaCodec`/`AudioTrack` 音画同步，暴露 `allowSoftwareDecoder` 开关 | 当前 `MediaPlayer` 已默认硬解优先，无公开 API 可强制 |
| **搜索 / 编辑** | 产品功能扩展（多选分享已实现，见 §3） | — |
| **隐私政策正式文案** | 当前是应用内固定文案；上架前需替换为真实生效的政策文本/链接 | 合规阻塞项 |

Provider 的 `insert` / `delete` 抛 `UnsupportedOperationException`、`call()` 未实现，是「只读查询 + 收藏（应用内自建库）」的**有意设计**；自建相册/收藏/复制写入分别走 `CustomAlbumStore` / `FavoriteStore` / `MediaWriter`（见 [数据层 §6](data-layer.md)）。

## 2. 工程与验证盲区（勿当作通过）

| 项 | 现状 |
|---|---|
| 自动化测试 | 单元测试覆盖：`XmpParser`（8）、`CoordConverter`（4）、`SelectionState`（7）、4 个模板占位 = 23；**无仪器测试/UI 自动化**，UI 行为靠真机手测 + 截图脚本（方法见 [验证方法与判据](verification.md)） |
| 设备覆盖 | 仅 Redmi / Android 16 真机（1280×2772）+ Android 16 x86_64 模拟器；第二台 ROM、平板/折叠屏、`fontScale > 1`、横屏未验证 |
| 深色模式 | 应用主题整体已适配（含宫格/多选/选图/添加到，2026-10-19 真机截图抽查）；**地图页除外**——百度 `TextureMapView` 底图不跟随 `uiMode`，写死的工具栏/底卡无法单独改深色 |
| 查看器沉浸态 | 深色底与主题正交，当前只确认了非沉浸态的主题表现 |
| 收藏（自建库） | 已真机验证：多选批量收藏/取消、查看器爱心联动、收藏相册出现/消失；删除标记（`is_trashed`）流程：收藏 → 删除后「收藏」相册隐藏（无灰封面/不空白）、恢复后重新出现且计数恢复、彻底删除后收藏行移除；其它应用/系统删除由 `reconcileFavoriteFlags` 在相册页刷新时校正。系统媒体库 `is_favorite` 不再写入（有意，见 §3）；旧版本写入系统媒体库的收藏标记**未迁移/导入** |
| ABI 变体 | 10 个变体均已离线段构建并核对产物名 / `lib/` 内容 / 体积（2026-10-17），但未做安装验证、Studio Build Variants 图形流程未复验；`universal` 内多 ABI 同时存在时的 16KB page size 对齐未验证 |
| 构建平台 | 只在 Windows 命令行验证过（`adb install`；ABI 变体改造后各 `install<变体>` 任务未复验）；Android Studio 图形 Run 流程、其它操作系统、屏幕密度拆分下多 output 的命名未实测 |
| GL 画面 | 查看器/视频不在 `uiautomator` 视图树中，无自动化断言手段，只能截图逐像素判断；**缩略图宫格已改为 View，可以出现在视图树里** |
| 外部调用 | 只在本应用内用 `main/src/debug/ExternalClientDebugActivity` 模拟调用方验证（选图结果转发 + 代理 URI 读取 64KB + 外部大图）；**真实第三方 APK 未验证**；`getCallingPackage()` 在非 `startActivityForResult` 场景为 null（回退 `launchedFromPackage`） |
| 添加到/复制写入 | 已在真机验证：逻辑加入自建相册、复制到嵌套目录相册（`Pictures/douyin/comment/`）、删除（系统"最近删除"）；大文件复制/磁盘满/取消等边界未压测 |
| 自建相册添加/管理 | 已真机验证：详情页标题栏 `[添加 +]`（仅自建相册）、选图页预选已有成员、跨数据源取消勾选 + 新勾选后按 diff 增删、封面条目被移除后清空封面；超过一整页（200 项）的相册未见真机数据（预选按相册 URI 全量拉取，逻辑上支持）；取消选图页、无变化场景已手测 |
| 最近删除 | 已真机验证：相册页**最底部入口**（更多之后、无封面、非空显示/清空后隐藏）、trashed 条目查询（需 `QUERY_ARG_MATCH_TRASHED=MATCH_ONLY`）、恢复 / 彻底删除 / 清空（均走系统授权）、查看器翻页；**系统 30 天自动清理未等待验证**（由 MediaStore 负责）；未验证大量条目分页性能 |

## 3. 明确不做（避免误当缺陷）

| 项 | 原因 |
|---|---|
| MIUI 首次 scheme 跳转的「是否允许打开」确认框 | 系统安全行为（`com.miui.securitycenter`），应用侧不绕过 |
| 查看器/详情面板展示经纬度 | 产品选择：与系统相册一致，只给地址 |
| 用 View/RecyclerView 重写**查看器/视频** | 这两处 GL 自绘是既定架构（缩略图宫格已于 2026-10-19 改为 View），见 [GL 自绘渲染](gl-rendering.md) |
| 地图卡片内嵌大图/街景等 BmSDK 能力 | Maven 版 SDK 缺 `BmBaseView`/`BmImageView` 等类（初始化时 SDK 内部捕获降级），当前功能用不到；如启用需先评估 `NoClassDefFoundError` |
| 外部大图模式的收藏/详情 | 外部 URI 不属于本应用媒体库（无 MediaStore id），EXIF/地图/收藏（需本应用媒体库 id）都不可用；只做浏览/分享/视频播放 |
| 自建相册复制文件 | 产品选择：自建相册是应用内逻辑集合（不产生重复文件）；需要"物理落盘"时用「添加到相机/文件夹相册」 |
| 收藏同步到系统媒体库 | 收藏是应用内自建库（`FavoriteStore`）：不写 `IS_FAVORITE`、系统相册/其它应用看不到本应用收藏；旧系统收藏标记不导入。原因：写回系统媒体库需要逐次系统授权（Android 12+），批量收藏体验差且曾出现失败（2026-10-19 用户反馈后改为自建库） |
