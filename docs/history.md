# 开发史与设备适配记录

本文件按时间正序记录**长期有效的实测结论与根因**：每条的「背景 / 根因 / 修法 / 验证数据」都可在后续排查中复用。按日期实录，不用删除线；早期结论后来被推翻的，在该条内以「更正记录」子条目说明结论变化。

「未覆盖」项不在这里展开，统一汇总在 [暂缓项与已知限制](backlog.md)。

---

## 开发期设备适配记录（首轮真机验证前，按主题整理）

早期开发没有逐条记录日期，按主题保留；其中多条在 2026-09-26 真机复测中复核过。

### 数据层

**1. `MediaStore.MediaColumns.XMP` 是 BLOB**

- 根因：MediaProvider 把 XMP 以 BLOB 存储，直接 `Cursor.getString()` 抛 `Unable to convert BLOB to string`，**整条查询失败**。
- 修法：`MediaStoreFetcher.readXmp()` 按列类型读取（BLOB 按 UTF-8 转字符串）。
- 验证：2026-09-26 真机相册计数「动态照片 12 项」正常，证明 `XMP LIKE` 过滤 BLOB 列可用。

**2. 排序表达式的白名单与稳定键**

- 介质：`SORT_DATE_DESC = COALESCE(date_taken, date_modified * 1000) DESC, _id DESC`。
- **更正记录**：曾以为 MediaProvider 的严格校验「只允许列名 + ASC/DESC、不支持表达式」。真机复测（Git 版本对照 `MediaProvider.java:6053-6059` 与 `util/SQLiteTokenizer.java`）确认实际是**逐 token 白名单**：数字、括号、`*` 不产生 token；`COALESCE` 命中允许的函数表即放行；`datetaken` / `date_modified` 属允许列名。真机 `content query` 用 `COALESCE(datetaken, date_modified * 1000) DESC` 通过且顺序正确；把列名写成 Kotlin 常量名（`date_taken`、`no_such_column`）才报 `Invalid token`。
- 稳定次级键 `_id DESC`（防御性加固）：SQLite 对完全并列的排序键不保证返回顺序，配合 limit/offset 分页理论上可能重复/漏项（本库 216 张图片中有 2 组并列）。追加后并列组内顺序确定为「新在前」。**实测去掉该键后连续 4 次查询结果也一致**（本库未复现不稳定），保留属加固。
- 口径澄清：`MediaStore.Files`（`content://media/external/file`）原始行数 1749 **含非媒体文件**；其中 1525 条 `datetaken` 为 NULL 的**全部是这些非媒体文件**。应用展示的媒体条目 223 条（216 图 + 7 视频）的 `datetaken` **全部非 NULL**，即 `COALESCE` 回退在本库不参与排序。

**3. `UriMatcher` 子节点按注册顺序匹配**

- 根因：先注册的 `albums/*` 通配节点会遮挡后注册的 `albums/folder/*`、`albums/app/*`，多段 URI 返回 `NO_MATCH`。
- 修法/约定：注册顺序必须「具体模式在前、通配在后」（`GalleryProvider.kt` 中 `CODE_FOLDER_ITEMS` / `CODE_APP_ITEMS` 先于 `CODE_ALBUM_ITEMS`）。
- 验证：2026-09-26 真机复测，相册详情/目录相册/三方相册均正常取数。

**4. `owner_package_name` 对非 owner 应用不可见**

- 根因：读取为 null，三方应用相册不能按该列过滤。
- 修法：按 bucket 名映射分组后以 `bucket_id IN (…)` 查询；仅在读到的 owner 为空时回退 `owner_package_name = ?`。

### GL 宫格与查看器

**5. 「默认 z-order 被窗口遮挡」的结论更正**

- **更正记录**：早期判定「`SurfaceView` 默认 z-order 会被窗口层遮挡、必须 `setZOrderOnTop(true)`」。真正原因是给 `GLSurfaceView` 本身调了 `setBackgroundColor`——View 背景绘制在**窗口层**，会盖住位于窗口**后方**的 surface。
- 修法：**不给 GL View 设 View 背景**，清屏色走 `glClearColor`。实测去掉背景后默认 z-order 完全可用：
  - 查看器：默认 z-order，标题栏/底栏以 `FrameLayout` 覆盖层叠加，正常显示与接收触摸；
  - 宫格：起初保留 `setZOrderOnTop(true)`；后因底部悬浮胶囊导航要盖在宫格上、缩略图从胶囊下方滚过，**改为默认 z-order**；
  - 视频播放器：同样默认 z-order（半透明覆盖层要能透出视频）。

**6. GL 面遮挡窗口 View → 空态文案进 GL 绘制**

- 根因：GL surface 若位于窗口层之上会遮挡窗口绘制的 View。
- 修法：宫格空态文案改为在 GL 内绘制（`BadgeCache.emptyText` 纹理化文本），不再用 `TextView`；改用默认 z-order 后该约束依然保留（统一走 GL 内绘制）。

**7. 大图翻页「闪烁一下」**

- 根因：早期实现让 `GlImageViewer` 尺寸贴合当前照片（`wrap_content` + `onMeasure`），切到不同尺寸照片时 surface 改变大小——系统在新尺寸下会**先把旧缓冲拉伸合成 1~2 帧**（抓帧实测：settle 后一帧照片被压扁、下一帧才正确）。
- 修法：GL 视图恒定 `match_parent`，整个会话内不改变 surface 尺寸；照片 fit 与留白全部由渲染器绘制。闪烁消失。

**8. 视频「画面颠倒」**

- 根因（用合成测试视频定位，无旋转 / 90° 元数据两种）：
  1. `GlVideoView` 的 UV 映射把 v=0 放在了画面顶部（与 GL 常规相反），所有视频被上下翻转；
  2. MediaPlayer 渲染到 Surface 时**已按容器元数据旋转**，`videoWidth/Height` 是旋转后的显示尺寸；实现里又用 `MediaMetadataRetriever`/`MediaExtractor` 读到的 rotation 手动转了一遍 → 二次旋转。
- 修法：去掉手动旋转与尺寸交换；UV 改为常规约定（左上顶点 `(0,1)`、左下 `(0,0)`）并乘 `getTransformMatrix()`。查看器动态照片叠加（`GlImagePagerRenderer.drawVideoQuad`）曾沿用照片纹理约定（v=0 在顶部）同样颠倒，已同步改为同一约定。

**9. 新拍/动态照片「一直闪烁」**

- 根因：查看器解码最长边原为 `视口最长边 × 2`（本机 5544 → 受 `GL_MAX_TEXTURE_SIZE` 限制实际 4096），单张纹理 2304×4096 ≈ 36MB；工作集（当前页 + 前后各一页）两张就超过 64MB 纹理缓存 → **LRU 每帧互相淘汰**，`drawPage` 发现缺纹理又触发解码上传，形成「解码→上传→淘汰→重解码」死循环（logcat 实测 100ms/轮，画面在两页纹理间逐帧交替）。
- 修法：解码上限改为 `min(GL_MAX_TEXTURE_SIZE, 视口最长边)`（纹理约 17~23MB），缓存提升到 **96MB**，工作集可完整驻留。
- 验证：循环消失，稳定性实测 **0/3120 帧差异**。

### 视频播放器

**10. 覆盖层要「半透明 + 看得见视频」，窗口必须透明**

- 根因：默认 z-order 下 surface 位于窗口**之后**，窗口不透明背景会把半透明栏合成成不透明黑，视频透不过来。
- 修法：窗口（decorView/主题背景）设为透明 + `#99000000` 栏；横屏实测有效。

**11. 「暂停后栏仍会自动隐藏 / 再点播放无效」**

