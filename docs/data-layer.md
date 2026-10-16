# 数据层：Provider 契约与媒体库

`core-data` 模块负责「系统媒体库 → 应用数据接口」的全部转换。全应用只有一条数据出口：

```
MediaStore（系统）
   ↑ 查询/更新
GalleryProvider（自绘 ContentProvider，exported=false，同进程）
   ↑ ContentResolver.query / update        ↓ 变更通知（notifyChange）
UI（Fragment / Activity）
```

- UI **不直接查询 MediaStore**；但对媒体文件的解码/播放则直接使用 `MediaItem.uri`（它本身就是 `content://media/...` 的 MediaStore URI），不经过本 Provider 转发。
- Provider 只读（`insert` / `delete` 抛 `UnsupportedOperationException`），唯一写操作是把「收藏」写回 MediaStore。

## 1. 包与文件

`com.senk.gallery.data`（`core-data/src/main/java/`）：

| 文件 | 职责 |
|---|---|
| `provider/GalleryContract.kt` | authority、URI/参数常量、列定义、MIME 类型、URI 构造辅助函数 |
| `provider/GalleryProvider.kt` | `UriMatcher` 分发 `query` / `update` / `getType`；监听 MediaStore 变更并向外转发 |
| `provider/MediaStoreFetcher.kt` | 查询 `MediaStore.Files`：筛选、排序、分页、把游标读成 `MediaItem`、按 bucket 扫描统计 |
| `provider/AlbumResolver.kt` | 组装「常用 / 更多」相册列表与相册内容查询 |
| `provider/ExifFetcher.kt` | 用 `ExifInterface` 读取单张图片的 EXIF，供详情页展示 |
| `provider/XmpParser.kt` | 解析 `MediaStore.XMP` 字符串，判定动态照片 / 全景并提取内嵌视频长度 |
| `provider/GalleryCursorReader.kt` | UI 侧读取器：把 Provider 输出的游标读成 `MediaItem` / `AlbumInfo` |
| `entity/MediaObject.java`、`MediaItem.java`、`MediaSet.java` | 领域对象基类 / 媒体条目 / 相册集合 |
| `pojo/*.java` | 13 个具体型号（相册子类与数据包，见 §2） |

## 2. 对象模型

```
MediaObject                 id / uri / name / mimeType / size / dateModified
├─ MediaItem                媒体条目（图片或视频，含全部展示字段）
└─ MediaSet                 相册集合（albumType / coverUri / count / data）
   └─ pojo/* 相册子类        AllItemAlbum / CameraAlbum / VideoAlbum / ScreenshotAlbum /
                            ScreenRecordAlbum / LivePhotoAlbum / FavoriteAlbum /
                            PanoramaAlbum / FolderAlbum / ThirdPartyAlbum
pojo/* 数据包                LocalImage / LocalVideo / ExifData
```

- `MediaObject.TYPE_*`：`TYPE_UNKNOWN(0) / TYPE_IMAGE(1) / TYPE_VIDEO(2)`；`MediaItem.isVideo()` 即 `mediaType == TYPE_VIDEO`。
- `MediaItem` 字段：`mediaType / dateTaken / width / height / duration / orientation / isFavorite / isTrashed / isPending / isMotionPhoto / motionVideoLength / isPanorama / bucketId / bucketName / relativePath / ownerPackage / exif`，并提供 `copyFrom()`。
- `MediaSet.TYPE_*` 常量：`all / camera / video / screenshot / screen_record / live / favorite / panorama / folder / third_party / recently_deleted`（`recently_deleted` 仅预留，功能见 [backlog](backlog.md)）。
- 时间字段统一为**毫秒**；`size` 为字节；`duration` 为毫秒（继承自 MediaStore 口径）。

## 3. Provider 契约

### 3.1 URI 表

authority：`com.senk.gallery.data.provider`（见 `core-data/src/main/AndroidManifest.xml`，`exported=false`）。

| URI | 参数 | 返回 | 说明 |
|---|---|---|---|
| `content://…/media` | `limit`, `offset` | `Columns.ALL` 游标 | 照片页全量列表（图片+视频，拍摄时间倒序） |
| `content://…/media/{id}` | — | 同上（0 或 1 行） | 单个媒体条目 |
| `content://…/media/{id}/exif` | — | `ExifColumns.ALL`（恒 1 行） | 详情页 EXIF；非图片或读取失败时各列兜底默认值 |
| `content://…/albums` | `category=common\|more` | `AlbumColumns.ALL` | 相册列表；缺省 `common` |
| `content://…/albums/{type}` | `limit`, `offset` | `Columns.ALL` | 相册内容；`{type}` 为上表 `MediaSet.TYPE_*` 中「常用」项 |
| `content://…/albums/folder/{bucketId}` | `limit`, `offset` | 同上 | 目录相册内容（`bucketId` 经 `Uri.decode`） |
| `content://…/albums/app/{packageName}` | `limit`, `offset` | 同上 | 三方应用相册内容（`packageName` 经 `Uri.decode`） |

