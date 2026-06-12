package com.bjbyhd.screenreader_huawei.biller.ui.main

import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerQueryBridge
import com.bjbyhd.screenreader_huawei.biller.ui.common.BaseMviViewModel
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.Date
import java.util.Locale

/**
 * 账单列表 ViewModel — MVI 模式核心 (v4.2 — 分类筛选 Flow 驱动)
 *
 * 模块: feature/biller/ui/main
 * 职责:
 *   - 聚合 [IBillerQueryBridge] 的多个 Flow，产生单一的 [BillListUiState]
 *   - 接收 View 层发出的 [BillListEvent]，执行业务操作后更新状态
 *   - 通过 [StateFlow] 驱动 UI 单向渲染
 *
 * ## v4.2 分类筛选修复
 *
 *   - [_filterCategoryId] 提升为 [MutableStateFlow]，作为 [combine] 的第 4 个显式输入
 *   - 修改筛选条件立即触发 [buildUiState] 重算，消除旧版中修改 [filterCategoryId] 后
 *     UI 不刷新的问题
 *   - 哨兵值 -1L 匹配 categoryId == null 的记录（"未分类"），
 *     解决从统计 Tab "其他"跳转时列表为空的 Bug
 *
 * ## MVI 循环
 *
 * ```
 * Fragment                           ViewModel
 *   │                                   │
 *   ├─ collect(uiState) ←─────────── StateFlow<BillListUiState>
 *   │                                   │
 *   ├─ onEvent(ClickBill) ───────────→ when(event) {
 *   │                                   │   ClickBill → updateState { copy(editingBill=...) }
 *   │                                   │   UpdateAlias → launchSafe { ... }
 *   │                                   │   ...
 *   │                                   │ }
 * ```
 *
 * @see BillListUiState UI 状态快照
 * @see BillListEvent 用户交互事件
 * @see IBillerQueryBridge 数据层读取契约
 */