- 根因：`VideoPlayManager.start()/pause()` 均为主线程 post（异步生效），而 `togglePlayback` 同步读 `isPlaying()` 并调用 `scheduleAutoHide()`——暂停瞬间 `isPlaying()` 仍为 true，重新排上 5s 自动隐藏；恢复瞬间仍为 false，计时器又排不上。
- 修法：播放/暂停两个分支都用 `handler.post { scheduleAutoHide() }`，等状态生效后再排计时器。

**12. 封装进 `VideoPlayManager` 后播放器黑屏**

- 根因：`attachTexture`（GL surface 晚于 `setSource` 到达，播放器页正是此顺序）原实现仅当 `mediaPlayer != null` 才调用 `createPlayerIfPossible()` → 播放器永不创建。
- 修法：`attachTexture` 中**总是**调用 `createPlayerIfPossible()`（先释放已有播放器）；`setSource` 同样尝试，两边幂等。两种到达顺序（播放器页 surface 先 / 查看器 source 先）都成立。

### 地图

**13. 百度地图 SDK 初始化收敛为唯一入口**

- 初版在 `GalleryApp.onCreate` 直接 `SDKInitializer.setAgreePrivacy(true) + initialize()`，两个问题：① 在用户看到隐私提示前就代替用户「同意」，合规风险；② `initialize` 一旦完成，`SDKInitializer.isInitialized()` 为 true，之后 `LocationAddressResolver` 里的 `setCoordType(BD09LL)` **永远不会执行**——SDK 退回默认 GCJ-02 却接收 BD09LL 坐标，地图与逆地理编码整体偏移。
- 修法：唯一初始化点 `BaiduMapSdk.ensureInitialized(context)`（幂等；先校验 `PrivacyPrefs.isAgreed`；内部 `setAgreePrivacy` + `setCoordType(BD09LL)` + `initialize`；失败只记日志不抛异常）。调用方：`ViewerActivity.ensureSheetMap()`、`PhotoMapActivity.onCreate()`、`LocationAddressResolver`；媒体权限授予后由 `MainActivity.updatePermissionState()` **预热**（鉴权异步，提前初始化避免首次逆地理编码返回 `PERMISSION_UNFINISHED`）。
- 崩溃记录：`TextureMapView` 在 SDK 未初始化时构造会在 `JNIInitializer$InitOptions` 上抛 `NullPointerException`（详情面板地图卡片默认走到该路径，真机栈：`TextureMapView.<init> → BaiduMap.<init> → BaseMapInner.<init> → JNIInitializer.isUserTest`）。收敛到 `BaiduMapSdk` 后复测通过；查看器在 AK 不可用时隐藏地图卡片而不崩溃。

**14. MIUI 跨应用跳转确认**

- 首次经 scheme 拉起高德等三方应用，系统弹出「相册 想要打开 高德地图，是否允许？」（`com.miui.securitycenter` 的 `ConfirmStartActivity`），点「始终允许」后不再出现。属系统安全行为，应用侧不绕过。

---

## 2026-09-26 首轮真机完整验证（Android 16）

**设备与数据**：Android 16 / SDK 36 真机，arm64-v8a only；媒体 223 条（216 图 + 7 视频，`datetaken` 全部非 NULL）。

**构建/静态数据**：

- `gradlew clean` + `--no-build-cache` + 杀 daemon 后 `:main:assembleDebug` **91/91 任务真实执行 53s**，BUILD SUCCESSFUL；`:main:assembleRelease` SUCCESSFUL，产出 **35.90MB**（当时未签名）。
- `ndk.abiFilters 'arm64-v8a'` 生效后 debug APK **90.53MB → 39.96MB**、release **87.69MB → 35.90MB**（原生库 69.78MB → 18.10MB）。
- `:main:lintDebug` 0 error / **7 warning**；`lintVitalRelease` = No issues found。
- 单元测试 16 全绿（`XmpParserTest` 8 + `CoordConverterTest` 4 + 4 个模块模板）。

**手测清单（全部正常、无崩溃）**：照片 Tab / 相册 Tab / 相册详情 / 查看器 / 沉浸模式 / 上划详情面板 / EXIF / 地图卡片 / 逆地理编码 / 视频播放（控制栏、横屏）/ 动态照片长按播放 / 收藏写回。

**收藏写回链路**：点击收藏弹出系统授权「要允许相册修改这张照片吗？」（`RecoverableSecurityException`），点「允许」后 `is_favorite` 由 0 → 1；再点可复原为 0（同一 URI 授权后不再弹窗），全程无崩溃。

**相册计数**：动态照片 12 / 全部 223 / 相机 195 / 视频 7 / 截屏 11 / 微信 8 / QQ 3 —— 证明 `XMP LIKE` 过滤 BLOB 列在真机可用。

**隐私政策同意页**（`pm clear` 模拟首启）：

- 首启显示同意页（`PrivacyActivity` 为焦点，非 `MainActivity`）；「退出」/返回键都直接退出且**不写入**同意状态，再次启动仍显示同意页；
- 点「用户协议与隐私政策」链接弹出可滚动政策正文；
- 点「同意」写入 `gallery_privacy.xml`（`privacy_agreed=true`）→ 进入系统媒体权限弹窗 → 进入主界面；第二次启动不再显示（`am start` 与桌面图标两种方式均验证）；
- 未同意时直接 `am start MainActivity` 不会初始化百度 SDK（logcat 无 `LBSAuthManager`/`authtoken` 活动），也不崩溃；同意后 SDK 正常初始化。

**本轮顺带改动**：`DateFormats` 的 `SimpleDateFormat` 改为 `ThreadLocal`（进程级单例会被主线程与解码线程并发调用）；`AlbumResolver.addAlbumRow` 显式 `Array<Any?>` 消除 Kotlin 交叉类型警告；`main-ui` 移除未使用的 `viewpager2`（由 `main` 显式声明，避免依赖 material 传递）。

**归档**：本机 `verification-reports/`（`2026-09-26-device-verification.md` + 11 张真机截图；目录不进版本库）。

---

## 2026-09-28 百度地图 SDK 改为 Maven 引入（Android 16 复测）

**背景**：本地集成把 `BaiduLBS_Android.aar` + 16 个 so（4 ABI × 4 文件）放进版本库，共 **75.5MB**，拖慢 clone 且二进制归属复杂。改用 Maven Central（`com.baidu.lbsyun`）后**仓库自有代码仅 456KB**。

**产物一致性（逐字节核对）**：Maven `BaiduMapSDK_Map:8.2.0` + `base:8.2.0` 的 16 个 so 与原 `libs/baidumap/**` 的 SHA-256 **全部一致**；`Map-8.2.0.aar` 的 sha256 `bbdd358d…b690` 与 Maven Central 元数据一致。

**三个 Artifact 缺一不可**（实测编译错误数）：仅 `Map` → 20 处未解析；`Map + Search` → 仍 3 处（`CoordinateConverter`）；`Map + Search + Util` → 0 处。原因：`Map` 只覆盖渲染，`GeoCoder` 在 `Search`，`CoordinateConverter` 在 `Util`。

**曾担忧的风险（已排除）**：Maven 各 Artifact 的 class 总数 1266 vs 本地单体 AAR 1803，**743 个 class 仅本地存在**（安全与鉴权包等），其中 9 个被 Maven 侧字节码引用却未随包发布，理论上可能 `NoClassDefFoundError`。真机实测不成立：地图与逆地理编码正常，logcat 无相关报错。唯一真实但非致命的差异：Maven `Map` 缺「BmSDK」视图类，SDK 初始化时走一次失败的类查找并降级（`W` 级，SDK 内部捕获）：

```
W System.err: java.lang.ClassNotFoundException:
  Didn't find class "com.baidu.platform.comapi.bmsdk.view.BmBaseView" / "BmImageView"
```

之后引擎照常加载，功能不受影响；若日后用到「地图内嵌大图/街景」等 BmSDK 能力需重新评估（见 [backlog](backlog.md)）。

