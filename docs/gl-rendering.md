# GL 自绘渲染

缩略图宫格、大图查看器、视频渲染三处界面是**基于 `GLSurfaceView` 自绘**的（shader/纹理逐帧绘制），其余界面用 androidx/material 与百度地图 `TextureMapView`。本文件讲这三条 GL 链路的实现与约定。

包位置：`main-ui/src/main/java/com/senk/gallery/ui/gl/`（下文以类名引用），查看器的宿主在 `ViewerActivity`，见 [界面与交互](ui-guide.md)。

## 1. 组件清单

| 组件 | 角色 |
|---|---|
| `GlThumbnailGridView` | 宫格 View：触摸/惯性滚动（`OverScroller`）、触发生命周期内的缩略图加载器、对外 API |
| `GlGridRenderer` | 宫格渲染器：按可见行绘制缩略图/角标/空态，触发缩略图请求与「接近底部」回调 |
| `GridGeometry` | 宫格几何：单元格尺寸、三向预留、滚动范围、坐标换算与命中测试 |
| `GlImageViewer` | 查看器 View：手势（单击/双击/双指/拖页/上划面板）、翻页动画、图片解码调度 |
| `GlImagePagerRenderer` | 查看器渲染器：±1 页预加载绘制、fit/缩放/平移、动态照片内嵌视频叠加、播放图标 |
| `GlVideoView` | 播放器 View：aspect-fit OES 视频渲染 |
| `VideoPlayManager` | 播放状态机：`MediaPlayer + SurfaceTexture(OES)`，`File` / `Embedded` 两种源 |
| `EmbeddedVideoDataSource` | `MediaDataSource`：`Os.pread` 读文件尾部的内嵌视频范围 |
| `BadgeCache` | 角标/文字/空态纹理缓存（时长、渐变、动态照片角标、播放图标、空态文本） |
| `MediaTextureStore` | 缩略图/大图纹理 LRU 缓存 + 跨线程上传队列 |
| `ThumbnailLoader` | 宫格缩略图异步加载（`ContentResolver.loadThumbnail`，固定 4 线程） |
| `GlUtils` | shader 编译、纹理上传/删除、`GL_MAX_TEXTURE_SIZE` |

## 2. 通用约定（改动前先读）

1. **`RENDERMODE_WHEN_DIRTY`**：三个 GL View 全部按需重绘。渲染器上的 `items` / `scrollY` / `offsetPx` / `scale` 等字段是 `@Volatile`、由主线程写、GL 线程读，**每次修改后都要 `requestRender()`**。
2. **GL 线程回调必须切主线程**：渲染器的 `onScrollChanged`、`onNearEnd` 都在 GL 线程触发，View 层统一 `post`/`postOnAnimation` 后再交给宿主（宫格是 `onNearEnd → post { onLoadMore }`）。
3. **默认 z-order（`setZOrderOnTop(false)`）**：surface 位于窗口层之后，窗口层控件（标题栏、胶囊导航、播放器控制栏）可正常盖在 GL 之上；反过来，**绝不要给 `GLSurfaceView` 设 View 背景色**——View 背景绘制在窗口层，会盖住位于其后的 surface（这是早期「黑屏/遮挡」类问题的根因之一，见 [history](history.md)）。清屏色一律用 `glClearColor`。
4. **纹理只在 GL 线程创建/删除**：解码线程产出 `Bitmap` 后进 `MediaTextureStore` 的上传队列，GL 线程在 `onDrawFrame` 里 `drainUploads()`；LRU 淘汰的纹理 ID 进 `pendingDeletes`，同样由 GL 线程统一删除。GL 上下文重建（`onSurfaceCreated`）时清空纹理缓存与角标缓存，并重建占位纹理。
5. **恒定尺寸**：查看器/播放器的 GL View 恒为 `match_parent`，整个会话不 resize（fit 与留白都由渲染器绘制）。这是「翻页闪烁」的修复前提——surface 尺寸变化时系统会先拉伸合成旧缓冲 1~2 帧。
6. **颜色不写字面量**：占位色、清屏色由宿主从主题注入（宫格 `setSurfaceBackgroundColor()`，查看器 `setMediaColors()`）；角标色等「有意恒定色」除外（见 [主题规范](theme.md)）。

## 3. 宫格（`GlThumbnailGridView` + `GlGridRenderer` + `GridGeometry`）

### 3.1 几何模型（`GridGeometry`）

默认 4 列、无边距（`padding = 0`）、单元格间隔 `gap = 1.5dp`（由 View 注入）。三个纵向量必须分清：

