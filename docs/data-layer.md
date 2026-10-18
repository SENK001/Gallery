# 数据层：Provider 契约与媒体库

`core-data` 模块负责「系统媒体库 → 应用数据接口」的全部转换。主数据出口是一条只读查询链，另有两条**有意设计**的写入/对外出口：

```
MediaStore（系统）
   ↑ 查询/更新/写入                        ↓ 变化通知
GalleryProvider（自绘 ContentProvider，exported=false，同进程）
   ↑ ContentResolver.query / update        ↓ notifyChange(根 URI)
UI（Fragment / Activity）

另：
CustomAlbumStore（core-data，SharedPreferences + 版本化 JSON）   ← 自建相册逻辑集合（不复制文件）
MediaWriter（core-data，MediaStore 写入）                        ← 「添加到」相机/文件夹相册时的物理复制
ExternalMediaProvider（core-data，exported=false + 临时授权）     ← 对外选图结果的只读代理 URI
```

- UI **不直接查询 MediaStore**；但对媒体文件的解码/播放则直接使用 `MediaItem.uri`（它本身就是 `content://media/...` 的 MediaStore URI），不经过本 Provider 转发。
- `GalleryProvider` 只读（`insert` / `delete` 抛 `UnsupportedOperationException`），唯一写操作是「收藏」——但收藏写入的是**应用内自建收藏库**（`FavoriteStore`，不修改系统媒体库）；自建相册与复制写入分别走 `CustomAlbumStore` / `MediaWriter`（见 §6）。

## 1. 包与文件

`com.senk.gallery.data`（`core-data/src/main/java/`）：

| 文件 | 职责 |
|---|---|
| `provider/GalleryContract.kt` | authority、URI/参数常量、列定义、MIME 类型、URI 构造辅助函数 |
| `provider/GalleryProvider.kt` | `UriMatcher` 分发 `query` / `update` / `getType`；监听 MediaStore 变更并向外转发 |
| `provider/MediaStoreFetcher.kt` | 查询 `MediaStore.Files`：筛选、排序、分页、把游标读成 `MediaItem`、按 bucket 扫描统计 |
| `provider/AlbumResolver.kt` | 组装「常用 / 我的相册 / 更多」相册列表与相册内容查询 |
| `provider/ExifFetcher.kt` | 用 `ExifInterface` 读取单张图片的 EXIF，供详情页展示 |
| `provider/XmpParser.kt` | 解析 `MediaStore.XMP` 字符串，判定动态照片 / 全景并提取内嵌视频长度 |
| `provider/GalleryCursorReader.kt` | UI 侧读取器：把 Provider 输出的游标读成 `MediaItem` / `AlbumInfo` |
| `provider/MediaWriter.kt` | 物理复制写入：把媒体复制到目标 `RELATIVE_PATH` 目录（`IS_PENDING` 流程、流式复制、重名去重） |
| `provider/ExternalMediaProvider.kt` | 对外代理 Provider：`content://com.senk.gallery.external/media/{id}` 只读代理到 MediaStore |
| `album/CustomAlbumStore.kt` | 自建相册持久化（SharedPreferences + 版本化 JSON；线程安全、坏数据回退） |
| `album/FavoriteStore.kt` | **自建收藏库**读写入口（应用私有数据，不修改系统媒体库；查询时覆盖 `is_favorite` 列） |
| `db/FavoriteDb.kt` | 收藏的 SQLite 存储（`databases/gallery_favorites.db`；旧 SharedPreferences 收藏一次性迁移） |
| `entity/MediaObject.java`、`MediaItem.java`、`MediaSet.java` | 领域对象基类 / 媒体条目 / 相册集合 |
| `pojo/*.java` | 具体型号（相册子类与数据包，见 §2） |

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
- `MediaSet.TYPE_*` 常量：`all / camera / video / screenshot / screen_record / live / favorite / panorama / folder / third_party / custom / recently_deleted`（`recently_deleted` 仅预留；`custom` 为自建相册）。
- **自建相册行**复用 `AlbumColumns`：`_id = "custom:<uuid>"`、`album_type = custom`、`bucket_id = <uuid>`（该列在 custom 行里承载自建相册 id）、`item_count = 成员数`。
- 时间字段统一为**毫秒**；`size` 为字节；`duration` 为毫秒（继承自 MediaStore 口径）。

## 3. Provider 契约

### 3.1 URI 表

