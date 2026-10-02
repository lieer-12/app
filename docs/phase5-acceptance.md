# Phase 5 验收记录（进行中）

日期：2026-10-02。范围依据已确认的
`superpowers/specs/2026-10-02-phase5-settings-backup-design.md`。
这是分批进度记录，**不是 Phase 5 完成证明**。完整验收、推送及远端核对
之前不开始卡通 UI 改版。

## 已接线的功能

- Room v5：只新增单行设置表和本机世代元数据表，保留十张业务表和显式迁移链。
- SettingsRepository、SettingsViewModel、Flow 状态、读取失败禁用、重新读取、失败保存保留值和重复提交保护。
- Activity 共享设置 ViewModel；主题选择真正影响根 Material 3 主题。
- 四模块日期展示采用持久化格式；日期存储、业务计算、订阅日期输入和 CSV 仍保持原语义。
- 新建订阅异步读取保存的默认币种和提醒组合；旧订阅不读取这些默认偏好。
  读取失败禁用保存；迟到的读取结果只作用于原编辑会话。
- 提醒组合控件只提交单个天数的选中意图，在事务内对最新已保存组合增删；
  Flow 发布延迟不会丢失另一项已提交的选择。
- 偏好读取失败错误与表单验证分离；继续输入不会清除恢复提示，也不再显示正在读取。
- 通知导航等待初始 back-stack entry 后再跳转，避免冷启动时导航图尚未安装。
- 设置页只展示已接线的偏好，不展示尚未实现的备份、清空或提醒总开关。

## 本批测试和运行证据

基线在 JDK 25 实际重新执行：205 项 JVM 测试、26 套件，0 失败/错误。
第一批主题与设置存储曾通过 224 项 JVM 测试、debug APK、测试 APK 和 lint；
lint 为 0 error / 44 warning，APK 18,350,978 字节。此数字是中间结果，
**不代替当前扩大范围后的最终重跑**。

TDD 记录（不把编译缺 API 当作运行时 RED）：

- v4 -> v5 先观察到期望 v5、实际 v4 的断言失败；实现迁移后通过。
- 保存非法币种/提前 2 天先观察到未拒绝写入的断言失败；校验后通过。
- 刷新读取失败保留旧显示但仍允许写入，先观察到错误写入；加入可用状态后通过。
- 主题选择控件、保存失败后重新读取文案、深/浅色覆盖系统模式均有失败再通过的记录。
- 日期格式和三模块日期消费先观察到格式/文本断言失败，再接线。
- 新建 EUR 默认偏好和读取失败保护先观察到旧 CNY/错误保存，再接线。
- 日期/币种/提醒默认偏好控件先观察到缺少真实交互，再接线。
- 延迟观察的真实 SettingsScreen + ViewModel 测试先观察到 `{1, 7}` 变成 `{7}`，
  单天事务增删后通过；读取失败后继续编辑仍保留错误的回归也已增加。
- 冷启动通知导航先观察到 “Navigation graph has not been set” 异常；等待初始
  back-stack entry 后，目标打开和事件消费的回归通过。
- 文件数据库关闭再打开的偏好持久化回归测试已增加；不再将同一个内存库的仓库重建称为重启持久化。

第一批设置范围最终重跑（JDK 25.0.3 / Gradle 9.4.1）：

```powershell
$env:JAVA_HOME='D:\jdk'
$env:ANDROID_SERIAL='emulator-5556'
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:connectedDebugAndroidTest
```

结果：BUILD SUCCESSFUL，1 分 58 秒。

- JVM：247 项 / 32 套件，0 failure / error / skipped。
- 设备：22 项 / 11 套件，0 failure / error / skipped；实际仪器执行约 23 秒。
- debug APK 和 androidTest APK 均构建成功。
- lint：0 error / 45 warning；新增 BOM 的版本更新提示为 warning，旧 AGP/KAPT、SDK、图标及其他历史提示仍未全部清理。
- debug APK：18,385,010 字节，约 17.53 MiB，低于 30 MB。
- `git diff --check` 通过；schema 比较确认十张业务表共 75 字段定义均未变化，v5 共 12 表。