**许可声明**：Maven Central 上 `Map`/`base`/`common` 的 POM 均声明 Apache-2.0，这是**发行方元数据声明**，不等同于与百度签订的《开发者服务条款》；再分发前需自行核对服务条款。`.gitignore` 保留 `/libs/baidumap/` 作回退保险。

**构建与验证数据**：冷编译全量门禁（`:main:assembleDebug :main:assembleRelease :core-data:testDebugUnitTest :common-util:testDebugUnitTest :main:lintDebug`）**327/327 任务真实执行，BUILD SUCCESSFUL（3m27s）**；debug APK 38.67MB / release 35.81MB（与本地 AAR 路线 38.77MB 基本一致）；APK 内 arm64 的 4 个 so 齐全；单元测试 14 全绿（`:core-data` + `:common-util` 两个任务口径；全仓含 `main`/`main-ui` 模板共 16）。

**真机端到端**：照片宫格 → 查看器 → 详情面板：地图卡片瓦片（`百度地图 V20`）、蓝点标记、缩放/比例尺控件按设计隐藏、**逆地理编码返回地址正确**（与 EXIF 原始 GPS 一致，证明 WGS-84→BD09LL 转换与坐标系设置生效）；点击卡片进入 `PhotoMapActivity` 正常；`logcat -b crash` 为空。

---

## 2026-10-01 三连改：动态 ABI / APK 改名 / 首页宫格铺满

### 1. 打包 ABI 改为按连接设备动态决定

**动机**：`ndk.abiFilters 'arm64-v8a'` 硬编码 → arm64 包装不进 x86_64 模拟器（原生库加载失败），而模拟器是日常调试主力。

**实现**（根 `build.gradle` 的 `galleryAbiFilters()` + 辅助闭包）：`adb devices` 只认状态列 `device`（忽略 `offline`/`unauthorized`）→ 多设备时**真机优先于模拟器** → `adb -s <serial> shell getprop ro.product.cpu.abilist` → 按 `arm64-v8a / armeabi-v7a / x86 / x86_64` 白名单归一化去重。adb 路径优先 `-Pgallery.adb`，其次由 `sdk.dir` 推导 `platform-tools/adb[.exe]`，再次 `ANDROID_SDK_ROOT`/`ANDROID_HOME`，最后 PATH。

**失败一律降级不报错**：adb 不存在、`adb devices` 非 0、无设备、设备无 `abilist`、ABI 全不在白名单 —— 五种情况都退回 `arm64-v8a` 并打 `[abi]` 日志。

**覆盖项**：`-Pgallery.abiFilters=a,b`（显式指定）、`=none`（关闭过滤）、`-Pgallery.abiAuto=false`、`-Pgallery.abiDeviceSerial=<serial>`、`-Pgallery.adb=<path>`、`-Pgallery.androidSdk=<path>`。

**configuration cache 安全性**：探测在**配置阶段**执行，结果随 configuration cache 记录，插拔设备**会使缓存失效并重新探测**（这正是必须放配置阶段的原因）；解析结果记忆化在 `rootProject.ext` 上（脚本局部变量不承载状态——Gradle 每次调用闭包都会重新委托，实测会退化成每次调用都探测一遍）；一次配置只起一轮 adb 进程。

**Gradle 9 适配**：最初用 `Project.exec {}`，经 `rootProject.galleryAbiFilters()` 从子项目调用时闭包 delegate 不再是 `Project`（`Could not find method exec()`）；改用官方推荐、对 configuration cache 友好的 `providers.exec {}`。

**验证**（本机 x86_64 模拟器 / Android 16 / SDK 37 / density 480）：

| 场景 | 期望 ABI | 实测 APK 内 `lib/` | APK 体积 |
|---|---|---|---|
| 接入 x86_64 模拟器，无覆盖 | `x86_64` + `arm64-v8a` | 两个 ABI 各 4 个 so | 58.44MB |
| **断开设备**，无覆盖 | `arm64-v8a` | 仅 arm64 4 个 so | **38.67MB**（与改动前同规格） |
| `-Pgallery.abiFilters=arm64-v8a`（设备在线） | 覆盖生效 | 仅 arm64 | 38.67MB |

- **分支覆盖（stub adb，8 种）**：单模拟器、单真机、模拟器+真机（取真机）、`offline`、空列表、`adb devices` 返回 1、设备 ABI 全不在白名单、`abilist` 为空——全部按预期解析或降级；`-Pgallery.abiFilters` / `-Pgallery.abiAuto=false` 两路径经计数器确认**完全不调用 adb**。
- **端到端（x86_64 模拟器）**：`adb install` → 首启隐私页 → 主界面 → 推入带 GPS EXIF 的测试 JPEG 并触发媒体扫描 → 宫格 → 查看器 → 详情面板。关键证据：logcat 出现 `nativeloader: Load .../lib/x86_64/libBaiduMapSDK_map_v8_2_0.so ... ok`、`[BD]buildtime: map-engine libapp_BaiduMapApplib.so`，地图卡片正常渲染（水印 + 蓝点），EXIF 中文标签显示；无崩溃。`ClassNotFoundException: BmBaseView/BmImageView` 在 x86_64 上同样出现且同样被 SDK 内部降级（非本次引入）。
- 未覆盖项见 [backlog](backlog.md)。

**更正记录（2026-10-17）**：本节机制已**整体删除**，ABI 改为 flavor 维度、由构建变体选择（见文末 2026-10-17 条目）。原因：它把「构建目标」绑在「当前插着的设备」上——多设备、无设备（CI）与 Studio 图形流程的结果都不确定，且产物名不带 ABI、分不清包里装的是哪套 so。全部 `-Pgallery.*` 覆盖项随之删除。

### 2. APK 产物改名（`SenkGallery-Debug.apk` / `SenkGallery-Release.apk`）

**需求**：产物名从 AGP 默认的 `main-*.apk` 改为 `SenkGallery-*.apk`。

**最终做法**：用 AGP **公开**的 Variant API 直接改产物名——`VariantOutput.outputFileName` 是 `Property<String>`（源码注释 *"It is safe to modify it once you need custom artifact name"*）：

```groovy
def apkDisplayNames = [ debug: 'SenkGallery-Debug.apk', release: 'SenkGallery-Release.apk' ]
androidComponents { onVariants(selector().all()) { variant ->
    variant.outputs.forEach { it.outputFileName.set(apkDisplayNames[variant.name]) }
} }
```

产物直接是 `outputs/apk/<variant>/SenkGallery-<Variant>.apk`，AGP 把新名贯彻到 `output-metadata.json` 的 `outputFile` 与 `baselineProfiles/*/SenkGallery-Release.dm`；Studio Run/Debug 与 `installDebug`/`installRelease` 照常工作，磁盘上不再有 `main-*.apk`。

**更正记录（保留过程）**：第一版实现走「打包完再复制副本到 `outputs/renamed/`」，理由是「新 API 里 `BuiltArtifact.outputFile` 是只读 `String`，没有公开写法能改文件名」。**这个结论错了**：把「产物收集结构 `BuiltArtifact`（只读）」误当成「变体输出配置 `VariantOutput`（可写）」。经外部提示后实测 `VariantOutput.outputFileName.set(...)` 在 AGP 9.2.1 完全可用，遂改为上述写法并删除复制方案。网上常见的 `(output as VariantOutputImpl).outputFileName` 强转**不需要**——内部类只是对公开属性的 override，三条路径等价。

**踩坑记录（对后续仍有效）**：

