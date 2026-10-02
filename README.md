# 生活管理 Android App

当前实现范围：Phase 1（待办事项核心闭环）、Phase 2（日程安排）、Phase 3（习惯打卡）和 Phase 4（订阅费用管理）。四个业务模块共享同一个 Room 数据库，底部导航另保留已有设置页。下述为已实现能力，不代表需求文档中的所有功能或设备验收均已完成。

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
- 订阅 CRUD、取消/恢复、独立的手工实际扣费记录、周/月/季/年周期预测
- CNY 本月预计/实际费用、最近 6 个月对比、分类占比和周期对比图（原生 Canvas，带文字汇总）
- 扣费前 1/3/7 天本地 09:00 提醒、取消/删除清理、重启及后台校准
- 隐私确认后通过系统文件选择器导出 UTF-8 BOM CSV，包含全部币种

暂未实现：Phase 5 备份/恢复等扩展、打卡提醒、习惯暂停和归档；没有这些功能的页面、假数据或空白占位 Tab。

## 构建环境

- Android SDK Platform 35
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- JDK 25（Gradle 运行时）；Android 字节码目标仍为 Java/Kotlin 17，以保持设备兼容性

在具备 Android SDK、JDK 25 和 Gradle 后运行：

```powershell
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

本项目已在 Android SDK Platform 35、Gradle 9.4.1 与 JDK 25 环境下完成 debug APK 构建。项目使用项目内 Gradle 安装时，可运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

请在 `local.properties` 配置本机 `sdk.dir`，并令 `JAVA_HOME` 指向 JDK 25。首次构建需要下载依赖；缓存齐全后可添加 `--offline`。旧 Phase 结构脚本是对应历史阶段的边界检查，当前代码使用 Phase 4 脚本。连接设备后可运行 `gradle :app:connectedDebugAndroidTest`；编译测试 APK 不等于设备测试已通过。

习惯回归测试新增 Robolectric 4.17：在主机上执行真实 Room DAO/仓库和 Compose 交互，不替代真机测试。首次运行还需下载 Robolectric Android 28 测试运行时；它默认缓存于用户目录的 `.m2/repository`，与 Gradle 依赖缓存分开。只有两类缓存均齐全时才能完整离线测试，Gradle 的 `--offline` 本身不会阻止 Robolectric 首次下载运行时。Robolectric 与新增 Compose 测试依赖仅用于测试，不加入正式 APK。

## 权限与提醒行为

- Android 13+ 需要用户授予通知权限；拒绝后仍可使用待办 CRUD。
- 目标时间前 15 分钟创建本地提醒；精确闹钟权限不可用时使用安全的非精确兜底。
- 完成或删除待办时取消对应提醒。
- 订阅提醒支持多选提前 1/3/7 天，本地时间 09:00；精确闹钟权限不可用时使用非精确兜底，可能延迟。
- 编辑订阅不改变取消/恢复状态；取消保留实际扣费历史，删除会清除该订阅及关联扣费记录和提醒。
- 金额保存为最小货币单位 `Long`。统计仅汇总 CNY，其他币种可管理和导出，不做汇率换算。实际扣费须手工记录，不会更新预测锚点。
- 读取失败时禁用 CSV 导出；提醒通知到达正在编辑的页面时，会保留草稿并明确提示，保存或关闭当前表单后再打开目标详情。

## 阶段状态

- Phase 1：待办核心闭环已实现；待设备验收通知行为。
- Phase 2：日程模块已实现、结构检查和 debug APK 构建已通过；等待仪器测试与设备提醒验收。
- Phase 3：打卡模块已实现、结构检查、规则/状态单元测试与 debug APK 构建已通过；等待 Room 仪器测试和设备 UI 验收。
- Phase 4：订阅模块已实现；验证结果、已完成与待验收事项见 [Phase 4 验收记录](docs/phase4-acceptance.md)。
- Phase 5：未开始。

2026-10-02 第一批审计修复仅处理习惯数据完整性和打卡资格，见 [修复与回归验收记录](docs/2026-10-02-habit-integrity-fixes.md)。编辑习惯保留历史打卡；开始日期之前或未选星期不允许新增打卡，但已有错误记录仍可撤销。数据库版本保持 v4，无 schema 变更或破坏性迁移；旧版本已经误删的记录无法由本修复恢复。

## 当前已知限制

- 尚未加入子任务、批量操作和手动拖拽排序，这些属于待办模块的后续完善项。
- 日程不含拖拽改期、双指缩放和“仅取消某次重复日程”的编辑入口。
- 仪器测试、设备提醒行为和打卡 UI 交互仍需在真机或模拟器验证。
- 订阅的通知点击、权限切换、CSV 文件提供方行为和深浅色交互仍需设备验收；没有接入银行卡/支付自动扣费或汇率服务。
- CSV 为消费数据明文文件，请选择可信保存位置，谨慎分享；不提供 CSV 导入或备份恢复。进程被系统终止时，进行中的导出不保证恢复。
- 重新审计发现的待办/日程提醒失效、通知入口和保存一致性问题尚待后续修复；日程周/日视图尚非按小时的时间轴。完整待修清单见上述第一批报告，不能将本批通过等同于全部需求验收。
- 习惯整体今日进度、周/月打卡率和庆祝动画，订阅年度/同比及逐订阅占比，以及设置、国际化、隐私保护和发布配置等仍不完整；Gradle Wrapper、发布签名、SDK 发布要求与性能/适配验收待完善。
