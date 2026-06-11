package com.bjbyhd.screenreader_huawei.biller.ui.stub

import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerImportExportBridge
import com.bjbyhd.screenreader_huawei.biller.data.model.FullBillExport
import com.bjbyhd.screenreader_huawei.biller.data.model.ImportResult

class StubImportExportBridge : IBillerImportExportBridge {

    override suspend fun exportRecords(limit: Int?): List<FullBillExport> = emptyList()

    override suspend fun importFromCsv(records: List<FullBillExport>): ImportResult =
        ImportResult(total = 0, inserted = 0, skipped = 0, errors = emptyList())
}
