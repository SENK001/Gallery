# 外部调用 API（选图 / 看大图）

外部应用可以通过本应用（「相册」，包名 `com.senk.gallery`）完成**选择照片/视频**与**查看大图**两件事。
本文件给出 action、extras、返回值、URI 授权与限制的完整约定；实现入口是：

- `main` 的 `com.senk.gallery.ExternalEntryActivity`：`exported=true`，外部**唯一入口**（透明窗口，只做校验/路由）；
- `external-api` 模块的 `com.senk.gallery.external.GalleryExternal`：action / extra 常量与 Intent 构造工具；
- `core-data` 的 `com.senk.gallery.data.provider.ExternalMediaProvider`：选图结果 URI 的只读代理 Provider。

> 关键设计：**返回给调用方的是本应用代理 URI**（`content://com.senk.gallery.external/media/{id}`），
> 不是 MediaStore 原始 URI。调用方不需要、也无法拿到系统媒体库权限；由本应用对**用户实际选中的条目**
> 逐个临时授权，调用方直接 `ContentResolver.openInputStream(...)` 读取即可。

---

## 1. 能力一览

| Action | 作用 | 调用方式 | 结果 |
|---|---|---|---|
| `com.senk.gallery.action.PICK_IMAGE` | 打开本应用选图页（单选/多选、照片/相册分类） | `startActivityForResult` | `RESULT_OK` + 代理 URI（`data` / `ClipData` / extras） |
| `com.senk.gallery.action.VIEW_IMAGE` | 以本应用查看器全屏查看调用方给的图片/视频 | `startActivity`（或 `startActivityForResult`） | 用户退出查看器后返回；无数据结果 |

目标组件也可以用 `setPackage("com.senk.gallery")` 或显式 `setClassName("com.senk.gallery", "com.senk.gallery.ExternalEntryActivity")` 指定。

---

## 2. 前提条件（调用前请先判断）

1. **隐私政策已同意**：本应用首启有隐私同意页，用户未同意前，外部调用会被拒绝（Toast
   「请先打开相册并同意隐私政策」，返回 `RESULT_CANCELED`），不会读取任何媒体。
   调用方无法代替用户同意；首次调用失败时应引导用户**手动打开一次「相册」**完成同意。
2. **媒体权限（仅 PICK_IMAGE）**：选图需要用户已授予照片/视频访问权限。未授权时本应用会弹出系统
   权限申请；拒绝则返回 `RESULT_CANCELED`。若用户只授予了「部分照片」权限，选图页只列出用户允许的媒体。
3. **看大图不需要本应用的媒体权限**：待查看 URI 的读权限由**调用方**通过
   `Intent.FLAG_GRANT_READ_URI_PERMISSION` 临时授予本应用，本应用只在该 URI 上读写（只读）。
4. 本应用 `minSdk 36`（Android 16+）；调用方无特殊版本要求，能发 `startActivityForResult` 即可。

---

## 3. `PICK_IMAGE`：选择照片/视频

### 3.1 请求

```kotlin
val intent = GalleryExternal.pickImageIntent(
    allowMultiple = true,                 // 单选传 false（或省略）
    maxCount = 9,                         // 多选上限；≤0 时用默认值 9；单选忽略
    title = "选择封面",                    // 可选：选图页标题
).setPackage(GalleryExternal.APP_PACKAGE)
startActivityForResult(intent, REQ_PICK_IMAGE)
```

请求 extras：

| Extra | 类型 | 必填 | 说明 |
|---|---|---|---|
| `com.senk.gallery.extra.ALLOW_MULTIPLE` | `Boolean` | 否 | 是否多选，默认 `false` |
| `com.senk.gallery.extra.MAX_COUNT` | `Int` | 否 | 多选上限，默认 `9`（≤0 视为默认）；单选模式忽略 |
| `com.senk.gallery.extra.TITLE` | `String` | 否 | 选图页标题（空/缺省用「选择照片」） |

### 3.2 结果（`RESULT_OK`）

返回的 URI 全部是**本应用代理 URI**：`content://com.senk.gallery.external/media/{id}`。

