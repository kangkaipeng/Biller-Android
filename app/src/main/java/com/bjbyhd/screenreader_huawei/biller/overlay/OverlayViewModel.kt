package com.bjbyhd.screenreader_huawei.biller.overlay

import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 悬浮窗 MVI ViewModel — 管理悬浮窗的全部 UI 状态。
 *
 * ## MVI 循环
 *
 * ```
 * 外部事件 (ShowSingle, ...)        用户交互 (ToggleExpand, DragTo, ...)
 *        │                                    │
 *        └──────────→ onEvent() ←─────────────┘
 *                        │
 *                  _uiState.update { ... }
 *                        │
 *                  uiState: StateFlow
 *                        │
 *              CheckmarkOverlay.collect() → render()
 * ```
 *
 * ## 生命周期
 *   由 [FloatingOverlayService] 创建并持有。Service 存活期间 ViewModel 持续存在。
 *   Service.onDestroy 时 ViewModel 随 Service 一起被 GC。
 *
 * ## 扩展
 *   P3 新增 UpdateCategory / UpdateAlias / UpdateNote 事件。
 *   P4 PLUS 模式新增 ShowList / AppendEntries 事件。
 */
class OverlayViewModel {

    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    fun onEvent(event: OverlayEvent) {
        when (event) {
            is OverlayEvent.ShowSingle -> {
                _uiState.update {
                    it.copy(
                        mode = OverlayMode.CHECKMARK,
                        phase = OverlayPhase.COLLAPSED,
                        bill = event.bill,
                    )
                }
            }
            is OverlayEvent.ToggleExpand -> {
                _uiState.update {
                    val next = when (it.phase) {
                        OverlayPhase.COLLAPSED -> OverlayPhase.EXPANDED
                        OverlayPhase.EXPANDED -> OverlayPhase.COLLAPSED
                        OverlayPhase.HIDDEN -> OverlayPhase.COLLAPSED
                    }
                    it.copy(phase = next)
                }
            }
            is OverlayEvent.Dismiss -> {
                _uiState.update {
                    it.copy(phase = OverlayPhase.HIDDEN, bill = null)
                }
            }
            is OverlayEvent.DragTo -> {
                _uiState.update {
                    it.copy(dragX = event.x, dragY = event.y)
                }
            }
            is OverlayEvent.DragEnd -> {
                _uiState.update {
                    it.copy(dragX = -1f, dragY = -1f)
                }
            }
            // P3: UpdateCategory, UpdateAlias, UpdateNote
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 公共类型定义
// ═══════════════════════════════════════════════════════════════

/**
 * 悬浮窗显示模式
 *
 * - [CHECKMARK]: 支付成功页面 → 显示绿色 ✓（可展开编辑）
 * - [PLUS]: 账单列表页面 → 显示 + 按钮（P4 实现）
 */
enum class OverlayMode { CHECKMARK, PLUS }

// ═══════════════════════════════════════════════════════════════
// 状态 & 事件定义
// ═══════════════════════════════════════════════════════════════

/**
 * 悬浮窗 UI 状态快照 — 驱动 [CheckmarkOverlay] 渲染。
 *
 * @property mode   悬浮窗模式: CHECKMARK / PLUS
 * @property phase  显示阶段: HIDDEN / COLLAPSED / EXPANDED
 * @property bill   当前关联的账单（CHECKMARK 模式）
 * @property dragX  拖拽中的 X 坐标，-1f 表示未在拖拽（使用持久化默认位置）
 * @property dragY  拖拽中的 Y 坐标，-1f 表示未在拖拽
 */
data class OverlayUiState(
    val mode: OverlayMode = OverlayMode.CHECKMARK,
    val phase: OverlayPhase = OverlayPhase.HIDDEN,
    val bill: ParsedBill? = null,
    val dragX: Float = -1f,
    val dragY: Float = -1f,
    // P3 扩展: editingField, categoryList, ...
)

/**
 * 悬浮窗显示阶段
 */
enum class OverlayPhase {
    /** 未显示 — rootView 尚未 addView 或已 removeView */
    HIDDEN,
    /** 收缩态 — 40dp 圆形 FAB */
    COLLAPSED,
    /** 展开态 — 280dp 详情卡片 + dim 背景 */
    EXPANDED,
}

/**
 * 悬浮窗事件 — 外部触发 / 用户交互。
 */
sealed class OverlayEvent {
    /** CHECKMARK 模式: 显示单笔账单 */
    data class ShowSingle(val bill: ParsedBill) : OverlayEvent()
    /** 切换收缩 ↔ 展开 */
    object ToggleExpand : OverlayEvent()
    /** 关闭悬浮窗（移除 View，重置状态） */
    object Dismiss : OverlayEvent()
    /** 拖拽中 — 更新位置 */
    data class DragTo(val x: Float, val y: Float) : OverlayEvent()
    /** 拖拽结束 — 吸附边缘 + 持久化 */
    object DragEnd : OverlayEvent()
    // P3: UpdateCategory(categoryId), UpdateAlias(alias), UpdateNote(note)
    // P4: ShowList(entries), AppendEntries(newEntries)
}
