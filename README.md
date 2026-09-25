# Gallery（相册）

Android 相册应用：照片宫格、相册分类、大图查看器、视频播放、动态照片长按播放。

缩略图宫格、大图查看、视频渲染三处**基于 `GLSurfaceView` 自绘**（shader/纹理逐帧绘制，而非 View/RecyclerView 组合）；其余界面使用 androidx/material（`RecyclerView`、`ViewPager2`、`MaterialCardView` 胶囊导航、`MaterialToolbar`）与百度地图 `TextureMapView`——即并非“不引入任何三方 UI 组件”。数据层在 MediaStore 之上自建 `ContentProvider`；视频使用 `MediaPlayer + SurfaceTexture(OES)`，硬解优先、无拷贝。

## 功能

- **隐私政策同意页**：首次启动显示（**全屏页，非弹窗**；结构与间距对齐系统应用首启页：应用标识 + 欢迎说明 + 权限逐条说明 + 「隐私」标识 + 可点政策链接 + 底部「退出 / 同意」）；未同意不进入主界面、不初始化任何三方 SDK
- **照片**：全量图片/视频按拍摄时间倒序宫格，滚动分页加载
- **相册**：常用（全部、相机、视频、截屏、录屏、动态照片、收藏、全景）+ 更多（三方应用、Pictures 子目录），空相册自动隐藏
- **查看器**：左右翻页（拖动跟手 + fling）、双击/双指缩放、单击进出沉浸模式；分享 / 收藏 / 详情（EXIF）；前后页纹理预加载
- **视频播放**：播放/暂停、拖动进度条、横竖屏切换；标题栏与控制栏播放中 5s 自动隐藏、暂停时保持显示；播放中屏幕常亮
- **动态照片**：长按原位播放文件尾部内嵌 MP4（`Os.pread` 直读，不拷贝），松手/翻页/退后台恢复静图
- 收藏写回 MediaStore（Android 12+ 走 `RecoverableSecurityException` 授权流程）

## 模块结构

```
Gallery/
├─ main/         应用壳：隐私同意页、权限流程、底部胶囊导航、打包入口
├─ main-ui/      UI 与 GL 组件：宫格、查看器、视频播放、播放管理器
├─ core-data/    Java bean、GalleryProvider(ContentProvider)、MediaStore 抓取、XMP 解析
├─ common-util/  位图/尺寸/日期/文件大小等工具
└─ docs/         开发计划与设备适配记录
```

核心 UI 组件（`main-ui/.../ui/gl`）：

| 组件 | 说明 |
|---|---|
| `GlThumbnailGridView` | GL 宫格（`OverScroller` 自绘、异步解码 + LRU 纹理缓存、仅对可见行发请求；无优先级队列/离开取消） |
| `GlImageViewer` | 大图查看器（翻页/缩放/沉浸，`ImageDecoder` 采样且由它隐式应用 EXIF 方向，无手写方向矩阵） |
| `GlVideoView` | GL 视频渲染（aspect-fit，委托 `VideoPlayManager`） |
| `VideoPlayManager` | `MediaPlayer + SurfaceTexture(OES)` 状态机；支持 `File` / `Embedded`（动态照片内嵌 MP4）两种源，播放器页与查看器共用 |
| `EmbeddedVideoDataSource` | `MediaDataSource` 实现，`Os.pread` 读取文件尾部内嵌视频范围 |

## 构建与运行

要求：

- Android SDK Platform **37.2**（`compileSdk 37.2`，`targetSdk 36`）
- **JDK 21**：Gradle wrapper **9.4.1**，daemon JVM 由 `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=21` 指定，本机缺失时首次构建会自动下载
- `local.properties` 指向 SDK，并配置百度地图 AK（见下节）

```bash
./gradlew :main:assembleDebug
./gradlew :core-data:testDebugUnitTest
adb install -r main/build/outputs/apk/debug/main-debug.apk
```

## 百度地图 AK 配置

AK 不进版本库，从 `local.properties`（已被 `.gitignore` 忽略）读取，模板见仓库根 [local.properties.example](local.properties.example)：

```properties
BAIDU_MAP_API_KEY=你的AK
```

