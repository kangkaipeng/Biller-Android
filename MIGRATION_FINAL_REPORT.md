# BillerApplication 迁移完成报告

> 日期：2026-06-12
> 源码：53 Kotlin + 48 资源 + 20 Logger = 73 个 Kotlin 文件

---

## 一、迁移步骤总览

| 步骤 | 层 | 新增 | 迁移 | 重构 | 状态 |
|------|-----|------|------|------|------|
| 1 | Logger 模块 | — | 20 文件 | — | ✅ |
| 2 | Room 数据库层 | — | 6 文件 | v4→v1, source+mergeStatus→flags | ✅ |
| 3 | UI 层 (MVI) | 3 Stub | 14 文件 | ViewModelFactory 重写 | ✅ |
| 4 | 监听层 | 3 文件 | 2 Manifest+XML | Service 薄层 (~25行) | ✅ |
| 5 | 解析层 | 3 文件 | 6 文件 | 微信/支付宝 Extractor 重写 | ✅ |
| 6 | 数据处理层 | 1 文件 | — | 先到先写 + 后到合并 | ✅ |
| 7 | 数据仓库层 | 1 文件 | 3 Bridge 精简 | 15→10 方法，Stub→真实 | ✅ |

---

## 二、最终架构

```
系统回调 (Android Framework)
  │
  ▼
┌──────────────────────────────────────────────────┐
│ 监听层 (service/)                                  │
│   BillerAccessibilityService / NotificationService  │
│   → BillEventProcessor (分发 + 去重 L1)             │
└────────────────────┬─────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────┐
│ 解析层 (parser/)                                   │
│   ParsedBill / ScreenMatch                         │
│   WeChatScreenExtractor  ← 位置驱动 (~140行)       │
│   AlipayScreenExtractor  ← 锚点+键值对 (~180行)     │
│   WeChatNotificationExtractor / AlipayNotificationExtractor │
└────────────────────┬─────────────────────────────┘
                     │ ParsedBill
                     ▼
┌──────────────────────────────────────────────────┐
│ 数据处理层 (pipeline/)                              │
│   BillProcessingPipeline                           │
│   L2: windowId+金额+商户 DB去重                     │
│   L3: 异源融合 (5秒窗口)                            │
│   先到先写，后到合并                                   │
└────────────────────┬─────────────────────────────┘
                     │ BillRecord
                     ▼
┌──────────────────────────────────────────────────┐
│ 数据仓库层 (data/)                                  │
│   BillRepository → DAO → Room                      │
│   UI 查询/编辑/统计/导入导出                          │
└────────────────────┬─────────────────────────────┘
                     │ Flow
                     ▼
┌──────────────────────────────────────────────────┐
│ UI 层 (ui/)                                       │
│   MVI: BaseMviViewModel → ViewModels → Fragments   │
│   BillDashboardActivity (底部导航)                  │
└──────────────────────────────────────────────────┘
```

---

## 三、关键架构变更（相对 HWApplication）

| 维度 | HW 原版 | BillerApplication |
|------|---------|-------------------|
| Service 职责 | 采集+解析+去重+持久化 混在一起 | 只采集+路由 (~25行) |
| 解析器组织 | 按渠道分 (screen/notification) | 按 App 分 (wechat/alipay) |
| WeChatScreenExtractor | 620 行，match+extract 两阶段 | 140 行，单一 parse() |
| AlipayScreenExtractor | 667 行，match+extract+MatchContext | 180 行，锚点+位置 |
| 去重 | Service 内 DedupStage | BillEventProcessor L1+L1a + Pipeline L2+L3 |
| source/mergeStatus | 两个 String 字段 | 一个 Long flags 位掩码 |
| BillerRepository | 590 行，含融合+单字段更新 | 精简，只读+编辑 |
| Bridge 接口 | 4 个接口 21 方法 | 3 个接口 16 方法 |
| 初始化 | 各处 DCL getInstance(context) | Application.onCreate 统一 init |
| 数据库 | v4 + 3 次迁移 | v2 + 1 次迁移 |

---

## 四、去重体系

```
L1a: 内容哈希 (无 windowId, 1s TTL)  — 跨窗口重复 (支付宝多窗口渲染)
L1b: 窗口哈希 (带 windowId, 2s TTL)  — 同窗口重复回调
L2:  DB windowId+金额+商户          — 跨 session 重复 (App 重启后)
L3:  DB 异源融合 (5s+金额+通道)      — 无障碍+通知双源合并
```

