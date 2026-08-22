# Phase 3 打卡模块设计

## 目标与范围

本 Phase 在现有 Kotlin + Jetpack Compose Android 应用中交付离线可用的打卡模块。模块采用既有的 MVVM + Clean Architecture 分层、共享现有 Room 数据库，并且只在底部导航中增加已实现的“打卡”入口。

本 Phase 的功能范围为：

- 习惯的创建、查看、编辑和删除；
- 每日打卡记录与按月浏览的打卡日历；
- 连续打卡、当前周期进度和按习惯查看的年度热力图；
- 每个功能状态均由 `StateFlow`/`Flow` 驱动；
- 单元测试、仪器测试、结构检查、README 和验收报告。

提醒、暂停、归档以及订阅模块均不在本 Phase 实现；不创建它们的实体字段、页面、导航入口或示例数据。后续 Phase 如需这些能力，将以新的非破坏性数据库迁移增加。

## 技术约束

- 语言和 UI：Kotlin、Jetpack Compose、Material Design 3，并沿用应用的亮色/深色主题。
- 数据：Room 本地持久化；不依赖网络，不使用 destructive migration。
- 架构：`domain` 放置模型、规则、仓库接口与用例；`data` 放置 Room 实体、DAO、仓库实现；`ui/habit` 放置 Compose 页面、状态和 ViewModel。
- 兼容性：保持现有 Gradle、AGP、JDK 25 运行环境和 Java/Kotlin 17 字节码目标；不为本模块新增第三方日历或图表依赖。

## 数据模型与迁移

数据库由版本 2 升级到版本 3，并添加一条仅创建新表和索引的 `Migration(2, 3)`。既有待办、标签、日程与例外数据不会被修改、删除或重建。

### Habit

`Habit` 是领域模型；`HabitEntity` 为对应 Room 实体。字段为：

- `id`：主键；
- `name`：非空习惯名称；
- `iconKey`、`colorKey`：UI 选择的稳定标识；
- `frequencyType`：`DAILY`、`WEEKLY`、`MONTHLY` 或 `CUSTOM`；
- `frequencyValue`：`WEEKLY`/`MONTHLY` 的周期目标次数；日常固定为 1；自定义频率保存已选星期数；
- `customDaysOfWeek`：仅 `CUSTOM` 使用，以 ISO 星期值的逗号分隔文本保存；
- `startDate`、`note`、`createdAt`、`updatedAt`。

不在本版本保存提醒时间、暂停截止日或归档状态，避免提前建立未验收功能的持久化契约。

### HabitRecord

`HabitRecord`/`HabitRecordEntity` 仅表示某个习惯在某个本地日期已完成。记录包含 `id`、`habitId`、`date`、`createdAt`。`habitId` 外键引用 `habits` 并启用级联删除；`(habitId, date)` 唯一索引保证同一习惯每天最多一条记录。DAO 在事务中切换记录，重复点击不会产生重复数据。

## 频率、完成与连续规则

所有规则集中在纯 Kotlin 的 `HabitRules` 中，供用例、ViewModel 和测试共同使用。

| 频率 | 记录可用日期 | 周期完成定义 | 连续打卡定义 |
| --- | --- | --- | --- |
| `DAILY` | 开始日期及之后每天 | 当天有记录 | 从今天向前的连续自然日都有记录 |
| `CUSTOM` | 开始日期及之后、星期在已选集合中 | 每个应打卡日有记录 | 从最近应打卡日向前，连续应打卡日都有记录 |
| `WEEKLY` | 开始日期及之后每天 | 自然周内记录数达到目标 N | 连续自然周都达到目标 N |
| `MONTHLY` | 开始日期及之后每天 | 自然月内记录数达到目标 N | 连续自然月都达到目标 N |

`CUSTOM` 至少选择一个星期；`WEEKLY` 目标限制为 1–7，`MONTHLY` 为 1–31。统计不创建派生表：范围内记录以 Flow 读取后实时计算。这样频率改动不会遗留预生成任务，也不会篡改历史记录。

## UI 与交互

底部导航新增一个“打卡”项目，其余可见入口维持已验收的待办与日程；订阅不显示。

打卡模块内部用 Material 3 标签切换“任务”和“统计”：

- 任务页显示当前习惯列表、当前日期的快捷打卡/撤销、周/月目标进度和新增按钮；
- 点击习惯打开编辑/详情界面，支持名称、图标、颜色、频率、目标次数、自定义星期、开始日期、备注和删除；
- 统计页可选择一个习惯，展示当前月打卡日历、当前连续打卡、最长连续打卡、当前周期进度和该习惯最近 53 周的热力图。

日历与热力图均使用 Compose 的确定性网格绘制，不需要网络或第三方依赖。完成状态不仅通过颜色表达，也有文本和 content description，适配深色主题与辅助功能。

## 状态与数据流

`HabitDao` 暴露习惯列表及日期范围记录的 `Flow`。`HabitRepository` 是 domain 层接口的实现，负责实体映射、保存、删除和事务式切换记录。用例为读写操作提供清晰的 ViewModel 边界。

`HabitViewModel` 用 `StateFlow` 合并习惯流、可见月份记录流、当前选中日期、当前统计习惯与编辑草稿，生成单一 `HabitUiState`。保存、删除、打卡、撤销和月份切换都是 ViewModel 事件；Composable 不直接访问 DAO 或数据库。Room 的 Flow 推送变更后，列表、日历和统计自动同步刷新。

无效表单不写入数据库，并通过 UI 状态显示具体校验错误。找不到已选择的习惯时，统计页选择第一个可用习惯；没有习惯时显示真实的空状态和创建入口，而不是假数据。

## 测试与验收

- `HabitRulesTest` 覆盖各频率的可打卡日、周期完成、连续计算和表单校验边界；
- `HabitViewModelTest` 覆盖保存、切换打卡、撤销、重复打卡保护和状态刷新；
- Room 仪器测试覆盖唯一记录约束、删除级联和 v2→v3 迁移保留既有表；
- `tools/check-phase3-structure.ps1` 检查分层文件、Room v3 迁移、已接入的打卡导航，以及不存在订阅占位实现；
- 执行相关 JVM 测试、结构检查和 `:app:assembleDebug`；仪器测试仅在有可用 Android 设备/模拟器时运行，设备不可用会在验收报告中明确列为限制。

README 将更新为已完成的 Phase 1–3 状态；`docs/phase3-acceptance.md` 将逐项记录需求、验证命令、结果、未完成项和已知限制。Phase 3 验收完成后提交 Git 并尝试推送至既有 `origin/main`。

## 非目标与后续边界

- 不实现提醒通知、重复提醒、暂停、归档或恢复；
- 不实现订阅的任何 UI、导航、实体或样例；
- 不创建预生成的每日任务表，也不保存可从记录导出的统计快照；
- 不对已有数据库执行删除、重建或 destructive migration。
