# BillerApplication 架构报告

> 基于 HWApplication 项目分析，经代码审查后确定的迁移架构方案。
> 日期：2026-06-11

---

## 一、项目背景

**HWApplication**（自动记账）是一个隐私优先、完全离线的 Android 自动记账工具。通过 AccessibilityService 和 NotificationListenerService 自动捕获微信/支付宝的支付信息，持久化到本地 Room 数据库，并提供账单查看与统计功能。

- 包名：`com.bjbyhd.screenreader_huawei`（伪装华为屏幕阅读器包名以绕过微信无障碍限制）
- 模块：`:biller`（app）+ `:logger`（library）
- 技术栈：Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.2.10 / Room / MVI / XML 布局
- minSdk 29 → BillerApplication 调整到 30

---

## 二、最终分层架构（6 层 + 1 横切）

```
系统回调 (Android Framework)
   │
   ▼
┌──────────────────────────────────────────────┐
│ ① 监听层 (Service)                            │
│                                              │
│   AccessibilityService.onAccessibilityEvent()  │
│   NotificationListenerService.onNotification() │
│                                              │
│   职责：只监听、提取原始数据、按包名路由到解析层      │
│   输出：RawAccessibilityEvent / RawNotificationData│
│   不做：解析、去重、持久化                         │
└────────────┬─────────────────────────────────┘
             │ 根据 packageName 路由
             ▼
┌──────────────────────────────────────────────┐
│ ② 解析层 (Per-App Parser)                     │
│                                              │
│   parser/                                    │
│   ├── IAppParser.kt                          │
│   ├── BillSource.kt (枚举：来源标签)            │
│   ├── RawSourceEvent.kt (统一输入数据包)        │
│   ├── wechat/                                │
│   │   ├── WeChatParser.kt          ← 门面路由  │
│   │   ├── WeChatScreenExtractor.kt ← 无障碍解析 │
│   │   ├── WeChatNotificationExtractor.kt      │
│   │   └── WeChatMiniProgramExtractor.kt(未来)  │
│   ├── alipay/                                │
│   │   ├── AlipayParser.kt                    │
│   │   ├── AlipayScreenExtractor.kt           │
│   │   └── AlipayNotificationExtractor.kt     │
│   └── meituan/            ← 预留              │
│                                              │
│   职责：解析各 App 特有格式，输出统一业务模型        │
│   输出：ParsedBill                            │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ ③ 数据处理层 (Processing)                     │
│                                              │
│   ┌─ 短时间去重 ──┐   ┌─ 双源合并去重 ──┐       │
│   │ 同源 N 秒内   │ → │ 异源 N 秒内   │       │
│   │ 同金额？丢弃  │   │ 同金额？合并  │       │
│   └──────────────┘   └───────────────┘       │
│                                              │
│   职责：去重 + 双源融合，产生最终记录              │
│   输出：MergedBillRecord                      │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ ④ 数据仓库层 (Repository + Room)              │
│                                              │
│   职责：纯持久化 + 查询 + 分类匹配 + 导入导出       │
│   包含：Room Database / DAOs / Entities        │
│   不做：业务判断（去重/合并）                     │
│   输出：Flow<List<BillRecord>> → UI 层观察      │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ ⑤ UI 层 (MVI)                                │
│                                              │
│   BaseMviViewModel<UiState, Event>            │
│   ├── BillDashboardActivity (底部导航)         │
│   ├── BillListFragment / ViewModel            │
│   ├── ProfileFragment / ViewModel             │
│   └── StatsFragment / ViewModel               │
└──────────────────────────────────────────────┘

横切层:
┌──────────────────────────────────────────────┐
│ Logger 日志模块 (独立 library module)           │
│ CLog / LogConfig / Dispatcher / Appender /    │
│ Formatter / FileManager / CrashHandler        │
└──────────────────────────────────────────────┘
```

---

## 三、各层职责边界

| 层 | 输入 | 输出 | 禁止做的事 |
|----|------|------|-----------|
| 监听层 | Android 系统回调 | 原始数据对象 | 解析、去重、写入 DB |
| 解析层 | 原始数据对象 | ParsedBill | 处理 DB、去重判断 |
| 数据处理层 | ParsedBill | 去重后的 BillRecord | 直接写 DB（调仓库层接口） |
| 数据仓库层 | BillRecord / 查询条件 | DB 记录 / Flow | 业务判断（去重逻辑不在此层） |
| UI 层 | ViewModel StateFlow | 界面渲染 | 直接访问 Service 或 DB |

---

## 四、解析层设计细节

### 4.1 按 App 聚合，非按渠道聚合