1. `variant.artifacts.get(SingleArtifact.APK)` 在 Groovy 脚本里不可用（泛型擦除 + Groovy 派发，报 `Cannot cast java.lang.Class ...`）；要拿 APK 输出目录用约定路径 `layout.buildDirectory.dir("outputs/apk/<variant>")`。
2. `tasks.named("package<Variant>")` 在 `onVariants` 回调里报 `Task not found`（任务尚未注册）；须 `tasks.matching{}.configureEach{}` 延迟绑定。且 `assemble<Variant>` 实际依赖 `package<Variant>`，**不是** `package<Variant>Bundle`。
3. 复制方案下副本若放进 `outputs/apk/<variant>/share/` 会嵌在 `@InputDirectory` 里成为输入 → 每次构建判定过期重跑；必须与输入目录平级。

**ABI 拆分守卫**：固定文件名在 ABI 拆分（一个变体多个 output）时会互相覆盖；已在 `onVariants` 中仅当 `android.splits.abi.isEnable()` 且 `outputs.size() > 1` 时报错（不能对所有多 output 变体报错，否则误伤密度拆分；且 ABI 拆分与 `ndk.abiFilters` 本就互斥，该守卫在当前配置下不可达，属防御性代码）。新增变体时记得在 `apkDisplayNames` 补一行。

**验证**：`clean` + `assembleDebug assembleRelease` 产物名正确、无 `main-*.apk`、`output-metadata.json` 同步；`apksigner verify` release V3.0 签名有效且证书 SHA-1 与本地 release 密钥一致；`installDebug` 与直接 `adb install` 改名后的 release 包均成功；**未配置签名的分支**（临时移走 4 项 `RELEASE_*`）仍产出 `SenkGallery-Release.apk`（内容为未签名包）且 `outputFile` 一致，恢复签名后正常；配置缓存正常复用；门禁 BUILD SUCCESSFUL、单测 16/16、lint 0 error / 7 warning（较基线少 2 条，无新增）；产物名在连续构建与 clean 重建下稳定。

**更正记录（2026-10-17）**：`apkDisplayNames` 变体名查表已被 flavor 感知的通用命名取代（见文末条目）：产物名现为 `SenkGallery-<BuildType>-<ABI>.apk`，不再需要「新增变体时补一行」；改名机制（公开 Variant API、`outputFileName`、ABI 拆分守卫）本身不变。

### 3. 首页改版：照片页宫格铺满 + 滚动渐变标题栏

**需求**：① 宫格缩略图铺满整屏（含系统导航栏区域）；② 静止时标题栏正常显示（不透明白底 + 标题），**第一行位于标题栏下方**；③ 上划到缩略图来到标题栏下方时，标题栏变成**半透明黑色线性渐变遮罩**（上深下透明）并露出缩略图；④ 相册页保持原样。

**最终结构**（`activity_main.xml`）：根 `FrameLayout` → 内层 `FrameLayout`（pager 铺满）→ 覆盖层 `status_bar_bg`（高=状态栏 inset）→ `toolbar_scrim`（高=状态栏+标题栏）→ `toolbar`（`?attr/actionBarSize`，`marginTop` 让开状态栏，**背景透明**）→ `bottom_nav`。**`toolbar_scrim` 与 `toolbar` 的前后顺序于 2026-10-10 修正**（遮罩必须在前，见该条）。

**顶部底色的分层**：标题栏自身透明，**它透出的是依次几层**——页面背景（`fragment_photos` / `fragment_albums`）→ 再往下是宫格 GL surface 的**清屏色**。要让顶部随主题变色，这三层必须一起改（根布局 `gallery_surface_chrome` + `GlThumbnailGridView.setSurfaceBackgroundColor`），只改 toolbar 不够。

**实现要点**：

- `MainActivity.applyOverlayInsets()`：根布局不吃 inset，inset 只分给覆盖层（`status_bar_bg` 高度、`toolbar` 的 `marginTop`/左右、`scrim` 高度、`bottom_nav` 的 `marginBottom`，改 `layoutParams` 后**必须重新赋值**触发 `requestLayout`）。
- 标题栏背景**始终透明**；`applyChrome` / `applyToolbarAppearance` 只切文字/图标颜色与遮罩显隐。
- 滚动切换：`onGridScrolled(scrollY)` 在 **40px 死区**后切换，切页时重置滚动态。
- 渐变遮罩 `ToolbarScrimDrawable`（main-ui，代码绘制 `LinearGradient`，**单段：顶部约 55% 黑 → 全透明**，不用资源 shape，高度依赖设备状态栏），首页与相册详情页共用。
- `AlbumDetailActivity` 同步改版：`FrameLayout` 覆盖层结构；`grid.setTopInset(状态栏 + 标题栏)` 同一套算法；标题/返回键颜色随状态切换（`navigationIcon?.setTint()`）；**无底部胶囊导航**，底部预留只有系统导航栏。
- `GridGeometry` 三个纵向量分清：`topInset`（内容固定下移、不参与滚动，`cellY()` 含它）、`bottomInset`（撑长内容总高）、`bottomPadding`（滚到底时最后一行与屏幕底边的空白，由 `maxScroll` 保证）。`rowsHeight` 不含 `topInset`，`contentHeight` 含。
  正确关系：`maxScroll = rowsHeight + topInset − viewportHeight + bottomPadding`（由 `topInset + rowsHeight − maxScroll = 屏高 − bottomPadding` 推出）。
- 底部预留要「实测值 + 另加 inset」：照片页 = 胶囊占位 72dp（实测卡片 182px + 下边距 52px = 234px）+ 系统导航栏；相册详情页 = 仅系统导航栏。
- 滚动回调链：`GlGridRenderer.onScrollChanged`（GL 线程）→ `GlThumbnailGridView` post 主线程 → `PhotosFragment.onGridScroll` → `MainActivity.onGridScrolled`。

**踩过的坑（真机实测，按发现顺序）**：

