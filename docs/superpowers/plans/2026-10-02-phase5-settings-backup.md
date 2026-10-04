# Phase 5 实施计划

依据：已确认的 `../specs/2026-10-02-phase5-settings-backup-design.md`。先完成 Phase 5 验证、提交和远端核对，再开始卡通动感 UI。

`writing-plans` 技能不可用；Buddy 的项目内跟踪脚本也未安装。本计划作为可持续恢复的任务记录，沿用现有 Gradle/TDD 验证，不为流程新增依赖、Linear 工单、PR 或不相关文档。

## 必须成立的结果与连接

- 设置持久化并真正影响展示及新建订阅：`SettingsScreen -> SettingsViewModel -> SettingsRepository -> Room`；根主题及日期展示订阅设置 Flow。
- 备份包含十张业务表及设置：`BackupUseCase -> BackupRepository -> snapshot DAO / codec / file adapter`。
- 保护备份成功且二次确认后才替换，数据库失败原数据不变：`SettingsViewModel -> maintenance session -> Room transaction`。
- 旧草稿、异步操作和广播不能作用于恢复后的同 ID 数据：四模块、导航、Receiver、Worker 均接入本机世代和维护协调。
- 设置关闭的提醒不会被重启或后台校准重新开启：提醒 Use Case、Scheduler 和投递前数据库校验均读取设置。
- 完成报告包含可复现的验证、未完成和限制；不以存在文件或编译测试 APK 代替可运行验收。

## 顺序与文件级任务

1. **设置及非破坏性迁移**
   - 先写 `data/local/SettingsMigrationTest`，验证新 schema、单行默认设置、十表旧数据及维护世代。
   - 新增 `domain/model/AppSettings`、`domain/repository/SettingsRepository`、`data/local/entity/AppSettingsEntity`、`MaintenanceEntity`、`dao/SettingsDao`、`data/repository/SettingsRepositoryImpl`。
   - 修改 `LifeManagerDatabase` 增加 v5 实体和 `4 -> 5` migration；修改 `AppModule` 绑定仓库；生成 v5 schema。
   - 验证：针对迁移和仓库的 Robolectric 测试，及全量 JVM 回归。

2. **设置页面和真实消费者**
   - 先写设置 ViewModel 的读写失败、读取重试、快速重复提交和持久化测试；新建订阅默认偏好及保留已有设置的测试。
   - 新增 `ui/settings/SettingsUiState`、`SettingsViewModel`、日期格式适配；修改设置页、根 Activity/主题/导航及四模块日期显示。
   - 修改订阅新建编辑器读取默认币种和提醒组合；现有编辑器不被默认值覆盖，读取失败阻止静默保存。
   - 不展示尚未接线的提醒开关、备份按钮或习惯提醒占位功能。
   - 验证：设置和订阅 ViewModel、Compose 行为及 debug APK 构建。

3. **版本化完整快照和 JSON 校验**
   - 先写完整数据往返、历史记录保留、Long 精度、重复/孤儿/父子环、未知版本和大小限制测试。
   - 新增 `domain/backup` 模型和验证器、`data/backup` DTO/codec、快照 DAO/仓库。
   - 快照事务读取全部字段；不导出系统缓存、权限、本机世代或编辑草稿。
   - 验证：codec 单元测试及真实 Room 十表完整往返。

4. **维护会话和世代保护**
   - 先写维护拒绝新操作、等待已有写入、取消不推进世代、旧结果失效、恢复后 ID 复用测试。
   - 新增维护协调契约/实现，修改四模块写路径及 ViewModel、导航状态、BootReceiver/Worker/Receiver；统一锁顺序。
   - 不持锁等待文件选择或人机确认；维护中广播快速结束，结束时重新校准。
   - 验证：并发单元测试、现有通知草稿与 CRUD 回归。

5. **文件、恢复和清空闭环**
   - 先写来源校验、保护备份失败、相同 URI 拒绝、二次确认取消、插入失败回滚、进程重启边界及提醒失败分离测试。
   - 新增 Android 文件流适配、导出/预览/恢复/清空 Use Case，接入设置 ViewModel 和系统文件选择器。
   - 实现单事务完整替换及业务清空；恢复设置但不恢复本机世代，清空保留设置。
   - 显示真实进度、明文隐私警告和可理解的错误；成功备份保留在用户选择的文件，不进 Git。
   - 验证：Room 事务测试、Use Case/Compose 测试、模拟器文件选择器关键交互。

