# 第三方组件与素材声明

本项目的**自有代码**以 [Apache License 2.0](LICENSE) 授权，版权归 `Copyright 2026 SENK001`。

本文件列出项目中使用的第三方组件与素材。**这些内容不适用本项目自有的 Apache-2.0 授权**，各自遵循其原始许可；本项目的 `LICENSE` 也不对它们作出任何授权。

---

## 1. 代码素材：Material Design Icons（图形资源，随源码分发）

**重要**：项目中有 17 个矢量图标的路径数据是从 Google Material Design Icons **逐字节复制**而来（并非仅"参考样式"），因此必须保留以下声明。
| 项目 | 内容 |
|---|---|
| 素材 | Material Design Icons（Material Symbols 前身） |
| 来源 | https://github.com/google/material-design-icons |
| 许可 | Apache License 2.0（经 GitHub License API 核验：SPDX `Apache-2.0`） |
| 版权 | Copyright Google LLC（见上游仓库 `LICENSE` 与 `NOTICE`） |
| 修改情况 | 未修改路径数据；仅按需调整 `android:tint`、尺寸与文件名 |

涉及的图标对应关系（本项目文件 → 上游图标名）：

| 本项目文件 | 上游 Material 图标 |
|---|---|
| `main/src/main/res/drawable/ic_privacy_perm_media.xml` | `image` |
| `main/src/main/res/drawable/ic_privacy_photo.xml` | `image` |
| `main/src/main/res/drawable/ic_tab_photos.xml` | `image`（较早期版本的路径变体） |
| `main/src/main/res/drawable/ic_tab_albums.xml` | `folder` |
| `main/src/main/res/drawable/ic_privacy_perm_location.xml` | `location_on` |
| `main/src/main/res/drawable/ic_privacy_perm_favorite.xml` | `favorite` |
| `main-ui/src/main/res/drawable/ic_arrow_back.xml` | `arrow_back` |
| `main-ui/src/main/res/drawable/ic_favorite.xml` | `favorite` |
| `main-ui/src/main/res/drawable/ic_favorite_border.xml` | `favorite_border` |
| `main-ui/src/main/res/drawable/ic_fullscreen.xml` | `fullscreen` |
| `main-ui/src/main/res/drawable/ic_fullscreen_exit.xml` | `fullscreen_exit` |
| `main-ui/src/main/res/drawable/ic_info.xml` | `info` |
| `main-ui/src/main/res/drawable/ic_share.xml` | `share` |
| `main-ui/src/main/res/drawable/ic_play_arrow.xml` | `play_arrow` |
| `main-ui/src/main/res/drawable/ic_pause.xml` | `pause` |
| `main-ui/src/main/res/drawable/ic_nav.xml` | `navigation` |

合计 **17** 个文件复制自上游（其中 `image` 有 3 处、`favorite` 有 2 处，为同一图标的不同尺寸/色调复用）。

**非上游素材（本项目原创，随代码以 Apache-2.0 授权）**：
- `main/src/main/res/drawable/ic_launcher_foreground.xml`（应用图标前景，机器人与网格为自绘路径）
- `main/src/main/res/drawable/ic_launcher_background.xml`（108×108 网格底，Android 自适应图标模板式几何）
- `main/src/main/res/drawable/ic_privacy_badge_shield.xml`（隐私标识盾牌）
- 全部 `bg_*.xml`（圆角/渐变/描边等形状资源）与 `mipmap-*/ic_launcher*.webp`（由上述矢量图标生成）

---

## 2. 构建工具：Gradle Wrapper

| 项目 | 内容 |
|---|---|
| 文件 | `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` |
| 来源 | Gradle 发行版自动生成（`gradle-9.4.1-bin.zip`） |
| 许可 | Apache License 2.0 |
| 版权 | `Copyright © 2015 the original authors`（已保留于 `gradlew` 文件头部） |

---

## 3. 编译期依赖（随 APK 分发）

以下依赖均为 **Apache License 2.0**。各自版权归其上游组织所有（AndroidX 与 Material Components 为 The Android Open Source Project / Google LLC；kotlinx.coroutines 为 JetBrains s.r.o.）。完整许可文本见本仓库 [LICENSE](LICENSE)（同为 Apache-2.0），逐库版权与 NOTICE 以上游仓库为准。

