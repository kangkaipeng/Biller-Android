package com.bjbyhd.screenreader_huawei.biller

import android.app.Application
import com.bjbyhd.screenreader_huawei.biller.callback.CaptureNotifier
import com.bjbyhd.screenreader_huawei.biller.callback.NotificationChannels
import com.bjbyhd.screenreader_huawei.biller.data.BillRepository
import com.bjbyhd.screenreader_huawei.biller.data.BillerDatabase
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import com.bjbyhd.screenreader_huawei.biller.diagnostic.ParseFailureDumper
import com.bjbyhd.screenreader_huawei.biller.pipeline.BillProcessingPipeline
import com.bjbyhd.screenreader_huawei.biller.service.BillEventProcessor
import com.bjbyhd.screenreader_huawei.biller.service.NotificationLogger
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import com.bjbyhd.screenreader_huawei.logger.api.LogConfig
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application 入口 — 日志系统初始化 + 数据库预置分类
 *
 * 职责:
 *   1. 在 App 启动时完成 Logger 系统的初始化配置
 *   2. 首次启动时插入预置消费分类
 *
 * 通过 AndroidManifest.xml 中 android:name 注册。
 */
class BillerApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        CLog.init(this, LogConfig(
            logDir = File(filesDir, "logs"),
            consoleLevel = LogLevel.VERBOSE,
            fileLevel = LogLevel.INFO
        ))

        // 初始化顺序: 渠道 → 通知器（通知器依赖渠道已注册）
        NotificationChannels.init(this)

        NotificationLogger.init(this)
        BillRepository.init(this)
        BillProcessingPipeline.init(this)
        BillEventProcessor.init(appScope)
        CaptureNotifier.init(this)
        ParseFailureDumper.init(this)

        insertDefaultCategoriesIfNeeded()
    }

    private fun insertDefaultCategoriesIfNeeded() {
        appScope.launch {
            try {
                val dao = BillerDatabase.getInstance(this@BillerApplication).categoryDao()
                val existingCount = dao.count()
                if (existingCount == 0) {
                    DEFAULT_CATEGORIES.forEach { category ->
                        try {
                            dao.insert(category)
                        } catch (e: Exception) {
                            CLog.w("Biller/App") { "预置分类 '${category.name}' 插入跳过: ${e.message}" }
                        }
                    }
                    CLog.i("Biller/App") { "预置分类初始化完成: ${DEFAULT_CATEGORIES.size} 条" }
                } else {
                    CLog.d("Biller/App") { "分类已存在 ($existingCount 条), 跳过预置" }
                }
            } catch (e: Exception) {
                CLog.e("Biller/App", e) { "预置分类初始化失败: ${e.message}" }
            }
        }
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf(
            Category(name = "餐饮", iconEmoji = "🍔", colorArgb = 0xFFFF5722.toInt(), isDefault = true, sortOrder = 1),
            Category(name = "交通", iconEmoji = "🚇", colorArgb = 0xFF2196F3.toInt(), isDefault = true, sortOrder = 2),
            Category(name = "购物", iconEmoji = "🛒", colorArgb = 0xFFE91E63.toInt(), isDefault = true, sortOrder = 3),
            Category(name = "通讯", iconEmoji = "📱", colorArgb = 0xFF9C27B0.toInt(), isDefault = true, sortOrder = 4),
            Category(name = "居住", iconEmoji = "🏠", colorArgb = 0xFF795548.toInt(), isDefault = true, sortOrder = 5),
            Category(name = "娱乐", iconEmoji = "🎮", colorArgb = 0xFFFF9800.toInt(), isDefault = true, sortOrder = 6),
            Category(name = "医疗", iconEmoji = "🏥", colorArgb = 0xFFF44336.toInt(), isDefault = true, sortOrder = 7),
            Category(name = "教育", iconEmoji = "📚", colorArgb = 0xFF4CAF50.toInt(), isDefault = true, sortOrder = 8),
            Category(name = "转账", iconEmoji = "💸", colorArgb = 0xFF607D8B.toInt(), isDefault = true, sortOrder = 9),
            Category(name = "其他", iconEmoji = "📋", colorArgb = 0xFF9E9E9E.toInt(), isDefault = true, sortOrder = 10),
        )
    }
}
