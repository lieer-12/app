# 第三批审计修复：日程完整性与草稿交接

日期：2026-10-02。基线：`28937af`。用户确认范围限于以下五组修复；本批不是重新实施 Phase 3，也不启动 Phase 5。JDK 25 / Gradle 9.4.1 / Android SDK Platform 35，设备字节码目标仍为 17。

## 已实现与数据保护

| 确认的问题 | 实现 |
| --- | --- |
| 编辑日程 REPLACE 父记录使例外级联丢失 | 新建使用 INSERT；编辑在 Room 事务内检查目标存在并原地 UPDATE，保留 ID、创建时间及例外行。不存在的旧编辑目标返回错误，不重新插入。明确删除日程仍级联删除例外。 |
| 编辑重置元信息和隐藏自定义参数 | 保存时读取最新数据库对象，只替换表单可编辑字段，保留创建时间、时区、重复间隔/星期/结束日期；DAO 再保护创建时间。不新增自定义重复配置 UI。 |
| 无效提醒文本静默关闭提醒 | 非空文本必须可解析为非负 Int，拒绝字母、小数、溢出和负数；空白关闭提醒，0 表示零分钟提前。错误显示在编辑框内。 |
| 重复提交、草稿丢失、冲突确认过期及副作用错误 | 提交期间拒绝再次保存/编辑/关闭/替换草稿；数据库成功后在 Main 清空表单，再执行提醒副作用。提醒失败明确显示“已保存/已删除”，不保留可再次插入的草稿；数据库失败保留草稿。任意表单修改使确认失效，确认时重新读取并比较当前冲突记录。 |
| 通知重放或覆盖跨模块草稿 | Activity 持有日程 ViewModel 和可消费导航事件，NavGraph 必须接收该所有者，不再静默创建另一份日程 ViewModel；每次点击独立 token，消费仅匹配当前 token，并移除已接收 Intent extra。通知按仓库 ID 查询，不依赖可见列表；已有草稿显示等待提示，保存/关闭后交接，延迟旧查询不能覆盖新通知。发布替换通知时重建旧通知点击 PendingIntent，使用 CLEAR_TOP / SINGLE_TOP 复用 Activity；安装升级本身不会重写已经发布的通知。 |

`ScheduleOperationCoordinator` 为进程内 Mutex，协调日程写入/提醒副作用与重启/Worker 校准，防止校准旧快照覆盖当前写入。它不存业务数据，不新增数据库；不同模块仍共享既有 Room。通知查询和 UI 编辑、成功提交的草稿交接在 Main 串行，草稿判断/写入等待目标采用同一次原子 StateFlow 更新。

Room 保持 v4，数据库定义与导出 schema 无变化，不增加迁移、不使用 destructive migration、不清库。没有新增正式依赖、模块入口、假数据或需求文件修改。SDK 路径、代理、密钥和个人记录不提交。**旧版本已误删且无备份的日程例外无法由本修复恢复。**

## 测试过程

- 修改前基线全量测试通过；第二批基线为 174 项。
- 首轮 24 项日程测试中 15 项失败，复现真实 Room 编辑丢失例外/创建时间/旧 ID 复活、元信息和提醒校验错误、保存一致性/冲突确认/通知覆盖草稿及旧 PendingIntent 不复用 Activity；明确删除的级联行为等控制用例仍通过。
- 重复保存初测被旧冲突检测意外阻止，改用可控保存挂起窗口后独立执行 1 项、1 项失败，明确断言两条记录而非一条；随后拒绝重复提交。
- 首轮修复后 24 项全部通过。导航事件及 NavGraph 参数新增接口的首次编译缺失是开发中的接口 RED，不作为原有业务缺陷证据；接通后 28 项通过。
- 补测延迟查询期间新草稿、旧等待与新通知顺序、保存后打开等待目标、通知目标缺失、数据库删除失败、0/空白提醒、协调器阻止并发删除及真实独立 IO/Main 线程交接。这些是新实现的边界覆盖，不宣称每项都先复现业务失败。
- IO/Main 测试在真实单线程 IO Executor 上挂起已经完成的保存，Main 暂停，用 FIFO 哨兵确认保存返回/挂起后提醒副作用尚未开始；再推进 Main，检查保存记录与等待详情。它验证串行交接策略，不宣称逐指令复现所有竞态交错。
- 新增设备测试覆盖跨订阅模块返回、连续通知点击及 Activity 重建后的草稿/等待目标；须断言等待 token 增加、重建 token 不变、导航请求为 null 和 Intent extra 已移除。首次编译错误来自多余的 Compose `onNode` 导入，已移除；不是业务缺陷证据。设备用例未运行、不计入主机通过数。
- 独立复核未发现 Critical / Important，提出三项小改进：提交时输入视觉禁用、NavGraph 强制接收保留的日程所有者、明确旧通知替换时机。新增 Compose 用例修改前 1 项执行、1 项失败（标题输入仍启用），随后禁用所有文本、全天/日期时间、重复和颜色控件；修改 NavGraph 为必传所有者并更新待办导航测试的依赖装配，不改变待办行为。三个建议均已落实。

