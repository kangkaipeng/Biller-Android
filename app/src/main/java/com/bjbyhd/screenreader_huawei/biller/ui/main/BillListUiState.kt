package com.bjbyhd.screenreader_huawei.biller.ui.main

import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord

/**
 * 账单列表 MVI — UiState / Event / Effect / DisplayItem 定义 (v4)
 *
 * 模块: feature/biller/ui/main
 * 职责: 定义账单列表页面的不可变 UI 状态、用户交互事件、一次性副作用、列表展示项类型。
 *
 * ## 数据流向
 *
 * ```
 * Fragment  ──Event──→  ViewModel  ──Repository──→  Room
 *    ↑                      │
 *    ├──StateFlow<UiState───┤
 *    ├──Flow<Effect─────────┘    (一次性副作用: 剪贴板/Toast)
 * ```
 *
 * 所有状态变更通过 [BillListUiState.copy()] 生成新快照，
 * View 层通过 [kotlinx.coroutines.flow.collect] 消费新状态。
 * 一次性副作用通过 [BillListEffect] + Effect Channel 发射。
 */

/**
 * 账单列表页 UI 状态快照
 *
 * 设计原则：
 *   - 不可变（data class），所有变更通过 copy()
 *   - 单一数据源：Fragment 只从此对象读取渲染所需的一切
 *   - editingBill 非 null 时弹出编辑对话框，null 时关闭
 *
 * @property bills          按日期分组后的展示列表
 * @property categories     所有可选消费分类（对话框下拉选择器用）
 * @property isLoading      首次加载中标志（true 时显示 progress indicator）
 * @property editingBill    当前正在编辑的账单条目（null = 无对话框）
 */
data class BillListUiState(
    val bills: List<BillDisplayItem> = emptyList(),
    val categories: List<com.bjbyhd.screenreader_huawei.biller.data.category.Category> = emptyList(),
    val isLoading: Boolean = true,
    val editingBill: BillRecord? = null,

    // ═══════ v4.1 新增: 筛选状态 ═══════
    /** 当前选中的年月（默认: 当前月份），用于月份左右箭头切换 */
    val selectedYearMonth: java.time.YearMonth = java.time.YearMonth.now(),
    /** 分类筛选：null = 全部，非 null = 仅显示此分类 */
    val filterCategoryId: Long? = null,
    /** 渠道筛选 (v5.1)：null = 全部，WEIXIN / ALIPAY / OTHER */
    val filterChannel: String? = null,
    /** 搜索关键词（本地过滤 merchant / alias / note） */
    val searchQuery: String = "",
    /** 当前筛选条件下的月支出合计 */
    val monthlyExpenseSum: Double = 0.0,
    /** 当前筛选条件下的记录条数 */
    val monthlyCount: Int = 0,
    /** 搜索框是否展开（v4.5: 从 Fragment 局部状态提升到 UiState） */
    val isSearchExpanded: Boolean = false,
)

/**
 * 账单列表展示项 — 支持日期分组标题和账单条目两种类型
 *
 * RecyclerView 使用 [ListAdapter] + [DiffUtil] 处理两种 viewType。
 * [DateHeader] 代表"6月8日"等分组标题行。
 * [Bill] 代表单条账单卡片行。
 */
sealed interface BillDisplayItem {

    /** 日期分组标题 — "6月8日" 格式 */
    data class DateHeader(val dateLabel: String) : BillDisplayItem

    /** 账单卡片条目 */
    data class Bill(
        val record: BillRecord,
        /** 分类 emoji 图标 */
        val categoryEmoji: String,
        /** 分类名称（如"餐饮"），未分类时为 null */
        val categoryName: String?,
        /** 商户显示名：别名 > 原始商户 > "未知商户" */
        val merchantDisplay: String,
    ) : BillDisplayItem
}

/**
 * 用户交互事件 — View 层发出，ViewModel 处理
 *
 * 每种用户操作对应一个 Event，ViewModel 通过 [BillListViewModel.onEvent]
 * 统一分发处理。事件处理完成后更新 [BillListUiState] 驱动 UI 刷新。
 */
sealed interface BillListEvent {
    /** 点击账单条目 → 打开编辑对话框 */
    data class ClickBill(val bill: BillRecord) : BillListEvent

    /** 长按账单条目 → 复制到剪贴板 */
    data class LongPressBill(val bill: BillRecord) : BillListEvent

    /**
     * 批量更新账单可编辑字段 (v5.2)
     *
     * 将保存操作中的 alias / note / amount / txnId 变更合并为单一事件，
     * 替代逐个发射 [UpdateAlias] + [UpdateAmount] + [UpdateTransactionId] + [UpdateNote]。
     *
     * null 字段表示"无变更"，Repository 层保留数据库现有值。
     * 非 null 的空白字符串由 Repository 统一转为 null（清除语义）。
     *
     * @property billId 账单 ID
     * @property alias  商户别名，null = 不修改
     * @property note   备注内容，null = 不修改
     * @property amount 金额，null = 不修改
     * @property txnId  交易流水号，null = 不修改
     */
    /** 更新消费分类（null = 移除分类） */
    data class UpdateCategory(val billId: Long, val categoryId: Long?) : BillListEvent

    data class UpdateBillFields(
        val billId: Long,
        val alias: String? = null,
        val note: String? = null,
        val amount: Double? = null,
        val txnId: String? = null,
    ) : BillListEvent

    /** 删除账单 */
    data class DeleteBill(val billId: Long) : BillListEvent

    /** 关闭编辑对话框 */
    data object DismissDialog : BillListEvent

    // ═══════ v4.1 新增: 筛选事件 ═══════
    /** 切换月份（◀ ▶ 箭头） */
    data class SelectMonth(val yearMonth: java.time.YearMonth) : BillListEvent
    /** 按分类筛选（null = 全部） */
    data class SelectCategory(val categoryId: Long?) : BillListEvent
    /** 按渠道筛选 (v5.1) — null=全部, WEIXIN/ALIPAY/OTHER */
    data class SelectChannel(val channel: String?) : BillListEvent
    /** 搜索关键词输入 */
    data class Search(val query: String) : BillListEvent
    /** 切换搜索框展开/折叠（v4.5: 替代 Fragment 手动 visibility 操作） */
    data object ToggleSearch : BillListEvent
}

/**
 * 一次性副作用 — 通过 [BaseMviViewModel.effect] Channel 发射
 *
 * 与 [BillListEvent]（用户交互事件）不同，Effect 是 ViewModel 在处理完业务逻辑后
 * 向 View 层发射的"命令"——它只消费一次，不会被 StateFlow 重播。
 * 适用于: 剪贴板写入 + Toast、导航跳转等。
 */
sealed interface BillListEffect {
    /** 复制结构化文本到系统剪贴板 + Toast 提示 */
    data class CopyToClipboard(val text: String) : BillListEffect
}