**AndroidX（The Android Open Source Project）**

| 组件 | 版本 |
|---|---|
| `androidx.core:core-ktx` | 1.18.0 |
| `androidx.appcompat:appcompat` | 1.8.0 |
| `androidx.activity:activity-ktx` | 1.13.0 |
| `androidx.constraintlayout:constraintlayout` | 2.2.2 |
| `androidx.exifinterface:exifinterface` | 1.3.7 |
| `androidx.recyclerview:recyclerview` | 1.4.0 |
| `androidx.viewpager2:viewpager2` | 1.0.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 |

**Google**

| 组件 | 版本 | 许可 |
|---|---|---|
| `com.google.android.material:material` | 1.14.0 | Apache-2.0 |

**JetBrains**

| 组件 | 版本 | 许可 |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 |

**Android Gradle Plugin（仅构建期，不随 APK 分发）**：`com.android.application` / `com.android.library` 9.2.1，Apache-2.0。

---

## 4. 测试期依赖（不随 APK 分发）

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 | JUnit 4 |
| `androidx.test.ext:junit` | 1.3.0 | Apache-2.0 | AndroidX Test |
| `androidx.test.espresso:espresso-core` | 3.7.0 | Apache-2.0 | Espresso |

> 注意：`junit:junit` 使用 **EPL-1.0**，与本项目自有的 Apache-2.0 不同。它仅用于单元测试，不进入 APK。

---

## 5. 百度地图 SDK —— 专有软件，**不由本项目授权**

**这是本项目中唯一非开源的组件，请特别留意。**

| 项目 | 内容 |
|---|---|
| 组件 | 百度地图 Android SDK |
| Maven 坐标 | `com.baidu.lbsyun:BaiduMapSDK_Map:8.2.0`、`BaiduMapSDK_Search:8.2.0`、`BaiduMapSDK_Util:8.2.0`（及传递依赖 `base:8.2.0`、`common:1.0.43`） |
| 版权 | 百度（北京百度网讯科技有限公司） |
| 引入方式 | 由 Gradle 在**构建时**从 Maven Central 下载，**不存放在本仓库中** |

**声明与免责：**

1. 该 SDK 的 POM 元数据中自行声明许可为 `The Apache License, Version 2.0`。**本项目的作者无法核验该声明的效力，也不对其作出任何确认或保证。** 发行方元数据中的许可声明，不等同于与百度签订的《百度地图开放平台开发者服务条款》。
2. **本项目不对百度地图 SDK 主张任何授权，也不对其进行再许可。** 本项目的 Apache-2.0 授权仅覆盖本项目自有的源代码与资源，明确不覆盖该 SDK。
3. 任何人构建、使用或分发本项目时，**须自行**：
   - 遵守[百度地图开放平台服务条款](https://lbsyun.baidu.com/docs/pcsa?title=law/open/law)；
   - 自行申请 API Key（AK），并在 `local.properties` 中配置 `BAIDU_MAP_API_KEY`（模板见 `local.properties.example`）；
   - 自行确认其使用场景（含商业用途、再分发）是否获得百度授权。
4. 本仓库历史上曾包含百度地图 SDK 的二进制文件（AAR 与 4 个 ABI 的 `.so`，共 75.5 MB）。**已于提交 `9fdf1a7` 中连同全部 Git 历史一并移除**，当前仓库不含任何百度地图 SDK 二进制。

---

## 6. 其他说明

- **应用名称与图标**：本项目为个人学习/作品项目。项目名 "Gallery" 为通用词，未主张商标权。应用图标为自绘，不含第三方美术素材。
- **设计参考**：底部胶囊导航栏、隐私同意页在**视觉布局**上参考了高德地图与 MIUI/HyperOS 系统应用，属设计风格借鉴，**未复制其代码、资源文件或图标路径数据**。
- **真机验证截图**（`verification-reports/`）含个人照片，已在 `.gitignore` 中排除，不进版本库。

---

如有遗漏或声明不准确之处，欢迎提交 Issue 指出。
