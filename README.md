# 生活管理 Android App

当前实现范围：Phase 1（待办事项核心闭环）。

已包含的目标能力：

- Kotlin + Jetpack Compose + Material 3
- MVVM + `data / domain / ui` 分层
- Room 本地数据库与待办/标签关系
- 待办创建、编辑、删除、完成/取消、优先级、标签、搜索和基础日期筛选
- 今日完成数、待完成数和完成率
- 本地截止提醒、通知权限处理和确定性提醒 ID
- 系统浅色/深色主题

暂未实现：日程、订阅、打卡，以及这些模块的页面、假数据和空白占位 Tab。

## 构建环境

- Android SDK Platform 35
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- JDK 17（当前机器只有 JDK 25，未确认兼容）

在具备 Android SDK、JDK 17 和 Gradle 后运行：

```powershell
gradle test
gradle assembleDebug
```

当前工作区缺少 `gradle`、Android SDK 和 `adb`，因此本次不能声称编译或测试已经通过。完成工具链安装后，应先运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase1-structure.ps1
gradle test
gradle assembleDebug
```

## 权限与提醒行为

- Android 13+ 需要用户授予通知权限；拒绝后仍可使用待办 CRUD。
- 目标时间前 15 分钟创建本地提醒；精确闹钟权限不可用时使用安全的非精确兜底。
- 完成或删除待办时取消对应提醒。

## 阶段状态

- Phase 1：代码与测试骨架已写入；待 Android 工具链安装后进行完整构建和设备验收。
- Phase 2-5：未开始。

## 当前已知限制

- 尚未加入子任务、批量操作和手动拖拽排序，这些属于待办模块的后续完善项。
- Room schema、Kotlin/Compose 编译、单元测试和设备提醒行为尚未在本机验证，因为当前环境没有 Gradle、Android SDK、ADB 或 JDK 17。
