# Gallery（相册）

Android 相册应用：照片宫格、相册分类、大图查看器、视频播放、动态照片长按播放、多选操作、选图器与外部调用。

**缩略图宫格是标准 `RecyclerView` + 每格 View**（可被无障碍服务逐格读取；视频时长/动态照片角标与多选选择框都是普通 View）；**大图查看器与视频渲染**仍基于 `GLSurfaceView` 自绘（shader/纹理逐帧绘制）。其余界面使用 androidx/material（`RecyclerView`、`ViewPager2`、胶囊导航、`MaterialToolbar`、`TabLayout`）与百度地图 `TextureMapView`。数据层在 MediaStore 之上自建 `ContentProvider`，UI 只经 `ContentResolver` 访问；对外另有选图结果的只读代理 Provider 与 `external-api` 契约模块。

## 功能概览

- **隐私政策同意页**：首次启动全屏显示（非弹窗）；未同意不进入主界面、不初始化任何三方 SDK
- **照片**：全量图片/视频按拍摄时间倒序宫格，滚动分页加载；宫格铺满整屏，标题栏上划后转为**黑色渐变遮罩**
- **相册**：常用（全部、相机、视频、截屏、录屏、动态照片、收藏、全景）+ 我的相册（自建）+ 更多（三方应用、`Pictures` 子目录），空相册自动隐藏
- **相册详情**：与照片页一致的铺满式宫格
- **多选模式**：标题栏「多选」按钮或长按格子进入；格子右上角选择框、标题「已选择 N 项」、全选；底部动作栏 分享 / 收藏 / 添加到 / 删除（删除走系统「最近删除」授权）
- **自建相册**：应用内逻辑集合（不复制文件），相册页可新建/删除；多选「添加到」可加入自建相册，或复制到相机 / 识别到的文件夹相册
- **选图页**：照片 / 相册两个 tab 切换来源，支持单选、多选、按相册分类选，可被外部应用调用
- **查看器**：左右翻页、双击/双指缩放、单击进出沉浸；分享 / 收藏（应用内自建收藏库，不修改系统媒体库）/ 详情（EXIF 与地图卡片）；前后页纹理预加载
- **视频播放**：播放/暂停、拖动进度、横竖屏切换；控制栏播放中自动隐藏、暂停时保持显示；播放中屏幕常亮
- **地图**：EXIF 定位逆地理编码（百度）+ 高德/百度/腾讯/Google 导航跳转
- **动态照片**：长按原位播放文件尾部内嵌 MP4（直读，不拷贝），松手/翻页/退后台恢复静图
- **外部调用**：`PICK_IMAGE`（单选/多选选图）与 `VIEW_IMAGE`（外部大图），契约见 [外部调用 API](docs/external-api.md)

## 快速开始

要求：Android SDK **37.2**（compileSdk）、**JDK 21**（daemon 缺省自动下载）、`local.properties` 指向 SDK（Android Studio 自动生成）。minSdk / targetSdk 36，即设备需 **Android 16+**。

```bash
# 真机（arm64-v8a）；模拟器换成 :main:assembleX86_64Debug，安装路径改为对应的 x86_64/debug
./gradlew :main:assembleArm64Debug
adb install -r main/build/outputs/apk/arm64/debug/SenkGallery-Debug-arm64-v8a.apk
```

`:main:assembleDebug` 会一次构建全部 5 个 ABI 变体（arm64 / armv7 / x86 / x86_64 / universal）。

| 变体（各含 Debug / Release） | ABI | 产物（以 debug 为例） |
|---|---|---|
| `arm64` | `arm64-v8a` | `main/build/outputs/apk/arm64/debug/SenkGallery-Debug-arm64-v8a.apk` |
| `armv7` | `armeabi-v7a` | `main/build/outputs/apk/armv7/debug/SenkGallery-Debug-armeabi-v7a.apk` |
| `x86` | `x86` | `main/build/outputs/apk/x86/debug/SenkGallery-Debug-x86.apk` |
| `x86_64` | `x86_64` | `main/build/outputs/apk/x86_64/debug/SenkGallery-Debug-x86_64.apk` |
| `universal` | 4 个全打包 | `main/build/outputs/apk/universal/debug/SenkGallery-Debug-universal.apk` |

release 产物同构（`<flavor>/release/SenkGallery-Release-<ABI>.apk`）；未配置签名时为未签名包，文件名不变。

- 打包 ABI 由**构建变体**决定：`arm64` / `armv7` / `x86` / `x86_64` 各只打包一个 ABI（debug ≈33–40MB），`universal` 不做过滤、4 个 ABI 全打包（debug ≈90MB）。
- 百度地图 AK 与 release 签名都从 `local.properties` 注入（模板见 [local.properties.example](local.properties.example)）：缺失时地图功能不可用 / 产出未签名包，**不影响构建与其余功能**。

完整细节（工具链版本、ABI 变体、签名与 keytool、依赖清单）见 [构建与发布](docs/build-and-release.md)。

## 文档

全部工程文档在 [`docs/`](docs/README.md)，索引与「改动 → 需同步文档」维护约定见 [docs/README.md](docs/README.md)：

| 文档 | 内容 |
|---|---|
| [architecture.md](docs/architecture.md) | 模块划分（含 `external-api`）、依赖方向、启动流、关键设计决策 |
| [data-layer.md](docs/data-layer.md) | Provider 契约、MediaStore 抓取、相册归类、自建相册与复制写入、EXIF/XMP |
| [ui-guide.md](docs/ui-guide.md) | 页面结构、insets 分配、权限流程、手势与状态、多选/选图/添加到 |
| [gl-rendering.md](docs/gl-rendering.md) | 查看器/视频的 GL 自绘绘制、几何与异步管线 |
| [theme.md](docs/theme.md) | 色板、styles 约定、`ThemeUtils`、颜色校验 |
| [external-api.md](docs/external-api.md) | 外部调用契约：选图/大图 action、extras、结果与代理 URI 授权 |
| [build-and-release.md](docs/build-and-release.md) | 工具链、ABI 变体、签名、AK、产物命名、依赖 |
| [verification.md](docs/verification.md) | 门禁命令、通过线、真机流程、基线数据 |
| [history.md](docs/history.md) | 日期化实测结论与缺陷根因 |
| [backlog.md](docs/backlog.md) | 待办、验证盲区与明确不做 |

## 许可

本项目自有代码与资源以 [Apache License 2.0](LICENSE) 授权，版权归 `Copyright 2026 SENK001`。

第三方内容不适用该授权，完整清单与声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。两点需要留意：**百度地图 SDK** 为专有软件，构建时从 Maven Central 下载、不存放于本仓库，使用需自备 API Key 并遵守其服务条款；**Material Design Icons** 中 17 个矢量图标复制自 [google/material-design-icons](https://github.com/google/material-design-icons)（Apache-2.0，保留 Google 版权声明）。
