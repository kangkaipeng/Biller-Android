package com.bjbyhd.screenreader_huawei.biller.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecordDao
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import com.bjbyhd.screenreader_huawei.biller.data.category.CategoryDao

/**
 * Biller 模块 Room 数据库
 *
 * 模块: data
 * 职责: 持有 Room 数据库实例，提供 DAO 访问入口。
 *
 * 设计要点:
 *   - 单例模式：DCL + @Volatile 保证线程安全
 *   - v1 起步：新项目无旧数据，无需历史迁移
 *   - [exportSchema] = false：不导出 schema 到版本控制（可按需开启）
 *
 * @see BillRecord 账单 Entity
 * @see BillRecordDao DAO 接口
 * @see Category 分类 Entity
 * @see CategoryDao DAO 接口
 */
@Database(
    entities = [BillRecord::class, Category::class],
    version = 2,
    exportSchema = false
)
abstract class BillerDatabase : RoomDatabase() {

    /** 获取 BillRecordDao 实例 */
    abstract fun billRecordDao(): BillRecordDao

    /** 获取 CategoryDao 实例 */
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: BillerDatabase? = null

        /** v1 → v2: 新增 window_id 列（跨 session 去重） */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bill_records ADD COLUMN window_id INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): BillerDatabase {
            return INSTANCE ?: synchronized(BillerDatabase::class.java) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BillerDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private const val DB_NAME = "biller.db"
    }
}