新补充的持续观察失败/保存交错、取消写入和习惯日历无障碍日期格式用例也在
这次全量测试中通过。以上仅验收本批，不代表恢复安全、提醒总开关或性能目标已完成。

## 设备测试定位记录

本轮使用用户已建的 Medium_Phone / Android 15 API 35 / x86_64，独立端口
`emulator-5556`，`-read-only -no-window -no-snapshot-load -no-snapshot-save`。
只读会话的磁盘变更在退出后丢弃，不保存用户 AVD 快照；参见
[Android 官方模拟器说明](https://developer.android.com/studio/releases/emulator)。
未执行 wipe-data 或清空真实用户数据库。测试业务数据均为合成数据，
DAO 测试使用独立内存库；迁移测试使用独立测试库，通知测试只编辑未保存草稿。
设置 Activity 测试改变临时会话的偏好并在 finally 中恢复。

首次真实执行 16 个旧仪器测试，10 通过、6 失败：

- 两个 MigrationTestHelper 用例发生 AbstractMethodError，堆栈进入 Room 的 schema
  反序列化，尚未进入迁移 SQL。dependencyInsight 确认主 APK serialization core
  固定 1.6.3，测试 APK JSON 需要 1.8.1。按 Room 的现有需求使用
  serialization BOM 1.8.1 对齐，而非删除或跳过迁移测试。
  [Gradle 平台依赖说明](https://docs.gradle.org/current/userguide/platforms.html)。
- 四个通知 Activity 用例因 ActivityScenario 根据启动 Intent 匹配生命周期，
  测试创建的不同 action/category Intent 在 setIntent 后无法跟踪关闭或重建。
  测试复制启动 Intent 并更改通知 extras，仍通过真实 startActivity/onNewIntent
  路径，保留草稿、重复事件和重建不重放断言。没有修改生产通知代码掩盖此问题。

另增加 1/2/3 -> 5 迁移链和 v4 -> v5 十表 75 字段完整保留的设备用例，
以及主题/日期选择重建和订阅日期展示用例；最新全量设备重跑已全部通过。

v1 资产原仓库缺失：通过 git 历史核实首版三实体未变化后，v1 测试从 v2
schema 建立相同三表，删除仅 v2 新增的日程/例外表并设置 user_version=1；
运行完整 1 -> 5 迁移并验证目标 schema。此为测试夹具，不伪造历史生成的 1.json，
也不对生产数据库执行删除旧版本表的操作。

第二轮设备重跑 22 项中 17 通过、5 失败：缺少 v1 测试资产，及测试复制启动
Intent 时继承了 CLEAR_TASK 导致意外冷启动，并暴露通知导航图初始化竞态。
测试 flags 改为显式 CLEAR_TOP/SINGLE_TOP；冷启动另有独立 JVM 回归验证。
最新全量设备重跑已全部通过；保留这些定位记录，不隐藏此前失败。

## 尚未完成

- 系统文件选择/有界读取、导出后从文件重新读取验证、恢复保护备份和二次确认。
- 全局维护冻结、本机世代的写入/异步结果/广播校验、恢复后的草稿与导航失效。
- 清空业务数据的二次确认、事务回滚和提醒重建闭环。
- 三类提醒总开关，以及 Worker/Boot/Receiver、权限返回前台刷新接线。
- 关于及许可证核实、完整无障碍/大字体/深浅色审计和性能测量。
- Phase 5 完整验收、阶段提交/推送和远端核对；卡通 UI 尚未开始。

## 已知限制和审查待办

- Worker 独立数据库 builder 尚未注册 v5 默认初始化 callback；设置仓库读取时
  可以补齐默认行，但使用世代/提醒偏好前必须统一初始化，并补测后台先建库路径。
- 维护元数据已落库，但世代保护还没有消费者；不能宣称恢复并发安全已经实现。
- 本批深色模式证据覆盖根主题选择，不能替代所有图表、对话框的对比度和 TalkBack 人工验收。
- 原阶段的子任务、习惯提醒/暂停/归档、完整重复提醒策略等缺口没有默默扩大本批范围。
- 备份加密、SQLCipher、后台敏感遮罩、国际化、发布签名和最新 SDK 发布适配仍不在已确认本阶段范围。

## 第二批：完整快照和 JSON 校验基础

上一批设置功能已保存为本地提交 `4e3c6ed`；本批继续在同一功能分支工作，
不将 Phase 5 标记为完成，不提前推送完成状态或开始卡通 UI。

新增领域备份模型/固定字段契约/验证器、数据层 JSON DTO 映射/严格解析器/
规范化写入器、Room 快照 DAO/仓库及 Hilt 绑定。领域不依赖 Room、Android 或 JSON 库；
备份模型不会通过普通表单进行 trim、重算、丢弃未知历史或重建主键。

已实现范围：

- Room 单事务读取全部十张业务表及用户设置；本机世代不导出且不改变。
- JSON v1 / schema v5，SHA-256 完整载荷校验；全部表/字段白名单及严格类型。
- Long 精度、空串与 null、负色值/排序、原始星期列表、跨币种支付、
  开始日期前打卡和规则外日程例外均有保留测试。
- 重复 JSON 成员（含转义后同名）、缺表/字段、未知版本、无效 UTF-8/Unicode、
  截断/尾部垃圾、过深嵌套、重复主键/唯一键、孤儿引用、父子环明确拒绝。
- 64 MiB / 合计 100,000 业务行硬上限；先检查 SQLite 文本 UTF-8 字节数和行数，
  编码时先计数完整规范 JSON（包括转义/元数据），再分配输出数组，hash 采用小块流式计算。
- 对损坏原库只报告问题，不静默修复；异常位掩码在 Room Long -> Int 缩窄前验证。

TDD 及审查证据：空备份与十表快照先出现“尚未实现”的运行时失败，后通过。
无效 Unicode 导出先复现未拒绝；修正后通过。独立审查指出原始位掩码
`4294967297` 被缩窄为 `1`、超大文本在大小检查前读取/分配的问题；两项均在
真实 Room 回归中先断言失败，再在原始读取边界修正。规范 JSON 字节计数器的
UTF-8/控制字符/转义扩张用例先失败后实现。补充精确大小/行数边界接受、
10,000 行无环父链、独立固定 SHA-256 值和递归对象键顺序调整的回归；
定向复审确认两项 Important finding 均解决，无新 Critical/Important 问题。

本批验证明确是 **Room -> 快照 -> JSON -> 解码字段**，逐项比较所有 75 字段，
不是把解码结果替换写回数据库。替换事务、保护文件和确认流程仍属于后续任务。
设备测试使用独立内存库和合成数据，不修改用户业务库。

本批最终全量重跑：仍采用上文同一条五任务 Gradle 命令，JDK 25.0.3 /
Gradle 9.4.1 / API 35 Medium_Phone 只读会话。结果 **BUILD SUCCESSFUL，3 分 39 秒**。

- JVM：274 项 / 35 套件，0 failure / error / skipped；其中备份基础新增 27 项。
- 设备：23 项 / 12 套件，0 failure / error / skipped；新增真实 Android 的十表/75字段 JSON 往返用例。
- debug APK：18,510,312 字节，约 17.65 MiB；androidTest APK：1,154,795 字节，均构建成功。
- lint：0 error / 46 warning；新增 JSON 显式依赖的更新提示及原有技术债未被隐藏。
- schema 复核：十表/75字段旧定义 0 项变化；数据库仍 v5/12表，不需要新 migration。
- `git diff --check` 通过；临时构建/设备日志、真实数据库、备份或密钥均未纳入提交。

仍未完成真实文件备份/恢复、维护并发、提醒总开关和最终性能验收。
大小限制不等同于所有低内存设备的容量保证；完整 64 MiB / 100,000 行 Android
极限压力测试尚未执行，后续文件/性能验收必须据实记录。
