package com.senk.gallery.ui.grid

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 宫格页面控制器：照片页与相册详情页**完全相同**的数据逻辑收敛在这里。
 *
 * 职责：
 * - 用 [MediaPager] 分页加载（reset 重载 / 接近底部追加）；
 * - 监听 Provider 根 URI 的内容变化并自动回顶重载（删除/收藏/媒体库变化后刷新）；
 * - 把结果提交给 [MediaGridView]（reset → setItems + scrollToTop；分页 → appendItems）；
 * - 提供已加载列表给宿主做查看器定位、全选、多选动作目标集合。
 *
 * 宿主只保留各自不同的部分：insets 分配、标题栏 chrome（照片页在 MainActivity、
 * 相册详情页在本页）、多选 UI（动作栏/菜单）。这样两页共享同一套分页与刷新语义，
 * 不会出现「照片页回顶、相册页不回顶」这类行为漂移。
 *
 * @param baseUri 数据源（全量媒体或某个相册的内容 URI）。
 * @param pageSize 每页条数。
 */
class GridPageController(
    context: Context,
    private val grid: MediaGridView,
    baseUri: Uri,
    private val pageSize: Int = MediaPager.DEFAULT_PAGE_SIZE,
) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val pager = MediaPager(appContext, baseUri, pageSize)
    private var observer: ContentObserver? = null
    private var scope: CoroutineScope? = null

    /** reset 加载完成（已回顶、已提交数据）后回调，宿主据此恢复标题栏静止态。 */
    var onResetLoaded: (() -> Unit)? = null

    /** 任意一次加载完成（reset 或分页）后回调。 */
    var onLoaded: (() -> Unit)? = null

    /** 开始监听内容变化并做首次加载（宿主在 onViewCreated/onCreate 调用）。 */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        if (observer == null) {
            val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    // 内容变化：从第一页重载并回顶（与旧 GL 宫格行为一致）
                    load(reset = true)
                }
            }
            resolver.registerContentObserver(GalleryContract.CONTENT_URI, true, contentObserver)
            observer = contentObserver
        }
        load(reset = true)
    }

    /** 停止监听（宿主 onDestroyView/onDestroy 调用）。 */
    fun stop() {
        observer?.let { resolver.unregisterContentObserver(it) }
        observer = null
        scope = null
    }

    /**
     * 加载数据。reset = true 时从第一页重载并回顶；false 时追加下一页。
     * 交错调用（滚动回调频繁触发）由 [MediaPager] 内部防重入。
     */
    fun load(reset: Boolean) {
        val coroutineScope = scope ?: return
        if (pager.isLoading()) {
            return
        }
        coroutineScope.launch {
            val oldSize = if (reset) 0 else pager.items().size
            val updated = pager.load(reset)
            if (!updated) {
                return@launch
            }
            val snapshot = pager.items()
            if (reset) {
                grid.scrollToTop()
                grid.setItems(snapshot)
                onLoaded?.invoke()
                onResetLoaded?.invoke()
            } else {
                if (snapshot.size > oldSize) {
                    grid.appendItems(snapshot.subList(oldSize, snapshot.size))
                }
                onLoaded?.invoke()
            }
        }
    }

    /** 当前已加载条目快照。 */
    fun items(): List<MediaItem> = pager.items()

    /** 已选条目（按当前列表顺序）。 */
    fun selectedItems(): List<MediaItem> = grid.selection.selectedItems(pager.items())

    /** 全选当前已加载条目（受 `selection.maxCount` 限制）。 */
    fun selectAll() {
        grid.selection.selectAll(pager.items())
    }

    /**
     * 全选 / 取消全选当前已加载条目（多选菜单「全选」按钮的切换语义）：
     * 已全选 → 清空已选并**保持多选态**；否则全选。
     */
    fun toggleSelectAll() {
        val items = pager.items()
        if (items.isEmpty()) {
            return
        }
        if (grid.selection.isAllSelected(items)) {
            grid.selection.clear()
        } else {
            grid.selection.selectAll(items)
        }
    }

    /** 当前已加载条目是否已全选（用于切换菜单文案「全选 / 取消全选」）。 */
    fun isAllSelected(): Boolean = grid.selection.isAllSelected(pager.items())

    /** 当前列表里是否存在指定 id 的条目（查看器定位用）。 */
    fun indexOf(id: Long): Int = pager.items().indexOfFirst { it.id == id }

    /** 是否已到底（宿主如需展示「没有更多」可用）。 */
    fun endReached(): Boolean = pager.endReached()
}