构造辅助（`GalleryContract`）：`mediaPageUri(limit, offset)`、`mediaUri(id)`、`exifUri(id)`、`albumsUri(category)`、`albumItemsUri(type)`、`albumItemsPageUri(type, limit, offset)`、`folderAlbumItemsUri(bucketId)`（含分页版本）、`appAlbumItemsUri(packageName)`（含分页版本）。实际拼 URL 时优先用这些函数，不要手写字符串。

MIME（`getType`）：`media` → `vnd.android.cursor.dir/vnd.gallery.media`；`media/{id}` → `…/cursor.item/…`；`media/{id}/exif` → `…/cursor.item/vnd.gallery.exif`；`albums` → `…/cursor.dir/vnd.gallery.album`；各相册内容 → 媒体 dir。

### 3.2 列契约（UI 的真实数据接口）

`GalleryContract.Columns.ALL`（22 列，`media` 与相册内容查询的输出）：

| 列名 | 含义 |
|---|---|
| `_id` | MediaStore 文件 ID |
| `uri` | 媒体 URI 字符串（`content://media/external/images|video/media/{id}`） |
| `display_name` | 文件名 |
| `mime_type` | MIME |
| `media_type` | `TYPE_IMAGE` / `TYPE_VIDEO` 数字 |
| `date_taken` | 拍摄时间（毫秒；为空时已回退为 `date_modified`） |
| `date_modified` | 修改时间（毫秒） |
| `size` | 字节 |
| `width` / `height` | 像素 |
| `duration` | 视频时长（毫秒） |
| `orientation` | EXIF 方向 |
| `is_favorite` / `is_trashed` / `is_pending` | 0/1 |
| `is_motion_photo` | 0/1 |
| `motion_video_length` | 内嵌视频字节数（0 = 未知） |
| `is_panorama` | 0/1 |
| `bucket_id` / `bucket_name` | 相册桶 |
| `relative_path` | 相对路径（如 `DCIM/Camera/`） |
| `owner_package` | 归属应用（对其他应用通常不可见，见 §6） |

`AlbumColumns.ALL`（8 列）：`_id`（这里不是数字，是 `albumType:bucketId|packageName` 拼出的稳定键）、`album_type`、`album_name`、`cover_uri`、`item_count`、`bucket_id`、`relative_path`（目录相册的路径）、`owner_package`（三方相册的包名）。

`ExifColumns.ALL`（22 列）：`make / model / lens_model / f_number / exposure_time / shutter_speed / aperture / iso / focal_length / focal_length_35mm / datetime / flash / white_balance / software / artist / copyright / latitude / longitude / has_location / image_width / image_height / orientation`。

### 3.3 分发与写操作

- `query` 按 `UriMatcher` 分发到 `MediaStoreFetcher` / `AlbumResolver` / `ExifFetcher`；结果统一 `setNotificationUri(CONTENT_URI)`；未知 URI 返回 `null`。
- **`UriMatcher` 按子节点注册顺序匹配**：`albums/folder/*`、`albums/app/*` 必须先于通配的 `albums/*` 注册（`GalleryProvider` 已按此顺序，「具体在前、通配在后」，改动注册处时不要打乱）。
- `update` 仅支持 `media/{id}`：从 `ContentValues` 读 `is_favorite`，经 `queryItems` 解析出 MediaStore URI 后写 `MediaStore.MediaColumns.IS_FAVORITE`，成功后 `notifyChange`。Android 12+ 的授权流程（`RecoverableSecurityException`）由调用方（查看器）处理，见 [界面与交互](ui-guide.md)。
- `insert` / `delete` 一律抛 `UnsupportedOperationException`；`call()` 未实现。

### 3.4 变更通知与数据刷新

```
MediaStore.Files(外置卷) 变化
   → GalleryProvider 内部 ContentObserver.onChange
   → notifyChange(content://com.senk.gallery.data.provider)
   → UI 侧 ContentObserver（PhotosFragment / AlbumsFragment / AlbumDetailActivity）
   → 重新查询（照片页会重置到第一页）
```

注意两点：

1. 观察目标是**根 URI**（不是具体子路径），任何媒体库变化都会触发整页重载；照片页重载会回到顶部（这也是验证时截图不要写进 `/sdcard` 的原因，见 [验证方法与判据](verification.md)）。
2. `query` 返回的游标带 `setNotificationUri`，但 UI 侧目前自建 `ContentObserver` 监听根 URI，两条路径等效。

## 4. MediaStore 抓取（MediaStoreFetcher）