| 位置 | 类型 | 说明 |
|---|---|---|
| `data` | `Uri` | **单选**时 = 选中的那个代理 URI；多选时为空 |
| `ClipData` | `ClipData` | **多选**时包含全部代理 URI（每项 `getUri(0)`）；单选时为空 |
| `com.senk.gallery.extra.SELECTED_URIS` | `ArrayList<String>` | 全部代理 URI 字符串（单选/多选均有，顺序 = 选择顺序） |
| `com.senk.gallery.extra.SELECTED_COUNT` | `Int` | 选中数量 |

结果 Intent 带 `FLAG_GRANT_READ_URI_PERMISSION`，并且本应用还会对调用方
（`getCallingPackage()`）逐个显式 `grantUriPermission(..., FLAG_GRANT_READ_URI_PERMISSION)`。

> **推荐读取方式**：优先读 `EXTRA_SELECTED_URIS`（两种模式都存在），单选可回退到 `data`，
> 多选可回退到 `ClipData`。

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
        // 用户取消 / 隐私未同意 / 权限被拒：都按取消处理
        return
    }
    val uris = data.getStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS)
        ?.map(Uri::parse)
        ?: listOfNotNull(data.data)               // 兜底：单选 data
        ?: data.clipData?.let { clip ->           // 兜底：多选 ClipData
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
    val count = data.getIntExtra(GalleryExternal.EXTRA_SELECTED_COUNT, uris.size)
    uris.forEach { uri ->
        contentResolver.openInputStream(uri)?.use { input ->
            // 读取图片/视频字节（只读）；也可用 openFileDescriptor(uri, "r")
        }
    }
}
```

也可以查询元数据（列名与系统 `OpenableColumns`/`MediaStore` 对齐）：

```kotlin
contentResolver.query(
    uri,
    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE),
    null, null, null,
)?.use { cursor -> /* _id, display_name, mime_type, size, media_type */ }
```

### 3.3 取消

以下情况返回 `RESULT_CANCELED`（部分情况本应用会 Toast 提示）：

- 用户按返回/关闭选图页；
- 隐私政策未同意；
- 媒体权限被拒绝；
- action 不正确（未知 action）。

---

## 4. `VIEW_IMAGE`：查看大图

### 4.1 请求

```kotlin
val imageUri: Uri = ... // 调用方自己的 content:// URI
val intent = GalleryExternal.viewImageIntent(listOf(imageUri), position = 0)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // 必须：把 URI 读权限临时授予本应用
    .setPackage(GalleryExternal.APP_PACKAGE)
startActivity(intent)
```

请求 extras：

| Extra | 类型 | 必填 | 说明 |
|---|---|---|---|
| `data` | `Uri` | 二选一 | 单个待查看 URI（推荐与 `VIEW_URIS` 同时给当前项） |
| `com.senk.gallery.extra.VIEW_URIS` | `ArrayList<String>` | 二选一 | 多图 URI 列表，支持左右翻页；第一项不一定是初始项，初始项由 `VIEW_POSITION` 指定 |
| `com.senk.gallery.extra.VIEW_POSITION` | `Int` | 否 | 初始位置，默认 0，越界自动收敛 |
| `com.senk.gallery.extra.TITLE` | `String` | 否 | 顶部标题，缺省显示文件名 |

约束：

- 只支持 `content://` 与 `file://` 两种 scheme；`http(s)` 等网络地址会被拒绝（`RESULT_CANCELED` + Toast）。
  `file://` 在 Android 7+ 的跨应用共享场景基本不可用，请优先使用 `content://`。
- 调用方必须对每个 URI 带 `FLAG_GRANT_READ_URI_PERMISSION`（或已用 `grantUriPermission` 显式授予本应用），
  否则查看器无法读取文件。若是本应用 MediaStore 之外的第三方 Provider，请确保其支持 `openFile`/`openInputStream`。
- 本应用会保持接收 `VIEW_IMAGE` 的 `ExternalEntryActivity` 存活，直到查看器关闭，
  以保证调用方授予的临时 URI 权限在查看期间有效（这是不立即 `finish()` 的原因）。

### 4.2 行为与结果

