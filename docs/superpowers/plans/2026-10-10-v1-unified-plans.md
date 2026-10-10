# V1 统一计划实施计划

日期：2026-10-10。依据：已获用户全文确认的 [设计](../specs/2026-10-10-v1-unified-plans-design.md)。
分支：`feat/v1-unified-plans`，用户选择在当前干净分支开发，不创建工作树。

`writing-plans` 技能不可用，使用文件级任务、明确依赖与验收记录替代。Buddy 默认的新分支 / PR / 网页验收流程不直接套用原生 Android；不新增 Linear、网页依赖或流程文档。沿用现有 Gradle、TDD、独立审查及 Android 设备验收。总体路线不是一次可交付的单任务，每批在执行前细化并检查覆盖；当前执行 B1（A 已完成）。

## 目标与关键连接

| 必须成立的用户结果 | 实现与连接 | 验收 |
| --- | --- | --- |
| 待办加日期成为日程，身份和内容不变，两类可完成/撤销 | Plan 编辑 / 完成用例 → PlanRepository → 同一 plans 行 | 规则、Room、Compose 测试 |
| 旧待办/日程、标签、父子、例外无损升级，打卡/订阅不变 | 共享 LegacyPlanConverter → 5→6 Migration / v1 备份转换 → v6 验证 | 字段比对、完整迁移链、故障回滚 |
| 日期不是假区间，长期目标不挤入今日，重复完成结束系列 | PlanTimeRules / PlanOccurrences → 列表、日历、冲突、统计、提醒 | 确定性时区 / DST / 边界测试 |
| v1/v2 备份严格验证再恢复，旧令牌不能写新 ID | 版本分派 → 校验 → 转换 → 保护备份 / 维护事务 → 世代 | codec、真实 Room / 文件 / 旧草稿回归 |
| 完成或关提醒后不会继续投递，也不会与旧广播重号 | PlanReminderScheduler / Receiver → 当前仓库、设置、世代、例外 | 延迟广播、升级校准、权限设备测试 |
| 四入口均为实装功能，可离线运行并保留草稿 | Hilt 单数据库 → 三个 Plan VM → Compose / 导航 / Settings | JVM、设备 CRUD / 导航 / 适配 |

## 激活关口

旧 DAO、旧备份十表契约、旧 Receiver 和页面仍共同依赖 v5。A–D 可以开发底层并在独立测试装置验证，不提前改变生产数据库版本、不提前开放计划入口。未接入生产的构件明确标注为底座，不宣称用户已可使用。

E 是原子切换批：生产 Room v6、Hilt、统一仓库消费者、备份/维护、提醒、导航与设置一起切换；移除正常业务路径中的旧实现，仅保留旧文件 / 旧 Intent 兼容。若规模超出单批，先拆 E 的可编译准备提交，激活提交必须同时满足全部连接，不能留下双表写入、空 Tab 或不可运行 APK。

版本 1.0.0 / code 3 仅在统一功能接通并验收后修改。A–D 不安装到个人设备，不删除旧表、不清除现有数据。源码撤回不等于设备数据库可降级。

## A · 统一领域规则（本轮）

四个顺序任务，限于五个 Kotlin 文件及文档，不修改 Room / Hilt / UI / APK 安装身份。
下文 Kotlin 生产路径相对 `app/src/main/java/com/example/lifemanager/`；测试相对 `app/src/test/java/com/example/lifemanager/`，设备测试相对 `app/src/androidTest/java/com/example/lifemanager/`。

1. **模型和时间分类**
   - 新建 `domain/plan/Plan.kt`：统一身份、内容、四种时间模式、完成、重复、提醒、关联字段；领域不依赖 Room/Compose/Data。
   - 新建 `domain/plan/PlanRules.kt` 及 `test/.../domain/plan/PlanRulesTest.kt`：先测试 NONE/DEADLINE/TIMED/ALL_DAY 分类和有效时间校验；inactive 字段保留，不凭它们分类。明确重复在 NONE / DEADLINE 不生效。
   - 验收：缺少日期、两个截止值、逆序区间、同日全天、非法时区、非活动参数行为。
2. **完成和移除日期**，依赖 A1
   - 同一规则文件实现完成幂等、撤销清时间；移除日期只改模式 / updatedAt，保留原参数；添加日期保留 ID / 内容 / 完成 / 审计。
   - 同一测试文件覆盖重复系列参数和例外不被清除、重复点击不改完成时刻、编辑不撤销完成。确认 UI 属于 E，不把领域函数当作已完成交互。