class BillListViewModel(
    private val billRepo: IBillerQueryBridge,
) : BaseMviViewModel<BillListUiState, BillListEvent>(
    initialUiState = BillListUiState()
) {

    companion object {
        private const val TAG = "Biller/ListVM"

        /** 哨兵值 — 统计 Tab "其他"分类对应的筛选键，匹配 categoryId == null 的记录 */
        const val FILTER_UNCATEGORIZED = -1L
    }

    // ═══════════ 内部状态 — 作为 combine 显式输入的 MutableStateFlow ═══════════

    /**
     * 当前选中的年月
     *
     * 从 [BillListUiState.selectedYearMonth] 中独立出来作为 [MutableStateFlow]，
     * 使其可以作为 [combine] 的输入源之一。当用户切换月份时，
     * 此 Flow 发射新值 → combine 重新执行 → buildUiState 按新月筛选列表。
     */
    private val _selectedMonth = MutableStateFlow(YearMonth.now())

    /**
     * 当前分类筛选条件 (v4.2)
     *
     * null = 全部类别
     * [FILTER_UNCATEGORIZED] (-1L) = 未分类（匹配 categoryId == null 的记录）
     * 其他 Long 值 = 具体分类 ID
     *
     * 作为 [combine] 的第 4 个显式 Flow 输入，修改即触发列表重算。
     * 同步更新 [BillListUiState.filterCategoryId] 供 UI Chip checked 状态回读。
     */
    private val _filterCategoryId = MutableStateFlow<Long?>(null)

    /**
     * 当前渠道筛选条件 (v5.1)
     *
     * null = 全部渠道
     * "WEIXIN" / "ALIPAY" = 具体支付平台
     * "OTHER" = 非微信/支付宝的其他 App
     *
     * 作为 [combine] 的第 5 个显式 Flow 输入。
     */
    private val _filterChannel = MutableStateFlow<String?>(null)

    // ═══════════ 初始化 — 5-Flow combine ═══════════

    init {
        launchSafe {
            combine(
                billRepo.observeAll(),
                billRepo.observeCategories(),
                _selectedMonth,
                _filterCategoryId,
                _filterChannel,                  // Flow 5: 渠道筛选 (v5.1)
            ) { records, categories, month, filterCat, filterCh ->
                buildUiState(records, categories, month, filterCat, filterCh)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    // ═══════════ Event 分发 — 统一入口 ═══════════

    override fun onEvent(event: BillListEvent) {
        CLog.d(TAG) { "onEvent: ${event::class.simpleName}" }
        when (event) {
            is BillListEvent.ClickBill          -> onBillClicked(event.bill)
            is BillListEvent.LongPressBill      -> onBillLongPressed(event.bill)
            is BillListEvent.UpdateCategory     -> onUpdateCategory(event.billId, event.categoryId)
            is BillListEvent.UpdateBillFields  -> onUpdateBillFields(
                event.billId, event.alias, event.note, event.amount, event.txnId
            )
            is BillListEvent.DeleteBill         -> onDeleteBill(event.billId)
            is BillListEvent.DismissDialog      -> onDismissDialog()
            // v4.1 筛选事件
            is BillListEvent.SelectMonth    -> onSelectMonth(event.yearMonth)
            is BillListEvent.SelectCategory -> onSelectCategory(event.categoryId)
            is BillListEvent.SelectChannel  -> onSelectChannel(event.channel)
            is BillListEvent.Search         -> onSearch(event.query)
            is BillListEvent.ToggleSearch   -> onToggleSearch()
        }
    }

    // ═══════════ Event 处理器 ═══════════

    private fun onBillClicked(bill: BillRecord) {
        updateState { copy(editingBill = bill) }
    }

    private fun onBillLongPressed(bill: BillRecord) {
        val text = buildClipboardText(bill)
        sendEffect(BillListEffect.CopyToClipboard(text))
    }

    /** 更新消费分类 */
    private fun onUpdateCategory(billId: Long, categoryId: Long?) {
        launchSafe { billRepo.updateBillFields(billId, categoryId = categoryId) }
    }

    /**
     * 批量更新账单字段
     *
     * 将多个字段的变更合并为单次 Repository 调用:
     *   1× getById + 1× update → 1× Room Flow 发射 → 1× UI 重建
     *
     * 对比旧实现（逐字段独立发射 4 个 Event），减少 75% 的 DAO 操作
     * 并消除多次 submitList 导致的 DiffUtil 排队效应。
     *
     * @param billId 账单 ID
     * @param alias  商户别名，null = 不修改
     * @param note   备注内容，null = 不修改
     * @param amount 金额，null = 不修改
     * @param txnId  交易流水号，null = 不修改
     */
    private fun onUpdateBillFields(
        billId: Long,
        alias: String?,
        note: String?,
        amount: Double?,
        txnId: String?,
    ) {
        launchSafe { billRepo.updateBillFields(billId, alias = alias, note = note, amount = amount, txnId = txnId) }
    }

    private fun onDeleteBill(billId: Long) {
        launchSafe {
            billRepo.deleteById(billId)
            updateState { copy(editingBill = null) }
        }
    }

    private fun onDismissDialog() {
        updateState { copy(editingBill = null) }
    }

    // ═══════════ v4.2 筛选事件处理 ═══════════

    /** 切换月份 → 更新 Flow，combine 自动重新筛选列表 */
    private fun onSelectMonth(yearMonth: YearMonth) {
        _selectedMonth.value = yearMonth
    }

    /**
     * 按分类筛选 (v4.2 修复)
     *
     * 同步更新:
     *   1. [_filterCategoryId] MutableStateFlow → 驱动 combine 重算列表
     *   2. [BillListUiState.filterCategoryId]  → 供 UI Chip checked 状态回读
     */
    private fun onSelectCategory(categoryId: Long?) {
        _filterCategoryId.value = categoryId
        updateState { copy(filterCategoryId = categoryId) }
    }

    /** 按渠道筛选 (v5.1) */
    private fun onSelectChannel(channel: String?) {
        _filterChannel.value = channel
        updateState { copy(filterChannel = channel) }
    }

    private fun onSearch(query: String) {
        updateState { copy(searchQuery = query, isSearchExpanded = false) }
    }

    private fun onToggleSearch() {
        val current = _uiState.value
        if (current.isSearchExpanded) {
            updateState { copy(isSearchExpanded = false, searchQuery = "") }
        } else {
            updateState { copy(isSearchExpanded = true) }
        }
    }

    // ═══════════ 剪贴板文本构建 ═══════════

    private fun buildClipboardText(bill: BillRecord): String {
        val merchant = bill.merchantAlias ?: bill.merchant ?: "-"
        val amount = bill.amount?.let { "¥${String.format("%.2f", it)}" } ?: "-"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(bill.timestamp))
        val channel = when (bill.paymentChannel) {
            "WEIXIN" -> "微信支付"
            "ALIPAY" -> "支付宝"
            else -> bill.paymentChannel
        }
        return buildString {
            appendLine("商户：$merchant")
            appendLine("金额：$amount")
            appendLine("时间：$time")
            appendLine("通道：$channel")
            appendLine("交易单号：${bill.transactionId ?: "-"}")
            appendLine("备注：${bill.note ?: "-"}")
        }
    }

    // ═══════════ 状态构建 — 5 个显式输入 ═══════════

    /**
     * 将原始数据 + 筛选条件映射为 UI 状态
     *
     * @param records        全量账单记录（来自 observeAll Flow）
     * @param categories     全部分类信息（来自 observeCategories Flow）
     * @param month          目标年月（来自 _selectedMonth Flow）
     * @param filterCategoryId 分类筛选条件: null=全部, -1L=未分类, 其他=具体分类ID
     * @param filterChannel  渠道筛选条件: null=全部, WEIXIN/ALIPAY/OTHER
     */
    private fun buildUiState(
        records: List<BillRecord>,
        categories: List<Category>,
        month: YearMonth,
        filterCategoryId: Long?,
        filterChannel: String?,     // v5.1
    ): BillListUiState {
        val currentState = _uiState.value
        val categoryMap = categories.associateBy { it.id }

        // 步骤 1: 按月份筛选
        val monthStart = month.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val monthEnd = month.atEndOfMonth().atTime(23, 59, 59)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val monthFiltered = records.filter { it.timestamp in monthStart..monthEnd }

        // 步骤 2: 按分类筛选 (v4.2 — 哨兵值 -1L 匹配未分类)
        val categoryFiltered = when (filterCategoryId) {
            null  -> monthFiltered  // 全部
            FILTER_UNCATEGORIZED -> monthFiltered.filter { it.categoryId == null }
            else -> monthFiltered.filter { it.categoryId == filterCategoryId }
        }

        // 步骤 2b: 按渠道筛选 (v5.1) — 在分类筛选之后
        val channelFiltered = when (filterChannel) {
            null    -> categoryFiltered
            "OTHER" -> categoryFiltered.filter { it.paymentChannel !in setOf("WEIXIN", "ALIPAY") }
            else    -> categoryFiltered.filter { it.paymentChannel == filterChannel }
        }

        // 步骤 3: 按关键词搜索
        val searchFiltered = if (currentState.searchQuery.isNotBlank()) {
            val query = currentState.searchQuery.lowercase()
            channelFiltered.filter { record ->
                (record.merchant?.lowercase()?.contains(query) == true) ||
                (record.merchantAlias?.lowercase()?.contains(query) == true) ||
                (record.note?.lowercase()?.contains(query) == true)
            }
        } else channelFiltered

        // 步骤 4: 排序 + 映射 + 分组
        val billItems = searchFiltered
            .sortedByDescending { it.timestamp }
            .map { record -> mapToDisplayItem(record, categoryMap) }

        val groupedItems = groupByDate(billItems)

        // 步骤 5: 统计汇总
        val expenseSum = searchFiltered
            .filter { (it.amount ?: 0.0) > 0 }
            .sumOf { it.amount ?: 0.0 }

        return BillListUiState(
            bills = groupedItems,
            categories = categories,
            isLoading = false,
            editingBill = currentState.editingBill,
            selectedYearMonth = month,
            filterCategoryId = filterCategoryId,
            filterChannel = filterChannel,      // v5.1
            searchQuery = currentState.searchQuery,
            monthlyExpenseSum = expenseSum,
            monthlyCount = searchFiltered.size,
        )
    }

    /** 将单条记录映射为展示项 */
    private fun mapToDisplayItem(
        record: BillRecord,
        categoryMap: Map<Long, Category>,
    ): BillDisplayItem.Bill {
        val category = record.categoryId?.let { categoryMap[it] }
        return BillDisplayItem.Bill(
            record = record,
            categoryEmoji = category?.iconEmoji ?: "",
            categoryName = category?.name,
            merchantDisplay = record.merchantAlias
                ?: record.merchant
                ?: "未知商户",
        )
    }

    /** 按日期分组，在日期变化处插入 DateHeader */
    private fun groupByDate(items: List<BillDisplayItem.Bill>): List<BillDisplayItem> {
        val result = mutableListOf<BillDisplayItem>()
        val dateFormat = dateFormatTL.get()
        var lastDateLabel: String? = null

        for (item in items) {
            val dateLabel = dateFormat.format(Date(item.record.timestamp))
            if (dateLabel != lastDateLabel) {
                result.add(BillDisplayItem.DateHeader(dateLabel))
                lastDateLabel = dateLabel
            }
            result.add(item)
        }
        return result
    }

    /** ThreadLocal 缓存 SimpleDateFormat — 线程安全复用 (P3-2.5) */
    private val dateFormatTL: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("M月d日", Locale.getDefault())
    }
}
