# 验证方法与判据

本文件规定「改完怎么验」：门禁组合、静态检查、真机流程、GL 相关专项判据，以及本机基线数据。长期结论与根因记录在 [开发史](history.md)；证据文件放在本机 `verification-reports/`（已在 `.gitignore` 中排除，不进版本库）。

## 1. 最小门禁（每次改动）

```bash
./gradlew :main:assembleDebug --offline            # 编译通过
./gradlew :core-data:testDebugUnitTest \
          :common-util:testDebugUnitTest --offline # 单元测试
./gradlew :main:lintDebug --offline                # lint
python scripts/check-color-pairs.py                # 颜色成对 + 悬空引用
```

发布前再加 `:main:assembleRelease`（含签名）与冷编译（`--stop` + `clean` + `--no-build-cache`）。

**通过线**（2026-10 基线；数值可随环境漂移，判断时看「规则 + 位置」而非总数）：

| 项 | 通过线 |
|---|---|
| 构建 | BUILD SUCCESSFUL，冷编译任务真实执行 |
| 单元测试 | **16/16** 全绿（见 §2） |
| lint | **0 error** / ≤5 warning；无「与本次改动相关」的新条目 |
| 颜色校验 | `OK —— 29 个颜色全部成对定义，且无悬空引用。` |
| 崩溃 | `adb logcat -d -b crash` 中无 `com.senk.gallery` |

lint 的版本检查类告警（`GradleDependency` / `NewerVersionAvailable`）会随联网查 Maven 新版而漂移，凡出现在 `libs.versions.toml` 的此类条目与代码改动无因果关系，按「文件位置」排除。

## 2. 单元测试清单

| 测试 | 数量 | 覆盖 |
|---|---|---|
| `XmpParserTest`（core-data） | 8 | 动态照片标记（`GCamera:MotionPhoto` / `MicroVideo`）、非动态照片反例、`Container:Item` 的 `Item:Length` 提取、旧格式 `MicroVideoOffset`、缺标记返回 0、`GPano` 全景、尺寸兜底 `isPanoramaBySize`（含 8000×2000 真、4000×3000 假、0×0 假） |
| `CoordConverterTest`（common-util） | 4 | 上海/深圳/北京 WGS-84→GCJ-02 数值（1e-6 容差）、境外坐标保持不变 |
| `ExampleUnitTest` × 4 模块 | 4 | 模板占位（工程脚手架，无业务断言） |

当前 **没有连接仪器（instrumentation）测试与 UI 自动化测试**；UI 行为靠 §4 真机手测 + §5 像素判据。

## 3. 静态检查：颜色成对（`scripts/check-color-pairs.py`）

- 逐模块（`main`、`main-ui`）比对 `values/colors.xml` 与 `values-night/colors.xml` 名字集合：缺 night → 报错；night 多出 → 报错（拼写错误）。
- 扫描 `res/` + `java/` 的 `.xml/.kt/.java`，`@color/xxx` 引用必须都有定义（改名未收尾的兜底）。
- `res/color/` 的 ColorStateList 不需要 night 版（内部状态色各自成对即可）。
- 通过输出行见 §1 通过线；规范细节见 [主题与颜色规范](theme.md)。

## 4. 真机端到端流程

```bash
ADB=$ANDROID_HOME/platform-tools/adb

# 安装（产物名见 build-and-release.md）
$ADB install -r main/build/outputs/apk/debug/SenkGallery-Debug.apk

# 模拟首启（清空同意状态与权限）
$ADB shell pm clear com.senk.gallery
$ADB shell am start -n com.senk.gallery/.PrivacyActivity

# 深色模式切换（观察主题适配）
$ADB shell cmd uimode night yes     # / no

# 崩溃检查
$ADB logcat -d -b crash | grep senk
```

**截图铁律：不要用 `/sdcard` 中转。** `adb shell screencap -p /sdcard/xxx.png` 会写入 MediaStore → 触发照片页 `ContentObserver` → `loadPage(reset = true)` → `scrollToTop()`，**每截一张图就把宫格弹回顶部**（曾据此误判「应用自己回弹」）。截图与 `uiautomator dump` 一律走 `/data/local/tmp`（不进媒体库）：

```bash
$ADB shell screencap -p /data/local/tmp/_s.png
$ADB pull /data/local/tmp/_s.png ./screenshots/xxx.png
$ADB shell rm /data/local/tmp/_s.png
```

**界面状态检查**：

- `uiautomator dump` 可取 View 的真实 bounds（如标题栏 `[52,207][196,304]`），用于几何量化；
- 两点限制：① 渐变遮罩（纯 `View`、无文本）**不出现在 dump 里**，判定「是否已上划」要用「与静止截图对比亮度」；② **GL 内容不在 View 树中**（宫格/查看器画面 dump 不到），GL 相关一律用截图逐像素判断（见 §5）。
- `monkey` 拉起应用前先 `am force-stop`：否则会把上次遗留的查看器带到前台，静止态截图会抓成整屏单张照片。

