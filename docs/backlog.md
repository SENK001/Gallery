# 暂缓项与已知限制

本文件是「未实现 / 未覆盖 / 明确不做」的**唯一收口**。计划做功能时从这里取；记录新限制时加到这里，不要再散落到其它文档。

## 1. 功能待办

| 项 | 内容与依据 | 备注 |
|---|---|---|
| **最近删除** | 查询 `IS_TRASHED = 1`；恢复/彻底删除用 `MediaStore.createTrashRequest / createDeleteRequest`，系统 30 天自动清理。Provider 已预留 `MediaSet.TYPE_RECENTLY_DELETED`，UI 入口隐藏（`AlbumsFragment` 有注释占位） | 依赖 Provider 新增写操作 |
| **自建相册** | 目录固定 `Pictures/<相册名>`；新建/重命名/删除相册、添加/移出照片；写入用 `RELATIVE_PATH` 更新或 `createWriteRequest`。Provider 的 `folder` 类型即目录相册基础 | 依赖 Provider 新增写操作 |
| **音频焦点** | `VideoPlayManager` 目前只设 `setAudioAttributes(USAGE_MEDIA/CONTENT_TYPE_MOVIE)`，未 `requestAudioFocus`：其它应用播放时不会自动压低/暂停 | 车载/后台共存场景需要 |
| **XMP 文件回退** | XMP 列为空时**没有**回退读文件内嵌 XMP（exifinterface 1.3.7 无 MotionPhoto API，需自行正则解析）：XMP 空 ⇒ 判为非动态照片/非全景（全景另有尺寸兜底） | 影响特殊相机写库不全的场景 |
| **查看器按需高分解码** | 解码上限为 `min(GL_MAX_TEXTURE_SIZE, 视口最长边)`，放大超过约 1.2× 属于纹理放大 | 需要高倍放大看细节时做 |
| **详情面板把手拖动收起** | `sheet_handle` 只存在于布局，无 Kotlin 绑定；收起目前靠下划内容区/单击/返回键 | 小交互补齐 |
| **缩略图请求优先级与离开取消** | `ThumbnailLoader` 无优先级队列；滚出屏幕的请求仍会跑完并进缓存，仅在 `shutdown` 时批量取消 | 大数据量下的优化项 |
| **MediaCodec 自建管线** | 若需「强制硬解/禁用软解」精确控制：`MediaExtractor` 选轨 → `MediaCodecList.isHardwareAccelerated()` 选码 → OES 渲染 + `MediaCodec`/`AudioTrack` 音画同步，暴露 `allowSoftwareDecoder` 开关 | 当前 `MediaPlayer` 已默认硬解优先，无公开 API 可强制 |
| **搜索 / 多选分享 / 编辑** | 产品功能扩展 | — |
| **隐私政策正式文案** | 当前是应用内固定文案；上架前需替换为真实生效的政策文本/链接 | 合规阻塞项 |

Provider 的 `insert` / `delete` 抛 `UnsupportedOperationException`、`call()` 未实现，是「只读 + 收藏写回」的**有意设计**；做上述相册/最近删除功能时需要一并扩展。

## 2. 工程与验证盲区（勿当作通过）

| 项 | 现状 |
|---|---|
| 自动化测试 | 无仪器测试/UI 自动化；UI 行为靠真机手测 + 截图脚本，方法见 [验证方法与判据](verification.md) |
| 设备覆盖 | 仅 Redmi / Android 16 真机（1280×2772）+ Android 16 x86_64 模拟器；第二台 ROM、平板/折叠屏、`fontScale > 1`、横屏未验证 |
| 深色模式 | 应用主题整体已适配；**地图页除外**——百度 `TextureMapView` 底图不跟随 `uiMode`，写死的工具栏/底卡无法单独改深色（会出现「深色栏压浅色地图」），需与底图一起处理 |
| 查看器沉浸态 | 深色底与主题正交，当前只确认了非沉浸态的主题表现 |
| 收藏写回 | 仅验证「允许」授权路径；拒绝授权后的回退分支未单独验证 |
| ABI 变体 | 10 个变体均已离线段构建并核对产物名 / `lib/` 内容 / 体积（2026-10-17），但未做安装验证（改动时无设备/模拟器连接）、Android Studio Build Variants 图形流程未复验；`universal` 内多 ABI 同时存在时的 16KB page size 对齐未验证 |
| 构建平台 | 只在 Windows 命令行验证过（`adb install`；ABI 变体改造后各 `install<变体>` 任务未复验）；Android Studio 图形 Run 流程、其它操作系统、屏幕密度拆分下多 output 的命名未实测 |
| GL 画面 | 不在 `uiautomator` 视图树中，无自动化断言手段，只能截图逐像素判断 |

## 3. 明确不做（避免误当缺陷）

| 项 | 原因 |
|---|---|
| MIUI 首次 scheme 跳转的「是否允许打开」确认框 | 系统安全行为（`com.miui.securitycenter`），应用侧不绕过 |
| 查看器/详情面板展示经纬度 | 产品选择：与系统相册一致，只给地址 |
| 用 View/RecyclerView 重写宫格/查看器/视频 | 三处 GL 自绘是既定架构，见 [GL 自绘渲染](gl-rendering.md) |
| 地图卡片内嵌大图/街景等 BmSDK 能力 | Maven 版 SDK 缺 `BmBaseView`/`BmImageView` 等类（初始化时 SDK 内部捕获降级），当前功能用不到；如启用需先评估 `NoClassDefFoundError` |
