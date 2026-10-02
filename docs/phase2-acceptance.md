# Phase 2 验收记录：日程安排

> 下述验证为 Phase 2 初始实现时的历史记录，不代表当前代码全部验收。2026-10-02 第三批日程完整性修复及最新验证见 [第三批验收记录](2026-10-02-schedule-integrity-fixes.md)。当前已进入 Phase 4，结构检查使用 `tools/check-phase4-structure.ps1`；旧 Phase 2 脚本不作为当前全仓验收门槛。

## 已实现

- 共享 Room 数据库的 `Schedule` 与 `ScheduleException` 数据表，以及显式、非破坏性的 v1→v2 migration。
- 日程创建、编辑、删除；全天/定时、地点、参与者、备注、颜色、单个提前提醒与重复规则。
- Compose Material 3 月、周、日视图；月视图日期事件标记、日期切换日视图、FAB 与详情/编辑对话框。
- 重复日程按可见时间窗口展开，不预生成无限实例；保存前检测时间重叠，要求用户确认后才保存。
- AlarmManager 日程提醒、设备重启/每日 Worker 补排程、通知点击进入对应日程。
- `ScheduleRulesTest`、`ScheduleViewModelTest`、`ScheduleDaoTest` 与 Phase 2 结构检查脚本。

## 已执行验证

- `powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase2-structure.ps1`：通过。
- `:app:assembleDebug`：通过，生成 `app/build/outputs/apk/debug/app-debug.apk`。
- `ScheduleRulesTest`：通过。
- `ScheduleViewModelTest`：2/2 通过。
- `TodoViewModelTest` 的空标题、保存新待办测试：各 1/1 通过。

## 验证命令

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase2-structure.ps1
.\.tools\gradle-9.4.1\bin\gradle.bat :app:testDebugUnitTest --tests "*ScheduleRulesTest"
.\.tools\gradle-9.4.1\bin\gradle.bat :app:assembleDebug
```

## 已知限制

- 拖拽改期与双指缩放未实现；用户可通过月/周/日切换和编辑器改期完成同等数据操作。
- 单次发生的取消例外已具备数据库与领域模型支持，当前编辑器未提供“仅取消本次重复日程”的入口。
- 真实设备上的通知权限、精确闹钟授权与通知触发仍需在设备上验收。
- `ScheduleDaoTest` 是 Android 仪器测试；当前没有已连接的模拟器/设备，尚未执行。
- 本机 Gradle 在一次运行中传入多个带空格的测试方法过滤条件时会停滞；单个方法过滤可稳定完成。未将该行为误记为整套单元测试已通过。

第三批已修复日程编辑导致例外级联丢失、元信息/隐藏重复参数丢失、无效提醒静默关闭、重复保存/副作用错误以及通知覆盖草稿；全天/重复提醒策略、重复冲突窗口、小时轴、自定义重复 UI 和例外展示仍待后续处理。已有数据库例外不再因编辑丢失，不等于这些例外已经参与日历和提醒计算。
