# Phase 4 订阅费用管理设计

## 目标与范围

在既有 Kotlin、Jetpack Compose、Material 3、Hilt、Room 应用中交付离线可用的订阅费用管理模块。完成后才在共享底部导航增加“订阅”入口；不创建任何 Phase 5 设置、备份恢复、汇率换算或其他未来模块的占位内容。

本 Phase 包含订阅 CRUD、实际扣费记录 CRUD、按计费周期实时预测、取消/恢复、扣费前提醒、费用统计图表和单一 CSV 导出。所有状态经 `SubscriptionViewModel` 的 `StateFlow` 暴露；UI 仅调用领域用例/仓库，不访问 DAO。

## 数据模型与迁移

Room 从 v3 以显式 `Migration(3, 4)` 升级至 v4，禁止 destructive migration。新增：

- `SubscriptionEntity`：`appName`、`amountMinor: Long`、ISO 4217 `currency`（默认 CNY）、`billingCycle`（WEEKLY/MONTHLY/QUARTERLY/YEARLY）、`nextBillingDate`、`startDate`、可选 `category`/`note`、`isActive`、可选 `cancelDate` 与创建/更新时间。
- `SubscriptionPaymentEntity`：订阅外键、实际 `amountMinor`、`currency`、`paidAt` 与可选 `note`。
- `SubscriptionReminderEntity`：订阅外键和 `daysBefore`（仅 1、3、7），以 `(subscriptionId, daysBefore)` 作为主键。

删除订阅以外键级联删除付款和提醒；取消订阅只更新活跃状态与取消日期，保留全部历史。金额持久化及运算均使用最小货币单位 `Long`，不使用 `Double`。

## 领域规则与统计

订阅名称、金额（大于零）、开始日期、下次扣费日期和周期为必填项。`nextBillingDate` 是周期锚点；使用 `LocalDate.plusWeeks/plusMonths/plusYears` 推导未来和历史月份的发生日，月底行为遵循 `LocalDate`。展示与提醒使用从锚点向前推导的有效下一次扣费日，不自动改写原锚点。

手工付款仅记录实际支出，不改写订阅或预测；因此回填历史不会改变未来账单。统计仅汇总 CNY：预计序列由活跃订阅的推导扣费构成，取消日期后的周期不计入；实际序列由所有手工付款构成，包含取消订阅的历史。两条序列并列显示、互不替代。非 CNY 数据可在列表、详情和 CSV 中使用，但不进入总额或图表。

## 界面与导航

`SubscriptionScreen` 提供“订阅”和“统计”页签。订阅页展示本月 CNY 预计/实际卡片和按有效下一次扣费日排序的列表；提供空态、错误状态、FAB 新增和常用应用名称快捷选择，但不插入示例订阅。详情/编辑页可编辑订阅、选择多个提前提醒日、取消/恢复/删除，并管理实际付款历史。

统计页使用原生 Compose Canvas 绘制最近 6 个月的预计/实际柱状图、CNY 分类占比及月付/年付对比。每个图表都有等价文字汇总和 `contentDescription`，不引入第三方图表依赖。主题继续使用现有 Material 3 深浅色体系。

## 提醒与导出

每个活跃订阅为选中的 1、3、7 天各创建一个唯一 AlarmManager PendingIntent，触发于有效扣费日前的本地 09:00。新增专用订阅提醒调度器、接收器和通知渠道；编辑、取消、恢复、删除时先取消旧提醒，再根据数据库重新安排。BootReceiver 与现有 WorkManager 补排程会重新安排活跃订阅。通知携带订阅 ID，点击后导航到其详情。通知权限或精确闹钟不可用时，UI 说明限制但核心数据照常可用。

CSV 通过系统 `CreateDocument` 输出单个 UTF-8 BOM 文件。导出前说明文件包含个人消费数据；每行以 `record_type=subscription` 或 `record_type=payment` 区分，字段正确 CSV 转义，且不依赖网络。

## 验证与交付

提供周期、金额、预计/实际统计和 CSV 转义的单元测试；DAO CRUD、级联删除及 v3→v4 迁移仪器测试；ViewModel 状态和提醒键去重测试；关键 Compose 操作测试。更新 README、Phase 4 验收记录和结构检查脚本；只能把实际运行的验证标为通过。已知限制将明确记录无设备/模拟器时未执行的仪器测试，以及既有构建警告。
