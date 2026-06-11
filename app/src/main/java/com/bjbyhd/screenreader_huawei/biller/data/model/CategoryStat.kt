package com.bjbyhd.screenreader_huawei.biller.data.model

/**
 * 分类支出统计模型 — 数据层传输对象
 *
 * 模块: feature/biller/data/model
 * 职责: 承载按分类聚合的支出统计数据，用于统计查询接口
 *       ([IStatisticsRepository]) 与 UI 层 ([StatsUiState]) 之间的
 *       数据传输。
 *
 * ## 为何放在 data/model 而非 ui/stats？
 *
 *   该类型同时被 data 层（IStatisticsRepository、BillerRepository）
 *   和 UI 层（StatsUiState、StatsViewModel）引用。若定义在 ui 包中，
 *   将产生 data → ui 的反向依赖，违反分层架构原则。
 *
 *   放在 data/model 中使得依赖方向统一为 ui → data，符合架构分层。
 *
 * @property categoryId   分类 ID，-1 表示未分类
 * @property categoryName 分类名称（如"餐饮"）
 * @property iconEmoji     分类图标 Emoji
 * @property colorArgb     分类颜色 ARGB 整型值
 * @property amount        该分类的合计金额
 * @property count         该分类的账单数量
 * @property percentage    该分类在总支出中的占比 (0.0 ~ 1.0)
 */
data class CategoryStat(
    val categoryId: Long,
    val categoryName: String,
    val iconEmoji: String,
    val colorArgb: Int,
    val amount: Double,
    val count: Int,
    val percentage: Float,
)
