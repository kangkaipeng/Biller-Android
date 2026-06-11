package com.bjbyhd.screenreader_huawei.biller.data.bridge

import com.bjbyhd.screenreader_huawei.biller.data.model.FullBillExport
import com.bjbyhd.screenreader_huawei.biller.data.model.ImportResult

/**
 * 导入导出数据契约
 *
 * 职责: 全量账单的 CSV 导出与导入。
 *       与查询/编辑接口分离，遵循接口隔离原则。
 *
 * @see BillRepository 实现类
 */
interface IBillerImportExportBridge {

    /**
     * 导出全部账单记录（含分类名称）
     *
     * @param limit 上限数量，null = 不限制
     */
    suspend fun exportRecords(limit: Int? = null): List<FullBillExport>

    /**
     * 从 CSV 解析结果批量导入账单
     *
     * @return 导入结果统计（total / inserted / skipped / errors）
     */
    suspend fun importFromCsv(records: List<FullBillExport>): ImportResult
}
