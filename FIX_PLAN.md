# 代码整改计划

> 综合 `CODE_REVIEW_REPORT.md` 和 `CODE_REVIEW_PARSER.md`

---

## 整改分层

```
第1轮: 🔴 Bug 修复        (7 项, 影响数据正确性和稳定性)
第2轮: 🟠 安全加固         (3 项, 线程安全+空安全)
第3轮: 📝 KDoc 清理       (8 项, 旧引用+模块名)
第4轮: ♻️ 架构优化        (4 项, Context/命名/重复代码)
```

---

## 第1轮：Bug 修复（高优先级）

### 1.1 BUG-01 `isCached()` 去重逻辑错误

| 项 | 内容 |
|----|------|
| 文件 | `service/BillEventProcessor.kt:139` |
| 影响 | 第二次同内容事件永远不会被去重，产生重复账单 |
| 改动 | 删除 `if (lastTime != null) map.remove(hash)` |
| 范围 | 单行删除，无依赖 |

### 1.2 BUG-02 `LinkedHashMap` 线程不安全

| 项 | 内容 |
|----|------|
| 文件 | `service/BillEventProcessor.kt:33-35` |
| 影响 | 并发修改可能导致崩溃或数据错乱 |
| 改动 | `isDuplicate()` 加 `@Synchronized` |
| 范围 | 单方法注解，无依赖 |

### 1.3 BUG-07 SQL `merchant = NULL` 匹配遗漏

| 项 | 内容 |
|----|------|
| 文件 | `data/biller/BillRecordDao.kt:109` |
| 影响 | NULL 商户记录无法被 L2 去重命中 |
| 改动 | 加 `OR (merchant IS NULL AND :merchant IS NULL)` |
| 范围 | 单行 SQL，无依赖 |

### 1.4 BUG-06 `texts[1]` 越界风险

| 项 | 内容 |
|----|------|
| 文件 | `parser/wechat/WeChatScreenExtractor.kt:55-58` |
| 影响 | 异常界面结构导致崩溃 |
| 改动 | 门禁校验加 `texts.size < 3 → return null` |
| 范围 | 单行，无依赖 |

### 1.5 BUG-05 空 `extras` 未检查

| 项 | 内容 |
|----|------|
| 文件 | `parser/wechat/WeChatParser.kt:42` + `parser/alipay/AlipayParser.kt:29` |
| 影响 | 某些通知格式下 extras 为 null → NPE |
| 改动 | `sbn.notification.extras?.getString(...) ?: ""` |
| 范围 | 2 文件各 2 行 |

### 1.6 BUG-03 `SimpleDateFormat` 线程不安全

| 项 | 内容 |
|----|------|
| 文件 | `service/NotificationLogger.kt:24` |
| 影响 | 并发写入时日期格式紊乱 |
| 改动 | 改为 `java.time.format.DateTimeFormatter` |
| 范围 | 单文件 3 行（声明+使用） |

### 1.7 BUG-04 文件 I/O 在主线程

| 项 | 内容 |
|----|------|
| 文件 | `service/NotificationLogger.kt:44` |
| 影响 | 主线程磁盘写入可能 ANR |
| 改动 | `log()` 内用 `Thread { ... }.start()` 或协程 |
| 范围 | 单方法 10 行包裹 |

---

## 第2轮：安全加固（中优先级）

### 2.1 WARN-01 Context 存储在 ViewModel

| 项 | 内容 |
|----|------|
| 文件 | `ui/profile/ProfileViewModel.kt:40` + `ui/common/ViewModelFactory.kt:24` |
| 影响 | 违反 Android 规范，潜在内存泄漏 |
| 改动 | ProfileViewModel 改继承 `AndroidViewModel`，用 `getApplication()` 获取 Context |
| 范围 | 2 文件，改构造函数 + 内部引用 |

### 2.2 WARN-02 scope 可空导致静默数据丢失

| 项 | 内容 |
|----|------|
| 文件 | `service/BillEventProcessor.kt:33` |
| 影响 | `init()` 前收到事件静默丢弃 |
| 改动 | `lateinit var scope: CoroutineScope` |
| 范围 | 单文件 1 行 |

### 2.3 WARN-03 `toDouble()` 潜在崩溃

| 项 | 内容 |
|----|------|
| 文件 | `parser/alipay/AlipayScreenExtractor.kt:64` |
| 影响 | 金额解析异常直接崩溃 |
| 改动 | `toDoubleOrNull() ?: return null` |
| 范围 | 单文件 1 行 |

---

## 第3轮：KDoc 清理（低优先级）

### 3.1 ParsedBill.kt

| 原内容 | 改后 |
|--------|------|
| `模块: feature/biller/engine` | `模块: parser` |
| `作为 NotificationParser 和 IScreenParser 实现类` | `作为解析器统一输出` |
| `由 data 层 ([IBillerServiceBridge.persist])` | `由数据处理层` |
| `@property timestamp ... 无障碍路径为 System.currentTimeMillis()` | 改为 `回调到达时间` |

### 3.2 ScreenMatch.kt

| 原内容 | 改后 |
|--------|------|
| `模块: feature/biller/engine/screen` | `模块: parser` |
| `@see IScreenParser 策略接口` | 删除 |
| `@see WeChatScreenParser` | `@see WeChatScreenExtractor` |

### 3.3 Extractor KDoc 批量清理

| 文件 | 旧引用 | 处理 |
|------|--------|------|
| WeChatScreenExtractor | `@see IScreenParser` | 删除 |
| WeChatNotificationExtractor | `@see NotificationParserRegistry / IBillParser` | 删除 |
| AlipayScreenExtractor | `@see IScreenParser / WeChatScreenParser` | 删除/更新 |
| AlipayNotificationExtractor | `@see IBillParser` | 删除 |

### 3.4 其他 KDoc

| 文件 | 问题 | 处理 |
|------|------|------|
| NotificationKeywordChecker | 类名冗长 | 改 `NotificationFilter` |
| ParsedBill.extras | "暂存未来"但已在使用 | 改"语义扩展字段" |

---

## 第4轮：架构优化

### 4.1 门面重复代码（观察，暂不改）

WeChatParser / AlipayParser ~25 行重复。等后续有新 App 加入时再决定是否抽取。

### 4.2 MVI Effect 类型安全（可选）

`BaseMviViewModel` Effect 通道 `Any?` → `Effect` 泛型。改动影响所有子类，单独排期。

### 4.3 空 override 清理

| Service | 方法 | 处理 |
|---------|------|------|
| AccessibilityService | `onServiceConnected()` | 删除或加注释 |
| AccessibilityService | `onInterrupt()` | 删除 |
| NotificationService | `onListenerConnected()` | 删除 |
| NotificationService | `onNotificationRemoved()` | 删除 |

### 4.4 `e.printStackTrace()` → `CLog.e()`

NotificationLogger.kt:75。

---

## 影响范围矩阵

| 改动 | 文件数 | 风险 | 依赖 |
|------|--------|------|------|
| 第1轮 (7 Bugs) | 6 | 低 | 无 |
| 第2轮 (3 加固) | 4 | 中 | 无 |
| 第3轮 (KDoc) | 6 | 无 | 无 |
| 第4轮 (架构) | 4 | 中 | 单独排期 |

---

## 建议执行顺序

```
第1轮 (全部 7 Bug) → 测试验证
第2轮 (全部 3 加固) → 测试验证
第3轮 (全部 KDoc) → 编译通过即可
第4轮 (择机处理)
```
