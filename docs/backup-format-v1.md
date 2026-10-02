# 完整备份格式 v1（Phase 5 内部接口）

当前已实现快照和编解码基础；文件选择、输出后重读、保护备份及恢复确认流程仍待接线。
这不是可点击使用的备份功能，也不是完整 Phase 5 验收。

## 文件结构

UTF-8 JSON，必需且只允许以下顶层字段：

- `format`：`life-manager-backup`。
- `formatVersion`：整数 `1`；`schemaVersion`：整数 `5`。
- `appVersion`：非空应用版本字符串；`exportedAt`：可解析的 ISO Instant 字符串。
- `payload`：十张业务表数组，以及唯一的 `settings` 对象。
- `payloadSha256`：小写 64 位十六进制 SHA-256。

业务表名称和字段名与 Room v5 一致：`todos`、`tags`、`todo_tag_cross_ref`、
`schedules`、`schedule_exceptions`、`habits`、`habit_records`、`subscriptions`、
`subscription_payments`、`subscription_reminders`。每行必须包含该表全部字段，
包括值为 JSON `null` 的可空字段；字段白名单由 `BackupSchema` 固定。
即使表为空也必须提供 `[]`，不能遗漏表或字段。

设置字段为 `theme`、`dateFormat`、`defaultCurrency`、`todoReminders`、
`scheduleReminders`、`subscriptionReminders`、`defaultReminderDays`。
最后一项为不重复的 1/3/7 整数数组，导出按升序排列。单行主键和存储位掩码
是数据库实现细节；设置的完整用户语义保留。本机维护世代不从文件恢复。

## 类型和精度

- ID、金额和时间均为 JSON 十进制整数，直接解析为 Long，不能经由 Double。
- Room Boolean 导出为 JSON `true`/`false`，不接受 `0`/`1` 或字符串代替。
- Instant 对应 epoch milliseconds；LocalDate 对应 epoch days。`payments.paidAt`
  是日期而不是毫秒时间戳。
- 枚举保留名称；可空文本的 `null` 与 `""` 区分，标题不 trim，星期列表不重排或去重。
- 星期列表接受空串或可解析为 1–7 的逗号分隔整数；非法 token 明确拒绝，不能像旧映射器那样静默丢弃。
- 合法的开始日期前打卡、未选星期的历史打卡、规则外的日程例外和支付币种
  不同于订阅币种均保留。仅验证结构、类型和引用，不通过当前表单重建历史。

## 校验值规范

仅对 `payload` 计算 SHA-256；它检测损坏，**不提供加密、来源认证或防篡改保证**。
格式 v1 使用本项目规范化方式，不宣称兼容通用 JCS：

1. 对所有 JSON 对象的字段按 Kotlin 字符串字典序递归排列。允许字段名称均为 ASCII。
2. 数组维持原顺序；整数用 Long 标准十进制表示（`-0` 规范为 `0`）。不接受小数或指数形式。
3. 字符串、布尔值和 null 与项目固定的 kotlinx.serialization JSON 1.8.1
   `JsonElement.toString()` 输出逐字节一致，无 pretty-print 或额外空白，编码为 UTF-8。
   实际使用流式写入器，先计数再分配有界字节数组，不先构造完整转义字符串。
4. 对得到的字节计算 SHA-256，小写十六进制输出。

因此对象字段顺序及文件空白可以不同，校验仍通过；数组顺序不同需要重算校验。
导出全文件也使用同样的规范化顺序。未知格式/schema 版本和未知字段拒绝导入，
未来兼容需要显式转换，不忽略字段。

JSON 读入在构造树时拒绝重复的**解码后**字段名（如 `id` 与 `i\u0064`）、
无效 UTF-8/Unicode、尾部垃圾、非标准 JSON 和过深嵌套，并限制数组和对象节点预算。
业务验证还覆盖主键/联合键、标签唯一名、习惯-日期唯一键、全部外键和待办父子环。
标签名称按 SQLite BINARY 语义区分大小写，不做 Unicode 规范化。

## 边界与安全

硬上限为 64 MiB / 十张业务表合计 100,000 行（关联、例外和提醒也计数）。
可以在测试/内部调用中降低限额，不能提高。超限拒绝，不截断数据。
快照在一个 Room 事务中先统计行数和 SQLite 中的 UTF-8 文本字节总量，再读取
所有表与设置，不改库、不推进世代。设置位掩码在 Room 缩窄为 Int 前按原始
INTEGER/Long 值验证 0–7，不能将不兼容整数截断成合法选项。
文件 I/O 不在快照事务中执行；系统权限、闹钟缓存、草稿和待消费导航事件不导出。

文件包含明文个人数据。最终 UI 必须提示隐私风险，文件输出必须关闭并重新读取验证
后才报告成功；上述文件闭环、保护备份和恢复事务不在本批编解码器中实现。

实现参考：[Kotlin JSON 树 API](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/)
和 [1.8.1 字符串转义规范实现](https://github.com/Kotlin/kotlinx.serialization/blob/v1.8.1/formats/json/commonMain/src/kotlinx/serialization/json/internal/StringOps.kt)。
测试逐字节比较全部有效 BMP 字符、控制字符及补充平面字符，不仅自我比较计数结果。
