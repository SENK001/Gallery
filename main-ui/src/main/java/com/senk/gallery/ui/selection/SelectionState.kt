package com.senk.gallery.ui.selection

import android.util.Log
import com.senk.gallery.data.entity.MediaItem
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 多选状态 —— 照片页宫格、相册详情页宫格、选图页共用的一份「已选集合 + 是否处于多选态」。
 *
 * 设计要点：
 * - **与 View 无关**：宫格（[com.senk.gallery.ui.grid.MediaGridView]）内部监听本对象刷新格子，
 *   宿主（Fragment/Activity）监听本对象刷新标题栏、动作栏等外部 UI；两边互不引用。
 * - 只保存 id，不持有 [MediaItem]：分页刷新/数据重查后旧对象会被替换，按 id 匹配才不会错位。
 * - 选中顺序用 [LinkedHashSet] 保留，分享/添加等批量操作按用户点选顺序提交，行为可预期。
 * - 回调在主线程同步派发；监听器内部不要再触发会递归修改状态的逻辑（宫格只做 UI 刷新）。
 */
class SelectionState {

    /** 多选数量上限（≤0 视为不限）。选图页会按调用方给的 maxCount 设置。 */
    var maxCount: Int = Int.MAX_VALUE

    private val selectedIdsInternal = LinkedHashSet<Long>()
    private val listeners = CopyOnWriteArrayList<(SelectionState) -> Unit>()

    /** 是否处于多选模式。 */
    var isActive: Boolean = false
        private set

    /** 已选数量。 */
    val count: Int
        get() = selectedIdsInternal.size

    /** 已选 id 集合（只读视图，不要强转后修改）。 */
    val selectedIds: Set<Long>
        get() = Collections.unmodifiableSet(selectedIdsInternal)

    /**
     * 进入多选模式。
     *
     * @param initial 可选：进入时同时选中该项（长按进多选时用）。
     */
    fun enter(initial: MediaItem? = null) {
        var changed = false
        if (!isActive) {
            isActive = true
            changed = true
        }
        if (initial != null && addInternal(initial.id)) {
            changed = true
        }
        if (changed) {
            notifyListeners()
        }
    }

    /** 退出多选模式并清空已选。 */
    fun exit() {
        if (!isActive && selectedIdsInternal.isEmpty()) {
            return
        }
        isActive = false
        selectedIdsInternal.clear()
        notifyListeners()
    }

    /**
     * 切换选中态（已选 → 取消，未选 → 选中）。未处于多选态时自动进入。
     *
     * @return 切换后该项是否处于选中态；已达 [maxCount] 上限时保持未选并返回 false。
     */
    fun toggle(item: MediaItem): Boolean {
        if (!isActive) {
            enter()
        }
        val nowSelected = if (selectedIdsInternal.contains(item.id)) {
            selectedIdsInternal.remove(item.id)
            false
        } else {
            addInternal(item.id)
        }
        notifyListeners()
        return nowSelected
    }

    /**
     * 选中一项。未处于多选态时自动进入。
     *
     * @return 最终是否处于选中态（已达上限且原本未选时返回 false）。
     */
    fun select(item: MediaItem): Boolean {
        if (!isActive) {
            enter()
        }
        val added = addInternal(item.id)
        if (added) {
            notifyListeners()
        }
        return selectedIdsInternal.contains(item.id)
    }

    /** 取消选中一项（不会退出多选态）。 */
    fun deselect(id: Long) {
        if (selectedIdsInternal.remove(id)) {
            notifyListeners()
        }
    }

    /** 全选：优先选满 [maxCount]；已达上限时只保留前 maxCount 项。 */
    fun selectAll(items: List<MediaItem>) {
        var changed = false
        if (!isActive) {
            isActive = true
            changed = true
        }
        for (item in items) {
            if (selectedIdsInternal.size >= limit()) {
                break
            }
            if (selectedIdsInternal.add(item.id)) {
                changed = true
            }
        }
        if (changed) {
            notifyListeners()
        }
    }

    /**
     * 批量按 id 预选（一次回调）：选图页打开时用它勾选「已加入当前相册」的条目。
     *
     * 与 [selectAll] 的区别是入参为 id 集合，不要求调用方先拿到 [MediaItem]
     * （条目可能还没分页加载到）；同样受 [maxCount] 上限约束。
     */
    fun selectIds(ids: Collection<Long>) {
        var changed = false
        if (!isActive) {
            isActive = true
            changed = true
        }
        for (id in ids) {
            if (selectedIdsInternal.size >= limit()) {
                break
            }
            if (id > 0L && selectedIdsInternal.add(id)) {
                changed = true
            }
        }
        if (changed) {
            notifyListeners()
        }
    }

    fun isSelected(id: Long): Boolean = selectedIdsInternal.contains(id)

    /** 按 `source` 的顺序返回已选条目（不在 `source` 里的历史 id 会被忽略）。 */
    fun selectedItems(source: List<MediaItem>): List<MediaItem> =
        source.filter { selectedIdsInternal.contains(it.id) }

    fun addListener(listener: (SelectionState) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (SelectionState) -> Unit) {
        listeners.remove(listener)
    }

    /** 达到上限时的候选上限（Int.MAX_VALUE 表示不限）。 */
    private fun limit(): Int = if (maxCount <= 0) Int.MAX_VALUE else maxCount

    /** 尝试加入选中集合；已达上限时返回 false。 */
    private fun addInternal(id: Long): Boolean {
        if (selectedIdsInternal.contains(id)) {
            return false
        }
        if (selectedIdsInternal.size >= limit()) {
            return false
        }
        return selectedIdsInternal.add(id)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener(this)
            } catch (e: Exception) {
                // 单个宿主的 UI 异常不能打断多选状态机本身
                Log.e(TAG, "selection listener failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "SelectionState"
    }
}