3. **全库统计**，依赖 A1–2
   - 新建 `domain/plan/PlanStatistics.kt`、`test/.../domain/plan/PlanStatisticsTest.kt`。
   - 全库分母不套当前列表筛选；重复计一条；今日完成按 injected now / displayZone，不要求有截止日期。
   - 逾期仅未完成、非重复、有效日期：精确截止/区间按 Instant，日期截止/全天按计划时区日界，NONE 不逾期。今天日程展开在 C，不在 A 伪算。
   - 验收：空集有限零值、混合状态与重复系列只计一条、无日期完成 / 展示时区跨午夜、精确 Instant 相等和刚过、仅日期 / 全天闭区间与计划时区 / DST，以及无效记录不能产生成功统计。
4. **验证与记录**，依赖 A1–3
   - 定向真实 RED/GREEN，全量 JVM `--rerun`，Debug 构建与 lint；独立规则审查，修复后再复验。
   - 修改 README，新增 `docs/v1-unified-plans-acceptance.md`，记录实际数量、底座未接线 / 迁移未执行等限制。
   - 只提交本批文件，提交前 `git diff --check`；不把旧 57 项设备结果写成本轮验收。

## B · 无损转换、v6 数据实现与迁移装置（依赖 A）

拆为字段转换、存储事务、迁移验证三子批；本批开始时补充逐文件子计划。

### B1 · 第二批的字段与身份底座（2026-10-10）

1. 新建 `data/plan/LegacyPlanRows.kt` 定义统一原始存储行、来源映射、转换输出、标签/例外行与旧提醒偏好；输入直接使用现有旧实体的原始字段，不经领域集合归一化。Migration Cursor 与严格已验证的旧备份都可构造相同旧输入。
2. 先写 `test/.../data/plan/LegacyPlanConverterTest.kt`，再新增 `LegacyPlanConverter.kt`：传入新主键，不做旧 ID 加偏移；旧待办 parent 先 null，独立保存 parent 来源键；逐值保留标题/备注/Long/空值/重复星期字符串，初始化新增字段和旧四组合提醒资格。不是全库迁移或备份解析器。
3. 先写 `LegacyPlanReferencesTest.kt`，再新增 `LegacyPlanReferences.kt`：以 `(source, legacyId)` 解析父/标签/例外；纯 lookup 契约供 SQL 实现，内存映射供受限文件转换。拒绝映射重复、目标 ID 复用、找不到父或关系，不假定父 ID 小于子。全库环 / 标签外键检查留 B2 的事务装置，不能用局部 mapper 声称全库安全。
4. 按用户已确认的历史值兼容策略，先补领域读取/统计测试，再拆 `PlanRules` 的存量读取与新写入校验，统计使用读取校验。旧固定偏移时区、空白标题保留；新写入仍严格，不放宽坏时区或坏有效区间。
5. 定向真实 RED/GREEN、完整 JVM `--rerun`、Debug / lint、独立审查及最终复验；同步 CHANGELOG / README / 验收、提交并按本次推送授权交付当前功能分支。不改生产 schema、Hilt、UI、APK 或用户数据。

B1 对应设计 3/4/7 的共享转换规则与用户历史兼容补充。未包含 B2 存储事务和 B3 SQL 迁移链/故障回滚/设备升级，不能将 B1 验收当作 v6 已生效。

- 新建 `data/plan/LegacyPlanConverter.kt`：原始字段 DTO / 纯映射共享给 SQL Migration 和旧备份；不经旧 ViewModel，不将原重复星期 String 重编码损失表示。ZoneId 与旧提醒偏好由调用方注入。
- 新建 `data/plan` 下 PlanEntity、PlanTagCrossRef、PlanExceptionEntity、PlanLegacyRefEntity、PlanDao、PlanRepositoryImpl；新建 `domain/plan/PlanRepository.kt`。事务内 CRUD / parent detach / 防旧草稿复活，Flow 真实读错误向上传递。
- 新建 `data/local/migration/PlanMigration.kt`：逐行顺序 ID 分配、来源映射、二次 parent / tag / exception 重映射、源字段 / 行数 / FK / 环检查、设置合并和世代推进。先完整校验后 drop 旧表；失败回滚。
- 修改 Converters；生产 `LifeManagerDatabase`、工厂 / DI 的激活留给 E。测试专用 v6 Room 配置引用同一实体 / Migration，不能另写一套简化迁移测试冒充生产实现。
- 测试 `LegacyPlanConverterTest`、`PlanRepositoryRoomTest`、`PlanMigrationTest` / `PlanMigrationDeviceTest`：同 ID、Long 极值、父 ID 大于子、标签/例外、全天/跨区、原文本/完成、设置四组合、缺失/损坏设置与世代、插入故障、打卡/订阅逐值不变。历史 1–5 链与新安装 v6 均验证。

