# 第1轮 Bug 修复报告

> 日期：2026-06-12
> 修复项：7 个

---

## BUG-01：`isCached()` 去重逻辑错误

**文件**：`service/BillEventProcessor.kt:139`

**原因**：过期条目重新触发时，先插入新时间戳，再因 `lastTime != null` 把新值删除。导致同一内容第二次事件永远不会被去重。

**修复**：删除 `if (lastTime != null) map.remove(hash)` 一行。

```diff
- map[hash] = now
- if (lastTime != null) map.remove(hash)
+ map[hash] = now
```

---

## BUG-02：`LinkedHashMap` 线程不安全

**文件**：`service/BillEventProcessor.kt:112`

**原因**：`recentHashes` / `recentContentHashes` 被无障碍主线程和通知主线程并发访问，无同步保护。

**修复**：`isDuplicate()` 方法加 `@Synchronized` 注解。

```diff
- private fun isDuplicate(windowId: Int, bill: ParsedBill): Boolean {
+ @Synchronized
+ private fun isDuplicate(windowId: Int, bill: ParsedBill): Boolean {
```

---

## BUG-03：`SimpleDateFormat` 线程不安全

**文件**：`service/NotificationLogger.kt:24`

**原因**：`SimpleDateFormat` 非线程安全，单例对象中被多线程调用会紊乱。

**修复**：改为 `java.time.format.DateTimeFormatter`（不可变对象，线程安全）。

```diff
- private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
+ private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
```

---

## BUG-04：文件 I/O 在主线程

**文件**：`service/NotificationLogger.kt:44`

**原因**：`FileWriter` 在 `onNotificationPosted` 回调线程执行，主线程磁盘写入可能卡顿。

**修复**：写入逻辑移至 `Executors.newSingleThreadExecutor()`。

```diff
+ private val executor = Executors.newSingleThreadExecutor()
  fun log(sbn: StatusBarNotification) {
-     FileWriter(file, true).use { ... }
+     executor.execute {
+         FileWriter(file, true).use { ... }
+     }
  }
```

---

## BUG-05：空 `extras` 未检查

**文件**：`parser/wechat/WeChatParser.kt:42`、`parser/alipay/AlipayParser.kt:29`

**原因**：`sbn.notification.extras` 可能为 null（某些通知格式），直接调用 `getString()` 抛 NPE。

**修复**：改为安全调用。

```diff
- val title = sbn.notification.extras.getString("android.title") ?: ""
+ val title = sbn.notification.extras?.getString("android.title") ?: ""
```

---

## BUG-06：`texts[1]` 越界风险

**文件**：`parser/wechat/WeChatScreenExtractor.kt:54`

**原因**：门禁只检查 `first()`/`last()`，未保证 `size >= 3`。单元素或双元素列表访问 `texts[1]` 抛 `IndexOutOfBoundsException`。

**修复**：门禁加 `texts.size < 3` 条件。

```diff
- if (texts.first() != GATE_FIRST || texts.last() != GATE_LAST) {
+ if (texts.size < 3 || texts.first() != GATE_FIRST || texts.last() != GATE_LAST) {
```

---

## BUG-07：SQL `merchant = NULL` 匹配遗漏

**文件**：`data/biller/BillRecordDao.kt:109`

**原因**：SQL 中 `NULL = NULL` 恒为 false，商户为 null 的记录永久无法被 L2 去重命中。

**修复**：加 `IS NULL` 分支，使用空字符串 `''` 作为 SQL 参数中的 null 哨兵。

```diff
- WHERE window_id = :windowId AND amount = :amount AND merchant = :merchant
+ WHERE window_id = :windowId AND amount = :amount
+   AND (merchant = :merchant OR (merchant IS NULL AND :merchant = ''))
```

---

## 总结

| Bug | 类型 | 严重程度 | 改动行数 |
|-----|------|---------|---------|
| BUG-01 | 逻辑错误 | 🔴 数据正确性 | -1 行 |
| BUG-02 | 线程安全 | 🔴 并发安全 | +1 行 |
| BUG-03 | 线程安全 | 🔴 并发安全 | 1 行 |
| BUG-04 | 性能 | 🟠 主线程 I/O | ~10 行 |
| BUG-05 | 空安全 | 🔴 崩溃风险 | 2×2 行 |
| BUG-06 | 越界 | 🔴 崩溃风险 | 1 行 |
| BUG-07 | SQL | 🟠 数据完整性 | 1 行 |
