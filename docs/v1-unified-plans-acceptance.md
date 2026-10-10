# V1 统一计划分批验收

日期：2026-10-10；分支 `feat/v1-unified-plans`。全文设计与当前分支开发方式已获用户确认。

## 本轮基线

在应用修改前执行：JDK `D:\jdk`（25）、Gradle 9.4.1、现有网络 init 脚本，`:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain`。

- BUILD SUCCESSFUL，2 分 23 秒；65 tasks，2 executed / 63 up-to-date。
- 实际 XML：669 项 JVM，68 suites，0 failure / error / skipped。JVM 任务强制重跑。
- Debug 构建和 lint 为缓存构建检查；lint XML 0 error / 55 warning，未假称全量重新编译 / 重新分析。
- Gradle 仍报告 AGP 旧 DSL / Kotlin plugin 弃用提示；本批不为消除提示改变构建架构。
- 本轮尚未执行设备测试，历史 57 项设备通过不能作为 V1 验收。

## 批次 A · 领域底座

范围：统一 Plan 模型、四种时间模式有效字段校验、完成 / 撤销、日期转换与非活动参数保留、全库完成率 / 展示时区今日完成 / 非重复逾期。领域仅依赖 Java time 与现有纯领域枚举，不依赖 Room / Android UI / Data。

状态：领域底座局部验收通过，最终新增 34 项 / 2 suites，完整结果见下文。迁移、备份、提醒、页面不在本批；不修改生产 schema / version / manifest / Hilt，不安装、清空或改变真实用户库。不是整个 V1 验收。

初轮定向 GREEN：32 项 / 2 suites，0 failure / error / skipped，BUILD SUCCESSFUL（1 分 20 秒）。

TDD：先编写测试与类型契约，以尚未实现行为的临时空规则运行真实断言。首轮编译成功，32 项运行 / 22 项 assertion failure，明确捕获校验、完成、转换和统计缺失；不是把编译错误算 RED。补强“坏数据不得产生成功统计”的契约后，统计 15 项运行 / 12 项 assertion failure。随后替换临时规则为实质实现；临时空规则不会提交或交付。

独立计划审查发现 C/D 提前替换提醒 / 备份生产绑定会违反 E 的激活关口。已核对当前 Hilt 与数据库依赖，并修改路线：C/D 使用隔离可测试实现，现有生产绑定、默认格式和旧协议拒绝只在 E 切换。补充 A 的具体统计边界测试清单。此为计划修正，不是数据库 / 提醒已升级。

计划两项问题已独立定向复审关闭，无新增发现。

本批首次全量命令与基线相同，BUILD SUCCESSFUL（2 分 24 秒，65 tasks：13 executed / 52 up-to-date）：

- 701 项 JVM / 70 suites，0 failure / error / skipped，`:app:testDebugUnitTest --rerun` 实际执行；新增 32 项 / 2 suites。
- Debug APK 重新打包完成；lint 三类源分析重新执行成功，实际 XML 为 0 error / 55 warning，新增 domain/plan 无 lint issue。
- 本批无 Android instrumentation、真实 v5→v6 升级或新版 UI 验收；历史设备结果不混用。

独立代码审查无 Critical / Important，确认 A 的范围与实现一致。两项 Minor：进度文档要跟随验证更新，以及直接覆盖历史 `pending + completedAt` 的完成与 `completed + null` 的撤销；已补 2 项实质断言。这两项属于已有正确实现的覆盖补强，不虚称另一次功能修复 RED。定向独立复审确认两项关闭，未发现新增 Critical / Important / Minor。

最终复验（补强后的相同全量命令）：BUILD SUCCESSFUL，1 分 42 秒，65 tasks：8 executed / 57 up-to-date。

- 实际 XML：703 项 JVM / 70 suites，0 failure / error / skipped；JVM 强制重跑。PlanRules 19 项、PlanStatistics 15 项，总新增 34 项。
- Debug 构建为本批首次重打包后的 UP-TO-DATE 检查；lint 本次重新分析 UnitTest，production / AndroidTest 分析和最终 report 复用未变内容。实际 XML 0 error / 55 warning，新增 domain/plan 无 issue。
- `git diff --check` 通过；Git 提示后续 checkout 的 LF→CRLF 转换不是内容错误。
- 仅准备本地功能分支提交，不推送、合并或更新已发布 APK；不存在未实现方法、假页面或合成业务数据注入。

## 后续及已知限制

- B–F 未实施：来源双 ID 无损转换、Room v6 迁移/仓库、发生/提醒、v2/v1 备份兼容、统一页面/导航/设置、覆盖安装和版本交付。
- 本批构件尚未接入生产业务，不能在已发布软件中使用新的转换 / 完成规则；不以单元测试代替设备迁移验收。
- 独立统计页、今天真实日程、重复展开 / 例外 / 冲突、UI 移除日期确认均在后续实现；本批没有占位入口。
- V1 目标为 1.0.0 / code 3 / schema v6，当前仍为 0.1.1 / code 2 / schema v5。main、远端分支及已发布 APK 本轮未更新。
- 真机、稳定 60fps、Gradle Wrapper、正式签名等历史发布限制仍保留；不声称改版自动解决。

依据：[设计](superpowers/specs/2026-10-10-v1-unified-plans-design.md)、[计划](superpowers/plans/2026-10-10-v1-unified-plans.md)。