---

## 五、BillRecord flags 位标志

```
0x0001 — FLAG_NOTIFICATION    (通知栏)
0x0002 — FLAG_ACCESSIBILITY   (无障碍)
0x0004 — FLAG_MINI_PROGRAM    (微信记账本，预留)
0x0008 — FLAG_BILL_DETAIL     (支付宝账单详情，预留)

单源: flags = 0x0001 或 0x0002
合并: flags = 0x0003 (通知+无障碍)
判断: bitCount(flags) > 1 = 已合并
```

---

## 六、包结构

```
com.bjbyhd.screenreader_huawei.biller/
├── BillerApplication.kt
├── config/
│   └── TargetConfig.kt
├── data/
│   ├── BillRepository.kt          ★ 新建
│   ├── BillerDatabase.kt          (v2)
│   ├── YearMonthExt.kt
│   ├── biller/
│   │   ├── BillRecord.kt          (flags + windowId)
│   │   └── BillRecordDao.kt
│   ├── category/
│   │   ├── Category.kt
│   │   └── CategoryDao.kt
│   ├── bridge/
│   │   ├── IBillerQueryBridge.kt   (精简)
│   │   ├── IBillerImportExportBridge.kt
│   │   └── IStatisticsRepository.kt
│   └── model/
│       ├── CategoryStat.kt
│       ├── FullBillExport.kt
│       ├── MonthSummary.kt
│       ├── RawCategoryAgg.kt
│       └── RawMonthSummary.kt
├── parser/
│   ├── AccessibilityTreeDumper.kt  ★ 新建
│   ├── NotificationKeywordChecker.kt ★ 新建
│   ├── ParsedBill.kt
│   ├── ScreenMatch.kt
│   ├── wechat/
│   │   ├── WeChatParser.kt         ★ 新建 (门面)
│   │   ├── WeChatScreenExtractor.kt ★ 重写
│   │   └── WeChatNotificationExtractor.kt
│   └── alipay/
│       ├── AlipayParser.kt         ★ 新建 (门面)
│       ├── AlipayScreenExtractor.kt ★ 重写
│       └── AlipayNotificationExtractor.kt
├── pipeline/
│   └── BillProcessingPipeline.kt   ★ 新建
├── service/
│   ├── BillerAccessibilityService.kt ★ 重写 (薄层)
│   ├── BillerNotificationService.kt  ★ 重写 (薄层)
│   ├── BillEventProcessor.kt        ★ 新建
│   └── NotificationLogger.kt        ★ 新建
├── settings/
│   └── SettingsManager.kt
└── ui/
    ├── SettingsFragment.kt
    ├── about/AboutBottomSheet.kt
    ├── common/
    │   ├── BaseMviViewModel.kt
    │   ├── MonthPickerDialog.kt
    │   └── ViewModelFactory.kt       ★ 重写
    ├── dialog/BillEditDialog.kt
    ├── main/
    │   ├── BillDashboardActivity.kt
    │   ├── BillListFragment.kt
    │   ├── BillListUiState.kt
    │   ├── BillListViewModel.kt      ★ 修改 (updateBillFields)
    │   └── BillRecordAdapter.kt
    ├── profile/
    │   ├── ProfileFragment.kt
    │   ├── ProfileUiState.kt
    │   └── ProfileViewModel.kt       ★ 修改 (exportRecords)
    ├── stats/
    │   ├── StatsFragment.kt
    │   ├── StatsUiState.kt
    │   └── StatsViewModel.kt
    └── stub/                          (保留，供测试)
        ├── StubBillQueryBridge.kt
        ├── StubImportExportBridge.kt
        └── StubStatisticsRepository.kt
```

★ = 新建或重写

---

## 七、遗留待办

| # | 事项 | 文件 |
|---|------|------|
| 1 | KDoc 引用已废弃类型 | ParsedBill, ScreenMatch, 四个 Extractor |
| 2 | NotificationKeywordChecker 命名冗长 | parser/ |
| 3 | WeChatParser / AlipayParser 门面重复代码 | parser/wechat, parser/alipay |
| 4 | AlipayNotificationExtractor 含旧版 IBillParser KDoc 引用 | parser/alipay/ |
| 5 | 微信记账本小程序解析（未来） | parser/wechat/ |
| 6 | 通知栏解析器待日志数据充分后审核重构 | parser/*NotificationExtractor |