`main/build.gradle` 通过 `manifestPlaceholders.baiduMapApiKey` 注入，manifest 中 `com.baidu.lbsapi.API_KEY` 的 meta-data 值为 `${baiduMapApiKey}`（不再明文硬编码）。未配置时注入空串：**应用仍可编译运行**，仅地图卡片与逆地理编码不可用（SDK 初始化失败只记日志，不崩溃）。

## 技术要点

- **GL 自绘范围**：宫格/查看器/视频渲染为 GL 自绘（shader/纹理），`RENDERMODE_WHEN_DIRTY` + 脏区域请求重绘；导航、列表、工具条等使用 androidx/material 与百度地图 `TextureMapView`
- **视频方向**：旋转由 MediaPlayer 处理（送入 Surface 的帧已旋转），UV 采用 GL 常规约定（v=0 在图像底部）
- **数据接口**：UI 只通过 `ContentResolver` 访问 `content://com.senk.gallery.data.provider/...`（media / albums / exif / 更新收藏）
- **XMP**：`MediaStore.MediaColumns.XMP` 列按 BLOB 解析，判定动态照片（`Container:Item` video/，兜底 MicroVideoOffset）与全景（`GPano:ProjectionType`）
- **沉浸体验**：查看器/播放器内容区尺寸恒定，标题栏与底栏为覆盖层，进出沉浸像素级零跳变

详细设计、实施顺序与设备适配问题（含已修复 bug 的根因记录）见 [docs/development-plan.md](docs/development-plan.md)。

## 兼容性

- minSdk 36 / targetSdk 36 —— **仅支持 Android 16 及以上**；compileSdk 37.2
- 打包 ABI 仅 `arm64-v8a`（`main/build.gradle` 的 `ndk.abiFilters`）：百度地图 SDK 自带 4 个 ABI 的 so，全打包时 APK ≈90MB；只留 arm64-v8a 后 debug ≈38.7MB、release ≈35.9MB。需要 x86_64 模拟器调试时，临时把 `"x86_64"` 加回该行

## 真机验证

设备：Android 16 真机（代号已脱敏），Android 16 / SDK 36，HyperOS ROM 版本已脱敏，arm64-v8a only，屏幕尺寸已脱敏（2026-09-26 实测）

- **构建**：冷启动（`clean` + `--no-build-cache` + 杀 daemon）`:main:assembleDebug` 91/91 任务真实执行 53s，BUILD SUCCESSFUL；`:main:assembleRelease` BUILD SUCCESSFUL，产出 `main-release-unsigned.apk` 35.89MB
- **检查**：`:main:lintDebug` 0 error / 6 warning（`lintVitalRelease` = No issues found）；单元测试 16 个全绿（`XmpParserTest` 8 + `CoordConverterTest` 4 + 4 个模块模板测试）
- **手测**：照片 Tab / 相册 Tab / 相册详情 / 查看器 / 沉浸模式 / 上划详情面板 / EXIF / 地图卡片 / 逆地理编码 / 视频播放（控制栏、横屏）/ 动态照片长按播放 / 收藏写回 均正常，无崩溃
- **相册计数**：动态照片 12 项、全部 223 项、相机 195 项、视频 7 项、截屏 11 项、微信 8 项、QQ 3 项（证明 `XMP LIKE` 过滤 BLOB 列真机可用）
- **媒体库口径**：应用展示 **223 条 = 216 图片 + 7 视频**（与相册「全部 223 项」吻合），其 `datetaken` **全部非 NULL**；`MediaStore.Files` 原始行数为 1749（含非图片/视频文件），其中 1525 条 `datetaken` 为 NULL 的均为这些非媒体文件
- **排序**：`COALESCE(datetaken, date_modified*1000) DESC, _id DESC` 真机可用且分页稳定（同秒并列的跨页不稳定已由 `_id DESC` 稳定键修复；规则与 AOSP 依据见 [docs/development-plan.md](docs/development-plan.md) §10 设备适配记录）
- **逆地理编码**结果：`重庆市（地址已脱敏）`
- **隐私同意页**：首启显示（全屏页）；「退出」/返回键退出且不记录同意（再次启动仍显示）；点「用户协议与隐私政策」可查看政策正文；「同意」写入状态后进入权限流程与主界面；**第二次启动不再显示**；未同意时不会初始化百度地图 SDK
- **现场截图与完整报告**：本机 `verification-reports/`（**该目录不进版本库**，含 11 张真机截图与 `2026-09-26-device-verification.md`）；重新生成方式见其中 `README.md`
