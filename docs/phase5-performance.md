# Phase 5 性能测量（2026-10-05）

环境：Windows / JDK 25 / Gradle 9.4.1，API 35 Medium_Phone，1080×2400，
4 vCPU / 2 GiB RAM。仅独立隐藏只读会话，不修改用户 AVD、业务库或快照。
以下数字不是物理设备、Google Play 分发构建或完整性能基准。

## 已检查和落实的性能边界

- 待办、习惯、订阅 Lazy 列表沿用稳定业务 ID key，没有为视觉完整性生成假记录。
- 文件 IO、编解码和许可证文本读取在后台 dispatcher，不在 Compose 中读写业务表。
- 维护用户等待期间不持有 Room 事务 / 模块 Mutex；普通导出文件 IO 不持有数据库许可。
- 只做维护、语义和大字体必要修正，没有为了声称优化而重写统计口径或添加性能依赖。

## 页面可操作控件耗时

真实 MainActivity 的 `AppPagePerformanceDeviceTest` 点击实际导航，等待可用主操作，
并检查至少 48 dp；不写业务记录。含仪器、Compose 空闲等待和语义遍历开销，不是帧率。
第八批历史基线及本批最终 52 项硬件 GPU 设备全量回归的实际 logcat（不同会话，非 A/B）：

| 页面 | 第八批历史基线 | 本批最终全量 |
| --- | ---: | ---: |
| 日程 | 406 ms | 141 ms |
| 打卡 | 337 ms | 218 ms |
| 订阅 | 308 ms | 143 ms |
| 待办 | 239 ms | 73 ms |
| 设置 | 406 ms | 108 ms |

## 历史定位：debug 实测未达标

第八批安装当时 debug APK 后运行五次 `adb shell am start -S -W -n com.example.lifemanager/.MainActivity`。
直接记录 `TotalTime` 和系统 `LaunchState`，不将 WARM 改写为 COLD，不以绘制结束当作
数据加载完毕。测量期间主机也运行过 Gradle；未形成严格受控的 Macrobenchmark。

| 轮次 | SwiftShader / TotalTime | host GPU / TotalTime |
| --- | --- | --- |
| 1 | COLD / 7034 ms | COLD / 6795 ms |
| 2 | WARM / 7379 ms | COLD / 7679 ms |
| 3 | WARM / 7036 ms | WARM / 5822 ms |
| 4 | COLD / 10964 ms | COLD / 6148 ms |
| 5 | COLD / 5907 ms | COLD / 5371 ms |

`dumpsys SurfaceFlinger` 核对默认自动配置为 Google SwiftShader；新的独立会话加
`-gpu host` 后核对为 NVIDIA GeForce RTX 4050 Laptop GPU。未改 `config.ini`。
配置 / 启动态 / 负载不同，不能把这组差异当作源码性能优化或严格 A/B 证据。
硬件 GPU 仍未达到启动 <2 秒，不能宣布该目标通过。

## 帧数据的限制

debug 硬件 GPU 启动后 `dumpsys gfxinfo` 只有 2 个绘制帧：2 个 janky（100%），
CPU 帧 P95 约 2900 ms，GPU P95 24 ms。软件渲染同样只有 2 帧、2 个 janky，
GPU 直方图溢出至 4950 ms 桶。只有启动样本，不是连续页面切换 / 滚动样本；
明确未证明 60 fps，不从 UI 测试通过或短页面等待推导帧率。

## 非调试构建启动

相同源码 `:app:assembleRelease` 构建成功（4 分 8 秒）。仓库发布配置 / release 签名
没有变更，产物为 unsigned；本机用已有调试证书签名到 Git 忽略的 `.tools`，只供只读
模拟器验证。安装成功并检查 `dumpsys package` 不含 DEBUGGABLE 后才测量。
此前一次误用不存在的 APK 路径安装失败，随后输出仍是 debug，明确剔除，不计 release 数据。

硬件 GPU，五次系统均报告 COLD，`TotalTime`：1238、1390、1255、1253、1306 ms，
中位数 1255 ms，最大 1390 ms。这组模拟器非调试启动达到 <2 秒，但不证明物理设备达标。
本次 release 未启用 R8；不把构建类型差异当作本批源码优化成果。

unsigned release APK 12,232,272 字节（约 11.67 MiB）；临时调试签名副本
12,251,239 字节（约 11.68 MiB）。第八批历史 debug APK 18,984,755 字节，
本批最终 debug 为 18,990,037 字节（均约 18.11 MiB），
均低于 30 MB；androidTest APK 1,280,401 字节，不属于用户安装包体积。

