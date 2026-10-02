# Phase 4 订阅费用管理验收记录

验收环境：JDK 25、Gradle 9.4.1、Android SDK Platform 35，Android 字节码目标为 Java/Kotlin 17。最终验证日期：2026-10-02。

## 已实现

- MVVM + Clean Architecture：领域模型/规则/仓库接口、Room 数据层、`SubscriptionViewModel` 和 `StateFlow` 驱动的 Compose Material 3 页面；沿用深浅色主题。
- 订阅列表、详情/编辑、CRUD、取消/恢复和实际扣费记录 CRUD；取消保留付款历史，删除须确认并级联删除关联记录。
- 金额精确转换到 `Long` 最小货币单位，校验正数、ISO 币种和日期；周期始终从原始锚点独立推导，避免二月钳位后永久变成 28 日。手工扣费不改锚点和预测。
- CNY 本月预计/实际分别统计，最近 6 个月柱状图、分类占比和周期对比；非 CNY 仍可管理和导出，不换算、不混合累计。图表提供文字汇总。
- 多选提前 1/3/7 天、本地 09:00 提醒，通知渠道、取消/删除清理、接收前检查当前数据库状态、重启/时间变化/后台补排程和非精确兜底。
- 通过系统 `CreateDocument` 导出订阅及实际扣费的 UTF-8 BOM CSV；先显示消费数据隐私确认，正确转义逗号、引号和换行。
- 读取失败时不允许以空列表伪装成功导出；真正成功读取的空数据库仍可导出。通知到达时若正在编辑，则保留输入、在表单内明确提示等待查看，保存或关闭后打开目标详情。
- Room v3→v4 显式迁移只新增订阅、付款和提醒三张表，旧七张表的导出 schema 定义保持不变；没有 destructive migration。
- 底部导航提供待办、日程、打卡、订阅及既有设置入口；没有 Phase 5 占位页或预置假订阅。

## 验证记录

以下是最终源码上的实际运行结果，本记录不将编译或静态检查等同于设备验收。

- 结构检查：`powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1`。
- JVM：`:app:testDebugUnitTest`，覆盖周期锚点、金额、预计/实际分离、币种过滤、CSV 转义、提醒身份/时区/截止边界、并发协调和 ViewModel 状态。
- APK：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 新增 Android 仪器测试：`SubscriptionDaoTest`（级联、编辑保留付款、仓库事务回滚）、`SubscriptionMigrationTest`（v3 数据保留）和 `SubscriptionScreenTest`（新增表单及保存金额）。

| 验证 | 实际结果 |
| --- | --- |
| Phase 4 结构检查 | 通过；旧 v3 表的 schema 定义保持不变。 |
| `:app:testDebugUnitTest` | 119/119 通过，0 failures / 0 errors / 0 skipped（读取全部 15 个测试套件 XML）；其中订阅 ViewModel 21 项。 |
| `:app:assembleDebug` | 通过，生成 `app/build/outputs/apk/debug/app-debug.apk`。 |
| `:app:assembleDebugAndroidTest` | 通过，生成 Android 测试 APK，包含 schema assets。 |
| 全量联合命令 | `--no-daemon --console=plain --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 返回退出码 0，`BUILD SUCCESSFUL`。 |
| `git diff --check` / 敏感文件检查 | 通过；未跟踪本机 SDK 配置、数据库、签名密钥或个人消费记录。 |
| `adb devices` | 无连接设备；仪器测试未执行。 |

回归测试覆盖：原锚点月底/闰年恢复、金额溢出不显示负数且记录仍可编辑、旧表单不能撤销后续取消、旧提醒读取不能污染新表单、提醒接收与修改共享协调门、校准不丢当天延迟提醒、过期广播不能消费新提醒、通知事件不重播、ViewModel 中导出保留进度/结果；读取失败拒绝导出并可重试恢复、成功读取的空库可导出、两类编辑草稿等待通知时均保留，保存/关闭后打开目标详情。

当时针对 Phase 4 订阅变更的代码审查及修复复审已完成，该审查范围内的 Important 问题已处理；这不表示整个应用没有问题。后续全需求审计发现其他模块缺陷，第一批修复见 [习惯完整性修复报告](2026-10-02-habit-integrity-fixes.md)。功能与测试合并为一致的原子提交 `78ee6ba`；README、结构检查和本验收记录单独提交。

## 未完成与已知限制

- 当前无连接的设备/模拟器；Room 迁移、Compose 交互及系统通知/闹钟/文件选择器测试尚未在设备执行，不能称为完整设备验收通过。
- 本 Phase 按已批准设计实现最近 6 个月 CNY 统计，不含年度/同比报表、逐订阅占比、滑动快捷删除、支付自动导入或汇率换算。
- Phase 5 备份/恢复、云同步等未开始；习惯提醒、暂停/归档等历史阶段扩展也未实现。
- 精确闹钟和通知依赖用户的系统授权；厂商后台限制、强制停止和非精确闹钟可能延迟提醒。已过去的提醒日期不补发历史通知。
- CSV 是明文个人消费数据，不包含网络上传行为；导出不等于数据库备份，不提供导入。Activity 旋转不应取消 ViewModel 中的导出，但系统杀死进程后的导出恢复不保证。
- 原有 AGP 旧 DSL/Kotlin 插件、注解目标、KAPT 选项、协程 opt-in 和 `TodoTagCrossRef.tagId` 索引警告仍存在；本 Phase 不重构无关模块。
- 所有业务记录与提醒选择存于 Room；AlarmManager 身份缓存只保存取消闹钟所需的调度元数据，不是第二份业务数据库。离线应用无需网络，首次构建依赖下载仍需要网络。