- **集合**：`MediaStore.Files.getContentUri(VOLUME_EXTERNAL)`（外置卷，含图片与视频）。
- **基础筛选** `BASE_SELECTION` = `media_type IN (image, video)` AND `is_trashed = 0` AND `is_pending = 0`。
- **排序** `SORT_DATE_DESC`：

  ```sql
  COALESCE(date_taken, date_modified * 1000) DESC, _id DESC
  ```

  - `date_taken` 可能为空，回退 `date_modified * 1000` 保持毫秒量纲；
  - 末尾 `_id DESC` 是**稳定次级键**：并列键配合 limit/offset 分页理论上可能重复/漏项，加上后并列组内顺序确定为「新在前」；
  - MediaProvider 对排序串做**逐 token 白名单**校验，`COALESCE` 与 `date_taken` / `date_modified` 均在允许范围内（Android 16 真机实测通过，背景见 [history](history.md)）。
- **分页**：以 `Bundle`（`QUERY_ARG_SQL_SELECTION` / `SELECTION_ARGS` / `SORT_ORDER` / `LIMIT` / `OFFSET`）下传；`limit`/`offset` 为 0 时不传对应键。
- **投影**：查询用 `QUERY_PROJECTION`（19 列，含 XMP）；另有 `SCAN_PROJECTION`（8 列）供相册扫描统计。
- **XMP 读取**：`MediaStore.MediaColumns.XMP` 在 MediaProvider 中以 **BLOB** 存储，直接 `Cursor.getString()` 会抛 `Unable to convert BLOB to string` 并导致整个查询失败。`readXmp()` 按列类型读取（BLOB 按 UTF-8 转字符串）。
- **`readItem()` 的字段口径**：
  - `uri` 按类型拼 `images/media/{id}` 或 `video/media/{id}`；
  - `dateTaken`：`date_taken <= 0` 时回退 `dateModified`；
  - `isMotionPhoto` / `motionVideoLength` / `isPanorama` 由 `XmpParser` 从 XMP 得出；全景再由尺寸兜底（见 §5.3）。
- **`count()`**：先 `count(*)` 聚合投影；提供方不支持聚合时回退为读 `_id` 列数行数。
- **`scanBuckets()`**：一次扫描全部媒体，按 `bucket_id` 聚合成 `BucketInfo`（数量、是否含图/视频、最新封面、`relative_path`、`owner_package`），供「更多」相册使用。
- 其他：`queryMedia`（分页列表）、`queryMediaById`、`queryItems`（条件查询→`List<MediaItem>`）、`queryItemsCursor`（→`MatrixCursor`）、`latestItem`（取最新一条做封面）。

## 5. 相册归类（AlbumResolver）

### 5.1 常用（`category=common`）

8 个固定项，逐项 `count`，**空相册自动隐藏**（`count <= 0` 跳过）；封面取该筛选下最新一条：

| 类型 | 名称 | 判定 |
|---|---|---|
| `all` | 全部 | `BASE_SELECTION` |
| `camera` | 相机 | 路径 `DCIM/Camera/%` 或 `DCIM/100ANDRO/%` |
| `video` | 视频 | `media_type = video` |
| `screenshot` | 截屏 | 图片且路径 `Pictures/Screenshots/`、`DCIM/Screenshots/`、`Pictures/截图/`、`DCIM/截图/`，或文件名 `Screenshot_%`、`Screenshot-%`、`截屏%`、`屏幕截图%` |
| `screen_record` | 录屏 | 视频且路径 `DCIM/ScreenRecorder/`、`Movies/ScreenRecorder/`、`DCIM/Screen recordings/`、`Movies/Screen recordings/`、`Pictures/ScreenRecordings/`、`DCIM/录屏/`，或文件名 `Screenrecorder%`、`ScreenRecord%`、`Screen recording%`、`录屏%`、`屏幕录制%` |
| `live` | 动态照片 | XMP `LIKE '%MotionPhoto%'` 或 `'%MicroVideo%'` |
| `favorite` | 收藏 | `is_favorite = 1` |
| `panorama` | 全景 | XMP `LIKE '%GPano:%'`，或尺寸兜底 `width >= 6000 AND width >= height * 3` |

### 5.2 更多（`category=more`）

分两组，顺序固定：**三方应用**在前、**Pictures 子目录**在后。

- 三方应用分组（`groupBucketsByPackage`）：桶的归属判定为 —— `owner_package`（非空且不在排除名单）否则按**桶名**查 `KNOWN_APP_BUCKETS` 映射（微信 / QQ / 微博等的桶名别名）。注意 `owner_package_name` 对其他应用不可见（读取为 null），这条兜底是必需的。每组汇总数量、取最新封面、名称取已知中文名或已安装应用标签（`KNOWN_APPS`：微信、QQ、TIM、QQ Lite、微博、小红书），排序按已知应用顺序、再按名称。
- Pictures 子目录（`TYPE_FOLDER`）：`relative_path LIKE 'Pictures/%'`（且不是精确的 `Pictures/`），且**未被三方应用认领**（同上判定）的桶，按桶名排序。
- 排除名单（`EXCLUDED_PACKAGES` + `com.android.*`）：本应用、`providers.media`、`downloads`、`documentsui`、`shell`、`bluetooth`、`android`。

