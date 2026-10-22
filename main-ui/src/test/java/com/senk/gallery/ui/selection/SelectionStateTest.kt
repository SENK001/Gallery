package com.senk.gallery.ui.selection

import com.senk.gallery.data.entity.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SelectionState] 的纯 JVM 单元测试（不依赖 Android 运行时）。
 *
 * 覆盖多选状态机的关键语义：进入/退出、点击切换、数量上限、全选、按 id 匹配、
 * 监听器通知以及「只存 id、不持有旧 MediaItem」的设计约束。
 */
class SelectionStateTest {

    private fun item(id: Long): MediaItem = MediaItem().apply { this.id = id }

    @Test
    fun `toggle enters selection mode and keeps click order`() {
        val state = SelectionState()
        assertFalse(state.isActive)

        assertTrue(state.toggle(item(3)))
        assertTrue(state.toggle(item(1)))
        assertTrue(state.toggle(item(2)))

        assertTrue(state.isActive)
        assertEquals(3, state.count)
        assertEquals(listOf(3L, 1L, 2L), state.selectedIds.toList())

        // 再次点击同一条目 = 取消选中（仍处于多选态）
        assertFalse(state.toggle(item(1)))
        assertEquals(listOf(3L, 2L), state.selectedIds.toList())
        assertTrue(state.isActive)
    }

    @Test
    fun `select all respects max count`() {
        val state = SelectionState()
        state.maxCount = 2
        state.selectAll(listOf(item(1), item(2), item(3), item(4)))
        assertEquals(2, state.count)
        assertTrue(state.isSelected(1))
        assertTrue(state.isSelected(2))
        assertFalse(state.isSelected(3))
    }

    @Test
    fun `max count blocks new selection but allows deselect`() {
        val state = SelectionState()
        state.maxCount = 1
        assertTrue(state.select(item(1)))
        // 已达上限：select 返回是否仍选中（item(2) 未被选中 → false）
        assertFalse(state.select(item(2)))
        assertEquals(1, state.count)
        assertFalse(state.isSelected(2))
        // 取消后可再选其它条目
        state.deselect(1)
        assertTrue(state.select(item(2)))
        assertTrue(state.isSelected(2))
    }

    @Test
    fun `selectedItems filters by source order and ignores stale ids`() {
        val state = SelectionState()
        state.toggle(item(2))
        state.toggle(item(1))

        val source = listOf(item(1), item(2), item(3))
        assertEquals(listOf(1L, 2L), state.selectedItems(source).map { it.id })

        // 数据源刷新后旧 id 不存在 → 被忽略，不崩溃
        assertEquals(emptyList<MediaItem>(), state.selectedItems(listOf(item(3))))
    }

    @Test
    fun `selectIds preselects by id with one notification and respects max count`() {
        val state = SelectionState()
        var notifications = 0
        state.addListener { notifications++ }

        state.selectIds(listOf(7L, 9L, 7L, 0L, -1L))
        assertTrue(state.isActive)
        assertEquals(listOf(7L, 9L), state.selectedIds.toList())
        assertEquals(1, notifications)

        // 带上限后，超出的 id 不再加入
        val limited = SelectionState()
        limited.maxCount = 2
        limited.selectIds(listOf(1L, 2L, 3L))
        assertEquals(2, limited.count)
        assertTrue(limited.isSelected(1))
        assertTrue(limited.isSelected(2))
        assertFalse(limited.isSelected(3))
    }

    @Test
    fun `clear empties selection but keeps selection mode active`() {
        val state = SelectionState()
        state.enter(item(1))
        state.toggle(item(2))
        var notifications = 0
        state.addListener { notifications++ }

        state.clear()
        assertEquals(0, state.count)
        assertTrue("取消全选后仍应保持多选态", state.isActive)
        assertEquals(1, notifications)

        // 已空时再 clear 不重复通知
        state.clear()
        assertEquals(1, notifications)
    }

    @Test
    fun `isAllSelected detects full partial and empty states`() {
        val state = SelectionState()
        val items = listOf(item(1), item(2), item(3))

        assertFalse("空列表不算全选", state.isAllSelected(emptyList()))
        assertFalse(state.isAllSelected(items))

        state.selectAll(items)
        assertTrue(state.isAllSelected(items))
        assertTrue(state.isAllSelected(listOf(item(2), item(1))))

        state.deselect(2)
        assertFalse(state.isAllSelected(items))
    }

    @Test
    fun `exit clears selection and notifies listeners`() {
        val state = SelectionState()
        var notifications = 0
        val listener: (SelectionState) -> Unit = { notifications++ }
        state.addListener(listener)

        state.enter(item(1))
        assertEquals(1, notifications) // enter(initial) 一次性通知
        state.exit()
        assertEquals(2, notifications)
        assertFalse(state.isActive)
        assertEquals(0, state.count)

        // 退出后重复 exit 不重复通知
        state.exit()
        assertEquals(2, notifications)
        state.removeListener(listener)
    }

    @Test
    fun `listener failure does not break state machine`() {
        val state = SelectionState()
        var received = 0
        state.addListener { throw IllegalStateException("bad listener") }
        // 异常监听器会被 SelectionState 捕获（打日志），不能打断状态机
        state.addListener { received++ }
        state.toggle(item(1))
        assertTrue(state.isSelected(1))
        // toggle 在未激活时先 enter()（通知一次）再切换（再通知一次），共 2 次
        assertEquals(2, received)
    }
}