**HWApplication 现状**（按渠道分）：
```
parser/screen/WeChatScreenParser.kt       ← 微信无障碍
parser/screen/AlipayScreenParser.kt       ← 支付宝无障碍
parser/notification/WeChatNotificationParser.kt  ← 微信通知
parser/notification/AlipayNotificationParser.kt  ← 支付宝通知
```

**BillerApplication 目标**（按 App 分）：
```
parser/wechat/WeChatParser.kt             ← 微信唯一入口
parser/wechat/WeChatScreenExtractor.kt    ← 微信无障碍解析
parser/wechat/WeChatNotificationExtractor.kt ← 微信通知解析
parser/alipay/AlipayParser.kt             ← 支付宝唯一入口
...
```

### 4.2 门面模式（Facade）

每个 App 对外只暴露一个入口类，内部按 `BillSource` 标签分发：

```kotlin
enum class BillSource {
    NOTIFICATION,
    ACCESSIBILITY_SCREEN,
    MINI_PROGRAM
}

class WeChatParser : IAppParser {
    private val screenExtractor = WeChatScreenExtractor()
    private val notificationExtractor = WeChatNotificationExtractor()

    fun parse(event: RawSourceEvent): ParsedBill? {
        return when (event.source) {
            BillSource.ACCESSIBILITY_SCREEN -> screenExtractor.extract(event)
            BillSource.NOTIFICATION        -> notificationExtractor.extract(event)
            BillSource.MINI_PROGRAM        -> null  // 未来
        }
    }
}
```

### 4.3 不设共享工具类

每个 Extractor 的输入格式、解析策略完全不同，不抽出共享常量/正则，避免不必要的耦合。

---

## 五、HWApplication 中发现的问题清单

### 需立即修复

| # | 位置 | 问题 |
|---|------|------|
| 1 | ViewModelFactory.kt | `isAssignableFrom` 用法反了，导致 ProfileViewModel / StatsViewModel 永远无法被创建 |

### 迁移时重构

| # | 位置 | 问题 | 处理方式 |
|---|------|------|---------|
| 2 | ScreenParserRegistry + NotificationParserRegistry | 两个 Registry 结构完全相同，~80行重复 | 抽取泛型基类或简化注册逻辑 |
| 3 | PipelineTypes.ProcessResult | 定义了 5 种结果但从未使用 | 删除或在数据处理层实现编排器 |
| 4 | SettingsManager KDoc | 文档描述的方法实际不存在 | 修正文档 |
| 5 | TargetConfig 字符串 key | 硬编码字符串而非引用常量 | 改用常量引用 |
| 6 | PersistStage fire-and-forget | 写入失败无声丢失 | 接入错误回调 |
| 7 | BillerRepository.updateNote | 空值处理链可读性差 | 简化 |

### 架构调整（新项目已采纳）

| # | 原问题 | 新方案 |
|---|--------|--------|
| 8 | Service 混入解析/去重/持久化逻辑 | 监听层只提取+路由 |
| 9 | 通知路径和无障碍路径不对称（通知跳过 Pipeline） | 统一经过数据处理层 |
| 10 | Repository 承担融合+分类+存储+查询，24KB 单文件 | 融合上提到数据处理层，仓库层瘦身 |

---

## 六、迁移顺序

按照依赖从底层到上层：

```
第1步：Logger 模块        （零外部依赖）
第2步：Room 数据库层       （仅依赖 Room/AndroidX）
第3步：Contract 接口模型   （ParsedBill/ScreenMatch/RawSourceEvent等，纯定义）
第4步：解析层              （依赖 Contract）
第5步：数据处理层          （依赖 Contract + 仓库接口）
第6步：数据仓库层          （依赖 Room + Contract）
第7步：监听层              （依赖解析层 + 数据处理层）
第8步：UI 层              （依赖仓库接口 + MVI 基类）
```

---

## 七、HWApplication 模块映射（参考源）

| HWApplication 源路径 | BillerApplication 目标 |
|---------------------|----------------------|
| `logger/` | `logger/` (新模块) |
| `biller/.../data/BillerDatabase.kt` | `app/.../data/` |
| `biller/.../data/BillRecordDao.kt` | `app/.../data/` |
| `biller/.../data/BillRecord.kt` | `app/.../data/` |
| `biller/.../data/Category.kt` | `app/.../data/` |
| `biller/.../data/CategoryDao.kt` | `app/.../data/` |
| `biller/.../contract/` | `app/.../contract/` |
| `biller/.../parser/` | `app/.../parser/` (重构为按App聚合) |
| `biller/.../pipeline/` | `app/.../pipeline/` (数据处理层) |
| `biller/.../service/` | `app/.../service/` (监听层) |
| `biller/.../ui/` | `app/.../ui/` |
| `biller/.../settings/` | `app/.../settings/` |
| `biller/.../config/` | `app/.../config/` |
