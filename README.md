# Gallery（相册）

Android 相册应用：照片宫格、相册分类、大图查看器、视频播放、动态照片长按播放。

缩略图宫格、大图查看、视频渲染三处**基于 `GLSurfaceView` 自绘**（shader/纹理逐帧绘制，而非 View/RecyclerView 组合）；其余界面使用 androidx/material（`RecyclerView`、`ViewPager2`、`MaterialCardView` 胶囊导航、`MaterialToolbar`）与百度地图 `TextureMapView`——即并非“不引入任何三方 UI 组件”。数据层在 MediaStore 之上自建 `ContentProvider`；视频使用 `MediaPlayer + SurfaceTexture(OES)`，硬解优先、无拷贝。

## 功能

- **隐私政策同意页**：首次启动显示（**全屏页，非弹窗**；结构与间距对齐系统应用首启页：应用标识 + 欢迎说明 + 权限逐条说明 + 「隐私」标识 + 可点政策链接 + 底部「退出 / 同意」）；未同意不进入主界面、不初始化任何三方 SDK
- **照片**：全量图片/视频按拍摄时间倒序宫格，滚动分页加载。宫格**铺满整屏**（缩略图可画到系统导航栏区域）；静止时标题栏为不透明白底，上划到缩略图来到标题栏下方后，标题栏转为**黑色渐变遮罩**（上深下透明）并露出下方缩略图
- **相册**：常用（全部、相机、视频、截屏、录屏、动态照片、收藏、全景）+ 更多（三方应用、Pictures 子目录），空相册自动隐藏；本页顶部保持原有的不透明白底标题栏
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
| `GlThumbnailGridView` | GL 宫格（`OverScroller` 自绘、异步解码 + LRU 纹理缓存、仅对可见行发请求；无优先级队列/离开取消）。可见行按**每行与视口求交**判定，不做整除反推（后者会漏首行，见 [docs/development-plan.md](docs/development-plan.md) §10）。首页「照片」页与相册详情页均为**铺满式**布局（缩略图可画到系统导航栏区域，标题栏为覆盖层，上划后转黑色渐变遮罩） |
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
adb install -r main/build/outputs/apk/debug/SenkGallery-Debug.apk
```

## APK 产物与命名

`assembleDebug` / `assembleRelease` 直接产出改名后的 APK（不再有 `main-*.apk`）：

| 变体 | 产物 |
|---|---|
| debug | `main/build/outputs/apk/debug/SenkGallery-Debug.apk` |
| release | `main/build/outputs/apk/release/SenkGallery-Release.apk`<br>（未配置签名时同名，内容为未签名包） |

改名用的是 AGP **公开**的 Variant API —— `VariantOutput.outputFileName`（`Property<String>`，AGP 源码注释：*"It is safe to modify it once you need custom artifact name"*）：

```groovy
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { it.outputFileName.set(apkDisplayNames[variant.name]) }
    }
}
```

AGP 会把新名字贯彻到所有下游产物，实测同步生效的有：APK 本体、`output-metadata.json` 的 `outputFile`（Android Studio 靠它定位产物）、以及 release 的 `baselineProfiles/*/SenkGallery-Release.dm`。因此 Studio 的 Run/Debug 与 `installDebug`/`installRelease` 照常工作。

**改文件名 / 加新变体**只需编辑 `main/build.gradle` 的 `apkDisplayNames` 映射。

> 注意：老写法 `applicationVariants.all { outputFileName = ... }` 已随老 Variant API 在 AGP 8 移除，以上是 8.0+ / 9.x 的现行写法。若日后启用 **ABI 拆分**，一个变体会产生多个 output，固定文件名会互相覆盖——`main/build.gradle` 里有显式拦截（ABI 拆分与 `ndk.abiFilters` 本就互斥，AGP 会直接报 `Conflicting configuration`）。

## Release 签名

**签名口令不进版本库**：`main/build.gradle` 从 `local.properties`（已被 `.gitignore` 忽略）读取四项配置，模板见 [local.properties.example](local.properties.example)：

```properties
RELEASE_STORE_FILE=C\:\\Users\\you\\.android\\release.keystore
RELEASE_STORE_PASSWORD=
RELEASE_KEY_ALIAS=
RELEASE_KEY_PASSWORD=
```

```bash
./gradlew :main:assembleRelease
# 两种情况下产物都叫 main/build/outputs/apk/release/SenkGallery-Release.apk
#   已配置签名 -> 已签名包
#   未配置签名 -> 未签名包（不影响他人 clone 后构建）
```

**四项缺一不可**，任一为空即视为未配置并产出未签名包——这样开源仓库在没有密钥的情况下依然能构建。

生成新密钥（有效期 25 年，RSA 2048）与查看 SHA1（申请百度 AK 需要，AK 绑定「包名 + 签名 SHA1」）：

```bash
keytool -genkeypair -v -keystore "%USERPROFILE%\.android\release.keystore" ^
  -alias gallery -keyalg RSA -keysize 2048 -validity 9125 -storetype PKCS12
keytool -list -v -keystore "%USERPROFILE%\.android\release.keystore" -alias gallery
```

> ⚠️ 密钥文件与口令一旦丢失，**无法为已发布的同包名应用提供升级**（签名不一致会导致覆盖安装失败）。请单独备份密钥文件与口令。

## 百度地图 SDK 与 AK 配置

**SDK 从 Maven Central 引入**（`com.baidu.lbsyun`），无需本地 AAR/so，也无需 `jniLibs.srcDir`。注意**三个 Artifact 缺一不可**——它们各自只覆盖一部分 API：

| Artifact | 提供 |
|---|---|
| `BaiduMapSDK_Map:8.2.0` | 地图渲染（`TextureMapView`/`BaiduMap`/`MarkerOptions`），含 4 个 ABI 的 `.so`；经 `base`→`common` 传递引入 |
| `BaiduMapSDK_Search:8.2.0` | `GeoCoder` 逆地理编码 |
| `BaiduMapSDK_Util:8.2.0` | `CoordinateConverter`（WGS-84 → BD09LL） |

版本集中声明在 [gradle/libs.versions.toml](gradle/libs.versions.toml) 的 `baiduLbs`。**只声明 `Map` 会编译失败**（缺 `GeoCoder`/`CoordinateConverter` 等 20 余处引用）。

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
- 打包 ABI **按当前连接的设备自动决定**（`main/build.gradle` 的 `ndk.abiFilters`，探测逻辑在根 `build.gradle`）：
  - **插了设备** → 取该设备的 `ro.product.cpu.abilist`，APK 可直接装到它上面。x86_64 模拟器打包 `x86_64` + `arm64-v8a`，debug ≈58.4MB
  - **没插设备** → 退回 `arm64-v8a`（现代真机唯一需要的 ABI），debug ≈38.7MB / release ≈35.9MB；相比全打包 ≈90MB 省一半以上
  - 多设备时**真机优先于模拟器**；`offline`/`unauthorized` 状态的设备会被忽略
  - 手动覆盖：`-Pgallery.abiFilters=x86_64`（逗号分隔）、`-Pgallery.abiFilters=none` 关闭过滤（全打包 ≈90MB）、`-Pgallery.abiAuto=false` 关闭自动探测恒用 `arm64-v8a`、`-Pgallery.abiDeviceSerial=<serial>` 指定用哪台设备
  - 探测只在**配置阶段**发生且结果计入 configuration cache，因此插拔设备会自动触发重新探测（不会命中上一台设备的缓存）
  - adb 位置默认由 `local.properties` 的 `sdk.dir` 推导，也可用 `-Pgallery.adb=<path>` 或环境变量 `ANDROID_SDK_ROOT` 指定；找不到 adb 时同样退回 `arm64-v8a`，不会让构建失败

## 验证

构建、单元测试、lint 与真机手测的具体结论见 [docs/development-plan.md](docs/development-plan.md) §10「设备适配记录」——该节记录**可复现的技术结论与缺陷根因**，不含具体设备标识与位置信息。

现场的构建日志、lint 报告与真机截图归档在本机 `verification-reports/` 目录（**该目录已在 `.gitignore` 中排除，不进版本库**），重新生成方式见其中 `README.md`。

## 许可证

本项目**自有代码与资源**以 [Apache License 2.0](LICENSE) 授权，版权归 `Copyright 2026 SENK001`。

**第三方内容不适用该授权**，完整清单与声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，其中两点需要特别留意：

1. **百度地图 SDK 是专有软件**：它由 Gradle 在构建时从 Maven Central 下载（不存放在本仓库），本项目**不对其主张任何授权，也不对其再许可**。使用前须自行遵守[百度地图开放平台服务条款](https://lbsyun.baidu.com/docs/pcsa?title=law/open/law)并自备 API Key。其 POM 元数据虽声明 Apache-2.0，但该声明的效力需你自行核验。
2. **Material Design Icons**：项目中有 17 个矢量图标是从 [google/material-design-icons](https://github.com/google/material-design-icons) **逐字节复制**的路径数据（非仅样式参考），按 Apache-2.0 保留 Google 版权声明，对应关系见 `THIRD_PARTY_NOTICES.md` §1。

> 本仓库历史上曾包含百度地图 SDK 二进制（AAR + 4 个 ABI 的 so，共 75.5 MB），已于提交 `9fdf1a7` 中连同全部 Git 历史移除，当前仓库不含任何第三方二进制。
