package com.bjbyhd.screenreader_huawei.biller.ui.common

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bjbyhd.screenreader_huawei.biller.data.BillRepository
import com.bjbyhd.screenreader_huawei.biller.ui.main.BillListViewModel
import com.bjbyhd.screenreader_huawei.biller.ui.profile.ProfileViewModel
import com.bjbyhd.screenreader_huawei.biller.ui.stats.StatsViewModel

/**
 * ViewModel 工厂 — 注入 BillRepository
 */
class ViewModelFactory(private val appContext: Context) : ViewModelProvider.Factory {

    private val repo = BillRepository.getInstance()

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            BillListViewModel::class.java.isAssignableFrom(modelClass) ->
                BillListViewModel(repo) as T
            ProfileViewModel::class.java.isAssignableFrom(modelClass) ->
                ProfileViewModel(appContext.applicationContext, repo, repo) as T
            StatsViewModel::class.java.isAssignableFrom(modelClass) ->
                StatsViewModel(repo) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