1. **`toolbar` 的 `paddingTop` 会把控件撑高**：314? 实测从 208px 变成 360px，标题居中后落到状态栏与内容交界处，看着像被截断。必须用 `marginTop` 让开状态栏，不能动 padding。
2. **`wrap_content` 的 toolbar 会塌缩**成标题文字高度（123px vs 预留的 actionBarSize 208px）；必须显式 `layout_height="?attr/actionBarSize"`。
3. **`getRootWindowInsets()` 在 `onViewCreated` 时为 null**（视图未 attach），inset 全按 0 处理、顶部预留失效；必须 `ViewCompat.setOnApplyWindowInsetsListener` + `requestApplyInsets`。
4. **改 `layoutParams` 不重新赋值不生效**（遮罩高度实测踩到）。
5. **ViewPager2 的 Fragment 是懒创建的**：`setAdapter` 后立刻遍历拿不到照片页，`onGridScroll` 一直是 null、滚动从未生效；需首帧后 `pager.post {}` 补挂并在 `onPageSelected` 兜底。
6. **`GlGridRenderer` 用整除反推可视行会漏一行**（「第一行不显示」的真正根因）：`topInset` 不是 `rowStride` 整数倍时停在顶部会算出 `firstRow = 1`，第 0 行永不绘制，其槽位又被标题栏盖住。**改为逐行用 `cellRect` 的真实 y 与视口求交**。定位方法是先加日志打几何量（`vp/cell/topInset/rows/first/last`）——此前几轮靠调数值猜测都无效，先加日志才是正确第一步。
7. **库资源要用库的 R**：标题栏配色在 `main-ui`，`MainActivity`（`main`）里必须 `import com.senk.gallery.ui.R as UiR`。
8. **渐变遮罩两版都不对，最后靠「降不透明度 + 拉长衰减」解决**：① 单段但标题处已衰减到近乎透明，深色标题被浅色缩略图吞掉；② 顶部 80% 黑太生硬像黑带；最终 55%（`0x8C`）→ 整块线性渐隐。
9. **「顶部像留白」的诊断绕了远路（反面教材）**：曾把现象归因为「顶部最深的 55% 把缩略图压成均匀灰带」，改成「顶部透明 → 状态栏下沿最深」；后续实测确认顶部最深时缩略图同样能正常显示在状态栏区域（`y=2` 取样有照片色差），**顶部最深才是最终观感**，已改回单段。绕远路的原因：**用像素采样代替了看图**——采样到灰色就认为「被压暗」，没先确认按需求该不该压暗。保留此条提醒：遮罩观感以真机截图为准，数值只用来验证「是否与预期一致」。
10. **「回退」要精确到范围**：曾把「遮罩分两段」整体 revert，连静止态的边距/白底判断也被牵连。
11. **验证含渐变的改动，先临时屏蔽渐变**（硬性约定）：把 `START_ALPHA` 改 `0x00` 重建——顶部仍纯白 ⇒ 布局问题（查 `topInset`）；能看到缩略图 ⇒ 布局正确、只是观感。顺序：屏蔽验证布局 → 恢复调观感 → 移除临时代码。辅助判据：同一高度取**多个 x**、取值互不相同才算有内容透出。
12. **「相册」页一度被误伤**：把根布局 inset 整体去掉后相册页内容也顶到状态栏下。**照片页要「铺满」、相册页要「避让」，需求相反**——由 `AlbumsFragment` 自己加 `paddingTop`（状态栏 + 标题栏）还原。
13. **同一套改版要覆盖两处宫格页**：首轮只改了首页，相册详情页仍是旧布局，被指出后补齐；抽公共件 `ToolbarScrimDrawable` 供两页共用，避免参数漂移。
14. **补相册详情页时又把「静止态」做错了一次**：第一版给了「透明标题栏 + 缩略图从 y=0 铺满」的静止态，而照片页既定行为是「不透明白底标题栏 + 第一行在下方」。**改第二处时应逐条对照第一处的已验证行为**。
15. **`bottomInset` 不能用来做「底部留白」**：它会撑长内容，但滚动范围受 `maxScroll` 控制，余量不足一行时最后一行仍贴屏幕底边（实测比目标低 65px）；**`bottomPadding` 才是**正确工具。
16. **`maxScroll` 公式与 `cellY()` 不自洽的教训**：`cellY` 含 `topInset` 而公式没算 → 滚到底时最后一行多溢出 360px（正好一个 `topInset`）。由「`topInset + rowsHeight − maxScroll = 屏高 − bottomPadding`」推出公式后自洽。

**验证（真机 Redmi / Android 16 / 手势导航 / 1280×2772）**：

- 冷启动静止：`y=10` 为 `255,255,255`；标题 bounds `[52,207][196,304]`（完整落在标题栏内）；首行缩略图从 `y≈360` 开始（`GLDBG` 显示 `topInset=360 rows=50 count=200`）。
- 上划后：遮罩自顶部最深向下递减（实测 `y=120:162 → y=152:174 → y=320:240`）；`y=2` 处 `x200/x640/x1100` 取值互不相同（缩略图已到状态栏区域）。
- 相册页：顶部全 `255`（白底标题栏，行为不变）。
- 相册详情页：静止 `y=0..355` 全 `255`、标题区最暗像素 `min=33`（深色标题可见）、首行 `y≈400`；上划 `y=2` 非白、标题/返回键转白。
- 滚到底：照片页 `lastRowBottom=2486`（= 2772 − 286：胶囊 234 + 系统导航栏 52）、相册详情页 `2720`（= 2772 − 52），实测留白正确。
- 深色模式（`cmd uimode night yes`）：两页 `y=2..355` 整条 `(28,27,31)`，上划缩略图正常透出。
- 门禁：BUILD SUCCESSFUL、单测 16/16、lint 0 error / 7 warning（与基线一致，无新增）。

---

## 2026-10-06 颜色与主题规范化（全项目）

**需求**：① XML 统一用 style 设色，不在控件上写颜色；② 每个颜色都有浅色 + 深色两份；③ Kotlin/Java 取色一律走主题工具类。

**改前审计基线**（`276edbc`）：颜色定义 16 个、**缺 night 版本 15 个**（`main` 模块 9 色**全部**没有 night，`main/values-night/` 连 `colors.xml` 都不存在）；XML 硬编码字面量 **97 处**、`@android:color/*` 28 处、`@color/` 直接引用 54 处（其中 48 处指向无 night 的颜色）；KT/JAVA 取色设色 **42 处**；`?attr/color*` 使用 0 处；style 只有 4 个。

**做了什么**：

- 重写色板为语义命名 `gallery_<类别>_<用途>`：main-ui 23 色 + main 4 色 + 2 个 ColorStateList，**全部成对**；
- 新增 `main-ui/.../ui/theme/ThemeUtils.kt` 作为**代码取色唯一入口**（含 `isNightMode` 与 `applySystemBarAppearance`）；
- 新增两模块的 `values/styles.xml`，把颜色从布局搬进 style；
- `values-night/themes.xml` 父类 `Theme.Material3.Light` → **`Theme.Material3.Dark`**；
- 新增 `scripts/check-color-pairs.py` 做配对与悬空引用校验。

**踩过的坑**：

1. **点号样式名缺中间命名空间**：`Widget.Gallery.Toolbar` 隐含继承 `Widget.Gallery`，而框架没有 `Widget`，AAPT 报 `resource style/Widget not found`。用 `parent=""` 显式补齐 `Widget.Gallery` / `TextAppearance.Gallery` / `ShapeAppearance.Gallery`。
2. **同一元素不能有两个 `style=`**：批量替换颜色时与 `?attr/borderlessButtonStyle` 占位冲突，XML 解析失败（`AttributeNotUnique`）；颜色要并进同一个 style 或用 `app:tint` 之类属性。
3. **改名必须一次收尾**：`gallery_chrome_surface` 改名 `gallery_surface_chrome` 后漏掉 4 处引用，工作区一度不可编译；现已由脚本的「悬空引用」检查兜住。
4. **「深色下标题看不见」是真缺陷**：静止态文字取 `gallery_text_primary`（`#DE000000`，原无 night），深色底 `#1C1B1F` → 近黑压近黑。**底色有了 night、前景色没有**，是「必须成对」最典型的受害者。
5. **同一个占位色写了三遍**：`0xFFEEEEEE` 出现在 `GlImagePagerRenderer`、`GlGridRenderer`（写成 `Color.rgb(238,238,238)`）、`ImageViewThumbLoader`；已收进色板并由宿主注入。
6. **不要把「透明工具条」当通用做法**：查看器、地图页的工具栏与查看器底部栏原本不透明，被误设为透明后会透出下面 GL 画面。拆出 `Widget.Gallery.Toolbar.Surface`，并写明判据：**这页的内容会不会画到标题栏下面？会 → 透明；不会 → 不透明。**

**验证（真机 Redmi / Android 16 / 1280×2772）**：

- `scripts/check-color-pairs.py`：**29 个颜色全部成对、无悬空引用**；
- 浅色：照片页/相册页顶部 `y=2/120/300` 全 `255`；隐私首启页背景 `255`；
- 深色（`cmd uimode night yes`）：两页顶部全 `(28,27,31)`；相册页标题、分组标题、相册名/数量、胶囊导航全部可读（截图确认，无白块）；
- 门禁：BUILD SUCCESSFUL、单测 16/16、lint **0 error / 5 warning**（由 7 降为 5，样式化顺带消掉 2 条）。

---

## 2026-10-10 标题栏文字改为绘制在渐变遮罩之上（照片页 / 相册详情页）

**现象**：上划进入渐变遮罩态后，标题栏文字与背景**颜色相同、分不清**（照片页的「相册」、相册详情页的「全部」与返回键；下方是亮色缩略图时最明显）。