### 5.3 相册内容查询（`queryAlbumItems`）

| 类型 | 查询方式 |
|---|---|
| `folder` | `bucket_id = ?` |
| `third_party` | 先做同样的桶分组取该包的桶集合，`bucket_id IN (…)`；桶集合为空时回退 `owner_package = ?`（兼容 `owner_package` 可读的场景） |
| 常用类型 | 直接使用 §5.1 表的筛选串；未知类型回退为「全部」 |

分页参数与 `media` 一致；结果行为 `Columns.ALL`。

### 5.4 相册行（`AlbumColumns`）构造

- `_id` = `albumType + ":" + (bucketId ?: packageName ?: "")`，作为相册在列表里的稳定键（目录相册用桶 ID，三方相册用包名）。
- 三方/目录相册的 `bucket_id` / `owner_package` / `relative_path` 分别填充，常用相册该三列为空。
- `AlbumResolver.albumItemsUri(type, bucketId, packageName)` 帮助调用方选择 `folder/…`、`app/…` 或普通路径。

## 6. XMP 解析（XmpParser）

输入是 `MediaStore.XMP` 列的字符串（可能为 null）：

- **动态照片判定**：含任一标记 `MotionPhoto` / `MicroVideo` / `MotionPhotoVersion` / `MicroVideoVersion`。
- **内嵌视频长度** `motionVideoLength(xmp)`：
  1. 新格式（Google Motion Photo 容器）：找 `<Container:Item …>` 中 `Item:Mime="video/…"` 或 `Semantic="MotionPhoto"` 的条目，取 `Item:Length`；
  2. 旧格式：`GCamera|Camera:MicroVideoOffset`（距文件尾的距离即视频长度）；
  3. 都取不到返回 0。
- **全景判定**：含任一标记 `GPano:ProjectionType` / `GPano:UsePanoramaViewer` / `GPano:CroppedAreaImageWidthPixels`；另有尺寸兜底 `isPanoramaBySize(width, height)`：`width >= 6000 && width >= height * 3`（无 XMP 的宽幅全景）。
- 未实现：XMP 列为空时**没有**回退读文件内的 XMP（exifinterface 1.3.7 无 MotionPhoto API），即「XMP 空 ⇒ 非动态照片/非全景（尺寸兜底除外）」。见 [backlog](backlog.md)。

## 7. EXIF（ExifFetcher）

- `queryExif(resolver, id)`：按 `_id` 查出 `MediaItem`，**仅当不是视频**时用 `ExifInterface` 从 `item.uri` 流式读取；任何异常吞掉并返回空 `ExifData`。
- 输出恒为 1 行 `MatrixCursor`（22 列），读不到的列给默认值：经纬度 `0.0`、`has_location = 0`、宽高/方向回退为 `MediaItem` 的对应值。
- EXIF 字段到列名的对应（节选）：`TAG_MAKE→make`、`TAG_MODEL→model`、`TAG_LENS_MODEL→lens_model`、`TAG_F_NUMBER→f_number`、`TAG_EXPOSURE_TIME→exposure_time`、`TAG_SHUTTER_SPEED_VALUE→shutter_speed`、`TAG_APERTURE_VALUE→aperture`、`TAG_PHOTOGRAPHIC_SENSITIVITY→iso`、`TAG_FOCAL_LENGTH→focal_length`、`TAG_FOCAL_LENGTH_IN_35MM_FILM→focal_length_35mm`、`TAG_DATETIME_ORIGINAL→datetime`、`TAG_FLASH→flash`、`TAG_WHITE_BALANCE→white_balance`、`TAG_SOFTWARE→software`、`TAG_ARTIST→artist`、`TAG_COPYRIGHT→copyright`、`latLong→latitude/longitude/has_location`。
- **GPS 脱敏**：Android 10+ 下除非持有 `ACCESS_MEDIA_LOCATION` 权限，`ExifInterface.latLong` 读到的是脱敏值；应用在首启权限流程里申请该权限。

## 8. 已知限制（详见 [backlog](backlog.md)）

- `MediaItem.fromCursor()` 未实现；游标读取统一走 `MediaStoreFetcher.readItem()`（Provider 侧）与 `GalleryCursorReader.readMedia()`（UI 侧）两个入口。
- `MediaItem.exif` 是死字段：`ExifFetcher` 不回填它，也没有任何写入方；EXIF 只在详情页按需单查。
- XMP 无文件回退（§6）；缩略图/EXIF 均无磁盘缓存。
