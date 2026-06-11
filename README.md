# Biller-Android

<!-- 无障碍记账工具 | Accessibility Bill Tracker -->

一个**隐私优先、完全离线**的 Android 自动记账工具。通过无障碍服务和通知监听自动捕获微信 / 支付宝的支付信息，持久化到本地数据库，并提供账单查看与统计分析功能。

> 无需 Root，无需联网，数据完全存储在本地。

---

## ✨ 特性

- **🤖 全自动记账** — 通过 AccessibilityService + NotificationListenerService 自动识别微信 / 支付宝支付行为
- **🔒 隐私优先** — 完全离线运行，数据 100% 存储在本地 Room 数据库，不上传任何信息
- **🎯 精准去重** — 四层去重体系（内容哈希 → 窗口哈希 → DB 持久化 → 异源融合），有效避免重复记录
- **🔀 双源融合** — 通知栏 + 无障碍双通道数据自动合并，信息互补更完整
- **📊 消费统计** — 按分类 / 月度维度查看消费分布，一目了然
- **📤 数据导出** — 支持 CSV 格式导出，方便用 Excel / 其他工具进一步分析
- **📝 自定义日志** — 独立 Logger 模块，支持文件 / Logcat / 远程多通道输出，ANR 检测与崩溃捕获
- **♿ 无障碍友好** — 包名伪装为华为屏幕阅读器，绕过微信无障碍限制

---

## 📱 截图与支持场景

### 支持的支付场景

| 应用 | 场景 | 无障碍 | 通知栏 | 双源融合 |
|------|------|--------|--------|----------|
| 微信 | 扫码支付 | ✅ | ✅ | ✅ |
| 微信 | 转账 | ✅ | ✅ | ✅ |
| 支付宝 | 扫码支付（无优惠） | ✅ | ✅ | ✅ |
| 支付宝 | 扫码支付（有优惠） | ✅ | ✅ | ✅ |
| 支付宝 | 转账 | ✅ | ✅ | ✅ |
| 微信 | 记账本小程序 | ⚠️ 未实现 | — | — |

---

## 🏗️ 架构

```
系统回调 (Android Framework)
   │
   ▼
┌──────────────────────────────────────┐
│ ① 监听层 (service/)                    │
│   AccessibilityService +               │
│   NotificationListenerService          │
│   职责：只监听、提取原始数据、路由         │
└────────────┬─────────────────────────┘
             │
             ▼
┌──────────────────────────────────────┐
│ ② 解析层 (parser/)                     │
│   按 App 聚合（门面模式）                │
│   ├── wechat/  (WeChatParser)         │
│   │   ├── WeChatScreenExtractor       │
│   │   └── WeChatNotificationExtractor │
│   └── alipay/  (AlipayParser)         │
│       ├── AlipayScreenExtractor       │
│       └── AlipayNotificationExtractor │
│   输出：统一 ParsedBill 模型              │
└────────────┬─────────────────────────┘
             │
             ▼
┌──────────────────────────────────────┐
│ ③ 数据处理层 (pipeline/)               │
│   L1 内存去重 → L2 DB 去重 → L3 融合    │
│   先到先写 + 后到合并                    │
└────────────┬─────────────────────────┘
             │
             ▼
┌──────────────────────────────────────┐
│ ④ 数据仓库层 (data/)                   │
│   Room Database + BillRepository      │
│   纯持久化 + 查询 + 分类匹配             │
│   数据以 Flow 流向 UI 层               │
└────────────┬─────────────────────────┘
             │
             ▼
┌──────────────────────────────────────┐
│ ⑤ UI 层 (ui/) — MVI 架构              │
│   ├── BillListFragment (账单列表)       │
│   ├── ProfileFragment (当月概览)        │
│   └── StatsFragment (分类统计)          │
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│ Logger 日志模块 (logger/ — 独立 library)│
│ CLog / Dispatcher / Appender /         │
│ Formatter / CrashHandler / ANR 检测    │
└──────────────────────────────────────┘
```

---

## 🔬 去重体系

多源数据（通知栏 + 无障碍）可能几乎同时到达，设计四层去重确保不重复：

