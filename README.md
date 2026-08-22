# 生活管理 Android App

当前实现范围：Phase 1（待办事项核心闭环）、Phase 2（日程安排）与 Phase 3（习惯打卡）。

已包含的目标能力：

- Kotlin + Jetpack Compose + Material 3
- MVVM + `data / domain / ui` 分层
- Room 本地数据库与待办/标签关系
- 待办创建、编辑、删除、完成/取消、优先级、标签、搜索和基础日期筛选
- 今日完成数、待完成数和完成率
- 本地截止提醒、通知权限处理和确定性提醒 ID
- 系统浅色/深色主题
- 日程月/周/日视图、日程 CRUD、重复规则、冲突确认与本地提醒
- 习惯 CRUD、每日打卡/撤销、按月打卡日历、频率感知的连续统计和最近 53 周热力图

暂未实现：订阅、打卡提醒、习惯暂停和归档；没有这些功能的页面、假数据或空白占位 Tab。

## 构建环境

- Android SDK Platform 35
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- JDK 25（Gradle 运行时）；Android 字节码目标仍为 Java/Kotlin 17，以保持设备兼容性

在具备 Android SDK、JDK 25 和 Gradle 后运行：

```powershell
gradle test
gradle assembleDebug
```

本项目已在 Android SDK Platform 35、Gradle 9.4.1 与 JDK 25 环境下完成 debug APK 构建。项目使用项目内 Gradle 安装时，可运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase2-structure.ps1
.\tools\check-phase3-structure.ps1
.\.tools\gradle-9.4.1\bin\gradle.bat :app:assembleDebug
```

## 权限与提醒行为

- Android 13+ 需要用户授予通知权限；拒绝后仍可使用待办 CRUD。
- 目标时间前 15 分钟创建本地提醒；精确闹钟权限不可用时使用安全的非精确兜底。
- 完成或删除待办时取消对应提醒。

## 阶段状态

- Phase 1：待办核心闭环已实现；待设备验收通知行为。
- Phase 2：日程模块已实现、结构检查和 debug APK 构建已通过；等待仪器测试与设备提醒验收。
- Phase 3：打卡模块已实现、结构检查、规则/状态单元测试与 debug APK 构建已通过；等待 Room 仪器测试和设备 UI 验收。
- Phase 4-5：未开始。

## 当前已知限制

- 尚未加入子任务、批量操作和手动拖拽排序，这些属于待办模块的后续完善项。
- 日程不含拖拽改期、双指缩放和“仅取消某次重复日程”的编辑入口。
- 仪器测试、设备提醒行为和打卡 UI 交互仍需在真机或模拟器验证。
