# 2026-10-09 · main 整合验收

## 范围与历史

用户明确要求将已有开发工作合并到 `main`。本次纳入 Phase 5 设置、完整备份与维护保护、第一批主题/导航/待办 UI，以及「日子芽」名称和图标；不新增业务功能，不将合并视为全部产品需求或正式发布验收完成。

- 远端主分支基线：`3af8d2ca36e38c2a4b6de3c8d4ec9f9c44c9f22a`。
- 开发分支来源：`feat/phase5-settings-backup`，提交 `c712de6aef548bc7b290a28bd142ce3507f7b86b`。
- 本地 `main` 原有 `ccfce305f6ffbb3ef465c705552457b8fa457971` 设计文档提交已经是开发分支祖先，随合并保留；远端主分支的 README 提交亦保留。
- 在项目创建的整合分支从远端主分支执行非快进合并，先不提交。没有内容冲突；原始合并树与开发分支树完全一致，树对象为 `2169a3e0d2aa3c367fec3ba237f1732f2fd3339f`。
- 在此基础上只更新 README、名称/图标验收的分支状态，新增本记录；应用源码、测试、构建配置、Room schema 与开发分支相同。README 源码入口、克隆命令和文档链接改为 `main`。
- 不强推、不重置、不压缩双方历史；原开发分支和旧 Release/tag 保留。通过检查后以快进方式更新本地主分支，再正常推送远端。

## 本轮验证

合并前重新执行开发分支全量 JVM 测试和设备测试，分别 669 项 / 68 套件、57 项 / 24 套件，均 0 failure / error / skipped；构建与 lint 检查通过。随后对实际整合工作树再次验证：

```powershell
$env:JAVA_HOME = 'D:\jdk'
$env:ANDROID_SERIAL = 'emulator-5556'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:connectedDebugAndroidTest --rerun :app:assembleRelease --console=plain
```

- `BUILD SUCCESSFUL in 2m 54s`；155 个任务，41 执行、114 up-to-date。两类测试均通过 `--rerun` 实际重新执行，不把缓存报告冒充重跑。
- JVM：669 项 / 68 套件，0 failure / error / skipped。
- API 35 模拟器：57 项 / 24 套件，0 failure / error / skipped。
- Debug、androidTest、Release 构建通过；lint 0 error / 55 warning，未隐藏现有警告。构建部分复用缓存，不声称重新编译全部源码。
- 仅在项目专用 `Rizhiya_Branding` AVD 上运行 instrumentation，不操作用户原模拟器或含个人数据的手机；不 wipe-data，不删除用户数据或快照。设备测试任务会卸载测试应用，不能在个人使用设备上照搬。
- 新构建的本地 Debug APK：18,686,567 字节，SHA-256 `b18ea1c0057d7cd0fc0d38dff9182ee871c7fd727d4296f4ab55e99e58d1a90e`；v2 签名验证通过，Debug 证书仍为 `55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`。本次本地产物未上传，不把它的哈希替换进已发布 APK 的下载说明；不同构建的二进制一致性未验收。
- 独立限定合并审查未发现 Critical / Important；确认显式迁移链、安装身份和主要配置没有合并回退。唯一 Minor 是合并后的分支状态文档需更新，本次已处理。这不是再次全面审计 Phase 5 或所有个人数据安全情境。
- 工作区和暂存差异检查通过；常见凭证模式及提交路径检查未发现明显密钥、私有数据库、APK 或 `.tools` 诊断产物，扫描不等于穷尽审计。

## 发布与限制

- 已发布的 [0.1.1 预览版](https://github.com/lieer-12/app/releases/tag/v0.1.1-rizhiya-preview-20261009) 仍对应 `777e1e9bb1c762ba721d90604400d8a2b2091627`，大小 18,685,604 字节，SHA-256 `65340e0b33e4976722836c9cdaa8ab2e2ba73f9d8b84d04ffe46a108e88631bf`。合并不覆盖资产、不移动 tag，也不另发正式版；Release 中原开发分支说明属于发布时历史背景。
- 合并不是正式发布批准。稳定 60 fps、真机性能/适配、其余模块整页 UI、高级功能、Gradle Wrapper、正式签名及许可证完整审计等限制仍见 README 和各阶段验收记录。
- 本次不改变数据库、备份格式、包名、签名配置或版本号，不增加 destructive migration，不清空个人数据。