authority：`com.senk.gallery.data.provider`（见 `core-data/src/main/AndroidManifest.xml`，`exported=false`）。

| URI | 参数 | 返回 | 说明 |
|---|---|---|---|
| `content://…/media` | `limit`, `offset` | `Columns.ALL` 游标 | 照片页全量列表（图片+视频，拍摄时间倒序） |
| `content://…/media/{id}` | — | 同上（0 或 1 行） | 单个媒体条目 |
| `content://…/media/{id}/exif` | — | `ExifColumns.ALL`（恒 1 行） | 详情页 EXIF；非图片或读取失败时各列兜底默认值 |
| `content://…/albums` | `category=common\|more\|custom` | `AlbumColumns.ALL` | 相册列表；缺省 `common` |
| `content://…/albums/{type}` | `limit`, `offset` | `Columns.ALL` | 相册内容；`{type}` 为「常用」项 |
| `content://…/albums/folder/{bucketId}` | `limit`, `offset` | 同上 | 目录相册内容（`bucketId` 经 `Uri.decode`） |
| `content://…/albums/app/{packageName}` | `limit`, `offset` | 同上 | 三方应用相册内容（`packageName` 经 `Uri.decode`） |
| `content://…/albums/custom/{albumId}` | `limit`, `offset` | 同上 | **自建相册内容**：按 store 内加入顺序分页查询 MediaStore |

构造辅助（`GalleryContract`）：`mediaPageUri(limit, offset)`、`mediaUri(id)`、`exifUri(id)`、`albumsUri(category)`、`albumItemsUri(type)`、`albumItemsPageUri(type, limit, offset)`、`folderAlbumItemsUri(bucketId)`、`appAlbumItemsUri(packageName)`、`customAlbumItemsUri(albumId)`（均含分页版本）。实际拼 URL 时优先用这些函数，不要手写字符串。

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
| `bucket_id` / `bucket_name` | 相册桶（custom 行的 `bucket_id` = 自建相册 UUID） |
| `relative_path` | 相对路径（如 `DCIM/Camera/`） |
| `owner_package` | 归属应用（对其他应用通常不可见，见 §7 旧坑说明） |

`AlbumColumns.ALL`（8 列）：`_id`（`albumType:bucketId|packageName` 拼出的稳定键）、`album_type`、`album_name`、`cover_uri`、`item_count`、`bucket_id`、`relative_path`（目录相册的路径）、`owner_package`（三方相册的包名）。

`ExifColumns.ALL`（22 列）：`make / model / lens_model / f_number / exposure_time / shutter_speed / aperture / iso / focal_length / focal_length_35mm / datetime / flash / white_balance / software / artist / copyright / latitude / longitude / has_location / image_width / image_height / orientation`。

### 3.3 分发与写操作

- `query` 按 `UriMatcher` 分发到 `MediaStoreFetcher` / `AlbumResolver` / `ExifFetcher`；结果统一 `setNotificationUri(CONTENT_URI)`；未知 URI 返回 `null`。
- **`UriMatcher` 按子节点注册顺序匹配**：`albums/folder/*`、`albums/app/*`、`albums/custom/*` 必须先于通配的 `albums/*` 注册（`GalleryProvider` 已按此顺序，「具体在前、通配在后」，改动注册处时不要打乱）。
- `update` 仅支持 `media/{id}`：从 `ContentValues` 读 `is_favorite`，写入应用内自建收藏库 `FavoriteStore`（**不再写 MediaStore**），成功后 `notifyChange`。查看器/多选动作因此不需要 `RecoverableSecurityException` 系统授权流程（2026-10-19 变更）。
- `insert` / `delete` 一律抛 `UnsupportedOperationException`；`call()` 未实现。写入路径见 §6。

### 3.4 变更通知与数据刷新