## C · 发生、冲突与提醒资格（依赖 A/B 契约）

- 新建 `domain/plan/PlanOccurrences.kt` / `PlanUseCases.kt`：日期截止点、真实区间、全天闭区间、窗口有界展开、例外过滤、系列完成单标记。共用计算供日历 / 冲突 / 提醒，不能复制不一致重复算法。
- 新建 `notification/PlanReminderSchedulerContract.kt`、`PlanReminderScheduler.kt`、`PlanReminderReceiver.kt`、资格 / identity 实现；规划字段和协议，不提前注册未接线 Receiver。
- 先新增隔离可测试的统一校准与旧身份清理实现，不更换当前 v5 的 Hilt / Worker / Receiver 调用路径。准备 ReminderReconciler / BootReceiver / Worker / Inventory / Notifications / AndroidMaintenanceReminderEffects 的接线变更；实际替换与旧 Receiver 协议拒绝只在 E 激活提交执行。C 结束时旧应用提醒仍正常运行，清理失败重试和不发双份的新版行为在独立装置验证。
- 测试发生规则、DST / date-only 09:00 / 全天、custom / leap / 月末、未来 trigger 而非仅未来 occurrence、完成/撤销、延迟广播、权限撤销、旧 ID / URI / 世代、旧通知安全落首页与新通知一次消费。
- reminder 数据失败明确区分“已保存但提醒失败”，不重插记录；全局开关不会重开单项关闭配置。

## D · v2 备份与维护适配（依赖 B/C 字段与身份）

- 保留当前 `domain/backup/BackupSchema.kt` 的 v1 常量与现有 codec 消费路径；新增隔离的 V2BackupSchema / 验证器 / codec / LegacyBackupConverter，准备 BackupDocument / validator / mapping / canonical codec 的版本化接线。v1 先校验原 hash / 字段 / 类型 / 关系再转换，v2 单独严格验证，不放宽未知键。默认 codec / 导出版本只有 E 才切换。
- 新增隔离的 v6 snapshot / backup repository / mutation 实现并在测试专用 v6 数据库验证，不在 D 替换当前 BackupSnapshotDao、BackupRepositoryImpl、BackupMutationRepositoryImpl 的生产调用与 Hilt 绑定；BackupMaintenanceWorkflow 的新版表 / 来源 / 设置 / 提醒接线也只在 E 激活。
- 准备设置备份预览 / 计数与旧待办无时区说明，E 才更换生产消费者；保持保护备份、最终独立确认、原子完整替换、清空保留设置、本机世代不导入和所有失败/取消边界。
- 新建 v2 格式说明，保留 v1 历史文档；测试两版本往返 / 恢复、未知版本/hash/关系/环/Long 精度、转换增行后 100k 上限、64MiB、来源与保护文件、事务故障和旧草稿 / 文件回调 / 通知失效。
- 设备文件测试只在项目专用合成库上运行。

## E · 页面实现及统一生产切换（依赖 B/C/D）

准备子批分别完成列表、编辑/详情、日历/统计和导航集成，每子批 ≤5 个任务，生产切换最后一次完成。

- 新建 `ui/plan/PlanViewModel.kt` / PlanUiState / PlanScreen / PlanList，`PlanEditorViewModel.kt` / editor state / PlanEditor / PlanDetail，`PlanStatisticsViewModel.kt` / PlanStatisticsScreen，日历分拆组件。StateFlow、维护准入和原世代结果发布沿用 GenerationAccess。
- 共用新增/编辑，时间模式与隐藏参数保留、移除重复区间明确确认；长文本、保存错误 / 提醒错误分开，同一草稿不可重复创建。分类转换保留筛选并提示去向，不隐式退出未保存页。
- 列表分类 / 状态 / 日期 / 标签 / 优先级 / 搜索 / 稳定排序；统计独立全库观察。日历只显示真实窗口，重复完成只一完成标记；周日首批为按日列表，不声称小时拖拽时间轴。
- 修改 `MainActivity.kt`、`ui/navigation/TopLevelNavigation.kt` / 新 PlanNavigationViewModel、AppModule / DatabaseModule / database factory，设置模型 / Entity / DAO / repository / SettingsViewModel / SettingsScreen、Manifest。四入口计划 / 打卡 / 订阅 / 设置。
- 生产数据库切到 v6、注册迁移；生成并检查 `app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/6.json`。旧 Todo/Schedule 正常 DAO/仓库/VM/页面移除，保留兼容 DTO/枚举或显式重命名通用枚举，不留两条写路径。
- 同批更新全部旧数据库装置、备份装置、导航 / 提醒 / 设置测试：用真实 v6 路径测试旧契约的迁移兼容，不为保留旧测试而保留旧业务系统。
- 新 JVM / Compose / AndroidTest 覆盖统一闭环、维护拒绝/恢复、旋转 / 切页 / 通知草稿、冲突确认过期、无假数据、320dp / 200% 字体 / 浅深色 / 读屏；打卡 / 订阅回归不省略。