| 属性 | 语义 | 参与滚动？ |
|---|---|---|
| `topInset` | 内容**整体下移**的固定偏移，给状态栏+标题栏让位；`cellY()` 含它 | 否（是平移） |
| `bottomInset` | 额外余量，把 `contentHeight` 撑长 | 是（只加长内容） |
| `bottomPadding` | 滚到底时**最后一行底边与屏幕底边的空白**，由 `maxScroll` 直接保证 | 通过 `maxScroll` 生效 |

公式（`viewportWidth/Height` 来自 `onSurfaceChanged`/`onSizeChanged`）：

```
cellSize     = (viewportWidth − padding×2 − gap×(columns−1)) / columns   （≥1）
rowCount     = ceil(itemCount / columns)
rowsHeight   = padding×2 + rowCount×cellSize + (rowCount−1)×gap + bottomInset   ← 不含 topInset
contentHeight= topInset + rowsHeight
cellX(col)   = padding + col×(cellSize+gap)
cellY(row)   = topInset + padding + row×(cellSize+gap)
maxScroll    = max(0, rowsHeight + topInset − viewportHeight + bottomPadding)
```

`maxScroll` 由目标式推出：滚到底时 `topInset + rowsHeight − maxScroll = viewportHeight − bottomPadding`，即「最后一行底边 = 屏高 − bottomPadding」。**漏加 `topInset` 会让最后一行多溢出整整一个 `topInset`**（真机踩过）。

- `cellRect(index, scrollY)` 输出单元格的**屏幕坐标**（含 `−scrollY`）。
- `indexAt(x, y, scrollY)` 做命中测试（用于点击），先减 `padding/topInset` 再按 `cellSize+gap` 整除，落在 gap 上返回 −1。

**底部留白用 `bottomPadding`，不要用 `bottomInset`**：`bottomInset` 只加长内容，余量不足一行时最后一行仍会贴着屏幕底边。

### 3.2 可见行判定与绘制（`GlGridRenderer.onDrawFrame`）

```
viewTop = scrollY;  viewBottom = scrollY + viewportHeight
for row in 0 until rowCount:
    rowTop = topInset + padding + row×(cellSize+gap)
    if (rowTop + cellSize <= viewTop) continue   // 整行在视口上方
    if (rowTop >= viewBottom)          break      // 行 y 递增，后面都不可见
    逐列 drawCell(index = row×columns + column)
```

**必须逐行用真实 y 与视口求交，不能整除反推首/末行**：`topInset` 不是行距整数倍时，反推会在停在顶部时算出 `firstRow = 1`，第 0 行永远不画（「第一行不显示」的根因）。

每帧顺序：清屏（`glClearColor`）→ `textureStore.drainUploads()` → `badgeCache.drainDeletes()` → 绘制各单元格 → 空态/回调。单元格绘制：

1. 无纹理且未标记失败 → 触发 `onRequestThumbnail(item, cellSize)`（由 `ThumbnailLoader` 异步加载），本次画**占位纹理**（2×2，主题色）；有纹理则按 **center-crop** 采样（按宽高比裁 u/v 区间）。
2. **视频**（`isVideo && duration > 0`）：底部 50% 画黑色→透明渐变（`BadgeCache.bottomGradient`，底部约 70% 黑），左下角画「圆形播放圈 + 加粗时长文字」（11sp；宽度超过单元格 60% 时等比缩小；边距为单元格 6%）。
3. **动态照片**：同样画底部渐变 + 左下角动态照片角标（36 个白点外圈 + 双圆环，参数取自官方矢量图比例；来自 `BadgeCache.motionBadge`，为**有意恒定白**）。
4. 空列表：居中绘制 `emptyText` 纹理（15sp，`0x8A000000`），不再用 `TextView`（GL 面会盖住窗口层 View）。

**分页触发**：`scrollY + viewportHeight >= contentHeight − viewportHeight×0.5` 时触发一次 `onNearEnd`（`nearEndNotified` 置位；`submitItems` 时重置）。宿主（Fragment/Activity）负责拉下一页。

**滚动回调**：`scrollY` 变化（与上次上报的 `lastNotifiedScrollY` 不同）时触发 `onScrollChanged`（GL 线程）；`resetNearEnd()` 会把 `lastNotifiedScrollY` 重置为 NaN，使下一次绘制强制上报一次（换数据后宿主可重算标题栏状态）。

### 3.3 触摸与惯性（`GlThumbnailGridView`）

