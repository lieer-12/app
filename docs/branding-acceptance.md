# 日子芽 · 名称与图标验收

日期：2026-10-09。用户批准「日子芽＋小芽手账」方向，并在图像服务持续返回 429 后明确选择 Android 原生矢量绘制。未使用 API 密钥生成备选方式，也不把手工图标说成 AI 生成。

## 实现

- 桌面显示名由「生活管理」改为「日子芽」。applicationId 与 namespace 仍为 `com.example.lifemanager`，不更换安装身份。
- 原生 XML VectorDrawable：圆润奶油色手账、笑脸、薄荷色新芽、珊瑚书签与完成标记；动态主题单色图层保留真实透明眼睛、笑脸和完成标记切口。
- Manifest 配置专用 `icon` 与 `roundIcon`；API 26+ 配色前景/背景，API 33+ 额外提供 `monochrome`。前景不含方形底板或预烘焙外框，实际裁剪由桌面决定。
- 图层 108dp，主体位于中央直径 66dp 的圆形安全区，包含笔画外缘；独立圆角/圆形预览由相同 Android 矢量路径机械渲染，见 `assets/rizhiya-icon-{rounded,circle}.{svg,png}`。预览不是所有手机的外框保证。
- 版本递增到 0.1.1 / versionCode 2。Room schema、持久化实体、迁移链、备份格式、提醒流程、ViewModel 和业务页面不变；没有 destructive migration 或清空用户数据。

## 验证证据

- 测试驱动：初版 4 项 JVM 用例出现 2 项预期失败；发现系统默认 AdaptiveIconDrawable 也能通过部分图形检查后，补充实际 ApplicationInfo 自定义图标注册检查。修订后 4 项均 RED（旧名称、未注册专用图标），实现后 4 项 GREEN。
- JVM 测试使用真实 PackageManager 解析桌面 Activity/名称，加载真实前景与单色资源，Native Graphics 绘制像素检查非空、透明边缘及圆形安全区；不通过源码字符串或 mock 图标获得通过。
- 独立新建的 API 35 模拟器位于忽略的 `.tools/branding-avds`，不复制用户数据、不修改原 AVD、不开启保存快照。测试只操作该实例 `emulator-5556`。
- 覆盖安装：在此独立实例安装已发布 0.1.0 APK，通过真实编辑页创建合成待办 `branding-upgrade-check`；使用 `adb install -r` 更新 0.1.1，重新打开实际待办页后同一记录仍可见。未卸载、未清数据；这是一个合成记录的升级检查，不代表所有真实数据/设备均已验收。
- APK v2 签名检查通过；新旧包证书 SHA-256 均为 `55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`。相同包名、递增版本、同证书满足当前测试包覆盖安装的关键条件，不保证未来正式签名包可直接覆盖。
- 实际 Launcher 应用抽屉已显示「日子芽」及新手账图标；系统当前采用较小圆角遮罩，与文档的大圆角/圆形预览不同。截图保留在忽略的 `.tools/branding-drawer.png`，不把预览当作实际桌面截图。
- 最终六任务 `BUILD SUCCESSFUL`，3 分 45 秒、155 个任务（49 执行 / 106 up-to-date）：全量 JVM 669 项 / 68 套件、API 35 设备 57 项 / 24 套件，均 0 failure / error / skipped；包含本批 4 项 JVM 和 3 项真实设备图标验证。
- Debug、androidTest 与 Release 构建通过；lint 实际报告 0 error / 55 warning，已有警告未 suppress。Debug APK 18,685,604 字节；未签名 Release APK 12,252,600 字节，均小于 30 MB。体积仅记录实际产物，不推断改名/图标带来体积优化；未签名 Release 不是安装包。
- 最终 Debug APK SHA-256：`65340e0b33e4976722836c9cdaa8ab2e2ba73f9d8b84d04ffe46a108e88631bf`。完成后重新检查签名、桌面与实际 APK 资源；示例渲染 PNG 不替代设备检查。
- 独立限域源代码审查无 Critical / Important。审查核算前景最远笔画约 32.16dp，小于 33dp 安全区半径；绕中心旋转不改变距离。当前 normal/round/mono 接线正确。
- 保留一项 Minor 覆盖边界：设备测试直接加载 round 资源，没有专门以错误/删除 Manifest `roundIcon` 指向制造失败；单色像素用例仅检查 normal 图标。编译后的 Manifest 与审查已核对实际指向，但不声称有完整负向回归。
- 2026-10-09 推送前复验：JVM 任务使用 `--rerun`，669 项 / 68 套件均 0 failure / error / skipped，构建与 lint 检查 `BUILD SUCCESSFUL in 48s`（154 个任务，3 执行 / 151 up-to-date）；独立 API 35 设备任务另用 `--rerun`，57 项 / 24 套件均 0 failure / error / skipped，`BUILD SUCCESSFUL in 59s`（80 个任务，1 执行 / 79 up-to-date）。复验的构建和 lint 主要复用未变化产物；不声称重新编译全部源码。报告汇总最初误读 instrumentation XML 的根层级，提交被拦截；核对原始 `testsuites` 与 24 个子 `testsuite` 总数一致后才提交。

本机复现（仅对项目专用、不含个人数据的测试设备）：

```powershell
$env:JAVA_HOME = 'D:\jdk'
$env:ANDROID_SERIAL = 'emulator-5556'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:connectedDebugAndroidTest :app:assembleRelease --console=plain
```

本机 init 文件、AVD、日志、设备 XML 与旧 APK 基线均忽略；instrumentation 任务会卸载测试应用，不得将该命令指向有个人业务数据的手机。

## 限制与交付状态

- 外框、桌面图标动画和单色染色由桌面设置及厂商支持决定；本次不修改系统形状设置，不宣称所有手机都能强制圆形。
- 最低 API 26；本轮 JVM 图标用例覆盖 API 28/33，实际设备为 API 35 模拟器，不代表真机或 API 26 全适配。
- 修改名称和图标不表示其余 UI 整页改版、Phase 5 性能或正式发布已完成。
- 源码提交 `777e1e9bb1c762ba721d90604400d8a2b2091627` 已推送到 `feat/phase5-settings-backup`；[0.1.1 预览 Release](https://github.com/lieer-12/app/releases/tag/v0.1.1-rizhiya-preview-20261009) 已公开发布，仍为 prerelease。上传资产 `app-debug.apk` 的 GitHub SHA-256 与上述本地产物一致，大小 18,685,604 字节；未上传不可直接安装的未签名 Release APK。
- 已有公开 0.1.0 Release 与旧资产保留；安装新版前建议导出完整备份，然后覆盖安装。签名冲突时不要直接卸载或清除数据。
- 矢量、预览、测试与文档已纳入提交；密钥、模拟器数据、测试日志和旧 APK 基线留在忽略的本机目录，未纳入 Git。默认分支只同步 README，Phase 5、UI 和品牌代码仍在开发分支；发布后的说明更新不改变 APK 对应的源码提交。