真实 Room 仓库回归在 Robolectric Android 28 上执行实际 Room 生成代码，不使用 DAO mock。Compose 回归执行真实页面、NavGraph 和 ViewModel，仓库替换为可观察数据源；不等同于 Hilt Activity 或设备验收。

全量联合 Gradle 命令（测试、两种 APK、lint 均强制重新执行）返回退出码 0、`BUILD SUCCESSFUL`。从全部 **26 个套件 XML** 汇总确认 **205/205 通过，0 failures / 0 errors / 0 skipped**，比基线新增 31 项。日程专用套件 36 项：规则 3、既有 ViewModel 2、编辑回归 20、真实 Room 仓库 4、Compose 页面/导航 3、通知兼容 1、导航事件 3。共享 `ReminderKeyTest` 三项也通过，其中一项检查待办/日程提醒 ID 不冲突。

`assembleDebug` 与 `assembleDebugAndroidTest` 均通过：debug APK 为 **18,184,411 字节**（低于 30 MB），测试 APK 为 **1,248,247 字节**。新增 `ScheduleNotificationActivityTest` 两项仅确认编译，**未执行、不计入 205 项通过数**。Phase 4 结构脚本和 `git diff --check` 通过，schema/数据库定义无差异；`adb devices` 无连接设备。

首次 `--offline :app:lintDebug` 因缺少 `lint-gradle:32.2.0` 缓存未运行到源码检查；随后通过临时命令行代理下载工具，在线 lint 退出码 0。最终联合命令再次以完整缓存离线运行 lint，通过，报告 **0 Error / 44 Warning**（含目标 SDK/依赖版本、KAPT、应用图标和受保护广播检查等警告）。没有关闭规则、增加 baseline 或把警告等同于发布验收；代理参数未写入仓库。

最终独立只读复核确认三项小改进均足够，无未解决 Critical / Important / Minor；复核者独立核对了 205 项 XML 和 lint 报告，没有执行构建或设备测试。复核不作为设备运行证据。

复验命令（仓库根目录，`JAVA_HOME` 指向 JDK 25）：

```powershell
.\.tools\gradle-9.4.1\bin\gradle.bat --console=plain --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1
git diff --check
```

首次测试需要下载 Gradle 依赖和 Robolectric Android 运行时；两类缓存完整后可离线测试。构建网络需求不改变 App 离线使用能力。

## 未完成与已知限制

- 当前没有连接设备/模拟器，不执行 `connectedDebugAndroidTest`。实际 Hilt Activity 所有者、通知冷启动/旋转/连续点击、Android 13+ 权限、精确闹钟和厂商后台限制仍待设备验收；设备测试只编译。不承诺系统杀进程后的草稿和通知事件恢复。
- 本批不处理全天日程提醒时间策略、重复提醒续排、投递前数据库校验、历史通知撤回和失败提醒即时重试入口。后台校准仍使用既有提醒算法，本批只协调读写顺序。
- 冲突确认重新校验的是现有算法发现的记录，不扩大重复发生冲突检测窗口或修复重复展开算法。自定义重复参数保留不等于已有可编辑 UI；周/日视图仍非小时轴。拖拽、缩放和仅取消一次入口未实现；保存例外不丢失也不等于例外已参与日历/提醒计算。
- Mutex 只协调同一进程的既有调用，不宣称跨进程事务性；Boot/Worker 接入以代码复核为主，新增执行测试直接覆盖 ViewModel 与协调器。Room 与 AlarmManager 不是同一事务，副作用失败后记录已持久化，仍可再次编辑或等待校准。
- 待办统计口径及子任务/批量/排序、习惯提醒/归档/整体进度/周月率/动画、订阅年度/同比/逐项占比等仍未补齐。Phase 5、Wrapper、发布签名/SDK 要求、备份隐私、设置/国际化、性能/适配验收仍见第一批报告，不在本批提前实现。
- 既有 AGP/Kotlin/KAPT、协程 opt-in、标签外键索引和 Robolectric SQLiteMode 弃用警告仍存在；本批不重构无关配置。
