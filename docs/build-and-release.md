# 构建与发布

## 1. 环境与工具链

| 项 | 值 | 位置 |
|---|---|---|
| AGP | **9.2.1** | `gradle/libs.versions.toml` |
| Gradle | wrapper **9.4.1** | `gradle/wrapper/gradle-wrapper.properties` |
| daemon JDK | **21**（首次构建自动下载） | `gradle/gradle-daemon-jvm.properties` + `fooJay-resolver-convention` 插件（`settings.gradle`） |
| 配置缓存 | 开启（`org.gradle.configuration-cache=true`） | `gradle.properties` |
| JVM 参数 | `-Xmx2048m -Dfile.encoding=UTF-8` | `gradle.properties` |
| Java 兼容 | source/target 11 | 各模块 `build.gradle` |
| Kotlin | 由 AGP 内置（`built_in_kotlinc`），**没有** `org.jetbrains.kotlin.android` 插件 | 根 `build.gradle`、`settings.gradle` |
| 仓库 | `google()`（插件仓库限定 `com.android.*` 等组）+ `mavenCentral()`；`FAIL_ON_PROJECT_REPOS` | `settings.gradle` |
| 打包入口 | `:main`（application）；`main-ui` / `core-data` / `common-util` 为 library | `settings.gradle` |

**依赖版本策略**：全部锚定 Gradle 本地缓存中已有版本，保证可离线编译（`--offline`）；升级版本时以“本地可解析”为准，不追最新。

## 2. 常用命令

```bash
./gradlew --stop                      # 停 daemon（排查“改了脚本不生效”时先做）

# 常规构建
./gradlew :main:assembleDebug

# 冷启动全新编译（排除增量缓存干扰，验证时用）
./gradlew clean
./gradlew :main:assembleDebug --offline --no-build-cache

# Release + 单元测试 + lint（发布前）
./gradlew :main:assembleRelease \
          :core-data:testDebugUnitTest \
          :common-util:testDebugUnitTest \
          :main:lintDebug --offline
```

产物位置：

| 产物 | 路径 |
|---|---|
| Debug APK | `main/build/outputs/apk/debug/SenkGallery-Debug.apk` |
| Release APK | `main/build/outputs/apk/release/SenkGallery-Release.apk`（已签名）或未签名包（见 §4） |
| 基线 Profile | `main/build/outputs/apk/release/baselineProfiles/*/SenkGallery-Release.dm` |
| 单元测试报告 | `<module>/build/reports/tests/testDebugUnitTest/index.html`（原始数据 `<module>/build/test-results/testDebugUnitTest/*.xml`） |
| Lint 报告 | `main/build/reports/lint-results-debug.html` / `.xml` |

安装与真机流程见 [验证方法与判据](verification.md)。

## 3. 动态 ABI 选择（`galleryAbi`，根 `build.gradle`）

**背景**：百度地图 SDK 自带 4 个 ABI 的 `.so`——全部打包 APK ≈90MB，只留 `arm64-v8a` 则 ≈39MB；但 arm64 包**装不进 x86_64 模拟器**（原生库加载失败），而模拟器调试很常用。因此按「当前连接的设备」自动决定 ABI，没有设备时退回 `arm64-v8a`。

探测流程（配置阶段执行）：

1. 解析 adb：`-Pgallery.adb=<path>` → `sdk.dir/platform-tools/adb(.exe)` → PATH 上的 `adb`；
2. `adb devices` 取 `state == device` 的行（兼容 CRLF/CR/LF；`offline`/`unauthorized` 忽略）；多台设备时**真机优先于模拟器**（模拟器序列号形如 `emulator-5554`），`-Pgallery.abiDeviceSerial=<serial>` 可指定；
3. `adb -s <serial> shell getprop ro.product.cpu.abilist` 取 ABI 列表，归一化为 SDK 支持的 4 个（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`），写入 `:main` 的 `ndk.abiFilters`；
4. 任一环节失败 → 默认 `arm64-v8a`。日志以 `[abi]` 开头，构建时可见采用了哪组。

**覆盖项**：

| 参数 | 效果 |
|---|---|
| `-Pgallery.abiFilters=arm64-v8a,x86_64` | 显式指定（逗号分隔），跳过自动探测 |
| `-Pgallery.abiFilters=none` | 不做 ABI 过滤，4 个 ABI 全打包（≈90MB） |
| `-Pgallery.abiAuto=false` | 关闭自动探测，恒用默认 `arm64-v8a` |
| `-Pgallery.adb=<path>` | 指定 adb 可执行文件 |
| `-Pgallery.abiDeviceSerial=<serial>` | 多设备时指定优先使用哪台 |
| `-Pgallery.androidSdk=<path>` | 指定 SDK 目录（默认读 `local.properties` 的 `sdk.dir`） |

实现要点（改动前必读）：

- 用 `providers.exec` 而不是 `Project.exec`：后者在 Gradle 9 已不是稳定公开 API，且 `providers.exec` 对配置缓存友好（`build.gradle` 内有注释）；
- 探测发生在**配置阶段**，结果作为配置缓存的输入被记录：连接/断开设备会**自动使缓存失效**并重新探测，不会出现「缓存里还是上一台设备」；
- 解析结果经 `rootProject.ext.galleryAbiResolved / galleryAbiCache` 记忆化（闭包每次调用都会重新委托，脚本局部变量不足以承载状态）；
- ABI 拆分（`splits.abi`）与 `ndk.abiFilters` **互斥**（AGP 会报 Conflicting configuration），本应用不启用任何拆分。

## 4. 签名与密钥（`local.properties`）

AK 与签名口令**都不进版本库**，统一从 `local.properties`（已忽略）读取；模板见根目录 `local.properties.example`。

| 键 | 缺失时行为 |
|---|---|
| `sdk.dir` | Android Studio 自动生成；其它场景可用 `ANDROID_SDK_ROOT` / `-Pgallery.androidSdk` |
| `BAIDU_MAP_API_KEY` | 注入空串 → SDK 初始化失败但**应用不崩溃**（地图/逆地理编码不可用） |
| `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | 四项缺一即视为未配置 → release 产出**未签名包**，不影响他人 clone 后构建 |