| 层 | 策略 | TTL | 范围 |
|----|------|-----|------|
| **L1a** | 内容哈希去重 | 1 秒 | 无 windowId（支付宝多窗口） |
| **L1b** | 窗口哈希去重 | 2 秒 | 带 windowId（同窗口回调） |
| **L2** | DB windowId + 金额 + 商户 | 持久 | 跨 session 去重 |
| **L3** | DB 异源融合 | 5 秒 | 无障碍 + 通知合并 |

---

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| 最低 SDK | Android 11 (API 30) |
| 目标 SDK | Android 14 (API 35) |
| 数据库 | Room |
| 异步 | Kotlin Coroutines + Flow |
| 架构 | MVI (Model-View-Intent) |
| 构建 | Gradle 9.4.1 + AGP 9.2.1 |
| Kotlin 版本 | 2.2.10 |
| 依赖注入 | 手动（Singleton 在 Application 中统一初始化） |
| 日志 | 自研 Logger 模块 |

---

## 📦 模块结构

```
BillerApplication/
├── app/                     # 主应用模块
│   └── src/main/java/.../biller/
│       ├── BillerApplication.kt    # 入口，统一初始化
│       ├── config/                 # 目标 App 配置
│       ├── data/                   # Room DB + Repository + Bridge 接口
│       ├── parser/                 # 解析层（按 App 聚合）
│       │   ├── wechat/             # 微信解析器
│       │   └── alipay/             # 支付宝解析器
│       ├── pipeline/               # 数据处理层（去重+融合）
│       ├── service/                # 监听层（无障碍+通知服务）
│       ├── settings/               # 设置管理
│       └── ui/                     # MVI UI 层
│           ├── main/               # 账单列表
│           ├── profile/            # 当月概览
│           ├── stats/              # 分类统计
│           ├── about/              # 关于页
│           ├── common/             # 公共组件
│           ├── dialog/             # 编辑对话框
│           └── stub/               # 桩实现
├── logger/                  # 日志库（独立 module）
│   └── src/main/java/.../logger/
│       ├── api/                    # CLog / LogConfig
│       ├── appender/               # Logcat / File / Remote
│       ├── core/                   # Dispatcher / StackTrace
│       ├── enhancement/            # ANR / Crash 处理
│       ├── file/                   # 文件管理 / 清理 / 导出
│       ├── formatter/              # 日志格式化
│       └── model/                  # LogLevel / LogRecord
└── build.gradle.kts         # 根构建配置
```

---

## 🚀 快速开始

### 环境要求

- Android Studio (最新版推荐)
- JDK 17+
- Gradle 9.4.1+（使用 wrapper，自动下载）

### 构建 & 安装

```bash
# 克隆项目
git clone https://github.com/kangkaipeng/Biller-Android.git
cd Biller-Android

# 编译 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 首次使用

1. 打开应用，按引导授予**通知监听权限**
2. 前往系统「无障碍」设置，开启 **BillerApplication** 无障碍服务
3. 完成！此后微信 / 支付宝的支付行为会被自动记录

---

## 📄 关键文档

| 文件 | 说明 |
|------|------|
| [ARCHITECTURE_REPORT.md](./ARCHITECTURE_REPORT.md) | 完整架构设计文档 |
| [CODE_REVIEW_REPORT.md](./CODE_REVIEW_REPORT.md) | 代码审查报告 |
| [CODE_REVIEW_PARSER.md](./CODE_REVIEW_PARSER.md) | 解析层专项审查 |
| [BUGFIX_REPORT.md](./BUGFIX_REPORT.md) | Bug 修复报告 |
| [FIX_PLAN.md](./FIX_PLAN.md) | 后续优化计划 |
| [MIGRATION_FINAL_REPORT.md](./MIGRATION_FINAL_REPORT.md) | 从 HWApplication 迁移报告 |

---

## ⚠️ 注意事项

- **无障碍服务限制**：部分系统（如 MIUI、HarmonyOS）可能会在后台自动关闭无障碍服务，需在系统设置中将应用加入电池优化白名单
- **微信无障碍限制**：包名伪装为华为屏幕阅读器以绕过微信的无障碍检测机制
- **通知栏权限**：Android 13+ 需要手动授予通知权限
- **WebView / 小程序**：微信记账本小程序等 WebView 场景暂不支持解析

---

## 📝 License

本项目仅供个人学习与研究使用。使用前请确保符合相关法律法规及支付平台的服务条款。

---

> 🤖 Built with ❤️ by [kangkaipeng](https://github.com/kangkaipeng)