**根因（是 z-order，不是配色）**：两页的遮罩 View 在 `FrameLayout` 里**声明在 `toolbar` 之后**，因此绘制在标题文字**之上**。数值上正好解释「同色」：标题是白字（255），遮罩顶部约 55% 黑，标题条处最大亮度被压到 `255 × (1 − α(y))`——真机实测**标题框内最大值 226**（y≈207）、**同 y 背景区最大值 233**（y≈304），两者被同一层渐变乘成**同一个上限**；白底 + 白字时更精确相等（同为 115）。所以不是「对比度偏低」，而是**逐像素同色**——加深遮罩、改文字颜色、加文字阴影都治不了本。

**修法**：把遮罩声明挪到 `toolbar` **之前**（`activity_main.xml`、`activity_album_detail.xml` 两处），标题与返回键即绘制在渐变之上；同时修正两处已失效的注释（曾引用不存在的 `bg_toolbar_scrim` 与 `applyChromeForPage()`）。

**判定依据（改动前必读）**：`Widget.Gallery.Toolbar` 里的 `elevation` 是 **Material 属性**（给 AppBarLayout 读），**不是** `android:elevation`；继承链也不设 background/elevation ⇒ `toolbar` 与遮罩的 View elevation **同为 0**，`FrameLayout` **完全按声明顺序绘制**。（`bottom_nav` 的 `cardElevation 10dp` 仍在遮罩之上，未受影响。）

**踩过的坑**：

1. **「标题栏背景透明」≠「标题在遮罩之上」**：透明的是背景，文字仍受声明顺序支配。这类缺陷在 `colors.xml` / `styles.xml` 里查不出来，必须看布局子 View 顺序。
2. **验证判据必须能区分「文字」与「透出的内容」**：修复前标题框内**纯白像素 = 0**（最大值与同 y 背景区一致）；修复后同一框内 **2894** 个纯白字形像素（最大值 255），背景区仍是 **0**。只看最大值/均值会被亮色缩略图骗过（内容本身也能到 233）。
3. **截图不能写 `/sdcard`**：`screencap -p /sdcard/…` 会让 MediaStore 变化 → 照片页 `ContentObserver` → `loadPage(reset=true)` → `scrollToTop()`，**每截一张图就把宫格弹回顶部**（实测滑动后 2s 与滑动前逐像素差 0.00，一度被误判为「应用自己回弹」）。截图与 `uiautomator dump` 一律走 `/data/local/tmp`。
4. **`toolbar_scrim` 不出现在 `uiautomator dump` 里**（纯 View、无 contentDescription，被无障碍树过滤），不能用 dump 判断是否已上划；改用「与静止截图对比标题条亮度」。
5. **`monkey` 只把已有任务带到前台**：先 `am force-stop` 再拉起，否则会抓到上次遗留的查看器画面。

**验证（真机 / Android 16 / 手势导航 / 1280×2772）**：

- 上划态（浅色）：照片页标题框 max 255、纯白像素 **2894**（20.72%），同 y 背景 max 233、纯白 0 → PASS；相册详情页纯白 **2426**（17.37%）→ PASS；
- 上划态（深色）：照片页纯白 2454、相册详情页 2263，背景均 0 → PASS；
- 静止态无回归（浅色/深色 × 两页）：标题条主题底色占比 **98.2%–98.4%**，首行缩略图起始 **y=360**，与改前逐项一致；
- 「相册」Tab 上划后标题条白色占比 98.4%（不出现遮罩，行为未变）；
- 门禁：`:main:assembleDebug` BUILD SUCCESSFUL；单测 16/16；lint 0 error / 5 warning（与基线一致）；`check-color-pairs.py` 29 色全部成对；
- 本轮证据与工具（本机 `verification-reports/`）：`capture.sh`（可复现截图流程）、`analyze_title.py`（纯白字形像素计数）、`check_static.py`（静止态回归判据）及 before/after/dark 三组截图。

---

## 2026-10-17 ABI 选择改为构建变体（删除动态探测）

**背景**：2026-10-01 起 ABI 由「当前连接的设备」在配置阶段动态探测（根 `build.gradle` 的 `galleryAbiFilters()` + 6 个 `-Pgallery.*` 覆盖项，见该条目及其更正记录）。它把构建目标绑在设备连接状态上：多设备/无设备/Studio 图形流程结果不确定，且产物名不带 ABI。改为把 ABI 做成 flavor 维度，由**构建变体**显式选择，产物名带上 ABI。

**实现**（`main/build.gradle`；根 `build.gradle` 的动态探测模块整体删除）：

- `flavorDimensions.add('abi')` + `productFlavors`；flavor 名 -> `ndk.abiFilters` 的单一事实来源是顶部 `abiFlavorFilters` 映射：

| flavor | ABIs | debug 实测 | release 实测 |
|---|---|---|---|
| `arm64` | `arm64-v8a` | 38.69MB | 35.94MB |
| `armv7` | `armeabi-v7a` | 33.13MB | 30.38MB |
| `x86` | `x86` | 40.01MB | 37.25MB |
| `x86_64` | `x86_64` | 40.32MB | 37.57MB |
| `universal` | 不过滤（4 个全打包） | 90.49MB | 87.74MB |

- 共 10 个变体，变体名 = `<flavor><BuildType>`（`arm64Debug` … `universalRelease`）；聚合任务 `assembleDebug` / `assembleRelease` 构建全部 5 个 flavor；
- 产物改名规则改为 `SenkGallery-<BuildType>-<ABI>.apk`（如 `SenkGallery-Debug-x86_64.apk`、`SenkGallery-Release-universal.apk`），路径 `outputs/apk/<flavor>/<buildType>/`；baseline profile `.dm` 与 `output-metadata.json` 自动跟随；
- `-Pgallery.abiFilters / .abiAuto / .adb / .abiDeviceSerial / .androidSdk` 与 `gallerySdkDir / galleryAdb / galleryExec / galleryDetectDeviceAbis` 全部删除。

**Gradle/AGP 适配（对后续有效）**：

1. AGP 9 公开 DSL 里 `flavorDimensions` 是 `MutableList`，用 `.add('abi')`；`ProductFlavor.dimension` 是属性，用 `dimension = 'abi'` 赋值（方法调用写法不可靠）；
2. `ndk { abiFilters.addAll(...) }` 可正常用于 Groovy：与既有的 `compileSdk { }` 一样走「属性 + Kotlin 函数类型参数」的 DSL 调用；
3. `variant.productFlavors` 是 `List<Pair<dimension, flavorName>>`（`ComponentIdentity` 提供），改名逻辑据此取 flavor 名，不再依赖变体名查表；
4. flavor 名 `x86_64` 带下划线**合法**，变体/任务名原样保留（`assembleX86_64Debug`、`installX86_64Debug`）；
5. 容器元素创建用惰性 `register(name) { ... }`、不用 `create(...)`：`create` 在新版 Gradle API 的检查中标记 `@Deprecated`；实测 AGP 枚举变体时会把惰性注册的 flavor 全部实例化（10 变体、ABI 过滤、产物改名、baseline profile 均正常）。

**验证**（本机 Windows 命令行 / `--offline`；`clean` 后实测）：

- 10 个变体全部构建成功（`assembleDebug` + `assembleRelease`）；逐 APK 核对产物名、`outputs/apk/<flavor>/<buildType>/` 路径与 `lib/` 内容：单 ABI flavor 各含对应 ABI 的 4 个 so，`universal` 含 4 ABI × 4 = 16 个 so；体积见上表；
- `output-metadata.json` 的 `outputFile` 与 `baselineProfiles/*/SenkGallery-Release-<ABI>.dm` 同步改名；
- 配置缓存复用正常（`Reusing configuration cache`，91 tasks up-to-date）；
- 门禁：`:main:lintArm64Debug` 0 error / 5 warning（与基线一致）；`:core-data:testDebugUnitTest :common-util:testDebugUnitTest` 全绿；
- **未做**：安装验证（改动时无设备/模拟器连接）、Studio Build Variants 图形流程复验；见 [backlog](backlog.md)。

