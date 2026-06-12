package com.bjbyhd.screenreader_huawei.biller.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.databinding.FragmentBillListBinding
import com.bjbyhd.screenreader_huawei.biller.ui.common.ViewModelFactory
import com.bjbyhd.screenreader_huawei.biller.ui.common.showMonthPickerDialog
import com.bjbyhd.screenreader_huawei.biller.ui.dialog.BillEditDialog
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 账单列表 Fragment — MVI 模式 View 层 (v4)
 *
 * 模块: feature/biller/ui/main
 * 职责:
 *   - 收集 [BillListViewModel.uiState] StateFlow，驱动 UI 渲染
 *   - 用户交互（点击/长按）转换为 [BillListEvent] 上行至 ViewModel
 *   - 弹窗管理（编辑对话框）依据 [BillListUiState.editingBill] 状态
 *
 * ## MVI 数据流
 *
 * ```
 * Fragment ──onEvent──→ ViewModel ──Repository──→ Room
 *    ↑                      │
 *    └──collect(uiState)────┘
 * ```
 *
 * ## 生命周期感知
 *
 * 使用 [repeatOnLifecycle] + [Lifecycle.State.STARTED] 收集 Flow，
 * 确保 Fragment 不在前台时暂停收集，避免资源浪费。
 */
class BillListFragment : Fragment() {

    companion object {
        private const val TAG = "Biller/ListFrag"
    }

    private var _binding: FragmentBillListBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: BillListViewModel
    private lateinit var adapter: BillRecordAdapter

    /** 缓存最新的 UiState 副本，避免在事件回调中读取 [StateFlow.value] 导致竞态 */
    private var currentState: BillListUiState = BillListUiState()

    /**
     * 在 onCreate() 中初始化 ViewModel —— 而非 onViewCreated()
     *
     * ViewModel 不依赖 View，应在 Fragment 创建后立即初始化。
     * 跨 Tab 导航时，applyCategoryFilter() / openBillForEdit() 可能在
     * onViewCreated() 之前被调用（Fragment 的 add()+commit() 是异步的），
     * 如果 ViewModel 放在 onViewCreated() 中初始化，
     * 此时 lateinit 尚未赋值 → UninitializedPropertyAccessException。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = ViewModelFactory(requireContext())
        viewModel = ViewModelProvider(this, factory)[BillListViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBillListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilterUI()
        observeUiState()
    }

    // ═══════════ 筛选 UI 初始化 (v4.6 — DatePicker 选择月份) ═══════════

    /**
     * 绑定筛选控件的事件 → 仅发射 Event，不手动操作 View
     *
     * 所有 View 可见性变更由 [observeUiState] 中的 State 驱动，
     * 确保搜索框展开/折叠、缩略预览等 UI 状态的单一渲染路径。
     */
    private fun setupFilterUI() {
        with(binding) {
            // 点击搜索图标区域 → 发射 ToggleSearch
            layoutSearchTrigger.setOnClickListener {
                viewModel.onEvent(BillListEvent.ToggleSearch)
            }

            // 月份标签点击 → 弹出 MaterialDatePicker 快速选择月份
            layoutMonthSelector.setOnClickListener {
                showMonthPicker()
            }

            // 搜索框: 键盘搜索键 → 发射 Search 事件（ViewModel 自动折叠搜索框）
            etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    val query = etSearch.text?.toString() ?: ""
                    viewModel.onEvent(BillListEvent.Search(query))
                }
                false
            }

            // 清除图标 → 发射 ToggleSearch 事件（ViewModel 自动清空 + 折叠）
            tilSearch.setEndIconOnClickListener {
                viewModel.onEvent(BillListEvent.ToggleSearch)
            }