## 非调试切页帧采样：稳定 60 fps 尚未验收

用真实 UIAutomator 层级中五个 Tab 的坐标点击，清空 gfxinfo 统计后按
日程→打卡→订阅→设置→待办循环三次；每次等待 UIAutomator 空闲，无业务数据写入。
统计共 716 帧，janky 116 帧（16.20%），帧时间 P50 / P90 / P95 / P99 为
26 / 42 / 48 / 69 ms。GPU P50 14 ms，但高分位落入溢出桶；不把该桶当作精确 GPU 耗时。
这是模拟器、空业务库、点击 / 动画混合采样，不是长列表滚动或真机受控基准。
不能声称稳定 60 fps；用户已选择继续性能专项，达标后再推送并开始 UI。
只删除本次生成的 `/sdcard/phase5-ui.xml`，不删用户文档或数据。

## 性能专项：系统帧时间线定位

用户已选择继续专项，性能达标前不推送或开始 UI。此前定位批未修改应用实现、
数据库、Gradle 或发布配置；诊断配置、工具和 trace 均留在 Git 忽略的 `.tools`。

### 先核对测量字段

Android 15 `FrameInfo.cpp` 的 CSV 名称数组将 `FrameInterval` / `FrameStartTime`
标为相反顺序；`FrameInfo.h` 的真实枚举顺序为 `FrameStartTime` / `FrameInterval`。
实测对应位置分别是时间戳和 16,666,666 ns。不能按错误表头推导刷新间隔。
SurfaceFlinger 自身报告 VSYNC period 16,666,666 ns；gfxinfo 样本的预算为 50 ms，
包含流水线延迟，不直接等同单帧 16.67 ms CPU 预算，也不能用总延迟倒数宣称帧率。
源码核对：[FrameInfo.cpp](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/libs/hwui/FrameInfo.cpp)、
[FrameInfo.h](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/libs/hwui/FrameInfo.h)。

### Perfetto / OpenGL 样本

相同非调试 APK、原 1080×2400 host GPU，30 秒系统追踪；三轮五 Tab 点击，
每次间隔 900 ms，不并发运行 Gradle，不写业务数据。
录制 `android.surfaceflinger.frametimeline`、CPU 调度和 gfx/view 等 atrace，
用官方 Perfetto v58.2 Windows Trace Processor 离线查询，下载校验 SHA-256 后执行。
最初读取 `/data/local/tmp` 配置被 SELinux 拒绝；改为 stdin 后真实录制成功。
只有录制结束且文件为非零字节后解析，早期零字节副本不作测量依据。
有效 trace 16,206,327 字节，stats 没有非零 error / data_loss。

- 543 个应用 FrameTimeline 记录；153 个含 App Deadline Missed，229 个含
  SurfaceFlinger 超时，498 个含 Buffer Stuffing；类别可重叠，不能相加为卡顿率。
- 主线程 `Compose:recompose` 平均 0.661 ms，最大 24.889 ms；
  `AndroidOwner:onMeasure` 平均 3.821 ms，最大 69.619 ms。
- 主线程 `postAndWait` 平均 19.080 ms，最大 47.372 ms；RenderThread
  `eglSwapBuffersWithDamageKHR` 平均 10.291 ms，`dequeueBuffer` 平均 5.987 ms。
  嵌套 / 并行段不能相加。这表明绘制交接 / 缓冲等待贡献明显，不是单一重组问题。
- 最大测量尖峰内同时有重组、文本排版和 GC 类加载锁竞争；仅凭峰值不能断言
  GC 或某个模块为全部卡顿根因。暂无改代码后的性能改善证据。

