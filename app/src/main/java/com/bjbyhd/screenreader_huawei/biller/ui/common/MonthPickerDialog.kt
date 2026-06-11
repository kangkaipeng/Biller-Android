package com.bjbyhd.screenreader_huawei.biller.ui.common

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import com.bjbyhd.screenreader_huawei.biller.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.time.YearMonth

/**
 * Material 3 Exposed Dropdown Menu 月份选择器弹窗
 *
 * 职责: 使用 Material 3 规范的 [AutoCompleteTextView] + [TextInputLayout]
 *       (ExposedDropdownMenu 风格) 提供原生下拉选择体验。
 *
 * ## 业务规则
 *
 *   - 年份范围: 2000 ~ 当前年份（不含未来）
 *   - 月份范围: 选当前年份时封顶到当前月；选往年恢复 1~12
 *   - 年份切换时自动调整月份列表并 clamp 已选月份值
 *
 * ## Context 安全
 *
 * 方法参数接收 Context，不持有引用。弹窗关闭后无泄漏。
 *
 * @param context           用于 inflate 和创建 Dialog 的 Context
 * @param initialYearMonth  弹窗初始选中的年月
 * @param onMonthSelected   用户点击"确定"后的回调 (YearMonth)
 */
fun showMonthPickerDialog(
    context: Context,
    initialYearMonth: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
) {
    val now = YearMonth.now()
    val currentYear = now.year
    val currentMonth = now.monthValue

    // ═══════════ Inflate 布局 ═══════════
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_m3_month_picker, null)
    val tilYear  = view.findViewById<TextInputLayout>(R.id.til_year)
    val tilMonth = view.findViewById<TextInputLayout>(R.id.til_month)
    val actvYear  = view.findViewById<AutoCompleteTextView>(R.id.actv_year)
    val actvMonth = view.findViewById<AutoCompleteTextView>(R.id.actv_month)

    /** 判断当前是否有 Dropdown 展开 */
    fun isAnyDropdownOpen() = actvYear.isPopupShowing || actvMonth.isPopupShowing

    /** 关闭所有 Dropdown */
    fun dismissAllDropDowns() {
        if (actvYear.isPopupShowing) actvYear.dismissDropDown()
        if (actvMonth.isPopupShowing) actvMonth.dismissDropDown()
    }

    // ═══════════ 年份数据源: 2000 ~ 当前年份 ═══════════
    val yearItems = (2000..currentYear).map { "${it}年" }.toTypedArray()
    val yearAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, yearItems)
    actvYear.setAdapter(yearAdapter)

    // 填入初始年份
    val initialYear = initialYearMonth.year.coerceIn(2000, currentYear)
    actvYear.setText("${initialYear}年", false)

    tilYear.setEndIconOnClickListener { actvYear.showDropDown() }
    actvYear.setOnItemClickListener { _, _, _, _ -> actvYear.dismissDropDown() }

    // ═══════════ 月份数据源 — 根据所选年份动态范围 ═══════════
    /**
     * 刷新月份列表——根据当前选中年份动态调整最大值
     *
     * 所选年 = 今年 → maxMonth = 当前月份
     * 所选年 < 今年 → maxMonth = 12
     */
    fun refreshMonthAdapter(selectedYear: Int) {
        val maxMonth = if (selectedYear == currentYear) currentMonth else 12
        val monthItems = (1..maxMonth).map { String.format("%02d月", it) }.toTypedArray()
        val monthAdapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, monthItems
        )
        actvMonth.setAdapter(monthAdapter)

        val currentVal = actvMonth.text.toString().filter { it.isDigit() }.toIntOrNull()
            ?: initialYearMonth.monthValue
        val clamped = currentVal.coerceIn(1, maxMonth)
        actvMonth.setText(String.format("%02d月", clamped), false)
    }

    // 初始化月份列表
    refreshMonthAdapter(initialYear)

    tilMonth.setEndIconOnClickListener { actvMonth.showDropDown() }
    actvMonth.setOnItemClickListener { _, _, _, _ -> actvMonth.dismissDropDown() }

    // ═══════════ 动态联动: 年份变化 → 刷新月份列表 ═══════════
    actvYear.setOnItemClickListener { _, _, _, _ ->
        actvYear.dismissDropDown()
        val pickedYear = actvYear.text.toString().filter { it.isDigit() }.toIntOrNull()
            ?: initialYear
        refreshMonthAdapter(pickedYear)
    }

    // ═══════════ TouchInterceptor — 拦截穿透事件 ═══════════
    /**
     * 外层触摸拦截 FrameLayout
     *
     * 当 AutoCompleteTextView Dropdown 展开时，PopupWindow 只关闭自己但不消费事件。
     * 此拦截器在 Dialog 内容区域检测到外部触摸时，主动关闭所有 Dropdown 并消费事件，
     * 阻止触摸穿透到对话框外层的 layoutMonthSelector 触发新的 showMonthPicker()。
     */
    val touchInterceptor = object : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
            if (isAnyDropdownOpen() && ev?.action == MotionEvent.ACTION_DOWN) {
                // 检测触摸点是否在 ACTV 控件之外
                val locYear = IntArray(2); val locMonth = IntArray(2)
                actvYear.getLocationOnScreen(locYear)
                actvMonth.getLocationOnScreen(locMonth)
                val rawX = ev.rawX.toInt(); val rawY = ev.rawY.toInt()

                val hitYear = rawX in locYear[0]..(locYear[0] + actvYear.width) &&
                              rawY in locYear[1]..(locYear[1] + actvYear.height)
                val hitMonth = rawX in locMonth[0]..(locMonth[0] + actvMonth.width) &&
                               rawY in locMonth[1]..(locMonth[1] + actvMonth.height)

                if (!hitYear && !hitMonth) {
                    // 触摸点在 Dropdown 外部 → 关闭下拉，消费事件，阻止穿透
                    dismissAllDropDowns()
                    return true
                }
            }
            return super.onInterceptTouchEvent(ev)
        }
    }
    touchInterceptor.addView(view)

    // ═══════════ MaterialAlertDialogBuilder ═══════════
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle("选择月份")
        .setView(touchInterceptor)
        .setPositiveButton("确定") { _, _ ->
            val year = actvYear.text.toString().filter { it.isDigit() }.toIntOrNull()
                ?: initialYearMonth.year
            val month = actvMonth.text.toString().filter { it.isDigit() }.toIntOrNull()
                ?: initialYearMonth.monthValue
            onMonthSelected(YearMonth.of(year, month))
        }
        .setNegativeButton("取消", null)
        .setCancelable(true)    // P1-2.1: 恢复可取消——TouchInterceptor 已防止穿透
        .create()

    dialog.setCanceledOnTouchOutside(true)

    // 允许系统返回键关闭
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            // 若 Dropdown 展开，先关闭 Dropdown，不关闭 Dialog
            if (isAnyDropdownOpen()) {
                dismissAllDropDowns()
                return@setOnKeyListener true
            }
            dialog.dismiss(); true
        } else false
    }

    // show() 之前主动收起所有下拉 — 防止 AutoCompleteTextView 在获得焦点时自动展开
    actvYear.dismissDropDown()
    actvMonth.dismissDropDown()
    dialog.show()
}