6. **提醒偏好和恢复校准**
   - 先写开关关闭/开启、Receiver 投递前设置和世代检查、后台/重启不绕过开关测试。
   - 修改三类 Scheduler、Receiver、BootReceiver、Worker 和通知帮助类；日程补足恢复所需校验和权限撤销兜底。
   - 只有真实接线后才展示三类开关。习惯提醒依然明确未实现。
   - 验证：通知回归、权限被拒/被撤销及恢复后提醒重建设备验收。

7. **深色、无障碍、性能和关于**
   - 基于界面测试/测量定位问题，调整各页面语义、对比度、点击区域、滚动和必要的重复计算。
   - 关于信息读取真实 BuildConfig 并核实项目和依赖许可证，不编造开源声明。
   - 记录启动及页面性能、APK 体积；模拟器结果注明不能替代真机发布测量。
   - 验证：大字体/深浅色 Compose 和设备检查、lint、实际测量。

8. **最终验收与交付**
   - 更新 README、`docs/phase5-acceptance.md`、schema README；核对原需求及已确认范围。
   - 执行 JVM 测试、debug APK、测试 APK、lint、可用设备仪器测试；审查未接线代码和个人数据。
   - 仅提交实际修改文件；Phase 5 完成后推送并核对远端提交。未能推送时先解决交付阻碍，不抢先开始 UI。

## 命令