- `ACTION_DOWN` 停止惯性；移动超过 `scaledTouchSlop` 才算拖动（起手先吃掉 slop，保证跟手），拖动时 `parent.requestDisallowInterceptTouchEvent(true)` 防止 ViewPager2 抢手势。
- `scrollY` 始终 `coerceIn(0, maxScroll)`。
- `ACTION_UP` 未拖动 → `indexAt` 命中回调 `onItemClick`；已拖动 → `OverScroller.fling`（速度方向取反），`postOnAnimation` 逐帧写 `scrollY`。
- 尺寸/预留变更与数据提交都会 `clampScroll()`（`topInset` 等改变后滚动量可能越界）。

对外 API：`submitItems()`（全量刷新，`appendItems` 同义）、`scrollToTop()`、`setTopInset` / `setBottomInset` / `setBottomPadding`、`setColumns`、`setSurfaceBackgroundColor(color)`（同时刷新清屏色与占位色）、回调 `onItemClick` / `onLoadMore` / `onScrollChanged`。

### 3.4 纹理链路（`ThumbnailLoader` + `MediaTextureStore`）

- `ThumbnailLoader`：`Executors.newFixedThreadPool(4)`；`loadThumbnail(uri, Size(size,size), CancellationSignal)`，`sizePx` 夹取到 **96..1024**；同一 `id` 用 `inFlight` 去重；失败（未被取消时）`markFailed(id)` 防止无限重试；`onAttachedToWindow` 建、`onDetachedFromWindow` 关（`shutdown()` 取消在飞请求）。
- `MediaTextureStore`：`LruCache<Long, TextureEntry>`，容量按 `width×height×4` 字节计，默认 **32MB**（宫格）；查看器传 **96MB**。被淘汰/清除的纹理 ID 进删除队列，GL 线程 `drainUploads()` 统一删除。`clear()` 会回收未上传的 Bitmap。
- **无优先级队列、无离开取消**：滚出屏幕的缩略图请求仍会跑完并进缓存；宫格换页/重载不中断在飞任务（见 [backlog](backlog.md)）。

## 4. 查看器（`GlImageViewer` + `GlImagePagerRenderer`）

### 4.1 翻页模型

- `items` + `currentIndex` + `offsetPx`（当前页相对视口的水平位移，单位像素）。每帧绘制顺序：**前一页（−viewportWidth + offsetPx）→ 后一页（+viewportWidth + offsetPx）→ 当前页**，天然实现「拖动时两页同屏、前后页预加载」。
- 静止时 `offsetPx = 0`。拖页跟手：`offsetPx` 限制在 ±viewportWidth。
- 松手结算 `settlePage()`：位移 > 25% 页宽或速度超 `scaledMinimumFlingVelocity` 则翻页，否则回弹；用 `ValueAnimator`（220ms，`DecelerateInterpolator`）动画；翻页后 `currentIndex` ±1（越界则停在边缘）、清缩放并回调 `onPageChanged`。
- **页面纹理在绘制时按需请求**：`drawPage` 发现缺纹理即 `onRequestImage`（由于前后页都画，实际是「当前页 ±1 的预加载」）。

### 4.2 图片解码与纹理

- 图片：`ImageDecoder`（`ALLOCATOR_SOFTWARE`），目标最长边 `min(GL_MAX_TEXTURE_SIZE, max(viewportWidth, viewportHeight))`（约一屏分辨率）。EXIF 方向由 `ImageDecoder` **隐式套用**，没有手写方向矩阵。滑出工作集后同一张图只保留降低分辨率的纹理（放大超过约 1.2× 属于纹理放大，后续可按需做高分解码）。
- 视频封面：`loadThumbnail(uri, Size(viewportWidth/2, viewportHeight/2).coerceAtLeast(160))`。
- 缓存：`MediaTextureStore(96MB)`。容量依据：解码上限按「屏幕最长边」而非「≤4096」，单张纹理约 17~23MB，当前页 + 前后页工作集可完整驻留；若单张 36MB 会导致 LRU 每帧互踢、画面在两张纹理间逐帧闪烁（根因记录见 [history](history.md)）。
- 解码线程池 3 线程，`inFlight` 去重；失败 `markFailed` 后该页显示占位色。

### 4.3 缩放与平移