---

## 2026-10-19 宫格改 View + 多选/选图/自建相册/外部调用

**背景**：产品需求要求「宫格每格用 View 以适配无障碍」、新增多选模式、选图页（单选/多选/分类选）、自建相册与「添加到」、外部调用（选图/大图）。原架构中宫格是 `GLSurfaceView` 自绘：`uiautomator` 看不到格子、TalkBack 无法逐格聚焦；查看器/视频两处 GL 保持不变。模块上新增 `external-api`（纯契约 Android library），其余在原有 `main → main-ui → core-data` 内按包拆分（`ui/grid`、`ui/selection`、`ui/picker`、`ui/addto`）。

**做了什么**：

- **宫格 View 化**：`MediaGridView`（RecyclerView 子类）+ `MediaGridAdapter` + `item_media_cell.xml` + `GridCellLayout`（`onMeasure` 强制正方形）；缩略图 `ImageViewThumbLoader` 加 LRU Bitmap 缓存与 tag 防复用；视频时长/动态照片角标/选择框都是普通 View；删除 `GlThumbnailGridView` / `GlGridRenderer` / `GridGeometry` / grid 用 `ThumbnailLoader`。铺满式布局改为 `RecyclerView padding + clipToPadding=false`。照片页/相册详情页/选图页共用 `GridPageController`（分页 + ContentObserver 回顶 + 提交列表）与缩略图链路。
- **多选**：`SelectionState`（只存 id、保留点选顺序、maxCount、监听器）为与 View 无关的状态机；`SelectionActionBar`/`SelectionActions` 提供分享/收藏/添加到/删除；照片页与相册详情页共用。标题栏「多选」/全选、关闭、返回键先退多选、隐藏胶囊导航。
- **选图页** `ImagePickerActivity`：Toolbar + TabLayout（照片/相册）+ 共享宫格；相册 tab 复用 `AlbumListAdapter`，点相册进入内容二级视图；单选/多选/分类选；结果回传原始 URI + id。
- **自建相册**：设计为**应用内逻辑集合**（`CustomAlbumStore`，SharedPreferences + 版本化 JSON，线程安全），不复制媒体文件；相册页新增「我的相册」分区（新建/删除）；`AddToAlbumActivity` 支持逻辑加入自建相册、物理复制到相机/文件夹相册（`MediaWriter`，`IS_PENDING` + 64KB 流复制 + 重名去重 + 取消清理）。
- **外部调用**：`external-api` 定义 action/extras；`main.ExternalEntryActivity`（唯一 exported 入口，透明窗口）校验隐私/权限后路由选图页/查看器；选图结果转成 `ExternalMediaProvider` 的代理 URI 并对调用方逐个临时授权；`ViewerActivity` 增加外部大图模式（合成负 id `MediaItem`，不查本库）。`src/debug` 增加 `ExternalClientDebugActivity` 测试壳（release 不含）。
- **相册页标题栏**：给「相册」页 toolbar 铺一层不透明 `gallery_surface_chrome`（照片页仍透明以透出渐变/宫格），修复上划时列表内容从透明标题栏下透出的问题。

**真机验证中发现并修复的坑（后续排查先读）**：

1. **外部 VIEW_IMAGE 的 intent-filter 只写 scheme 不匹配**：带 `content://` data 的隐式 Intent 在真机上 `ActivityNotFoundException`（`ActivityStarterImpl: aInfo is null`），但 `pm resolve-activity` 却能解析。根因是 data 匹配还要求 `host` 与（content URI 可推断出的）MIME 类型匹配；`scheme`-only 的 filter 对带 authority 的 content URI 不成立。修法：`VIEW_IMAGE` 用独立 filter 并声明 `scheme=content + host="*" + mimeType="*/*"`（外加 file scheme）；`PICK_IMAGE` 没有 data，必须留在**不带 data** 的另一个 filter 里。**不要合并这两个 filter。**
2. **`MediaStore.createTrashRequest` / `createFavoriteRequest` 只接受 MediaStore 真实 URI**：传本应用 Provider URI（`content://com.senk.gallery.data.provider/media/{id}`）抛 `IllegalArgumentException: All requested items must be referenced by specific ID`。修法：批量删除/收藏授权请求改用 `MediaItem.uri`（`content://media/external/images|video/media/{id}`）；收藏的普通写回仍走 Provider。
3. **ViewPager2 切页后照片页回调丢失**：从相册页切回照片页，`onPageSelected` 触发时 Fragment 视图可能尚未重建，`supportFragmentManager.fragments` 里拿不到目标实例，导致上划宫格标题栏不再切换到渐变遮罩（真机复现）。修法：`registerFragmentLifecycleCallbacks` 在 `onFragmentViewCreated`/`onFragmentResumed` 时绑定 `onGridScroll` / `onSelectionStateChanged`，再保留 `onPageSelected` 与首帧 `post` 兜底。
4. **新增页面必须处理 system bars insets**：targetSdk 36 强制 edge-to-edge，「添加到」页最初没加 `setOnApplyWindowInsetsListener`，标题栏直接画在状态栏下面。修法：根布局 padding = `Type.systemBars()`。
5. **外部选图代理 URI 读取自测**：app 内测试壳验证 `query` 元数据 + `openInputStream` 读 64KB 成功（name/mime/size 正确），多选 2 张均通过；VIEW_IMAGE 隐式启动查看器成功。
6. **切页返回后标题栏与宫格滚动状态不一致（交付后用户反馈，已修复）**：照片页上划到渐变遮罩态 → 切到相册页 → 再切回照片页，标题变回黑字、渐变遮罩消失、状态栏不再是透明背景；而宫格（RecyclerView）仍保持原来滚动位置。根因：`MainActivity.applyChrome` 在切页时把 `gridScrolledPast` 重置为 false（旧假设是「切页会回顶」），但 ViewPager2 会保留 Fragment 与 RecyclerView 的滚动位置，切回来时也不会产生滚动事件，宿主缓存的「是否已上划」与实际永远不符。修法：`MediaGridView.currentScrollY()` 改为公开 API；`PhotosFragment.syncGridScrollState()` 用真实滚动位置回调宿主；MainActivity 在 `onPageSelected`（回到照片页）与 `onFragmentResumed` 时主动 sync 一次，标题栏三态即与实际位置一致（顶部返回＝静止态，滚动位置返回＝上划态）。真机复测：上划→切页→返回＝白字+遮罩+状态栏透出；顶部→切页→返回＝黑字+无遮罩+不透明状态栏；查看器返回同样正确。

**验证（真机 / Android 16 / 1280×2772）**：

- 构建：`:main:assembleArm64Debug` / `:main:assembleArm64Release` BUILD SUCCESSFUL；`:main-ui:testDebugUnitTest`（新增 `SelectionStateTest` 7 例）+ core-data/common-util 单测全绿（共 23）；lint 0 error / 3 warning（均为历史遗留）；`check-color-pairs.py` 29 色成对；release APK 已确认不含 debug 测试壳（`ExternalClientDebugActivity` 0 处），外部入口正常打包；
- 照片页/相册详情页：View 宫格渲染、滚动、标题栏渐变、点击进查看器；多选进入/勾选/全选/退出、动作栏、分享/收藏/添加到/删除；
- 选图页：外部 PICK_IMAGE 进入，单选取图回传 1 张、多选 2 张回传并读出代理 URI；相册 tab 分类进出正常；
- 自建相册：新建后立即加入 1 张，相册页「我的相册」显示；长按删除相册（照片保留）；复制 1 张到 `Pictures/douyin/comment/` 成功，删除该复制文件后恢复；
- 深色模式抽查：照片页/多选/相册页/选图页/添加到底色与文字正常（截图见本机 `verification-reports/`）；
- 无障碍：`uiautomator dump` 中每格含 `content-desc`（照片/视频+时间+选中态），多选态 `checkable=true`、选中格 `checked=true`；
- 崩溃：`adb logcat -d -b crash` 无 `com.senk.gallery`。

