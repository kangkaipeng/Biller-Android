package com.bjbyhd.screenreader_huawei.biller.ui.stub

import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerQueryBridge
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class StubBillQueryBridge : IBillerQueryBridge {

    override fun observeAll(): Flow<List<BillRecord>> = flowOf(emptyList())
    override fun observeCategories(): Flow<List<Category>> = flowOf(emptyList())
    override suspend fun getRecentRecords(limit: Int): List<BillRecord> = emptyList()
    override suspend fun getDistinctMerchants(): List<String> = emptyList()
    override suspend fun count(): Int = 0
    override suspend fun deleteById(id: Long) {}
    override suspend fun updateBillFields(id: Long, alias: String?, categoryId: Long?, note: String?, amount: Double?, txnId: String?) {}
    override suspend fun insertCategory(category: Category): Long = 0
    override suspend fun updateCategory(category: Category) {}
    override suspend fun deleteCategory(id: Long) {}
}
