package com.bjbyhd.screenreader_huawei.biller.ui.dialog

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.databinding.DialogBillEditBinding
import com.bjbyhd.screenreader_huawei.biller.ui.main.BillListEvent
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单详情/编辑 BottomSheet 对话框 (v5 — 双模式控件体系)
 *
 * 模块: feature/biller/ui/dialog
 * 职责: 展示账单完整的只读详情或可编辑表单。
 *       浏览模式使用只读 TextView 展示（层次清晰），编辑模式切换为 TextInputLayout 输入。
 *
 * ## 双模式设计
 *
 *   - 浏览模式: DisplayView (VISIBLE) + EditView (GONE)
 *   - 编辑模式: DisplayView (GONE)   + EditView (VISIBLE)
 *
 *   模式切换通过 visibility 控制——完全不同的控件组合，而非 isEnabled 切换。
 *   时间字段始终只读，分类字段以 Chip 呈现。
 *
 * ## 数据流
 *
 * ```
 * BillEditDialog
 *   ├─ 保存 → onEvent(UpdateAlias) + onEvent(UpdateAmount) + ...
 *   └─ 删除 → onEvent(DeleteBill)
 * ```
 *
 * @see BillListEvent 上行事件类型
 */
class BillEditDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "Biller/EditDialog"
        private const val ARG_BILL_ID = "bill_id"
        private const val ARG_BILL_AMOUNT = "bill_amount"
        private const val ARG_BILL_MERCHANT = "bill_merchant"
        private const val ARG_BILL_MERCHANT_ALIAS = "bill_merchant_alias"
        private const val ARG_BILL_NOTE = "bill_note"
        private const val ARG_BILL_TXN_ID = "bill_transaction_id"
        private const val ARG_BILL_TIMESTAMP = "bill_timestamp"
        /** 消费分类信息 */
        private const val ARG_BILL_CATEGORY_ID = "bill_category_id"
        private const val ARG_BILL_CATEGORY_NAME = "bill_category_name"

        private val TIME_FORMAT_TL = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        }

        /** 金额上限 — 1 亿元 */
        private const val MAX_AMOUNT = 100_000_000.0
        /** 键盘收起动画延迟 (ms) */
        private const val KEYBOARD_DELAY_MS = 200L

        /** 分类列表序列化 Key */
        private const val ARG_CATEGORY_IDS = "cat_ids"
        private const val ARG_CATEGORY_NAMES = "cat_names"
        private const val ARG_CATEGORY_EMOJIS = "cat_emojis"

        /**
         * 创建对话框实例 (增加分类列表参数)
         *
         * @param bill         待查看/编辑的账单记录
         * @param onEvent      ViewModel 事件处理器
         * @param categoryName 当前账单的分类名称，可选
         * @param categories   全部分类列表（供编辑模式下选择）
         */
        fun newInstance(
            bill: BillRecord,
            onEvent: (BillListEvent) -> Unit,
            categoryName: String? = null,
            categories: List<com.bjbyhd.screenreader_huawei.biller.data.category.Category> = emptyList(),
        ): BillEditDialog {
            return BillEditDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BILL_ID, bill.id)
                    bill.amount?.let { putDouble(ARG_BILL_AMOUNT, it) }
                    putString(ARG_BILL_MERCHANT, bill.merchant)
                    putString(ARG_BILL_MERCHANT_ALIAS, bill.merchantAlias)
                    putString(ARG_BILL_NOTE, bill.note)
                    putString(ARG_BILL_TXN_ID, bill.transactionId)
                    putLong(ARG_BILL_TIMESTAMP, bill.timestamp)
                    bill.categoryId?.let { putLong(ARG_BILL_CATEGORY_ID, it) }
                    categoryName?.let { putString(ARG_BILL_CATEGORY_NAME, it) }
                    // v5.1: 序列化分类列表
                    if (categories.isNotEmpty()) {
                        putLongArray(ARG_CATEGORY_IDS, categories.map { it.id }.toLongArray())
                        putStringArrayList(ARG_CATEGORY_NAMES, ArrayList(categories.map { it.name }))
                        putStringArrayList(ARG_CATEGORY_EMOJIS, ArrayList(categories.map { it.iconEmoji }))
                    }
                }
                this.onEvent = onEvent
            }
        }
    }

    /** ViewModel 事件处理器 — 由 Fragment 在 newInstance 时注入 */
    private lateinit var onEvent: (BillListEvent) -> Unit

    /** 当前是否为编辑模式（false = 浏览模式） */
    private var isEditing: Boolean = false

    private var _binding: DialogBillEditBinding? = null
    private val binding get() = _binding!!

    /** 从 arguments 恢复账单 ID */
    private val billId: Long get() = arguments?.getLong(ARG_BILL_ID, 0L) ?: 0L

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setOnShowListener { dialog ->
                val bottomSheet = (dialog as? BottomSheetDialog)
                    ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                bottomSheet?.let {
                    BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogBillEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        populateFields()
        setupButtons()
    }

    // ═══════════════════════════════════════════════════════════════
    // 字段填充 — 同时填充 DisplayView 和 EditView
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 arguments 填充双套控件——浏览模式 DisplayView + 编辑模式 EditView (v5)
     *
     * 优先显示别名（用户可编辑的显示名），原始商户名在下方提示。
     * 交易时间和分类始终只读。
     */
    private fun populateFields() {
        val args = arguments ?: return
        with(binding) {
            // ── 金额 ──
            val amount = if (args.containsKey(ARG_BILL_AMOUNT))
                args.getDouble(ARG_BILL_AMOUNT) else null
            val amountText = amount?.let { String.format("%.2f", it) } ?: "0.00"
            tvAmountDisplay.text = amountText
            etAmount.setText(amountText)

            // ── 商户名 ──
            val alias = args.getString(ARG_BILL_MERCHANT_ALIAS)
            val original = args.getString(ARG_BILL_MERCHANT)
            val merchantDisplay = alias ?: original ?: "未知商户"
            tvAmountMerchant.text = merchantDisplay   // 同行右侧展示
            tvMerchantDisplay.text = merchantDisplay
            etMerchant.setText(alias ?: original ?: "")

            // 如果显示别名，下方提示原始商户名
            if (alias != null && original != null) {
                tvMerchantOriginHint.text = "原始商户: $original"
                tvMerchantOriginHint.visibility = View.VISIBLE
            } else {
                tvMerchantOriginHint.visibility = View.GONE
            }

            // ── 交易时间（始终只读）──
            val timestamp = args.getLong(ARG_BILL_TIMESTAMP, 0L)
            tvTimeDisplay.text = if (timestamp > 0)
                TIME_FORMAT_TL.get().format(Date(timestamp)) else "—"

            // ── 消费分类（Chip，始终只读）──
            val catName = args.getString(ARG_BILL_CATEGORY_NAME)
            chipCategory.text = catName ?: "未分类"

            // ── 交易流水号 ──
            val txnId = args.getString(ARG_BILL_TXN_ID)
            tvTxnIdDisplay.text = if (!txnId.isNullOrBlank()) txnId else "—"
            etTransactionId.setText(txnId ?: "")

            // ── 备注 ──
            val note = args.getString(ARG_BILL_NOTE)
            if (!note.isNullOrBlank()) {
                tvNoteDisplay.text = note
                layoutNoteDisplay.visibility = View.VISIBLE
            } else {
                layoutNoteDisplay.visibility = View.GONE
            }
            etNote.setText(note ?: "")

            // 初始: 浏览模式
            setBrowseMode()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 模式切换 (v5 — visibility 切换替代 isEnabled)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 切换为浏览模式 (v5)
     *
     *   - DisplayView: VISIBLE（只读 TextView 展示）
     *   - EditView:    GONE（输入控件全部隐藏）
     *   - 按钮:        [编辑] [删除] 可见，[保存] [取消] 隐藏
     *   - 金额:        大字号品牌色（通过 display layout 展示）
     */
    private fun setBrowseMode() {
        isEditing = false
        with(binding) {
            tvDialogTitle.text = "账单详情"

            // ── 金额 ──
            layoutAmountDisplay.visibility = View.VISIBLE
            tilAmount.visibility = View.GONE

            // ── 商户 ──
            layoutMerchantDisplay.visibility = View.VISIBLE
            tilMerchant.visibility = View.GONE

            // ── 流水号 ──
            layoutTxnIdDisplay.visibility = View.VISIBLE
            tilTransactionId.visibility = View.GONE

            // ── 备注 ──
            tilNote.visibility = View.GONE
            // layout_note_display 的 visibility 由 populateFields 控制（null 时 GONE）

            // ── 分类（恢复到浏览 Chip）──
            layoutCategoryEdit.visibility = View.GONE
            layoutCategoryDisplay.visibility = View.VISIBLE

            // ── 按钮 ──
            btnEdit.visibility = View.VISIBLE
            btnDelete.visibility = View.VISIBLE
            btnSave.visibility = View.GONE
            btnCancel.visibility = View.GONE
        }
    }

    /**
     * 切换为编辑模式 (v5)
     *
     *   - DisplayView: GONE
     *   - EditView:    VISIBLE（OutlinedBox 输入框）
     *   - 按钮:        [编辑] [删除] 隐藏，[保存] [取消] 可见
     *   - 自动:        隐藏键盘 → 聚焦金额字段 → 弹出键盘
     */
    private fun setEditMode() {
        isEditing = true
        with(binding) {
            tvDialogTitle.text = "编辑账单"

            // ── 金额 ──
            layoutAmountDisplay.visibility = View.GONE
            tilAmount.visibility = View.VISIBLE

            // ── 商户 ──
            layoutMerchantDisplay.visibility = View.GONE
            tilMerchant.visibility = View.VISIBLE
            // 编辑时 helperText 提示原始商户
            val args = arguments
            val original = args?.getString(ARG_BILL_MERCHANT)
            if (!original.isNullOrBlank()) {
                tilMerchant.helperText = "原始商户: $original"
            }

            // ── 流水号 ──
            layoutTxnIdDisplay.visibility = View.GONE
            tilTransactionId.visibility = View.VISIBLE

            // ── 备注 ──
            tilNote.visibility = View.VISIBLE
            layoutNoteDisplay.visibility = View.GONE

            // ── 分类（编辑 ChipGroup）──
            layoutCategoryDisplay.visibility = View.GONE
            layoutCategoryEdit.visibility = View.VISIBLE
            buildEditCategoryChips()

            // ── 按钮 ──
            btnEdit.visibility = View.GONE
            btnDelete.visibility = View.GONE
            btnSave.visibility = View.VISIBLE
            btnCancel.visibility = View.VISIBLE
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 编辑模式 — 分类 Chip 动态构建 (v5.1)
    // ═══════════════════════════════════════════════════════════════

    /** 从 arguments 中反序列化分类列表，构建 Filter ChipGroup */
    private fun buildEditCategoryChips() {
        val args = arguments ?: return
        val ids = args.getLongArray(ARG_CATEGORY_IDS) ?: return
        val names = args.getStringArrayList(ARG_CATEGORY_NAMES) ?: return
        val emojis = args.getStringArrayList(ARG_CATEGORY_EMOJIS) ?: return
        val currentCatId = if (args.containsKey(ARG_BILL_CATEGORY_ID))
            args.getLong(ARG_BILL_CATEGORY_ID) else -1L

        val group = binding.chipGroupCategoryEdit
        group.removeAllViews()

        // "未分类" Chip
        val unlabeledChip = createFilterChip( "未分类")
        unlabeledChip.isChecked = currentCatId == -1L
        unlabeledChip.setOnClickListener {
            onEvent(BillListEvent.UpdateCategory(billId, null))
            // 更新 checked 状态并同步显示 Chip
            syncCategoryChecked(group, null)
            binding.chipCategory.text = "未分类"
        }
        group.addView(unlabeledChip)

        // 各分类 Chip
        for (i in ids.indices) {
            val catId = ids[i]
            val chipText = "${emojis.getOrElse(i) { "" }} ${names.getOrElse(i) { "" }}"
            val chip = createFilterChip( chipText.trim())
            chip.isChecked = catId == currentCatId
            chip.setOnClickListener {
                onEvent(BillListEvent.UpdateCategory(billId, catId))
                syncCategoryChecked(group, catId)
                binding.chipCategory.text = chipText.trim()
            }
            group.addView(chip)
        }
    }

    /** 创建 Filter Chip (代码构建，避免 inflate 依赖) */
    private fun createFilterChip(text: String): com.google.android.material.chip.Chip {
        return com.google.android.material.chip.Chip(requireContext()).apply {
            this.text = text
            isCheckable = true
            isClickable = true
            setChipBackgroundColorResource(com.google.android.material.R.color.m3_chip_background_color)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            chipStrokeWidth = 1f
            setChipStrokeColorResource(com.google.android.material.R.color.m3_chip_stroke_color)
        }
    }

    /** 同步 ChipGroup 的 checked 状态到指定 categoryId */
    private fun syncCategoryChecked(
        group: com.google.android.material.chip.ChipGroup,
        selectedId: Long?
    ) {
        group.post {
            // idx 0 = "未分类" (null)
            (group.getChildAt(0) as? com.google.android.material.chip.Chip)?.isChecked =
                selectedId == null
            // 后续索引 1..N = 分类列表
            val args = arguments ?: return@post
            val ids = args.getLongArray(ARG_CATEGORY_IDS) ?: return@post
            for (i in ids.indices) {
                (group.getChildAt(i + 1) as? com.google.android.material.chip.Chip)?.isChecked =
                    ids[i] == selectedId
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 按钮事件
    // ═══════════════════════════════════════════════════════════════

    private fun setupButtons() {
        with(binding) {
            btnEdit.setOnClickListener { setEditMode() }
            btnDelete.setOnClickListener { onDeleteClicked() }
            btnSave.setOnClickListener { onSaveClicked() }
            btnCancel.setOnClickListener { onCancelEditClicked() }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 保存 — 批量发射 UpdateBillFields (v5.2) + 同步 DisplayView + 反馈
    // ═══════════════════════════════════════════════════════════════

    /**
     * 保存编辑内容 — 逐字段差异对比后发射 [BillListEvent.UpdateBillFields]
     *
     * 仅将发生变化的字段通过单个批量事件发送，Repository 层 1× getById + 1× update，
     * 避免逐字段独立发射导致的多次 DAO 操作和 UI 重建。
     */
    private fun onSaveClicked() {
        val args = arguments ?: return

        // ═══════════ 收集变更字段 — 仅差异部分，null = 无变更 ═══════════

        var changedAlias: String? = null   // 非 null = 有变更，值为新的别名
        var changedNote: String? = null    // 非 null = 有变更，值为新的备注
        var changedAmount: Double? = null  // 非 null = 有变更，值为新的金额
        var changedTxnId: String? = null   // 非 null = 有变更，值为新的流水号

        // ── 金额 — 含范围校验 ──
        val newAmountStr = binding.etAmount.text?.toString()?.trim() ?: ""
        val newAmount = newAmountStr.toDoubleOrNull()
        if (newAmount != null) {
            when {
                newAmount <= 0.0 -> {
                    CLog.w(TAG) { "金额无效 (<=0): $newAmount，已忽略" }
                }
                newAmount > MAX_AMOUNT -> {
                    CLog.w(TAG) { "金额异常 (>1亿): $newAmount，已忽略" }
                }
                else -> {
                    val oldAmount = if (args.containsKey(ARG_BILL_AMOUNT))
                        args.getDouble(ARG_BILL_AMOUNT) else null
                    if (oldAmount == null || kotlin.math.abs(newAmount - oldAmount) > 0.001) {
                        changedAmount = newAmount
                    }
                }
            }
        }

        // ── 商户别名 ──
        // 保留 v4 行为: 仅当输入非空且不同于旧值时才视为变更（不支持清空别名）
        val newAlias = binding.etMerchant.text?.toString()?.trim() ?: ""
        val oldAlias = args.getString(ARG_BILL_MERCHANT_ALIAS)
        val oldMerchant = args.getString(ARG_BILL_MERCHANT)
        if (newAlias.isNotEmpty() && newAlias != (oldAlias ?: oldMerchant)) {
            changedAlias = newAlias
        }

        // ── 交易流水号 ──
        val newTxnId = binding.etTransactionId.text?.toString()?.trim() ?: ""
        val oldTxnId = args.getString(ARG_BILL_TXN_ID)
        if (newTxnId != (oldTxnId ?: "")) {
            changedTxnId = newTxnId
        }

        // ── 备注 ──
        val newNote = binding.etNote.text?.toString()?.trim() ?: ""
        val oldNote = args.getString(ARG_BILL_NOTE)
        if (newNote != (oldNote ?: "")) {
            changedNote = newNote
        }

        // null 字段表示"无变更"，Repository 层将保留数据库现有值
        if (changedAlias != null || changedNote != null || changedAmount != null || changedTxnId != null) {
            onEvent(
                BillListEvent.UpdateBillFields(
                    billId = billId,
                    alias = changedAlias,
                    note = changedNote,
                    amount = changedAmount,
                    txnId = changedTxnId,
                )
            )
        }

        // ── 同步 DisplayView 为最新值 ──
        syncDisplayFromEdit()

        // ── 隐藏键盘 → 切回浏览模式 ──
        hideKeyboard()
        setBrowseMode()
    }

    /** 将 EditView 中的当前值同步到浏览模式 DisplayView (v5) */
    private fun syncDisplayFromEdit() {
        with(binding) {
            // 金额
            val amountText = etAmount.text?.toString()?.trim() ?: "0.00"
            tvAmountDisplay.text = amountText

            // 商户
            val alias = etMerchant.text?.toString()?.trim() ?: "—"
            val merchantText = alias.ifEmpty { "—" }
            tvAmountMerchant.text = merchantText
            tvMerchantDisplay.text = merchantText

            // 流水号
            val txnId = etTransactionId.text?.toString()?.trim() ?: ""
            tvTxnIdDisplay.text = txnId.ifEmpty { "—" }

            // 备注
            val note = etNote.text?.toString()?.trim() ?: ""
            if (note.isNotEmpty()) {
                tvNoteDisplay.text = note
                layoutNoteDisplay.visibility = View.VISIBLE
            } else {
                layoutNoteDisplay.visibility = View.GONE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 取消 — 隐藏键盘 → 恢复原始值 → 切回浏览模式
    // ═══════════════════════════════════════════════════════════════

    /** 取消编辑 → 先隐藏键盘，再恢复表单原始值 + 回到浏览模式 (v5) */
    private fun onCancelEditClicked() {
        hideKeyboard()
        // 延迟切换——键盘收起动画约 KEYBOARD_DELAY_MS
        binding.root.postDelayed({
            populateFields()  // 重新从 arguments 填充，丢弃修改
        }, KEYBOARD_DELAY_MS)
    }

    /** 删除账单 → 发射事件 + 关闭对话框 */
    private fun onDeleteClicked() {
        onEvent(BillListEvent.DeleteBill(billId))
        dismiss()
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 隐藏软键盘 */
    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.root.removeCallbacks(null)
        _binding = null
    }

    /**
     * BottomSheet 被用户滑动关闭时，通知 ViewModel 清除 editingBill 状态
     *
     * 这确保下次点击账单时不会因为旧状态残留而跳过对话框弹出。
     */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onEvent(BillListEvent.DismissDialog)
    }
}
