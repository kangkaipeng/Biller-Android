package com.bjbyhd.screenreader_huawei.biller.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bjbyhd.screenreader_huawei.biller.R
import com.bjbyhd.screenreader_huawei.biller.databinding.ActivityDashboardBinding
import com.bjbyhd.screenreader_huawei.biller.ui.profile.ProfileFragment
import com.bjbyhd.screenreader_huawei.biller.ui.stats.StatsFragment

/**
 * Biller 主界面 Activity — 简单固定 Toolbar + BottomNav 三 Tab (v4.4)
 *
 * 模块: feature/biller/ui/main
 * 职责: 切换 统计 / 账单 / 我的 三个 Tab，Toolbar 标题随 Tab 动态更新。
 *
 */
class BillDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    /** Tab ID → 标题映射 */
    private val tabTitles = mapOf(
        R.id.nav_stats to "统计",
        R.id.nav_bills to "账单",
        R.id.nav_profile to "我的",
    )

    private val fragmentCache = mutableMapOf<Int, Fragment>()
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()

        if (savedInstanceState == null) {
            switchFragment(R.id.nav_stats)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            // 更新 Toolbar 标题
            binding.topAppBar.title = tabTitles[item.itemId] ?: "自动记账"
            switchFragment(item.itemId)
            true
        }
    }

    private fun switchFragment(tabId: Int) {
        val fragment = fragmentCache.getOrPut(tabId) {
            when (tabId) {
                R.id.nav_stats   -> StatsFragment()
                R.id.nav_bills   -> BillListFragment()
                R.id.nav_profile -> ProfileFragment()
                else             -> return
            }.also { fragment ->
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragment_content, fragment, tabId.toString())
                    .hide(fragment)
                    .commit()
            }
        }
        supportFragmentManager.beginTransaction().apply {
            activeFragment?.let { hide(it) }
            show(fragment)
        }.commit()
        activeFragment = fragment
    }

    /**
     * 切换到账单列表 Tab 并预筛选指定分类
     *
     * 由统计 Tab 的分类点击事件触发。流程:
     *   1. 切换到账单 Tab（show BillListFragment）
     *   2. 更新 BottomNav 选中项
     *   3. 设置 Toolbar 标题
     *   4. 发送 [BillListEvent.SelectCategory] 到 BillListViewModel
     *
     * @param categoryId 目标分类 ID，null = 全部（清除筛选）
     */
    fun navigateToBills(categoryId: Long?) {
        switchFragment(R.id.nav_bills)
        // 强制同步执行 Fragment 事务 — 确保 BillListFragment.onCreate() 完成，
        // ViewModel 已初始化，否则 applyCategoryFilter() 会触发 lateinit 崩溃
        supportFragmentManager.executePendingTransactions()

        binding.bottomNav.selectedItemId = R.id.nav_bills
        binding.topAppBar.title = tabTitles[R.id.nav_bills] ?: "账单"

        val billFrag = fragmentCache[R.id.nav_bills] as? BillListFragment
        billFrag?.applyCategoryFilter(categoryId)
    }

    /**
     * 切换到账单列表 Tab 并打开指定账单的编辑对话框
     *
     * 由统计 Tab 的最近交易点击事件触发。流程:
     *   1. 切换到账单 Tab
     *   2. 更新 BottomNav 选中项
     *   3. 发送 [BillListEvent.ClickBill] 到 BillListViewModel（触发编辑对话框弹出）
     *
     * 由于 BillListFragment 使用 show/hide 策略，其 ViewModel 和列表数据一直存活。
     * BillListViewModel 在收到 ClickBill 事件后会设置 editingBill，
     * Fragment 在显示时会自动弹出对应的编辑对话框。
     *
     * @param billId 目标账单 ID
     */
    fun openBillEdit(billId: Long) {
        switchFragment(R.id.nav_bills)
        // 强制同步执行 Fragment 事务 — 确保 Fragment 生命周期完成
        supportFragmentManager.executePendingTransactions()

        binding.bottomNav.selectedItemId = R.id.nav_bills
        binding.topAppBar.title = tabTitles[R.id.nav_bills] ?: "账单"

        val billFrag = fragmentCache[R.id.nav_bills] as? BillListFragment
        billFrag?.openBillForEdit(billId)
    }
}