- 签名同时启用 **v1 + v2 + v3**（v2+ 为 Android 7+ 必需，v1 兼容更早）。
- 密钥文件建议放仓库外（如 `~/.android/release.keystore`）；`.gitignore` 已兜底排除 `*.jks` / `*.keystore` / `keystore.properties` / `/main/release/`。
- 生成新密钥与查看 SHA1（申请百度 AK 用）的命令写在 `local.properties.example` 注释里。

## 5. APK 产物改名

`main/build.gradle` 用 AGP 公开 Variant API 把产物直接改名（不是打包后再复制副本）：

```groovy
def apkDisplayNames = [ debug: 'SenkGallery-Debug.apk', release: 'SenkGallery-Release.apk' ]
androidComponents { onVariants(selector().all()) { ... output.outputFileName.set(...) } }
```

- `VariantOutput.outputFileName` 是公开 `Property<String>`（AGP 明确允许自定义），改名会一路贯彻到 `output-metadata.json` 与 baseline profile `.dm` 文件名，Android Studio 的 Run/Debug 与 `installDebug`/`installRelease` 都照常工作，磁盘上不会留下 `main-*.apk` 的副本。
- 老写法 `applicationVariants.all { outputFileName = ... }` 已在 AGP 8 移除，**不要再使用**。
- 守卫：若启用了 ABI 拆分且变体有多个 output，构建直接报错（固定文件名会互相覆盖）；本应用不启用拆分，正常不会触发。
- 新增构建变体时，在 `apkDisplayNames` 补一行即可。

## 6. 版本号

```groovy
versionCode 10000000   // 格式 xx.xx.xx.xxx（与 versionName 对应）
versionName "1.0.0.0"
```

发版时两者同步递增（`1.0.0.0 → 1.0.0.1` 对应 `10000000 → 10000001`）。

## 7. 依赖清单（`gradle/libs.versions.toml`）

| 依赖 | 版本 | 用途 |
|---|---|---|
| `com.android.tools.build:gradle`（AGP） | 9.2.1 | 构建（内置 Kotlin 支持） |
| `androidx.core:core-ktx` | 1.18.0 | 基础 KTX |
| `androidx.appcompat` | 1.8.0 | AppCompatActivity |
| `com.google.android.material` | 1.14.0 | Material3 组件 |
| `androidx.activity:activity-ktx` | 1.13.0 | Activity Result API 等 |
| `androidx.constraintlayout` | 2.2.2 | 部分布局 |
| `androidx.recyclerview` | 1.4.0 | 相册列表 |
| `androidx.viewpager2` | 1.0.0 | 主页两页切换 |
| `androidx.exifinterface` | 1.3.7 | EXIF 读取 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | lifecycleScope |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | 异步查询 |
| `com.baidu.lbsyun:*`（map / search / util） | 8.2.0 | 地图、逆地理编码、坐标转换 |
| junit / androidx.test 系列 | 4.13.2 / 1.3.0 / 3.7.0 | 单元测试与仪器测试 |

百度 SDK 注意事项（`libs.versions.toml` 内注释同样强调）：

- **三个 Artifact 必须同时声明**，各自只覆盖一部分 API：`Map`（渲染：`TextureMapView` / `BaiduMap` / `MarkerOptions`，含 4 ABI 的 `.so`）、`Search`（`GeoCoder` 逆地理编码）、`Util`（`CoordinateConverter`）。`Map` 经传递依赖会带 `base`/`common`，`Search`/`Util` **不会**，缺了会编译失败。
- SDK 已发布到 Maven Central（`com.baidu.lbsyun`，POM 声明 Apache-2.0），**不再需要**本地 `libs/baidumap/` 与 `jniLibs.srcDir`（`.gitignore` 里保留排除规则仅为历史兜底）。
- 初始化唯一入口与 AK 校验见 [界面与交互](ui-guide.md)（`BaiduMapSdk.ensureInitialized`）；授权许可与第三方声明见根目录 `THIRD_PARTY_NOTICES.md`。

## 8. 发布前检查

1. `./gradlew clean` + `:main:assembleDebug --offline --no-build-cache`（冷编译无警告性失败）；
2. `:main:assembleRelease`（在 `local.properties` 配好签名的机器上产出已签名 `SenkGallery-Release.apk`）+ `:core-data:testDebugUnitTest` + `:common-util:testDebugUnitTest` + `:main:lintDebug`；
3. `python scripts/check-color-pairs.py`（颜色成对校验，见 [主题与颜色规范](theme.md)）；
4. 真机安装与端到端用例见 [验证方法与判据](verification.md)；历史版本验证数据见 [开发史](history.md)。