```
MediaStore.Files(外置卷) 变化
   → GalleryProvider 内部 ContentObserver.onChange
   → notifyChange(content://com.senk.gallery.data.provider)
   → UI 侧 ContentObserver（PhotosFragment / AlbumsFragment / AlbumDetailActivity / 选图页）
   → 重新查询（照片页会重置到第一页）

自建相册变化（CustomAlbumStore）/ 物理复制完成（MediaWriter）
   → 调用方显式 notifyChange(根 URI)
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
| `favorite` | 收藏 | **应用内自建收藏库**（`FavoriteStore` 的成员；不再读 MediaStore `is_favorite`） |
| `panorama` | 全景 | XMP `LIKE '%GPano:%'`，或尺寸兜底 `width >= 6000 AND width >= height * 3` |

### 5.2 我的相册（`category=custom`）

- 直接来自 `CustomAlbumStore.albums()`（创建顺序），**空相册也返回**（区别于系统相册的空相册隐藏规则）。
- 行：`_id = "custom:<uuid>"`、`album_type = custom`、`album_name = 名称`、`cover_uri = 记录里的封面（无则按成员最新一条兜底，仍无则 null）`、`item_count = itemIds.size`、`bucket_id = <uuid>`、其余列 null。
- 相册内容查询：按 store 的加入顺序取成员 id → 分块 `_id IN (...)` 查 MediaStore → 按原顺序重排 → 再按 `limit/offset` 分页（`limit<=0` 表示全量）。已从媒体库删除的 id 查不到时自动跳过（不报错）。

### 5.3 更多（`category=more`）

分两组，顺序固定：**三方应用**在前、**Pictures 子目录**在后。

- 三方应用分组（`groupBucketsByPackage`）：桶的归属判定为 —— `owner_package`（非空且不在排除名单）否则按**桶名**查 `KNOWN_APP_BUCKETS` 映射（微信 / QQ / 微博等的桶名别名）。注意 `owner_package_name` 对其他应用不可见（读取为 null），这条兜底是必需的。每组汇总数量、取最新封面、名称取已知中文名或已安装应用标签（`KNOWN_APPS`：微信、QQ、TIM、QQ Lite、微博、小红书），排序按已知应用顺序、再按名称。
- Pictures 子目录（`TYPE_FOLDER`）：`relative_path LIKE 'Pictures/%'`（且不是精确的 `Pictures/`），且**未被三方应用认领**（同上判定）的桶，按桶名排序。注意子目录可以嵌套（如 `Pictures/douyin/comment/`），桶名为最后一级目录名。
- 排除名单（`EXCLUDED_PACKAGES` + `com.android.*`）：本应用、`providers.media`、`downloads`、`documentsui`、`shell`、`bluetooth`、`android`。

### 5.4 相册内容查询（`queryAlbumItems`）

| 类型 | 查询方式 |
|---|---|
| `folder` | `bucket_id = ?` |
| `third_party` | 先做同样的桶分组取该包的桶集合，`bucket_id IN (…)`；桶集合为空时回退 `owner_package = ?` |
| `custom` | 按 store 加入顺序的 id 集合（见 §5.2） |
| `favorite` | 按 `FavoriteStore` 的 id 集合查 MediaStore，按拍摄时间倒序分页（id 超过 900 时退化为全量取回后内存分页） |
| 常用类型 | 直接使用 §5.1 表的筛选串；未知类型回退为「全部」 |

分页参数与 `media` 一致；结果行为 `Columns.ALL`。

### 5.5 相册行（`AlbumColumns`）构造

- `_id` = `albumType + ":" + (bucketId ?: packageName ?: "")`，作为相册在列表里的稳定键（目录相册用桶 ID，三方相册用包名，自建相册用 UUID）。
- 三方/目录/自建相册的 `bucket_id` / `owner_package` / `relative_path` 分别填充，常用相册该三列为空。
- `AlbumResolver.albumItemsUri(type, bucketId, packageName)` 帮助调用方选择 `folder/…`、`app/…`、`custom/…` 或普通路径。

## 6. 应用内数据与写入（自建相册 / 收藏 / 复制）

### 6.1 CustomAlbumStore（自建相册，逻辑集合）

- 位置：`core-data/.../album/CustomAlbumStore.kt`；SharedPreferences 文件 `gallery_custom_albums`，版本化 JSON：`{"version":1,"albums":[{"id":"uuid","name":"…","item_ids":[…],"cover_uri":…,"created_at":…}]}`。
- 语义：**应用内逻辑集合，不复制、不移动任何媒体文件**；同一张照片可属于多个自建相册；删除相册不会删除照片。
- 线程安全：所有公开方法在**同一把锁**内完成「读-改-写」（UI 与 Provider Binder 线程可能并发）；JSON 损坏时回退空列表并记日志，不崩溃、不清空其它数据（下次写入覆盖）。
- API：`albums()` / `album(id)` / `create(name)` / `delete(id)` / `rename(id, name)` / `addItems(albumId, ids, coverUri)` / `removeItems(albumId, ids)` / `containsName(name)`。
- 名称规则：trim 后 1–40 字符、拒绝 ISO 控制字符、忽略大小写重名；非法/重名 `create` 返回 `null`。
- 已知限制：成员是逻辑 id，系统删除照片后 store 不主动清除（相册内容查询会跳过已删除 id，但 `item_count` 可能偏大）；重命名/封面/相册内移除的 UI 暂未提供（store API 已具备）。

### 6.2 FavoriteStore（自建收藏库，SQLite）

- 位置：`core-data/.../album/FavoriteStore.kt`（对外 API）+ `core-data/.../db/FavoriteDb.kt`（SQLite 存储）。
- 语义：收藏是**应用私有数据**，不复制文件、**不修改系统媒体库的 `is_favorite`**。
  - 背景：写回系统媒体库需要用户逐次系统授权（Android 12+ `RecoverableSecurityException`），批量收藏体验差且部分场景直接失败（2026-10-19 用户反馈「多选收藏提示失败」）。
  - 读取：Provider 每次媒体查询取一次 `ids()` 快照，用其覆盖 `is_favorite` 列；「收藏」相册的列表/内容/封面都由本库计算（见 §5.1、§5.4）。
  - 写入：`Provider.update(media/{id}, is_favorite)` → `FavoriteStore.setFavorite(id, favorite)` → `notifyChange`；多选收藏 = 逐条 update。
- 存储：**SQLite** 数据库 `databases/gallery_favorites.db`，表结构：

  ```sql
  CREATE TABLE favorites (
    media_id   INTEGER PRIMARY KEY NOT NULL,  -- MediaStore 媒体 id
    created_at INTEGER NOT NULL               -- 收藏时间（毫秒），用于稳定排序
  );
  ```

  SQLite 自身保证并发串行化；单次批量写入使用事务；`id` 只增不重（主键约束）。
- 迁移：旧版本用 SharedPreferences（`gallery_favorites` / `favorites_json` JSON）保存收藏；首次创建 SQLite 库时**一次性导入**（`INSERT OR IGNORE`），成功后删除旧键；解析失败只记日志、保留旧键，不阻塞启动。自建相册（`CustomAlbumStore`）仍使用 SharedPreferences，**不在此次 SQLite 化范围**。
- 线程安全：SQLite + 单例 helper；坏数据/损坏库由 SQLite 自身抛错，调用方按空收藏兜底（`FavoriteDb` 迁移路径捕获异常）。
- 已知限制：收藏只在本应用内生效（系统相册/其它应用看不到）；旧版本写入 MediaStore 的收藏标记**不做迁移/导入**，也不再作为数据源；成员不主动 prune（媒体被删除后仍计入 `item_count`，内容查询会跳过）。

### 6.3 MediaWriter（物理复制）

- 位置：`core-data/.../provider/MediaWriter.kt`；`suspend copyToFolder(resolver, mediaIds, relativePath, onProgress): CopyResult`。
- 流程：按源 mime 选 `Images`/`Video` 集合 → `insert(DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH, IS_PENDING=1, DATE_TAKEN)` → 64KB 缓冲流式复制（循环内检查协程取消）→ `update(IS_PENDING=0)` 发布。
- 同名去重：扫描目标目录现有 `DISPLAY_NAME`（Images+Video，小写集合）+ 本批已用名 → `a.jpg`、`a (1).jpg`…；`DATE_TAKEN` 被部分 Provider 拒绝时自动去掉该列重试。
- 失败/取消：`finally` 删除刚插入的 pending 行，返回成功/失败计数与失败 id；结束后显式 `notifyChange(根 URI)`。
- 目标路径：相机 = `DCIM/Camera/`；文件夹相册 = 该相册的 `relative_path`（如 `Pictures/douyin/comment/`）。

## 7. 对外代理 Provider（ExternalMediaProvider）

- authority：`com.senk.gallery.external`（`exported=false` + `grantUriPermissions=true`）。
- URI：`content://com.senk.gallery.external/media/{mediaId}`；`proxyUri(id)` 供外部路由构造。
- 能力：`query` 返回 `_id / display_name / mime_type / size / media_type`；`getType` 返回 mime；`openFile("r")` 解析 id → MediaStore 真实 URI → `openFileDescriptor` 返回只读 fd。**不支持目录列举与任何写操作**。
- 安全：第三方只能读取被 `ExternalEntryActivity` 显式临时授权的那几个 id；非法 id/无权限统一安全失败（`FileNotFoundException` / null），不泄漏内部异常。
- 详见 [外部调用 API](external-api.md)。

