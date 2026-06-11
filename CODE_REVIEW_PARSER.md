# 解析层代码审查记录

> 日期：2026-06-11
> 状态：待处理（迁移完成后统一修复）

---

## 一、KDoc 引用废弃类型

### ① ParsedBill.kt

**第7行**：
```kotlin
职责: 作为 NotificationParser 和 IScreenParser 实现类（WeChat/Alipay）的统一输出，
      由 data 层 ([IBillerServiceBridge.persist]) 进一步转换为 BillRecord 入库。
```
- `NotificationParser` → 已删除，现为 `WeChatNotificationExtractor` / `AlipayNotificationExtractor`
- `IScreenParser` → 已删除
- `IBillerServiceBridge` → 未迁移，待后续

**第6行**：`模块: feature/biller/engine` → 应改为 `模块: parser`

### ② ScreenMatch.kt

**第6行**：`模块: feature/biller/engine/screen` → 应改为 `模块: parser`

**第29-31行**：
```kotlin
@see IScreenParser 策略接口
@see WeChatScreenParser 微信实现
@see AlipayScreenParser 支付宝实现
```
应改为：
```kotlin
@see WeChatScreenExtractor 微信无障碍提取器
@see AlipayScreenExtractor 支付宝无障碍提取器
```

### ③ 四个 Extractor 的 KDoc

| 文件 | 旧引用 | 应改为 |
|------|--------|--------|
| `WeChatScreenExtractor` | `@see IScreenParser 策略接口` | 删除或更新 |
| `WeChatNotificationExtractor` | `@see NotificationParserRegistry` | 删除（已无 Registry） |
| `WeChatNotificationExtractor` | `@see IBillParser` | 删除 |
| `AlipayScreenExtractor` | `@see IScreenParser 策略接口` | 删除或更新 |
| `AlipayScreenExtractor` | `@see WeChatScreenParser` | `@see WeChatScreenExtractor` |
| `AlipayNotificationExtractor` | `@see NotificationParser` | 删除 |
| `AlipayNotificationExtractor` | `@see IBillParser` | 删除 |

---

## 二、结构层面

### ④ WeChatParser 与 AlipayParser 代码高度重复

两个门面各 ~57 行，仅字符串不同。当前保留独立文件以支持各自独立演进，后续如无分化可考虑抽取共性。

### ⑤ NotificationKeywordChecker 命名冗长

`NotificationKeywordChecker.checkKeywords(pkg, title, text, expected)` — 类名和方法名重叠。可考虑简化为 `PaymentKeywordFilter`。

---

## 三、设计遗留（暂不修改）

### ⑥ ParsedBill.rawText 语义不一致

- 无障碍路径：填充人工构造的摘要 `"amount=19.93 merchant=某某"`
- 通知路径：填充原始通知文本
- 同名不同义，但改动影响范围大，暂维持现状

---

## 处理状态

| # | 问题 | 状态 |
|---|------|------|
| ① | ParsedBill.kt KDoc | 待处理 |
| ② | ScreenMatch.kt KDoc | 待处理 |
| ③ | Extractor KDoc 旧引用 | 待处理 |
| ④ | 门面代码重复 | 观察 |
| ⑤ | NotificationKeywordChecker 命名 | 待处理 |
| ⑥ | rawText 语义 | 不处理 |