- 查看器支持：左右翻页、双击/双指缩放、单击沉浸、视频封面 + 点击播放（同一套播放器）。
- 外部图片**不显示**「收藏」「详情」（EXIF/地图依赖本应用媒体库 id），分享仍可用。
- 用户退出查看器后本应用返回；如调用方用 `startActivityForResult`，会收到查看器返回码
  （通常为 `RESULT_CANCELED`，本应用不返回数据）。本能力只读展示，不产生 URI 结果。

---

## 5. 代理 URI 与临时授权

### 5.1 格式

```
content://com.senk.gallery.external/media/{mediaId}
```

- authority：`com.senk.gallery.external`（`ExternalMediaProvider`，`exported=false` + `grantUriPermissions=true`）；
- `{mediaId}` 是本应用内部 MediaStore 媒体 id（正整数）；
- 只支持精确到单条媒体的 URI，**不支持目录/列表查询**，也不支持 insert/update/delete。

### 5.2 授权语义（重要）

1. 授权发生在**选图结果返回时**：本应用对 `getCallingPackage()` 逐个 URI `grantUriPermission`，
   同时在结果 Intent 上带 `FLAG_GRANT_READ_URI_PERMISSION` + `ClipData`/`data`，系统也会授予一份。
2. **读权限是临时的**：系统可在调用方任务/进程被回收等时机回收经结果 Intent 授予的权限；
   显式授权也会随系统回收策略失效。**不要假设 URI 永久可读**，请在拿到结果后尽快读取/转存，
   不要长期持久化 URI 供以后后台读取（如确需长期保存，请把字节复制到调用方自己的存储）。
3. 授权对象是「用户实际选中的那几条媒体」，不是整个相册；未选中的媒体 id 即使拼出 URI 也读不到。
4. 应用被卸载/数据被清除后代理 URI 失效。

### 5.3 权限不足的表现

- 未授权/授权过期：`openInputStream` 抛 `SecurityException`（或 `FileNotFoundException`）；
- id 非法或媒体已被删除：`openInputStream` / `query` 返回失败，`openFile` 抛 `FileNotFoundException`；
- 本应用未同意隐私政策时**不会**返回任何 URI。

---

## 6. 完整示例

### 6.1 多选并读取（Activity Result API，推荐）

```kotlin
private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val data = result.data
    if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
    val uris = data.getStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS)
        ?.map(Uri::parse).orEmpty()
    val count = data.getIntExtra(GalleryExternal.EXTRA_SELECTED_COUNT, uris.size)
    Log.i("ExternalClient", "picked $count: $uris")
    uris.forEach { uri ->
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.onFailure { Log.e("ExternalClient", "read $uri failed", it) }
    }
}

fun pick() {
    val intent = GalleryExternal.pickImageIntent(allowMultiple = true, maxCount = 5)
        .setPackage(GalleryExternal.APP_PACKAGE)
    pickImage.launch(intent)
}
```

### 6.2 查看调用方图片

```kotlin
val uri: Uri = ... // 自己的 content URI
val intent = GalleryExternal.viewImageIntent(uris = listOf(uri), position = 0)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    .setPackage(GalleryExternal.APP_PACKAGE)
startActivity(intent)
```

### 6.3 不做编译期依赖（纯字符串拼接）

不引入 `external-api` 模块时，可直接用常量值（见第 7 节）：

```kotlin
val intent = Intent("com.senk.gallery.action.PICK_IMAGE")
    .addCategory(Intent.CATEGORY_DEFAULT)
    .putExtra("com.senk.gallery.extra.ALLOW_MULTIPLE", true)
    .putExtra("com.senk.gallery.extra.MAX_COUNT", 9)
    .setPackage("com.senk.gallery")
startActivityForResult(intent, 1001)
```

---

## 7. 常量速查

