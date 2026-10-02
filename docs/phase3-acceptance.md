# Phase 3 打卡模块验收记录

验收日期：2026-08-22。运行环境为 JDK 25、Gradle 9.4.1、Android SDK Platform 35；Android 字节码目标保持 Java/Kotlin 17。

> 以下为原阶段的历史实现/验证记录，不代表所有需求已完整验收。2026-10-02 复核发现编辑习惯会删除历史打卡、自定义星期最长连续统计可能卡死/错误，以及不应打卡日期仍可新增记录。这三项在第一批修复中处理，最新测试与限制见 [习惯完整性修复报告](2026-10-02-habit-integrity-fixes.md)。订阅模块已在后续 Phase 4 添加；本页的模块边界描述仅针对当时 Phase 3。

## 已完成

| 要求 | 结果 | 证据 |
| --- | --- | --- |
| Clean Architecture 与 MVVM/StateFlow | 已完成 | `domain` 规则与仓库接口、`data` Room 实现、`ui/habit` 的 `HabitViewModel` 与单一 `HabitUiState`。 |
| Room 离线存储与数据保护 | 已完成 | 新增 `habits`、`habit_records`、唯一索引、级联删除和显式 `Migration(2, 3)`；没有 destructive migration。 |
| 习惯 CRUD 与每日打卡 | 已完成 | 任务列表、编辑/删除对话框、今日打卡/撤销，以及 DAO 事务式切换。 |
| 频率规则与统计 | 已完成 | DAILY、CUSTOM、WEEKLY、MONTHLY 校验、周期进度、当前/最长连续；月历和最近 53 周热力图。 |
| Compose Material 3 与深色模式兼容 | 已完成 | 页面沿用应用主题的 Material 3 色彩；日历和热力图同时提供文字/content description。 |
| 只显示已实现模块 | 已完成 | 底部导航新增“打卡”；没有 Subscription 目标、页面或假数据。 |
| 测试、README、结构检查 | 已完成 | 规则/状态单元测试、Room 仪器测试源码、`tools/check-phase3-structure.ps1`、README 与本报告。 |

## 验证结果

| 命令/检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest --tests '*HabitRulesTest' --no-daemon` | 通过；14 个 `HabitRulesTest` 测试通过。 |
| `:app:testDebugUnitTest --tests '*HabitViewModelTest' --no-daemon` | 通过；2 个 ViewModel 行为测试通过。 |
| `:app:testDebugUnitTest --no-daemon` | 通过；33 个 JVM 单元测试通过，0 failures / 0 errors / 0 skipped，覆盖 Todo 删除回归。 |
| `:app:assembleDebug --no-daemon` | 通过；已生成 debug APK。 |
| `:app:assembleDebugAndroidTest --no-daemon` | 通过；DAO 与 v2→v3 迁移测试源码已编译到 Android 测试 APK。 |
| `adb devices` | 未发现已连接设备或模拟器。 |

## 未完成/已知限制

- `HabitDaoTest` 和 `HabitMigrationTest` 尚未在 Android 设备或模拟器上执行，因此不将它们标记为通过；测试会验证唯一记录、级联删除和旧待办数据在 v2→v3 后仍存在。
- Phase 3 不包含订阅、打卡提醒、暂停或归档；未建立对应 UI、导航、实体字段或假数据。
- 构建仍显示既有 AGP 旧 DSL、Kotlin 注解目标、KAPT 选项和 `TodoTagCrossRef.tagId` 索引警告；本 Phase 未对无关的既有模块做重构。
- 当前机器需要经本地 `127.0.0.1:7897` 代理访问 AndroidX/Google Maven；依赖已解析后可离线构建已缓存内容。
