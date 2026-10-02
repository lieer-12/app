# 第一批审计修复：习惯历史与打卡资格

日期：2026-10-02。基线：`cbdb6c7`。范围经过用户批准，仅处理习惯模块三项缺陷；不开始 Phase 5，不提前添加未实现入口。环境：JDK 25、Gradle 9.4.1、Android SDK Platform 35，设备字节码目标仍为 17。

> 本报告为第一批的历史记录。后续待办可靠性修复见 [第二批验收记录](2026-10-02-todo-reliability-fixes.md)，日程完整性及最新待修状态见 [第三批验收记录](2026-10-02-schedule-integrity-fixes.md)；下文“尚待修复”描述是第一批结束时的状态。

## 已修复

- 编辑习惯由替换父记录改为原地 `UPDATE`，保持主键、创建时间和全部历史打卡记录；修改频率也不清除历史。已删除习惯的旧编辑表单不能重新创建同 ID 记录。用户明确删除习惯时仍按既有规则级联删除其打卡记录。
- 自定义星期最长连续统计向前寻找下一个应打卡日，不再反复返回同一天；开始日期不是选定星期时正确寻找后续日期。未选星期的历史记录不贡献连续统计，缺失应打卡日会中断连续。日期上界测试覆盖外层推进和搜索到 `LocalDate.MAX` 的停止条件。
- UI/ViewModel 禁止在开始日期之前或未选星期新增今日打卡。仓库在同一 Room 事务中读取最新习惯规则并校验，防止依赖旧 UI 状态；不存在的习惯返回明确错误。已有不合规历史记录仍显示可撤销入口，撤销后不能重新创建。
- 扩展观察日期范围，确保未来开始日期不会隐藏今日遗留记录及撤销按钮，并保留可见月历/热力图中的历史记录。

数据库仍为 v4，导出 schema 与数据库定义未变；没有新增迁移、destructive migration 或清库。**本修复无法恢复旧版本已经误删、且没有备份的打卡记录。**

## 测试与验证

采用先复现失败再修改生产代码的测试驱动流程：

1. 基线全量 119 项 JVM 测试通过，但没有覆盖本批缺陷。
2. 规则/ViewModel 回归复现自定义统计错误或超时、非法新增和遗留撤销入口隐藏。初次新增 Room 测试还出现 JUnit 方法返回类型错误；修正为 `Unit` 后重新运行，不把测试框架错误当作生产缺陷证据。
3. 真实 Room 仓库和 Compose 回归在修复前失败：编辑/频率修改使记录清空、不合规新增未被拒绝、按钮错误可用。补充旧表单复活、未知 ID 和最新持久化频率校验后，仓库 11 项中 9 项失败、2 项通过。
4. 修复后习惯首轮 38 项全部通过。代码复核无 Critical/Important 问题，提出两项边界补测，已补入：未选星期历史撤销交互、搜索至未选星期的最大日期。

新增测试不使用 DAO mock：`HabitRepositoryRoomTest` 在 Robolectric Android 28 + Native SQLite 上运行实际 Room 生成代码和真实仓库；`HabitScreenRegressionTest` 运行实际 Compose 页面和 ViewModel，页面测试的仓库为可观察的测试替身。它们是主机回归，不是设备验收。

最终源码实际验证结果：

| 检查 | 结果 |
| --- | --- |
| 全量 JVM 测试（强制重新执行测试任务） | 17 套件、143/143 通过，0 failures / 0 errors / 0 skipped；由全部 XML 汇总确认。比基线新增 24 项。 |
| 习惯回归 | 40/40 通过：规则 20、ViewModel 5、真实 Room 仓库 11、Compose 页面 4。 |
| Debug APK | `:app:assembleDebug` 通过，`app/build/outputs/apk/debug/app-debug.apk` 为 18,151,643 字节，低于 30 MB。 |
| Android 测试 APK | `:app:assembleDebugAndroidTest` 通过，测试 APK 为 1,239,129 字节；不等于设备测试通过。 |
| 联合 Gradle 命令 | 退出码 0，`BUILD SUCCESSFUL`，离线缓存环境完成。 |
| Phase 4 结构脚本 / `git diff --check` | 通过；schema/数据库定义无差异。 |
| `adb devices` | 未发现设备，未执行 `connectedDebugAndroidTest`。 |

README 及 Phase 3/4 验收页已同步注明历史验收范围，避免将局部审查、构建成功或本批修复误写成全需求验收。

复验命令（仓库根目录，`JAVA_HOME` 指向 JDK 25）：

```powershell
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon --console=plain --offline :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1
git diff --check
```

首次需联网下载 Gradle 依赖及 Robolectric Android 运行时；后者默认使用用户 `.m2/repository` 缓存，不能仅凭 Gradle `--offline` 保证首次测试离线。新增 Robolectric/Compose 测试库和 JDK 模块开放选项均仅作用于测试运行，不扩大正式运行时依赖。本机代理、SDK 路径、数据库、签名密钥和个人记录不提交。

## 未完成与已知限制

- 无连接设备/模拟器；新增 `HabitDaoTest` 历史保留用例仅编译至测试 APK，尚未在设备执行。迁移、系统权限、通知及真机 UI/深浅色行为仍待设备验收。
- 本批不改变提醒、频率编辑后的历史统计口径或跨午夜自动刷新机制。保留记录不代表改规则后连续数字应保持原值；统计按当前规则重新计算。
- 构建的既有 AGP/Kotlin/KAPT、协程 opt-in 和标签外键索引警告未处理；测试显式 Native SQLite 配置产生 Robolectric `SQLiteMode` 弃用警告，当前 4.17 可运行，未来升级需复核。
- 待办审计问题仍待后续批次：日期改为过去时旧提醒清理、接收前数据库校验、日期选择器 UTC 初始化、通知进入详情/保护草稿、数据库成功而提醒失败后的保存一致性；统计按截止日期还是完成日期的口径需明确。
- 日程审计问题仍待后续批次：全天提醒、重复提醒续排、冲突检测覆盖重复发生、编辑保留创建时间/时区/重复参数、自定义重复 UI 和无效提醒分钟校验；周/日视图仍不是按小时的时间轴。
- 需求补全仍未完成：待办子任务/批量/排序/标签颜色/周完成率，习惯提醒/暂停归档/整体今日进度/周月打卡率/动画，订阅年度/同比/逐订阅占比/滑动快捷操作等。Phase 5 未开始。
- 工程与非功能验收仍待完善：完整迁移链/转换器设备测试、Gradle Wrapper、外部发布签名、compile/target SDK 发布要求、系统自动备份隐私规则、后台隐私遮罩、设置/国际化和性能/屏幕适配/无障碍验收。本批不代表全部需求或发布资格通过。