## 8. XMP 解析（XmpParser）

输入是 `MediaStore.XMP` 列的字符串（可能为 null）：

- **动态照片判定**：含任一标记 `MotionPhoto` / `MicroVideo` / `MotionPhotoVersion` / `MicroVideoVersion`。
- **内嵌视频长度** `motionVideoLength(xmp)`：
  1. 新格式（Google Motion Photo 容器）：找 `<Container:Item …>` 中 `Item:Mime="video/…"` 或 `Semantic="MotionPhoto"` 的条目，取 `Item:Length`；
  2. 旧格式：`GCamera|Camera:MicroVideoOffset`（距文件尾的距离即视频长度）；
  3. 都取不到返回 0。
- **全景判定**：含任一标记 `GPano:ProjectionType` / `GPano:UsePanoramaViewer` / `GPano:CroppedAreaImageWidthPixels`；另有尺寸兜底 `isPanoramaBySize(width, height)`：`width >= 6000 && width >= height * 3`。
- 未实现：XMP 列为空时**没有**回退读文件内嵌 XMP（exifinterface 1.3.7 无 MotionPhoto API），即「XMP 空 ⇒ 非动态照片/非全景（尺寸兜底除外）」。见 [backlog](backlog.md)。

## 9. EXIF（ExifFetcher）