| 常量 | 值 |
|---|---|
| `GalleryExternal.APP_PACKAGE` | `com.senk.gallery` |
| `ACTION_PICK_IMAGE` | `com.senk.gallery.action.PICK_IMAGE` |
| `ACTION_VIEW_IMAGE` | `com.senk.gallery.action.VIEW_IMAGE` |
| `EXTRA_ALLOW_MULTIPLE` | `com.senk.gallery.extra.ALLOW_MULTIPLE` |
| `EXTRA_MAX_COUNT` | `com.senk.gallery.extra.MAX_COUNT` |
| `EXTRA_TITLE` | `com.senk.gallery.extra.TITLE` |
| `EXTRA_VIEW_URIS` | `com.senk.gallery.extra.VIEW_URIS` |
| `EXTRA_VIEW_POSITION` | `com.senk.gallery.extra.VIEW_POSITION` |
| `EXTRA_SELECTED_URIS` | `com.senk.gallery.extra.SELECTED_URIS` |
| `EXTRA_SELECTED_COUNT` | `com.senk.gallery.extra.SELECTED_COUNT` |
| `DEFAULT_MAX_COUNT` | `9` |

---

## 8. 调试与验证（adb）

### 8.1 应用内测试壳（debug APK）

`main/src/debug` 内置一个**模拟第三方调用方**的 Activity（release 不包含），可直接在真机上走完整链路并验证代理 URI 可读：

```bash
# 选图（多选）：启动后选 2 张点「完成」，logcat 会打印结果数量、URI 元数据与读取字节数
adb shell am start -n com.senk.gallery/.debug.ExternalClientDebugActivity \
  --es mode pick --ez allow_multiple true
adb logcat -s ExternalClientDebug GalleryExternal

# 外部大图：用本应用媒体库第一张图走 VIEW_IMAGE
adb shell am start -n com.senk.gallery/.debug.ExternalClientDebugActivity --es mode view
```

测试壳会：调用 `GalleryExternal.pickImageIntent` → 收到结果后逐 URI `query` 元数据 + `openInputStream` 读 64KB → 同时写 logcat 与页面文本。

### 8.2 直接启动外部路由

```bash
# 启动选图页（多选；不需要 -n，依赖 intent-filter 解析）
adb shell am start -a com.senk.gallery.action.PICK_IMAGE \
  --ez com.senk.gallery.extra.ALLOW_MULTIPLE true \
  --ei com.senk.gallery.extra.MAX_COUNT 9

# 观察外部调用链路日志（入口/权限/结果转发/代理授权）
adb logcat -s GalleryExternal ExternalMediaProvider

# 查看外部图片（示例；真实测试请用调用方授予读权限的 content URI）
adb shell am start -a com.senk.gallery.action.VIEW_IMAGE \
  -d "content://media/external/images/media/<id>" \
  --grant-read-uri-permission
```

> 实现备注（改动 `main` Manifest 时必读）：`VIEW_IMAGE` 的 `<intent-filter>` 必须声明
> `scheme=content + host="*" + mimeType="*/*"`（以及 `file` scheme）；只写 `scheme` 时
> 带 data 的隐式 Intent 在真机上无法匹配（`ActivityNotFoundException`）。`PICK_IMAGE` 没有 data，
> 必须留在**不带 data** 的另一个 filter 里，不要合并。
>
> `adb shell am start` 不是 `startActivityForResult`：`getCallingPackage()` 为 null，结果没有接收方；
> 「结果转发 + 代理 URI 可读」请用 §8.1 的测试壳验证（同 UID 场景能验证路由/授权代码路径，
> 真实第三方跨 UID 授权建议再用独立 APK 复验）。

---

## 9. 已知限制

- 仅 Android 16+；不支持网络图片（`http(s)`），不支持 `file://` 跨应用（系统限制）。
- 外部大图模式只做浏览：无收藏、无详情/EXIF/地图、无「添加到相册」等依赖本应用媒体库的功能；
  分享、缩放、翻页、视频播放可用。
- 选图结果 URI 是临时授权，不保证长期可读（见 5.2）。
- 代理 Provider 为只读、单条精确访问；不支持目录列举、批量查询、写入。
- 用户只授予「部分照片」权限时，选图页只能看到用户允许的媒体。
- 选图页同时展示图片与视频，暂不支持按媒体类型过滤（见 [backlog](backlog.md)）。
- 选图页多选顺序 = 用户选择顺序；单选取第一张；调用方应按 `SELECTED_COUNT` 校验数量。
- 隐私未同意时外部调用无法自动完成同意流程，需用户手动打开本应用一次。
