# Gallery（相册）

Android 相册应用：照片宫格、相册分类、大图查看器、视频播放、动态照片长按播放。

缩略图宫格、大图查看、视频渲染**全部基于 `GLSurfaceView` 自绘**，不引入任何三方 UI 组件；数据层在 MediaStore 之上自建 `ContentProvider`；视频使用 `MediaPlayer + SurfaceTexture(OES)`，硬解优先、无拷贝。

## 功能

- **照片**：全量图片/视频按拍摄时间倒序宫格，滚动分页加载
- **相册**：常用（全部、相机、视频、截屏、录屏、动态照片、收藏、全景）+ 更多（三方应用、Pictures 子目录），空相册自动隐藏
- **查看器**：左右翻页（拖动跟手 + fling）、双击/双指缩放、单击进出沉浸模式；分享 / 收藏 / 详情（EXIF）；前后页纹理预加载
- **视频播放**：播放/暂停、拖动进度条、横竖屏切换；标题栏与控制栏播放中 5s 自动隐藏、暂停时保持显示；播放中屏幕常亮
- **动态照片**：长按原位播放文件尾部内嵌 MP4（`Os.pread` 直读，不拷贝），松手/翻页/退后台恢复静图
- 收藏写回 MediaStore（Android 12+ 走 `RecoverableSecurityException` 授权流程）

## 模块结构

```
Gallery/
├─ main/         应用壳：权限流程、底部胶囊导航、打包入口
├─ main-ui/      UI 与 GL 组件：宫格、查看器、视频播放、播放管理器
├─ core-data/    Java bean、GalleryProvider(ContentProvider)、MediaStore 抓取、XMP 解析
├─ common-util/  位图/尺寸/日期/文件大小等工具
└─ docs/         开发计划与设备适配记录
```

核心 UI 组件（`main-ui/.../ui/gl`）：

| 组件 | 说明 |
|---|---|
| `GlThumbnailGridView` | GL 宫格（`OverScroller` 自绘、异步解码 + LRU 纹理缓存、可见优先/离开取消） |
| `GlImageViewer` | 大图查看器（翻页/缩放/沉浸，`ImageDecoder` 采样，EXIF 方向） |
| `GlVideoView` | GL 视频渲染（aspect-fit，委托 `VideoPlayManager`） |
| `VideoPlayManager` | `MediaPlayer + SurfaceTexture(OES)` 状态机；支持 `File` / `Embedded`（动态照片内嵌 MP4）两种源，播放器页与查看器共用 |
| `EmbeddedVideoDataSource` | `MediaDataSource` 实现，`Os.pread` 读取文件尾部内嵌视频范围 |

## 构建与运行

要求：Android SDK 36.1、JDK 17+（Gradle toolchain 自动解析）、`local.properties` 指向 SDK。

```bash
./gradlew :main:assembleDebug
./gradlew :core-data:testDebugUnitTest
adb install -r main/build/outputs/apk/debug/main-debug.apk
```

## 技术要点

- **零三方 UI**：宫格/查看器/视频全部 GL 自绘（shader/纹理），`RENDERMODE_WHEN_DIRTY` + 脏区域请求重绘
- **视频方向**：旋转由 MediaPlayer 处理（送入 Surface 的帧已旋转），UV 采用 GL 常规约定（v=0 在图像底部）
- **数据接口**：UI 只通过 `ContentResolver` 访问 `content://com.senk.gallery.data.provider/...`（media / albums / exif / 更新收藏）
- **XMP**：`MediaStore.MediaColumns.XMP` 列按 BLOB 解析，判定动态照片（`Container:Item` video/，兜底 MicroVideoOffset）与全景（`GPano:ProjectionType`）
- **沉浸体验**：查看器/播放器内容区尺寸恒定，标题栏与底栏为覆盖层，进出沉浸像素级零跳变

详细设计、实施顺序与设备适配问题（含已修复 bug 的根因记录）见 [docs/development-plan.md](docs/development-plan.md)。

## 兼容性

- minSdk 36 / targetSdk 36（Android 16）
- 已在 MIUI（Android 16）真机验证