- `queryExif(resolver, id)`：按 `_id` 查出 `MediaItem`，**仅当不是视频**时用 `ExifInterface` 从 `item.uri` 流式读取；任何异常吞掉并返回空 `ExifData`。
- 输出恒为 1 行 `MatrixCursor`（22 列），读不到的列给默认值：经纬度 `0.0`、`has_location = 0`、宽高/方向回退为 `MediaItem` 的对应值。
- EXIF 字段到列名的对应（节选）：`TAG_MAKE→make`、`TAG_MODEL→model`、`TAG_LENS_MODEL→lens_model`、`TAG_F_NUMBER→f_number`、`TAG_EXPOSURE_TIME→exposure_time`、`TAG_SHUTTER_SPEED_VALUE→shutter_speed`、`TAG_APERTURE_VALUE→aperture`、`TAG_PHOTOGRAPHIC_SENSITIVITY→iso`、`TAG_FOCAL_LENGTH→focal_length`、`TAG_FOCAL_LENGTH_IN_35MM_FILM→focal_length_35mm`、`TAG_DATETIME_ORIGINAL→datetime`、`TAG_FLASH→flash`、`TAG_WHITE_BALANCE→white_balance`、`TAG_SOFTWARE→software`、`TAG_ARTIST→artist`、`TAG_COPYRIGHT→copyright`、`latLong→latitude/longitude/has_location`。
- **GPS 脱敏**：Android 10+ 下除非持有 `ACCESS_MEDIA_LOCATION` 权限，`ExifInterface.latLong` 读到的是脱敏值；应用在首启权限流程里申请该权限。

## 10. 历史坑（对后续有效）

- **`owner_package_name` 对其他应用不可见**（读取为 null）：三方应用相册分组必须保留「按桶名映射」的兜底。
- **XMP 是 BLOB**：见 §4。
- **`FileNotFoundException` 语义**：外部代理 Provider 只读；读取失败统一表现为 `FileNotFoundException`，调用方按"无权限/文件不存在"处理。

## 11. 已知限制（详见 [backlog](backlog.md)）

- `MediaItem.fromCursor()` 未实现；游标读取统一走 `MediaStoreFetcher.readItem()`（Provider 侧）与 `GalleryCursorReader.readMedia()`（UI 侧）两个入口。
- `MediaItem.exif` 是死字段：`ExifFetcher` 不回填它，也没有任何写入方；EXIF 只在详情页按需单查。
- XMP 无文件回退（§8）；缩略图/EXIF 均无磁盘缓存。
- 自建相册成员不主动 prune（§6.1 已知限制）；复制写入是尽力去重（并发写入/provider 规范化差异下仍可能被系统改名）。
- 收藏是应用内自建库（§6.2）：系统相册不显示本应用收藏，旧系统收藏标记不导入；卸载/清除数据后收藏记录丢失。