---

## 2026-10-19 收藏改为应用内自建库（不再修改系统媒体库）

**背景（用户反馈）**：多选模式下点「收藏」提示「收藏失败」。原实现把 `is_favorite` 写回系统媒体库：Android 12+ 需要用户逐次通过系统弹窗授权（`RecoverableSecurityException` + `MediaStore.createFavoriteRequest`），批量收藏时体验差，部分 ROM/媒体库状态下会直接失败；且本应用此前已把「删除」改为系统授权路径，收藏是最后一个依赖系统写权限的动作。产品决定：**收藏改用自建库，不再修改媒体库**。

**实现**：

- 新增 `core-data/album/FavoriteStore`（SharedPreferences `gallery_favorites` + 版本化 JSON）：只保存媒体 id，应用私有、不复制文件；单锁 + 内存缓存；坏 JSON 回退空集合。
- `GalleryProvider`：
  - 查询：每次媒体查询取一次 `FavoriteStore.ids()` 快照，经 `MediaStoreFetcher.readItem(cursor, favoriteIds)` **覆盖** `is_favorite` 列（覆盖点只有这一处，`/media`、`/media/{id}`、各相册内容查询统一生效）。
  - 写入：`update(media/{id}, is_favorite)` 写 `FavoriteStore`（不再 `resolver.update(MediaStore…IS_FAVORITE)`），变化时 `notifyChange`。
- `AlbumResolver`：「收藏」常用相册的计数/封面/内容改由 `FavoriteStore` 计算（收藏内容按拍摄时间倒序分页；id 数量 ≤900 直接 `_id IN` 查询，超出则全量取回内存分页）；其它相册内容查询把 `favoriteIds` 透传给 `MediaStoreFetcher` 以正确显示爱心状态。
- UI：`SelectionActions.favorite` 改为逐条 `update` 本地 Provider（无系统授权回调）；`ViewerActivity` 删除 `RecoverableSecurityException`/`REQUEST_FAVORITE`/`onActivityResult` 授权链路，直接写本地；照片页/相册详情页移除收藏授权 `ActivityResultLauncher`。
- 文档：data-layer 新增 §6.2、ui-guide 12.4、architecture/backlog/verification 同步；「收藏不同步系统媒体库、旧系统收藏标记不导入」列入 backlog「明确不做」。

**真机验证（Redmi / Android 16）**：

- 多选 1 张 → 收藏：无系统授权弹窗，Toast「已收藏 1 项」，自动退出多选；相册页出现「收藏 1 项」→ 打开只有该张；查看器爱心为实心 → 点按取消收藏（同样无弹窗）→ 返回相册页「收藏」相册消失；
- 收藏状态由 Provider 覆盖后，照片页/相册页/查看器三处一致；
- 崩溃：`adb logcat -d -b crash` 无记录；门禁：构建 + 单测 23/23 + lint 0 error/3 warning + 颜色校验通过。

**遗留（有意/已记录）**：收藏仅应用内可见（系统相册不显示）；旧版本写入 MediaStore 的收藏标记不做迁移；收藏库随应用数据清除而丢失（见 [backlog](backlog.md)）。

---

## 2026-10-19 收藏存储由 SharedPreferences 改为 SQLite

**背景（用户要求）**：上一条把收藏改成了应用内自建库，但存储用的是 SharedPreferences + JSON；用户明确要求「收藏使用 sqlite 存储」，并明确**不需要兼容旧数据**（开发期换方案）。自建相册（`CustomAlbumStore`）保持 SharedPreferences 不变，**本次只改收藏**。

**实现**：

- 新增 `core-data/db/FavoriteDb`（`SQLiteOpenHelper`）：数据库 `gallery_favorites.db`，表 `favorites(media_id INTEGER PRIMARY KEY, created_at INTEGER)`；`onCreate` 只建表，`onUpgrade` 仅日志（v1 初始版本，后续只允许 ALTER，不 DROP）。
- `FavoriteStore` 对外 API 完全不变（`ids/isFavorite/count/setFavorite`），底层改为 SQLite：
  - 查询：`ORDER BY created_at ASC, media_id ASC` 保证稳定顺序；`isFavorite`/`count` 用 `COUNT(*)`；
  - 写入：`INSERT OR IGNORE`（已收藏时返回 false，不重复刷新）/ `DELETE`；批量写入使用单事务。
  - 进程内单例 + SQLite 自身并发串行化，去掉原先的内存缓存与 JSON 解析。
- **不做旧数据兼容**：不读取/不迁移旧 SharedPreferences；旧版本收藏数据直接丢弃。
- 文档：data-layer §6.2 更新为 SQLite schema/不兼容说明，architecture/ui-guide/verification 同步。

**真机验证（Redmi / Android 16）**：

- 收藏 1 张 → 再收藏 1 张 → 重启进程（`am force-stop` + 重新拉起）后「收藏」仍为 2 项（SQLite 持久化）；
- `run-as` 导出数据库检查：表结构 `favorites(media_id INTEGER PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL)`，行为 `[(1000011473, …), (1000011511, …)]`；
- 自建相册 `sqlite_migration`（SharedPreferences 存储）仍正常显示 1 项，确认未受影响；
- 清理测试数据后 `favorites` 表为空、「收藏」相册隐藏；无崩溃；门禁构建/单测/lint/颜色校验通过。

---

## 2026-10-19 相册页新建相册后跳转选图（选择照片和视频）

**背景（用户反馈）**：相册页「新建相册」创建成功后只回到相册列表，用户还要再走「多选 → 添加到」才能放照片；期望创建后直接进入选图页挑照片/视频。

**实现**：

- `AlbumsFragment` 增加 `registerForActivityResult(StartActivityForResult)`：名称对话框创建成功后构造新相册的 `AlbumInfo`（`bucketId` = 自建相册 UUID），立即启动 `ImagePickerActivity`（多选、`MAX_COUNT_UNLIMITED`、标题「选择照片和视频」）。
- 选图结果：读 `ImagePickerActivity.EXTRA_SELECTED_IDS`（所选媒体 id）+ `GalleryExternal.EXTRA_SELECTED_URIS`（首项作封面），`CustomAlbumStore.addItems` 加入新相册 → `notifyChange` → 刷新列表 → Toast「已添加 N 项到相册」→ 直接打开 `AlbumDetailActivity` 让用户看到结果。
- 取消选图：保留刚创建的空相册并刷新列表（可之后再加/长按删除）。
- `ImagePickerActivity` 新增内部常量 `MAX_COUNT_UNLIMITED = Int.MAX_VALUE`（外部调用契约不变：≤0 仍表示默认 9）；选图页本身同时展示图片与视频，相册 tab 也可按分类选。
- 文档：ui-guide §7（新建相册流程）/§9（内部调用方式）、verification 手测项、README 功能概览。

**真机验证（Redmi / Android 16）**：

- 相册页「新建相册」输入 `pick_test_album` → 自动打开选图页（标题「选择照片和视频」、底部「已选择 0 项」）；
- 经「相册 tab → 视频」选 1 个视频、切「照片 tab」选 1 张图片（跨 tab 选择保留）→ 完成：进入 `AlbumDetailActivity`（标题 pick_test_album，2 格），相册页计数 2；`gallery_custom_albums` 记录 `item_ids=[videoId, imageId]`；
- 再新建 `pick_cancel_album` → 选图页直接返回（取消）：回到相册页，该相册显示「0 项」；
- 清理测试相册后列表恢复、无崩溃；门禁构建/单测/lint/颜色校验通过。