- `scale ∈ [1, 5]`；双击在 2.5× 与 1× 间切换；双指缩放以焦点为锚（换算内容坐标后重算 `panX/panY`）。
- 平移边界：`|panX| ≤ (scale−1)×width/2`，`|panY| ≤ (scale−1)×height/2`（`clampPan`）。
- `scale ≤ 1.01` 时按「翻页/上划面板」处理手势；放大态下拖动是平移。
- fit：所有页面 **contain** 居中（不裁剪）；留白由清屏色绘制。

### 4.4 观看底色（与系统深浅色正交）

渲染器区分两组颜色，由宿主注入（来自主题色板）：

- `clearLight` / `placeholderLight`：非沉浸（白底查看）用；`clearDark` / `placeholderDark`：沉浸（黑底）用。
- `lightBackground` 布尔由 `ViewerActivity` 按沉浸状态切换——**它表达的是「用哪种观看底色」，与系统深浅色无关**（正交维度）。

### 4.5 视频页与动态照片

- 视频条目在查看器中画**封面 + 播放图标**（圆形半透明黑底 + 白色三角，尺寸 = `min(视口宽, 高) × 0.22`，居中于 fit 矩形）。渲染器记录当前页图标矩形 `iconRect`，供 `isPlayIconHit(x, y)` 命中测试；切页时清空。
- **动态照片长按播放**：`startMotionVideo(item)` 要求 `isMotionPhoto && motionVideoLength > 0`，用 `VideoSource.Embedded(uri, length)` 起播；每帧 `updateTexImage()` + `getTransformMatrix()`，在**当前页**上以 center-fit 叠加 OES 视频（`drawMotionVideo`），尺寸读数就绪前保持静图；`stopMotionVideo()`（松手/翻页/`onPause`/`onDetachedFromWindow`）停止并回静图；播放错误记日志并回退静图。叠加视频的顶点/纹理约定与 `GlVideoView` 一致（v=0 在画面底部）。

### 4.6 手势入口汇总（供宿主接）

`onSingleTap(x, y)`（沉浸在宿主切换，查看器内先做播放图标命中）、`onDoubleTap` 内部处理、`onLongPress` / `onPressEnd`（动态照片）、`onSheetDragStart/onSheetDrag/onSheetDragEnd`（上划详情面板，dy 与速度）、`onPageChanged(index, item)`。上划与横滑的判定：位移方向谁绝对值大归谁，阈值都是 `scaledTouchSlop`。

## 5. 视频链路（`VideoPlayManager` + `GlVideoView`）

### 5.1 结构

```
GlVideoView (GLSurfaceView, RENDERMODE_WHEN_DIRTY)
 ├─ VideoRenderer        清屏黑 → OES 采样 → aspect-fit 绘制（恒定 match_parent）
 └─ VideoPlayManager     MediaPlayer + SurfaceTexture(OES) + 状态机
      ├─ VideoSource.File(uri)                  独立视频文件
      └─ VideoSource.Embedded(uri, videoLength) 动态照片尾部内嵌 MP4（EmbeddedVideoDataSource）
```

- `attachTexture(textureId)` **必须在 GL 线程**（`onSurfaceCreated` 里调用）：创建 `SurfaceTexture`、`setOnFrameAvailableListener`（触发 `requestRender`），主线程侧换绑 `Surface` 并重建播放器；GL 上下文重建（Activity 重建/后台回收）时自动换绑并重建播放器。
- 两种调用顺序都要成立：播放器页是「GL surface 先建 → `setVideoUri`」，查看器动态照片是「`setSource` 先到 → surface 后到」。因此 `attachTexture` 会**无条件**尝试 `createPlayerIfPossible()`，`setSource` 也会；两边都幂等（`mediaPlayer != null` 即返回）。
- `createPlayerIfPossible` 前提：`!released && source != null && surface != null`。准备流程：`setAudioAttributes(USAGE_MEDIA / CONTENT_TYPE_MOVIE)` → `setDataSource` → `setSurface` → `prepareAsync`；`onPrepared` 记录时长/宽高，`playWhenReady` 为真则起播；`onVideoSizeChanged` 更新尺寸；错误回调返回 true（消费）。
- 控制 API（主线程调用）：`setSource`（换源并置 `playWhenReady = true`）、`start` / `pause`、`seekTo`、`stop`（停并释放播放器，保留纹理与 Surface）、`release`（彻底释放）。状态读取：`isPlaying` / `duration` / `currentPosition`。
- 回调 `Listener`：`onPrepared(durationMs)` / `onCompletion()` / `onError(message)` / `onFrameAvailable()`（GL 线程可能回调，由 View 层转主线程）。

### 5.2 方向与 UV（历史坑，改动必读）