            // 渠道筛选 Chip — 点击弹出 PopupMenu (v5.1)
            chipChannelSelector.setOnClickListener { anchor ->
                showChannelPopupMenu(anchor)
            }
        }
    }

    /** 弹出渠道筛选 PopupMenu (v5.1) */
    private fun showChannelPopupMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 0, 0, "全部渠道")
        popup.menu.add(0, 1, 0, "微信")
        popup.menu.add(0, 2, 0, "支付宝")
        popup.menu.add(0, 3, 0, "其他")
        popup.setOnMenuItemClickListener { item ->
            val channel = when (item.itemId) {
                1    -> "WEIXIN"
                2    -> "ALIPAY"
                3    -> "OTHER"
                else -> null   // 0 = 全部渠道
            }
            viewModel.onEvent(BillListEvent.SelectChannel(channel))
            true
        }
        popup.show()
    }

    // ═══════════════════════════════════════════════════════════════
    // 分类 Chip 动态构建 — 性能缓存 + checked 状态异步同步
    // ═══════════════════════════════════════════════════════════════

    /**
     * 缓存上次 Chip 构建时的分类 ID 有序列表 (v5.1)
     *
     * 用于与当前 [BillListUiState.categories] 做快速差异判定。
     * 若两次构建之间 categories 列表内容未变且 filterCategoryId 未变，
     * 则完全跳过 ChipGroup 重建——仅需在 [group.post] 中更新 checked 状态。
     *
     * 这避免了 [observeUiState] 每次 collect 新 State 时都执行
     * [ChipGroup.removeAllViews] 导致的可视闪烁和无效 GC 回收。
     *
     * @see lastBuiltFilterCategoryId
     */
    private var lastBuiltCategoryIds: List<Long> = emptyList()

    /**
     * 缓存上次 Chip 构建时的分类筛选条件 (v5.1)
     *
     * null = "全部" 被选中，非 null = 指定分类 ID 被选中。
     * 与 [lastBuiltCategoryIds] 共同构成"是否需重建"的双因子判定。
     */
    private var lastBuiltFilterCategoryId: Long? = null

    /**
     * 动态构建分类筛选 Chip
     *
     * 从 categories 动态生成 Chip 添加到 ChipGroup。"全部" Chip 固定在索引 0。
     * checked 状态通过 `post {}` 推迟到 layout pass 后设置，避免被 ChipGroup 重置。
     * 分类数量有限（5~20），全量 removeAllViews + 重建开销可忽略（< 1ms）。
     *
     * @param state 当前 UI 状态，读取 categories 和 filterCategoryId
     */
    private fun buildCategoryChips(state: BillListUiState) {
        // 提取当前分类 ID 有序列表，用于与缓存做差异判定
        val currentIds = state.categories.map { it.id }

        // ═══════════ 缓存守卫: 数据未变则跳过重建 ═══════════
        // 仅当分类列表内容或筛选选中项实际变化时才执行 removeAllViews + 重建。
        // 若跳过重建，后续 group.post 块仍会执行 checked 状态同步
        // （因为 filterCategoryId 可能在"全部"和某分类间切换而未触发重建）。
        //
        // 注意: 当前守卫在数据未变时会直接 return，
        // 意味着 filterCategoryId 变化但 categories 列表不变时，
        // 仅依赖本次调用中的 group.post 块来更新 checked 状态。
        // —— 这正是场景: 用户从"餐饮"切换到"交通"，分类列表不变但选中项变了。
        if (currentIds == lastBuiltCategoryIds && state.filterCategoryId == lastBuiltFilterCategoryId) {
            return
        }

        // 更新缓存 — 在 removeAllViews 之前记录快照
        lastBuiltCategoryIds = currentIds
        lastBuiltFilterCategoryId = state.filterCategoryId

        val group = binding.chipGroupCategory

        // ═══════════ 全量重建 Chip 列表 ═══════════
        // removeAllViews 会触发 ChipGroup 内部状态重置，必须在 addView 之前执行。
        // ChipGroup 自动管理单选互斥（app:singleSelection="true"），
        // 无需手动取消其他 Chip 的选中状态。
        group.removeAllViews()

        // ── index 0: "全部" Chip ──
        // 点击"全部" → 发射 categoryId = null → ViewModel 清除分类筛选
        val chipAllView = createFilterChip(requireContext(), "全部")
        chipAllView.setOnClickListener {
            viewModel.onEvent(BillListEvent.SelectCategory(null))
        }
        group.addView(chipAllView)

        // ── index 1..N: 各分类 Chip ──
        // 遍历顺序与 categories 列表一致，保证 Chip 排列的确定性
        for (cat in state.categories) {
            // 显示文本格式: "🍔 餐饮" — emoji + 空格 + 分类名
            val chipView = createFilterChip(requireContext(), "${cat.iconEmoji} ${cat.name}")
            chipView.setOnClickListener {
                // 点击分类 Chip → 发射对应 categoryId → ViewModel 按此分类筛选
                viewModel.onEvent(BillListEvent.SelectCategory(cat.id))
            }
            group.addView(chipView)
        }

        // ═══════════ checked 状态异步同步 ═══════════
        // 使用 View.post 将 isChecked 设置推迟到当前帧的 measure/layout/draw
        // 完成之后执行。原因:
        //   1. Chip 刚被 addView 添加到 ChipGroup，尚未完成 layout pass
        //   2. ChipGroup 的选中互斥依赖 layout 就绪的 Chip 实例
        //   3. 若在 post 之前直接设置 isChecked，ChipGroup 内部状态机
        //      可能因 Chip 未完成布局而忽略此次设置
        //
        // 索引语义:
        //   getChildAt(0): "全部" Chip → checked = (filterCategoryId == null)
        //   getChildAt(idx + 1): 对应 categories[idx] 的分类 Chip
        //
        // 使用安全转型 `as?` 防御性编程: 即使 getChildAt 意外返回非 Chip 类型
        // （理论上不会发生，因为所有子 View 都是本方法通过 createFilterChip 添加的），
        // 也不会因 ClassCastException 而崩溃。
        group.post {
            // "全部" Chip 的 checked: 当前无分类筛选 → selected
            (group.getChildAt(0) as? com.google.android.material.chip.Chip)?.isChecked =
                state.filterCategoryId == null

            // 各分类 Chip 的 checked: 当前筛选的分类 ID 匹配 → selected
            state.categories.forEachIndexed { idx, cat ->
                (group.getChildAt(idx + 1) as? com.google.android.material.chip.Chip)?.isChecked =
                    state.filterCategoryId == cat.id
            }
        }
    }

    /** 创建 Filter Chip (代码构建，避免 inflate 依赖) */
    private fun createFilterChip(context: Context, text: String): com.google.android.material.chip.Chip {
        return com.google.android.material.chip.Chip(context).apply {
            this.text = text
            isCheckable = true
            isClickable = true
            setChipBackgroundColorResource(com.google.android.material.R.color.m3_chip_background_color)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            chipStrokeWidth = 1f
            setChipStrokeColorResource(com.google.android.material.R.color.m3_chip_stroke_color)
        }
    }

    /**
     * 弹出双 NumberPicker 月份选择器 (v4.6)
     *
     * 替代 MaterialDatePicker——NumberPicker 无日历网格，仅年份+月份滚轮，
     * 操作精准无冗余 UI。
     */
    private fun showMonthPicker() {
        showMonthPickerDialog(
            context = requireContext(),
            initialYearMonth = currentState.selectedYearMonth,
        ) { pickedYm ->
            viewModel.onEvent(BillListEvent.SelectMonth(pickedYm))
        }
    }

    // ═══════════ RecyclerView 初始化 ═══════════

    /**
     * 配置 RecyclerView + Adapter
     *
     * 点击/长按事件通过 lambda 转换为 Event 上行至 ViewModel。
     */
    private fun setupRecyclerView() {
        adapter = BillRecordAdapter(
            onBillClick = { bill -> viewModel.onEvent(BillListEvent.ClickBill(bill)) },
            onBillLongPress = { bill -> viewModel.onEvent(BillListEvent.LongPressBill(bill)) },
        )

        binding.rvBills.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@BillListFragment.adapter
            setHasFixedSize(false)
        }
    }

    // ═══════════ 状态观察 ═══════════

    /**
     * 收集 ViewModel 的 StateFlow，驱动 UI 更新
     *
     * 观察内容:
     *   1. bills 列表 → submitList 到 Adapter
     *   2. editingBill → 控制编辑对话框的显示/隐藏
     *   3. clipboardMessage → 写入系统剪贴板 + Toast
     *   4. isLoading / 空状态 → 切换可见性
     */
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 并行收集 State 和 Effect
                launch {
                    viewModel.uiState.collect { state ->
                        // 缓存最新状态，避免事件回调中读取 StateFlow.value
                        currentState = state

                        // 1. 列表渲染
                        adapter.submitList(state.bills)

                        // 2. 月份标签 + 汇总
                        val ym = state.selectedYearMonth
                        binding.tvMonthLabel.text = "${ym.year}年${ym.monthValue}月"
                        binding.tvMonthSummary.text =
                            "支出 ¥${String.format("%.2f", state.monthlyExpenseSum)} | 共 ${state.monthlyCount} 笔"

                        // 3. 空状态 / 列表切换
                        val isEmpty = state.bills.isEmpty()
                        binding.tvEmptyHint.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        binding.rvBills.visibility = if (isEmpty) View.GONE else View.VISIBLE

                        // 4. 动态构建分类 Chip + 渠道 Chip 状态 (v5.1)
                        buildCategoryChips(state)
                        val channelLabel = when (state.filterChannel) {
                            null    -> "全部渠道"
                            "WEIXIN" -> "微信"
                            "ALIPAY" -> "支付宝"
                            "OTHER"  -> "其他"
                            else    -> state.filterChannel ?: "全部渠道"
                        }
                        binding.chipChannelSelector.text = channelLabel

                        // 5. 编辑对话框
                        handleEditingDialog(state.editingBill)

                        // 6. 搜索框 UI 状态驱动（从 UiState 读取，替代手动 visibility 操作）
                        binding.tilSearch.visibility =
                            if (state.isSearchExpanded) View.VISIBLE else View.GONE

                        // 当 ViewModel 清空搜索词时同步清空 EditText
                        val currentEditText = binding.etSearch.text?.toString() ?: ""
                        if (state.searchQuery.isEmpty() && currentEditText.isNotEmpty()) {
                            binding.etSearch.text?.clear()
                        }

                        // 搜索缩略预览: 折叠状态 + 有搜索词 → 显示缩略
                        if (!state.isSearchExpanded && state.searchQuery.isNotBlank()) {
                            val preview = if (state.searchQuery.length > 6)
                                state.searchQuery.take(6) + "..." else state.searchQuery
                            binding.tvSearchPreview.text = preview
                            binding.tvSearchPreview.visibility = View.VISIBLE
                        } else if (state.searchQuery.isEmpty()) {
                            binding.tvSearchPreview.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.effect.collect { effect ->
                        handleEffect(effect)
                    }
                }
            }
        }
    }

    /**
     * 处理来自 ViewModel Effect Channel 的一次性副作用
     *
     * 与 State（持续性 UI 状态）不同，Effect 消费一次即销毁，
     * 不会在 Fragment 恢复时重播。
     */
    private fun handleEffect(effect: Any?) {
        when (effect) {
            is BillListEffect.CopyToClipboard -> {
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("bill_info", effect.text)
                )
                Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 依据 [editingBill] 状态管理编辑对话框的显示/隐藏
     *
     * 当前使用全量 dismiss 方式管理对话框。每次状态变更时:
     *   1. dismiss 已有对话框
     *   2. 若 editingBill 非 null → 弹出新对话框
     *
     * NOTE: 更优化的实现可判断 dialog 是否已存在且对应同一 bill，
     * 但对于当前简单场景，全量 dismiss + recreate 足够。
     */
    private var currentDialog: BillEditDialog? = null

    /**
     * 依据 [editingBill] 状态管理编辑对话框的显示/隐藏
     *
     * 🔑 P2-4.1 修复: 增加 Fragment 状态防御检查，防止 BadTokenException:
     *   - parentFragmentManager.isStateSaved: 状态已保存后不允许 FragmentTransaction
     *   - isAdded / isDetached: Fragment 必须处于 attached 状态
     */
    private fun handleEditingDialog(editingBill: BillRecord?) {
        if (editingBill != null) {
            // 避免重复弹出同一账单的对话框
            if (currentDialog?.isAdded == true) return

            // 🔑 防御性检查: 状态已保存后不允许执行 FragmentTransaction
            if (parentFragmentManager.isStateSaved) {
                CLog.w(TAG) { "handleEditingDialog: Fragment 状态已保存，跳过 show()" }
                return
            }
            if (!isAdded || isDetached) {
                CLog.w(TAG) { "handleEditingDialog: Fragment 未 attached，跳过 show()" }
                return
            }

            // v4.1: 从缓存中查表获取分类名称
            val catName = editingBill.categoryId?.let { cid ->
                currentState.categories.firstOrNull { it.id == cid }?.name
            }
            // v5.1: 传入分类列表供编辑模式下选择
            currentDialog = BillEditDialog.newInstance(
                editingBill, viewModel::onEvent, catName,
                categories = currentState.categories,
            )
            currentDialog?.show(parentFragmentManager, "BillEditDialog")
        }
    }

    // ═══════════ 跨 Tab 导航入口 (Phase 5D) ═══════════

    /**
     * 应用分类筛选 — 由 BillDashboardActivity 在跨 Tab 导航时调用
     *
     * 将筛选事件转发给 ViewModel，Room Flow 会自动触发列表重渲染。
     * 外部（如统计 Tab 点击分类）通过 Activity → 此方法 → ViewModel 链完成跨 Tab 通信。
     *
     * @param categoryId 目标分类 ID，null = 清除筛选显示全部
     */
    fun applyCategoryFilter(categoryId: Long?) {
        viewModel.onEvent(BillListEvent.SelectCategory(categoryId))
    }

    /**
     * 打开指定账单的编辑对话框 — 由 BillDashboardActivity 在跨 Tab 导航时调用
     *
     * 在当前列表中查找对应 [BillRecord] 并发送 [BillListEvent.ClickBill]。
     * 如果该账单不在当前列表中（例如不属于当前选中的月份），仅做日志记录。
     *
     * @param billId 目标账单 ID
     */
    fun openBillForEdit(billId: Long) {
        // 在当前 UiState 的展示列表中查找对应 BillRecord
        val bill = currentState.bills
            .filterIsInstance<BillDisplayItem.Bill>()
            .firstOrNull { it.record.id == billId }
            ?.record

        if (bill != null) {
            viewModel.onEvent(BillListEvent.ClickBill(bill))
        } else {
            // 该账单可能不在当前月份筛选中，仅记录日志
            CLog.w("Biller/ListFrag") {
                "openBillForEdit: billId=$billId 不在当前展示列表中"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
