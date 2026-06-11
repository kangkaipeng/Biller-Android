package com.bjbyhd.screenreader_huawei.biller.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bjbyhd.screenreader_huawei.biller.R
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.databinding.ItemBillRecordV2Binding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单列表 RecyclerView Adapter
 *
 * 模块: feature/biller/ui/main
 * 职责: 将 [BillDisplayItem] 列表渲染为 Material 3 风格的账单卡片列表。
 *
 * ## 设计
 *
 *   - 双 viewType: [VIEW_TYPE_HEADER] (日期分组) + [VIEW_TYPE_BILL] (账单卡片)
 *   - ViewBinding 绑定卡片布局，日期标题使用简单 TextView inflate
 *   - [DiffUtil] 按 item 类型进行增量更新
 *   - 点击和长按事件通过 lambda 回调上行至 Fragment
 *
 * ## 视觉层次
 *
 *   DateHeader: "6月8日" — 左侧色块 + LabelLarge
 *   Bill Card:
 *     Row 1: [🍔 emoji] 商户名 ·········· ¥11.01
 *     Row 2: [餐饮 chip] ·········· 13:25
 *     Row 3: 备注（条件显示）
 *
 * @see BillDisplayItem 展示项类型
 */
class BillRecordAdapter(
    private val onBillClick: (BillRecord) -> Unit,
    private val onBillLongPress: (BillRecord) -> Unit,
) : ListAdapter<BillDisplayItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_BILL = 1

        /** 时间格式化 — 月-日 时:分 */
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    // ═══════════ ViewType 判定 ═══════════

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BillDisplayItem.DateHeader -> VIEW_TYPE_HEADER
        is BillDisplayItem.Bill       -> VIEW_TYPE_BILL
    }

    // ═══════════ ViewHolder 创建 ═══════════

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_date_header, parent, false)
                DateHeaderViewHolder(view)
            }
            VIEW_TYPE_BILL -> {
                val binding = ItemBillRecordV2Binding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                BillViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    // ═══════════ ViewHolder 绑定 ═══════════

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BillDisplayItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
            is BillDisplayItem.Bill       -> (holder as BillViewHolder).bind(item)
        }
    }

    // ═══════════ DateHeader ViewHolder ═══════════

    /** 日期分组标题 ViewHolder — 简单的 TextView inflate */
    class DateHeaderViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val tvDate: TextView = view.findViewById(R.id.tv_date_header)

        fun bind(header: BillDisplayItem.DateHeader) {
            tvDate.text = header.dateLabel
        }
    }

    // ═══════════ Bill ViewHolder (ViewBinding) ═══════════

    /**
     * 账单卡片 ViewHolder
     *
     * 使用 ViewBinding 绑定 item_bill_record_v2.xml。
     * 点击和长按事件在 init 块注册。
     */
    inner class BillViewHolder(
        private val binding: ItemBillRecordV2Binding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 点击 → 上行到 Fragment 处理
            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition) as? BillDisplayItem.Bill
                item?.let { onBillClick(it.record) }
            }
            // 长按 → 上行到 Fragment 处理
            binding.root.setOnLongClickListener {
                val item = getItem(bindingAdapterPosition) as? BillDisplayItem.Bill
                item?.let { onBillLongPress(it.record) }
                true
            }
        }

        fun bind(item: BillDisplayItem.Bill) {
            val record = item.record
            val ctx = binding.root.context

            with(binding) {
                tvMerchant.text = item.merchantDisplay

                tvAmount.text = record.amount?.let {
                    "¥${String.format("%.2f", it)}"
                } ?: "-"

                // ── 第二行: 分类标签 + 时间 ──
                if (item.categoryName != null) {
                    tvCategoryLabel.text = item.categoryName
                    tvCategoryLabel.visibility = android.view.View.VISIBLE
                } else {
                    tvCategoryLabel.visibility = android.view.View.GONE
                }

                // 支付渠道 Chip
                tvPaymentChannel.text = when (record.paymentChannel) {
                    "WEIXIN" -> "微信"
                    "ALIPAY" -> "支付宝"
                    else -> record.paymentChannel
                }
                tvPaymentChannel.visibility = android.view.View.VISIBLE

                tvTime.text = TIME_FORMAT.format(Date(record.timestamp))

                // ── 第三行: 备注（条件显示） ──
                record.note?.let { note ->
                    if (note.isNotBlank()) {
                        tvNote.text = note
                        tvNote.visibility = android.view.View.VISIBLE
                    } else {
                        tvNote.visibility = android.view.View.GONE
                    }
                } ?: run {
                    tvNote.visibility = android.view.View.GONE
                }
            }
        }
    }

    // ═══════════ DiffUtil — 高效增量更新 ═══════════

    /**
     * DiffUtil 回调 — 供 [ListAdapter] 做增量更新判定
     *
     * [areItemsTheSame] 按 viewType 分发比较键:
     *   - Header: dateLabel 相同视为同一条
     *   - Bill: record.id 相同视为同一条
     *
     * [areContentsTheSame] 使用 data class 的 equals() 全字段比较。
     */
    private object DiffCallback : DiffUtil.ItemCallback<BillDisplayItem>() {

        override fun areItemsTheSame(old: BillDisplayItem, new: BillDisplayItem): Boolean {
            return when {
                old is BillDisplayItem.DateHeader && new is BillDisplayItem.DateHeader ->
                    old.dateLabel == new.dateLabel
                old is BillDisplayItem.Bill && new is BillDisplayItem.Bill ->
                    old.record.id == new.record.id
                else -> false
            }
        }

        override fun areContentsTheSame(old: BillDisplayItem, new: BillDisplayItem): Boolean {
            return old == new
        }
    }
}