PowerShell：`JAVA_HOME=D:\jdk`；使用项目现有 `.tools/gradle-9.4.1/bin/gradle.bat`。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon :app:testDebugUnitTest --rerun
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon :app:connectedDebugAndroidTest
git diff --check
```

## 风险及撤回策略

- 数据量/文件提供方失败：64 MiB、100,000 行限制，输出后重新读取校验；失败拒绝恢复。
- 并发与系统广播：本机世代持久化、统一锁顺序、维护失败后重新校准；不在广播生命周期内等待用户确认。
- 数据写入与闹钟非原子：分别呈现结果，数据成功不因闹钟失败被假称为失败或回滚。
- 旧版本升级：显式迁移、迁移测试，不能删除真实数据库解决问题。
- 开发撤回只处理本阶段源码提交并保留用户改动；上线后不自动降级 v5 数据库、不破坏性迁移。用户数据恢复只能通过已验证的保护备份和明确确认。

## 当前进度

- 设计：已确认。
- 基线：205 个 JVM 测试实际重跑通过（26 套件，0 失败/错误）。
- 任务 1：v5 设置与维护元数据表、显式迁移、仓库已实现；十张业务表保持不变。设置读写和关闭/重开持久化已有回归；设备迁移链已验证。Worker 首次建库初始化已在任务 4 底座批统一到数据库工厂并补测。
- 任务 2：根主题、四模块日期展示、默认币种/订阅提醒组合及新建订阅消费者已接线。单个提醒天数在事务内增删，避免延迟观察覆盖已有组合；失败读取错误独立保留。
- 设备验证揭示的旧问题：序列化库运行时版本混用、ActivityScenario 测试 Intent 标识/flags、冷启动通知导航早于导航图初始化。已定向修正并补测；247 项 JVM、22 项设备测试、debug/test APK 和 lint 实际执行通过（lint 0 error / 45 warning）。
- 任务 2 验证通过并保存为本地提交 `4e3c6ed`；完整结果见 `../../phase5-acceptance.md`。
- 任务 3：十表/75 字段完整快照、JSON 格式 v1、严格校验和 Hilt 绑定已实现，文件规范见 `../../backup-format-v1.md`。审查指出原始设置位掩码缩窄与大文本分配风险，已先复现再修正且复审通过。最终全量 274 JVM / 23 设备测试、debug/test APK、lint（0 error / 46 warning）通过。尚未对 UI 开放文件操作；此为快照到 JSON 的完整字段往返，不是数据库替换恢复。
- 任务 4：2026-10-03 完成维护协调器、原子世代仓库及统一数据库创建底座；27 项新增 JVM / 2 项新增设备测试。独立审查复现并修正取消交付及替换 Job 的会话泄漏，最终复审通过，全量 301 JVM / 25 设备 / APK / lint（0 error / 46 warning）通过。当前仅底座，四模块/导航/后台消费者尚未接线，任务 4 尚未整体完成。接线契约见 `../../maintenance-coordination.md`，完整结果见验收报告。
- 任务 4 消费者第一批：打卡与设置真实写入、快照、异步发布及渲染回调已接入维护许可/世代；取消维护保留草稿，更换数据使旧回调失效。独立审查的观察恢复、订阅外初始化及旧错误回退竞态，均先复现后修正并复审通过。本批共 31 项新增 JVM / 2 项新增设备回归；最终全量 332 JVM / 27 设备、APK 和 lint（0 error / 46 warning）通过。待办、日程、订阅、导航、根设置观察及后台仍待接线，任务 4 仍未整体完成；审查结论及最终交付记录见验收报告。
- 任务 4 消费者第二批（第五批交付）：待办、日程、订阅 ViewModel 的真实写入、Main 快照/结果发布、编辑/详情/扣费/冲突及渲染回调接入许可/原世代；冷启动通知一次性捕获，CSV 选择关联保存原世代和请求 ID。设置有限读取/更新也停止补建默认行，只由新建/迁移初始化。根主题/日期已消费受保护设置 ViewModel，本批只核对现有路径。定向 RED/GREEN、全量和设备证据见验收报告；Activity 导航事件、Receiver/Worker 与启动/退出提醒校准仍待接线，任务 4 尚未整体完成。
- 任务 5–8：尚未完成。下一步完成导航/后台维护准入，然后文件导出、保护备份、完整替换恢复及清空闭环。Phase 5 未完整验收，未作为完成阶段推送，卡通 UI 未开始。
- 第五批最终验收：独立审查复现并修正待办旧保存清理解锁新草稿的重复插入竞态，复审通过；最终全量 425 JVM / 30 设备、debug/test APK 与 lint（0 error / 46 warning）通过。schema 不变，完整证据见验收报告；局部消费者验收不替代真实文件恢复及后台接线。
- 任务 4 消费者第三批（第六批，2026-10-04 局部验收通过）：Activity 导航到达原世代、实际 Main 导航许可及业务交接，三类 Receiver 到达/IO 准入和全生命周期 8 秒界限，应用共享仓库/协调器 Worker 的重试、启动及 Boot 当前数据校准已接线。独立审查复现并修正通知与迟到观察、RUNNING 校准丢新请求的竞态；设备装置等待真实启用状态。最后完整五任务 530 JVM / 32 设备、APK 及 lint（0 error / 46 warning）通过，复审条件实际满足；完整 RED/GREEN 及限制见验收报告，任务 4/Phase 5 尚未整体完成。
- 第六批不包含 Task 6 的闹钟/通知 Intent 载荷世代、日程完整资格与开关校验，也不包含 Task 5 的维护成功/取消/失败退出校准闭环。旧广播仅在替换后到达仍可能被标记为到达时世代；恢复/清空入口保持关闭。Phase 5 未完整验收，未推送为完成阶段，UI 尚未开始。
- 任务 5 第一个有界子批（第七批，局部验收通过）：实现 Android 文件流、受限私有临时输入、导出关闭后重读 / 文档全量比对，以及只读预览计数；通过 Hilt 提供真实底层给后续设置消费者。独立审查的并发目录误拒绝先经行为 RED 再修正，补齐真实清理失败分支；最终 --rerun-tasks 强制完整五任务图 569 JVM / 34 设备、APK 和 lint（0 error / 28 warning）通过，限定复审通过。尚无保护备份 / 确认状态机 / 数据库替换或清空 / 文件选择 UI；详细边界见 `../../backup-file-safety.md`，完整证据见验收报告。Task 4–8 仍未整体完成，入口不提前开放，不推送为完成阶段，不开始 UI。