官方[FrameTimeline 说明](https://perfetto.dev/docs/data-sources/frametimeline)区分
应用与显示系统超时；Buffer Stuffing 可造成输入延迟和 dequeue 阻塞，
不能直接把所有 Late Present 记录当作应用计算超时或 FPS 缺帧。

### 渲染后端与单页对照（诊断，不是优化成果）

只在本次独立只读模拟器进程中将 `debug.hwui.renderer` 从 `skiagl` 临时改为
`skiavk`，重启同一应用、预热一轮后按同样点击间隔录制。实际 Pipeline 为 Vulkan。
gfxinfo 567 帧 / 257 janky（45.33%），P95 73 ms；GPU P95 4 ms，仍不能证明
整条显示链流畅。Vulkan 不是本环境的可接受修复，已恢复原 `skiagl` 并重启核对。
这不是严格多轮 A/B：与此前 UIAutomator 空闲采样方法不同，不直接比较 45.33% 与
16.20% 并声称应用退化；仓库不强制任何渲染后端。

恢复 OpenGL 后，固定点击已选中的待办 Tab 15 次（每次间隔 900 ms），
不切模块：666 帧 / 204 janky（30.63%），P95 42 ms。说明底部点击 / ripple
等动画也受当前渲染链路影响，不能仅靠导航栈调整就保证消除模拟器全部卡顿。

### 另一个已复现的应用侧问题：顶层导航历史持续累积

`NavGraph` 的 Tab 点击仅使用 `launchSingleTop`，它只限制栈顶相同路由，
不能限制不同 Tab 循环产生的新条目。真实设备上完成
日程→打卡→订阅→设置→待办后，连续两次系统返回实际依次显示设置、订阅；
说明整个 Tab 切换历史被保留。习惯和订阅 ViewModel 又是导航条目级所有者，
可能随重复条目持续增加观察任务。还未对条目 / 所有者数做自动化验收，
不能声称此问题解释上述所有帧延迟。

用户已确认采用现有 Navigation 2 的顶层保存 / 恢复栈策略。Tab、设置入口及三类
合格通知均调用 `navigateTopLevel`：popUpTo 起点并保存、singleTop、restoreState。
重复点击当前 Tab 不追加导航；非根模块一次系统返回到待办，不再重放 Tab 访问历史。
没有升级依赖、删动画或清空业务数据。保存的模块所有者有界保留，并非所有离屏观察都停止。

两个 JVM 回归对真实 NavController / entry ViewModelStore 检查 20 轮循环仍复用
同一模块所有者、草稿保留及退出最终清理；移除保存 / 恢复策略后，两项均为行为 RED。
设备新增七项真实 Activity 回归：循环返回、统计子页、通知离开后的两类草稿及配置
重建、维护拒绝新导航、返回按钮冻结和系统返回冻结。原实现的五项行为 RED，
按钮 / 系统返回另外两项 RED 后修正；不把错误 Tab 定位或无设备当作产品 RED。

首次根层 `BackHandler` 修正仍有一项失败。当前 activity-compose 1.10.1 的源码与
临时测试日志确认实际注册顺序是根拦截 → NavHost PredictiveBack → Settings 取消，
NavHost 后注册且启用，绕过根拦截。改为每个真实 destination 生命周期内的维护
拦截，备份 busy 时让设置取消处理器负责。临时反射诊断已移除，没有生产测试钩子。
七项定向设备回归 `BUILD SUCCESSFUL`，1 分 26 秒；五个模块冻结时不能返回，
解除冻结后正常返回根页。随后独立复审补两条绕过路径：待办顶部设置入口，及
进入全局维护之前的备份隐私确认 / 预览期间通知导航。四项真实设备行为 RED
（19 秒：顶部按钮仍可用，三类通知使备份确认界面消失）后修正：顶部、底部及
设置返回均有实时锁校验；三类通知忙碌时保留原事件，解锁后重新核验原世代。
11 项设备和相关导航 JVM 定向联合 `BUILD SUCCESSFUL`，1 分 32 秒。

复审还指出忙碌脉冲可能未进入 Compose 帧、导致实时门禁拒绝后丢失重试唤醒。
新增确定性 JVM 交错测试，原路径运行时 RED（1 分 16 秒）。现在只有实时 UI
门禁的拒绝会在释放普通数据库许可后等待备份状态 Flow 并重试；世代过期、请求
被替代及读取失败不会复活重试。等待期间允许真实维护会话进入，原事件世代不重捕。
接口临时未实现测试脚手架均已替换。最终六任务 `BUILD SUCCESSFUL`，3 分 1 秒：
658 JVM / 67 套件、52 设备 / 22 套件，均无 failure / error / skipped；lint 0 error /
57 warning，Debug / androidTest / Release 构建成功。独立限域复审无剩余 P1 / P2。

### 导航修正前后：同流程八轮采样

原隐藏进程已不在线，重新启动同参数的独立只读、host GPU 会话（无 wipe / snapshot）。
停止 Gradle daemon 后重新测量两种非调试 APK；安装成功且无 DEBUGGABLE 才运行。
每轮 `am start -S -W`，先预热一轮五 Tab，再重置 gfxinfo，按上述三轮 / 900 ms 流程。
先旧包两次 → 新包两次，再新包两次 → 旧包两次作反向顺序检查。不写业务数据，
不改渲染后端，不隐藏尖峰；旧包 DEX 无新增导航 helper，新包有，SHA-256 不同。

| 包 / 顺序 | 启动 TotalTime（均 COLD） | 绘制帧 | janky | P95 |
| --- | ---: | ---: | ---: | ---: |
| 旧包 1 | 781 ms | 788 | 21（2.66%） | 25 ms |
| 旧包 2 | 624 ms | 766 | 26（3.39%） | 25 ms |
| 新包 1 | 596 ms | 728 | 79（10.85%） | 44 ms |
| 新包 2 | 625 ms | 788 | 22（2.79%） | 21 ms |
| 新包重复 1 | 620 ms | 790 | 20（2.53%） | 19 ms |
| 新包重复 2 | 545 ms | 792 | 18（2.27%） | 19 ms |
| 旧包反向 1 | 577 ms | 792 | 18（2.27%） | 18 ms |
| 旧包反向 2 | 624 ms | 789 | 21（2.66%） | 20 ms |

本会话新包 545–625 ms，满足模拟器启动 <2 秒；APK 仍 <30 MB。
汇总旧包 86 / 3135（2.74%）、新包 139 / 3098（4.49%）。不能剔除新包首轮尖峰，
不能将 P50 17 ms 或较好单轮当作稳定 60 fps，**也不能宣称导航修正改善了帧卡顿率**。
反向检查的双方低卡顿与此前 16.2% 差异表明主机 / 采样状态变化显著；这不是
Macrobenchmark、真机或长列表结果，尚不能区分首轮冷路径与环境抖动的全部贡献。
已经证明的源码改进是有界导航条目 / 所有者与草稿、冻结保护，不是帧率达标。

### 最终包系统时间线复核（仅定位，非对照基准）

重新安装最终非 DEBUGGABLE APK，核对 OpenGL，录制同配置 30 秒追踪，仍不并行构建。
本次 trace 17,839,400 字节，stats 无非零 error / data_loss；开始先报 PID，待录制
完成、文件非零后才拉取 / 解析。此次启动 COLD 1254 ms，与上面的八轮无追踪采样不同。
trace 期间 gfxinfo 为 509 帧 / 232 janky（45.58%），P95 61 ms；追踪及环境状态不同，
不能把它同未追踪的新包 4.49% 直接比较，也不隐藏该结果。

- 应用 FrameTimeline 502 条，152 条含 App Deadline Missed，493 条含 SurfaceFlinger
  超时，438 条含 Buffer Stuffing；类别可重叠，不相加或直接当作 FPS。
- 主线程重组 315 段，平均 0.280 ms / 最大 8.993 ms；布局 121 段，平均 0.974 ms /
  最大 18.079 ms；`postAndWait` 479 段，平均 23.185 ms / 最大 53.633 ms。
- RenderThread `dequeueBuffer` 509 段，平均 18.345 ms / 最大 52.726 ms；
  `eglSwapBuffersWithDamageKHR` 509 段，平均 24.141 ms / 最大 53.216 ms。
  嵌套 / 并行区段不可相加；这些等待不等于 Compose 重组计算。

剩余样本中渲染交接 / 缓冲等待明显，仍不能断言全部原因只在主机或只在应用。
不通过删除动画、改模拟器分辨率、强制渲染后端或挑选较好轮次宣称达标；保持原行为。
真机需独立安装 / 保留原应用数据后测启动、切页、滚动；不要对个人手机运行会卸载应用
的 `connectedDebugAndroidTest`。目前仅连接独立只读 emulator-5556，已询问可用真机。
用户规定性能验收后才交付；源码和回归通过，但 Phase 5 整体验收 / 推送 / UI 仍未完成。

## 发布前仍需验证

- 物理设备启动、滚动、切页的受控采样，定位真实瓶颈后再优化；模拟器不能替代发布验收。
- 大数据列表、统计、长时间运行、不同字体 / 屏幕 / TalkBack 的完整验收。
- 项目 release 仍不启用 R8；性能验证可用本机调试证书临时签名安装，但不是发布签名，
  不提交证书、运行文件、trace 或 APK，不更改仓库发布配置。

功能回归和性能指标是不同结论；最新最终验收及交付状态见 `phase5-acceptance.md`。