- **旋转由 `MediaPlayer` 处理**：渲染到 `Surface` 的帧已按容器元数据旋转，`videoWidth/Height` 即旋转后的显示尺寸。渲染端直接用它做 aspect-fit，**不要**再读 rotation、不要再交换宽高（重复旋转会导致画面颠倒）。
- **UV 采用 GL 常规约定**：v=0 在画面底部（左上顶点取 `(0,1)`），且必须乘 `SurfaceTexture.getTransformMatrix()` 的结果。
- `GlVideoView` 顶点直接用 NDC（无投影矩阵），`GlImagePagerRenderer` 的视频叠加用正交投影 + 同一约定，两处保持一致。

### 5.3 播放底色与 z-order

`GlVideoView` 清屏恒黑、默认 z-order；`VideoPlayerActivity` 需把窗口背景设为透明，半透明控制栏（`#99000000`）才能透出下层视频（见 [界面与交互](ui-guide.md)）。播放中由宿主保持 `FLAG_KEEP_SCREEN_ON`。

### 5.4 解码策略与已知限制

- 链路是 `MediaPlayer`（底层 `MediaCodec`）：框架按 `MediaCodecList` 顺序选择解码器，厂商硬件解码器优先于 `c2.android.*` 软件解码器，即**默认硬解优先**；无可用硬解时框架回退软解，保证可播性。
- **`MediaPlayer` 没有公开 API 可指定/排除编解码器**；`setSurface + OES` 路径本身不会导致软解。
- 若日后需要「强制硬解 / 禁止软解」的精确控制，需要自建 `MediaCodec` 管线（`MediaExtractor` 选轨 → `MediaCodecList` 按 `isHardwareAccelerated()` 选码 → OES 渲染；音频 `MediaCodec + AudioTrack` 自行同步），见 [backlog](backlog.md)。
- 未实现：**音频焦点**（只设 `AudioAttributes`，未 `requestAudioFocus`）。

## 6. 动态照片内嵌视频读取（`EmbeddedVideoDataSource`）

- 视频位于文件**最后 `videoLength` 字节**（长度来自 XMP，见 [数据层](data-layer.md)）：`start = statSize − videoLength`。
- `getSize() = videoLength`；`readAt(position, …)` 用 `Os.pread(fd, buf, start + position)` 直接读，**不拷贝**视频数据；越界返回 −1；`close()` 关文件描述符（`VideoPlayManager.releasePlayer` 里统一 `close`）。
- 构造时校验 `videoLength > 0 && start >= 0`，否则抛 `IOException`（播放器回调 `onError`）。

## 7. 角标与文字纹理（`BadgeCache`）

LruCache（默认 48 项），键：`info:<时长>`、`bottom_gradient`、`motion`、`play`、`text:<空态文案>`；`textSizes` 记录文字纹理的逻辑尺寸。GL 线程专用；淘汰纹理进删除队列，`drainDeletes()` 在每帧开头执行；`onSurfaceCreated` 时 `evictAll()` 重建。

| 纹理 | 规格 | 用途 |
|---|---|---|
| `bottomGradient` | 4×64，透明 → `0xB3000000`（约 70% 黑） | 视频/动态照片底部渐变 |
| `videoInfo(text)` | 11sp 粗体白字 + 描边圆环内三角，按原始尺寸绘制、不拉伸 | 视频时长（左下角） |
| `motionBadge` | 36 白点外圈 + 双圆环 + 柔和阴影（官方矢量图比例） | 动态照片角标（左下角） |
| `playIcon` | 64dp 圆（`0x59000000`）+ 白色三角 | 查看器视频页播放图标 |
| `emptyText(text)` | 15sp，`0x8A000000`，四周 8dp 留白 | 宫格空态（GL 内绘制） |

这些纹理中的颜色是**有意恒定**的（画在任意用户照片之上，背景不可知），不要"顺手"接入主题色板。

## 8. GL 与窗口层的交互（排查类问题先读）

- 所有 GL View 默认 z-order；surface 位于窗口层**下方**，因此窗口层的标题栏/导航/控制栏能盖在 GL 上——这是查看器沉浸、播放器覆盖层的实现基础。
- surface 在窗口层下方 ⇒ **不要**给 GL View 设背景色/`setZOrderOnTop(true)`（宫格曾为底部胶囊导航改回默认 z-order）。
- `uiautomator dump` 看不到 GL 内容（它不是 View 树的一部分），GL 相关布局验证要用截图逐像素判断，判据见 [验证方法与判据](verification.md)。