## F · 升级、版本及交付（依赖 E 全部接线）

- 项目专用无个人数据设备安装已发布旧 APK 并加入合成完整十表数据，保留旧备份；覆盖安装新 Debug 同签名 APK，逐值核对转换、重启 / 提醒校准与 v1 文件恢复，再执行 v2 导出 / 恢复。connectedAndroidTest 会卸载，只在独立测试 AVD 操作，不拿个人设备升级样本跑卸载测试。
- 修改 `app/build.gradle.kts` 为 1.0.0 / 3 后重跑 JVM / instrumentation / Debug / androidTest / Release / lint，检查签名、体积和冷启动 / 切页测量；模拟器不能替代真机和稳定 60fps 证明。
- README、schema README、v1 验收和格式说明同步：实际数量、已完成 / 未完成 / 已知限制，不混用历史证据。
- 独立最终审查，检查依赖方向、未接线构件、secret / 用户数据 / 被误删功能。
- 按用户阶段交付授权提交 / 推送功能分支并核对远端；不自动合并 main、改写远端历史、覆盖已有 Release 或正式签名。发布新 APK需同签名策略和单独交付说明，历史预览保持不变。

## 命令与证据

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --tests 'com.example.lifemanager.domain.plan.*' --rerun --console=plain
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain
# 完整接线后，先核对指定串号为项目专用 AVD，再执行设备测试
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest --rerun :app:assembleRelease --console=plain
git diff --check
```

测试结果从实际 XML suite 求和，failure/error/skipped 单独记录；BUILD SUCCESSFUL 不能替代运行数量。UP-TO-DATE 构建 / lint 说明为缓存检查，JVM 用 `--rerun` 强制重跑。第一批不运行迁移和新 UI 设备验收。

## 风险与撤回

- 非活动字段 / 原文本在 domain 集合表示中损失：迁移/备份保存 raw DTO，领域计算另行解析，禁止 trim 或清空隐藏参数。
- 中间 schema 断开旧消费者：激活关口 E；新数据装置隔离于生产 v5。
- 跨设备时区 / DST：旧待办注入 ZoneId 并说明来源；date-only 保留 LocalDate，固定 Clock 测边界。
- 超大主键 / 世代：新顺序 ID，checked increment，溢出拒绝；SQL 事务失败不修复源库。
- SQL 与 AlarmManager 非原子：世代 / 资格复核、可重试校准、保存结果分离，旧广播不可写新身份。
- 源码回退保留用户改动；已升级设备只作更高版本前向修复，可在 V1 恢复有效 v1/v2 保护备份，不安装 V0 降级，不提供破坏性 fallback。

## 进度

- 完整设计与当前分支开发方式已确认。
- 独立计划审查发现 C/D 提前替换生产消费者与 E 激活关口冲突：已将现有绑定 / 默认格式 / 旧协议拒绝明确延后至 E；A 的统计边界测试清单补充。
- A1–4 已完成领域底座局部验收：最终 703 项 JVM / 70 suites 全量重跑通过（新增 34 项），Debug 构建检查与 lint 通过（0 error / 55 warning）；计划和代码审查意见已独立复审关闭。
- B1 已完成旧字段、双来源身份、关系映射及用户批准的存量读取兼容底座；最终 729 项 JVM / 72 suites 全量重跑通过（本批新增 26 项），Debug 构建检查通过，lint 0 error / 55 warning。独立审查的非法时区测试缺口补齐并复审关闭。详见验收记录。
- 下一批 B2：统一 Room 实体 / DAO / 仓库与事务测试；B3：SQL 迁移链、回滚及设备验证。B2/B3–F 未实施，生产应用仍为 V0.1.1 / schema v5，已发布 APK 未改；没有迁移 / 页面 / 设备升级或整个 V1 已完成的含义。
