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

# 常规构建：真机（arm64-v8a）单个变体；模拟器改用 :main:assembleX86_64Debug
./gradlew :main:assembleArm64Debug

# 一次构建全部 5 个 ABI 变体（arm64 / armv7 / x86 / x86_64 / universal）
./gradlew :main:assembleDebug

# 冷启动全新编译（排除增量缓存干扰，验证时用）
./gradlew clean
./gradlew :main:assembleArm64Debug --offline --no-build-cache

# Release + 单元测试 + lint（发布前）
./gradlew :main:assembleRelease \
          :core-data:testDebugUnitTest \
          :common-util:testDebugUnitTest \
          :main:lintArm64Debug --offline
```

产物位置（`<flavor>` 见 §3；路径中的 buildType 目录替换为 `release` 即 release 产物）：

| 产物 | 路径 |
|---|---|
| Debug APK（arm64 示例） | `main/build/outputs/apk/arm64/debug/SenkGallery-Debug-arm64-v8a.apk` |
| Release APK（arm64 示例） | `main/build/outputs/apk/arm64/release/SenkGallery-Release-arm64-v8a.apk`（已签名）或未签名包（见 §4） |
| 基线 Profile（arm64 示例） | `main/build/outputs/apk/arm64/release/baselineProfiles/*/SenkGallery-Release-arm64-v8a.dm` |
| 单元测试报告 | `<module>/build/reports/tests/testDebugUnitTest/index.html`（原始数据 `<module>/build/test-results/testDebugUnitTest/*.xml`） |
| Lint 报告 | `main/build/reports/lint-results-arm64Debug.html` / `.xml` |

安装与真机流程见 [验证方法与判据](verification.md)。

## 3. ABI 变体（flavor 维度 `abi`，`main/build.gradle`）

**背景**：百度地图 SDK 自带 4 个 ABI 的 `.so`——全部打包 APK ≈90MB，只留一个 ABI 则 30–40MB；但单 ABI 的包装不进其它架构的设备/模拟器（原生库加载失败），而模拟器调试很常用。因此把 ABI 做成构建变体：选中的变体决定 `ndk.abiFilters`，不再探测连接的设备（历史机制见 [开发史](history.md) 2026-10-01 条目及其更正记录）。

| flavor | ABI（`ndk.abiFilters`） |
|---|---|
| `arm64` | `arm64-v8a` |
| `armv7` | `armeabi-v7a` |
| `x86` | `x86` |
| `x86_64` | `x86_64` |
| `universal` | 无（不过滤，4 个 ABI 全打包） |

- 变体名 = `<flavor><BuildType>`，共 10 个：`arm64Debug` / `arm64Release` / … / `universalRelease`；
- 常用任务：`assembleArm64Debug`、`installX86_64Debug`、`lintArm64Debug`、`testArm64DebugUnitTest`；聚合任务 `assembleDebug` / `assembleRelease` 构建全部 5 个 flavor；
- Android Studio 在 Build Variants 面板选择变体（带 flavor 后不再存在默认的 `debug` / `release` 单选）；
- 单 ABI 产物 debug ≈33–40MB、release ≈30–38MB；`universal` debug ≈90MB、release ≈88MB（2026-10-17 实测数据见 [开发史](history.md)），日常开发用单 ABI 变体、分发/兼容用 `universal`。

实现要点（改动前必读）：

- flavor 名 -> `abiFilters` 的单一事实来源是 `main/build.gradle` 顶部的 `abiFlavorFilters` 映射（`flavorDimensions.add('abi')` + `productFlavors { ... register(flavorName) ... }`）；新增 flavor 只需在此映射加一行；
- 容器元素创建用惰性 `register(name) { ... }` 而非 `create(...)`：`create` 在新版 Gradle API 检查中标记 `@Deprecated`；AGP 枚举变体时会把惰性注册的 flavor 全部实例化（实测无差异）；
- AGP 9 公开 DSL 里 `flavorDimensions` 是 `MutableList`（用 `.add('abi')`），`ProductFlavor.dimension` 是属性（用 `dimension = 'abi'` 赋值）；
- ABI 拆分（`splits.abi`）与 `ndk.abiFilters` **互斥**（AGP 会报 Conflicting configuration），本应用不启用任何拆分；
- `x86_64` 这类带下划线的 flavor 名合法，变体/任务名原样保留。

## 4. 签名与密钥（`local.properties`）

AK 与签名口令**都不进版本库**，统一从 `local.properties`（已忽略）读取；模板见根目录 `local.properties.example`。

| 键 | 缺失时行为 |
|---|---|
| `sdk.dir` | Android Studio 自动生成；其它场景可用 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 环境变量 |
| `BAIDU_MAP_API_KEY` | 注入空串 → SDK 初始化失败但**应用不崩溃**（地图/逆地理编码不可用） |
| `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | 四项缺一即视为未配置 → release 产出**未签名包**，不影响他人 clone 后构建 |

- 签名同时启用 **v1 + v2 + v3**（v2+ 为 Android 7+ 必需，v1 兼容更早）。
- 密钥文件建议放仓库外（如 `~/.android/release.keystore`）；`.gitignore` 已兜底排除 `*.jks` / `*.keystore` / `keystore.properties` / `/main/release/`。
- 生成新密钥与查看 SHA1（申请百度 AK 用）的命令写在 `local.properties.example` 注释里。

## 5. APK 产物改名

`main/build.gradle` 用 AGP 公开 Variant API 把产物直接改名（不是打包后再复制副本）。命名规则 **`SenkGallery-<BuildType>-<ABI>.apk`**（`<ABI>` 为该 flavor 的 `abiFilters` 值，`universal` 用 `universal`），路径 `main/build/outputs/apk/<flavor>/<buildType>/`：

```groovy
androidComponents {
    onVariants(selector().all()) { variant ->
        def abiFlavor = variant.productFlavors.find { it.first == 'abi' }?.second
        ...
        output.outputFileName.set("SenkGallery-${variant.buildType.capitalize()}-${abiLabel}.apk")
    }
}
```

| 变体 | 产物 |
|---|---|
| `arm64Debug` | `arm64/debug/SenkGallery-Debug-arm64-v8a.apk` |
| `x86_64Release` | `x86_64/release/SenkGallery-Release-x86_64.apk` |
| `universalRelease` | `universal/release/SenkGallery-Release-universal.apk` |

- `VariantOutput.outputFileName` 是公开 `Property<String>`（AGP 明确允许自定义），改名会一路贯彻到 `output-metadata.json` 与 baseline profile `.dm` 文件名，Android Studio 的 Run/Debug 与 `installArm64Debug` 等 install 任务都照常工作，磁盘上不会留下 `main-*.apk` 的副本。
- 老写法 `applicationVariants.all { outputFileName = ... }` 已在 AGP 8 移除，**不要再使用**。
- 守卫：若启用了 ABI 拆分且变体有多个 output，构建直接报错（固定文件名会互相覆盖）；本应用不启用拆分，正常不会触发。
- 新增 flavor 时只需在 `abiFlavorFilters` 映射加一行，产物名自动跟随（`universal` 特判）。

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

1. `./gradlew clean` + `:main:assembleArm64Debug --offline --no-build-cache`（冷编译无警告性失败；全变体用 `:main:assembleDebug`）；
2. `:main:assembleRelease`（在 `local.properties` 配好签名的机器上产出 5 个 flavor 的已签名 `SenkGallery-Release-<ABI>.apk`）+ `:core-data:testDebugUnitTest` + `:common-util:testDebugUnitTest` + `:main:lintArm64Debug`；
3. `python scripts/check-color-pairs.py`（颜色成对校验，见 [主题与颜色规范](theme.md)）；
4. 真机安装与端到端用例见 [验证方法与判据](verification.md)；历史版本验证数据见 [开发史](history.md)。

## 9. CI（GitHub Actions）

工作流文件：`.github/workflows/ci.yml`（名称 **CI**）。

| 项 | 配置 |
|---|---|
| 触发 | `push`（任意分支/标签）自动运行；`workflow_dispatch` 可在 Actions 页面手动运行 |
| 并发 | 同一 ref 的新推送取消仍在跑的旧任务（`concurrency.cancel-in-progress`） |
| Runner | `ubuntu-latest`，单 job，超时 45 分钟 |
| JDK | Temurin **21**（`actions/setup-java@v5`） |
| Gradle | wrapper **9.4.1**；`gradle/actions/setup-gradle` 负责依赖/构建缓存与配置缓存 |
| Android SDK | **直接使用 runner 预装 SDK**：`ubuntu-latest` 镜像自带 `ANDROID_HOME=/usr/local/lib/android/sdk`、`platforms;android-37.2`、`build-tools;37.0.0`；流水线只做存在性检查，缺失时打印实际 platforms/build-tools 列表并失败 |
| 门禁步骤 | 编译 `:main:assembleArm64Debug` → 单测（`main:testArm64DebugUnitTest` + 三个 library 的 `testDebugUnitTest`）→ `python3 scripts/check-color-pairs.py` |
| 产物 | artifact `SenkGallery-Debug-arm64`（`main/build/outputs/apk/arm64/debug/*.apk`），保留 7 天 |

- **不需要 `local.properties`**：CI 用 runner 的 `ANDROID_HOME` 定位 SDK；AK 注入空串、release 签名跳过，均不影响 Debug 编译（已本地模拟验证）。
- `gradlew` 在 git index 中已置为可执行（`100755`）；Windows 检出后流水线仍带 `chmod +x ./gradlew` 兜底。
- Debug 只编 arm64 一个变体作为「可编译」代表（其余 flavor 只差 ABI 打包）；需要全变体时把任务改成 `:main:assembleDebug`。
- 本地复现同一组门禁的命令见 §2。
- **为什么不用 `android-actions/setup-android`**：2026-10-21 首次 CI 运行在 `ubuntu-latest`（Ubuntu 26）上该 action 安装 cmdline-tools 失败；而该镜像本身已预装上述 SDK 组件，故移除 action，改为存在性检查。runner 镜像后续若移除对应版本，需在 workflow 里补显式安装（并以镜像文档 `actions/runner-images/images/ubuntu/Ubuntu2604-Readme.md` 的组件清单为准）。
