package com.bjbyhd.screenreader_huawei.biller.data

import java.time.YearMonth
import java.time.ZoneId

/**
 * YearMonth → 毫秒时间窗扩展
 * 模块: data
 * 职责: 将 [YearMonth] 转换为 Room 时间窗查询所需的起止毫秒时间戳。
 *       统一时间窗计算逻辑，避免各调用方重复实现时区处理。
 *
 * ## 时区选择
 * 使用 [ZoneId.systemDefault] 而非 UTC——账单的时间戳来源于 Android 设备的
 * [System.currentTimeMillis]，基于系统默认时区。保持一致可避免跨时区偏差。
 *
 * ## 边界处理
 *   - startMs: 当月第一天 00:00:00.000
 *   - endMs:   当月最后一天 23:59:59.999
 *
 * 使用 [YearMonth.atEndOfMonth] 自动处理各月天数差异（28/29/30/31 天），
 * 无需手动维护月份天数表。
 */

/**
 * 将 [YearMonth] 转换为该月的毫秒时间窗
 *
 * @return Pair<Long, Long> 其中 first = 起始毫秒（含），second = 结束毫秒（含）
 */
internal fun YearMonth.toMillisRange(): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val startMs = this.atDay(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    val endMs = this.atEndOfMonth()
        .atTime(23, 59, 59, 999_999_999)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
    return Pair(startMs, endMs)
}