**媒体库复核**（这台设备上排序表达式是否被接受）：

```bash
$ADB shell "content query --uri content://media/external/file \
  --projection _id:datetaken \
  --sort 'COALESCE(datetaken, date_modified * 1000) DESC, _id DESC'"
```

对照（应报 `Invalid token`）：把 `datetaken` 换成 Kotlin 常量名 `date_taken`。细节见 [数据层 §4](data-layer.md)。

**手测清单**（当前版本应全部正常、无崩溃）：照片 Tab / 相册 Tab / 相册详情 / 查看器翻页 / 沉浸切换 / 上划详情面板 / EXIF / 地图卡片 / 逆地理编码 / 收藏写回授权 / 视频播放（控制栏、自动隐藏、横屏）/ 动态照片长按播放 / 隐私首启页（退出、链接、同意、二次启动不再显示）。

## 5. GL 相关验证专项

1. **GL 画面只能通过截图验证**（见 §4）；需要「文字 vs 内容」这类判断时，用**纯白像素计数**这类结构性指标，而不是最大值/均值——亮色缩略图本身也能达到高亮度，会骗过统计（标题栏 z-order 修复的判据：标题框内纯白像素修复前 **0**、修复后 **2894**，同 y 背景区始终 **0**；见 [开发史](history.md)）。
2. **改渐变/遮罩时，先临时屏蔽渐变再验证布局**（硬性约定）：把 `ToolbarScrimDrawable.START_ALPHA` 临时改 `0x00` 重建——
   - 屏蔽后顶部仍纯白 → 布局/边距问题（查 `topInset`）；
   - 屏蔽后能看到缩略图 → 布局正确，剩下只是渐变观感。
   顺序：① 屏蔽验证布局 → ② 恢复渐变调观感 → ③ 移除临时代码并确认无残留。
3. **不屏蔽时的辅助判据**：同一高度取**多个 x**，取值**互不相同**才说明有图像内容透出；只取单点或完全一致（如清一色 `(116,116,116)`）不足以区分「被压平」与「真的空白」。
4. **几何量化约定**：`maxScroll` 与 `cellY(rowCount−1) + cellSize − scrolled` 的差值应精确等于 `屏高 − bottomPadding`；打印几何量（`vp/cell/topInset/rows`）比反复调数值猜测有效。

## 6. 本机基线数据（Redmi 25053RT47C / Android 16 / 1280×2772 / density 520 / 手势导航）

用于对照，换设备后数值会变（状态栏 152px、`actionBarSize` 208px、系统导航栏 52px）：

| 场景 | 判据 | 基线 |
|---|---|---|
| 照片页静止（浅色） | 顶部取色 | `y=0..355` = `255`（标题栏区域）；首行缩略图从 `y≈360` 开始 |
| 照片页静止（深色） | 顶部取色 | `y=2..355` = `(28,27,31)` |
| 上划后 | 同高度多 x 取样 | 取值互不相同（有照片内容）；遮罩自顶部最深向下递减 |
| 滚到底 | 最后一行底边 | 照片页 `2486`（= 2772 − 286：胶囊占位 234 + 系统导航栏 52）；相册详情页 `2720`（= 2772 − 52） |
| 相册页 | 顶部 | 始终 `255`（白底标题栏，不受上划影响） |
| 相册详情页静止 | 顶部取色 | `y=0..355` = `255`，首行缩略图 `y≈400` |

历史各轮实测数据（构建耗时、APK 体积、像素差值、WCAG 对比度等）记录在 [开发史](history.md) 对应条目。

## 7. 验证产物归档

- 本机 `verification-reports/`：构建/测试/lint 日志、真机截图、一次性报告与脚本（`capture.sh`、`analyze_title.py` 等）。**不进版本库**，重新生成方法见该目录 `README.md`。
- 进入版本库的是「长期有效」结论：判据写在本文件，根因与数据写进 [开发史](history.md)；未覆盖项汇总在 [暂缓项与已知限制](backlog.md)。

## 8. 当前未覆盖（勿当作通过）

- 无仪器测试 / UI 自动化；全程手工 + 脚本辅助。
- 仅在一台 Redmi / Android 16 真机 + Android 16 x86_64 模拟器上验证过；其它 ROM 的状态栏高度、`actionBarSize`、系统导航栏取值都会改变 §6 的数值。
- 横屏、平板/折叠屏、`fontScale > 1`、地图页深色模式等场景未覆盖，见 [backlog](backlog.md)。
