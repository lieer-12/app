# Android 个人管理 App 开发 Prompt

> 文档状态：需求草案（实现前评审版）。本文档描述产品目标与实现约束，不代表当前就开始创建或运行项目；只有在用户明确要求开始实现后，才进入项目搭建阶段。

## 零、范围与决策约束

- **本版本技术路线固定为原生 Android**：Kotlin + Jetpack Compose + Material 3。Flutter 方案仅作为未来跨平台迁移的备选，不参与当前实现。
- **首个可交付版本（MVP）只要求 Phase 1**：先完成可编译、可安装、离线可用的待办闭环，再按 Phase 2-5 扩展。没有明确验收通过前，不提前实现后续模块的大量细节。
- **数据原则**：所有业务数据以本地数据库为唯一事实来源；UI 只通过 ViewModel/Use Case 读写数据，不直接访问 DAO。
- **时间原则**：持久化时间统一使用带时区语义的 `Instant` 或带明确本地日期语义的 `LocalDate`；展示时使用用户设置的时区和语言。不要用不带语义的字符串保存时间。
- **金额原则**：金额不得用 `Double` 作为持久化类型，使用货币最小单位的 `Long`（例如人民币分）或等价的精确十进制定点表示。
- **离线优先不等于无边界**：当前版本不依赖登录、云同步或远程 API；如果未来增加同步，必须另行设计冲突处理和数据迁移方案。

## 一、项目概述

开发一款 Android 原生应用，集四大功能于一体：每日待办事项、订阅费用管理、日程安排（日历视图）、打卡任务。目标用户为注重个人效率与开销管理的用户，应用需离线可用、界面简洁现代。

---

## 二、技术栈与工程约束

### 当前采用：原生 Android
- **语言**: Kotlin
- **UI 框架**: Jetpack Compose（Material Design 3）
- **架构**: 单 Activity + MVVM；按 data / domain / ui 分层，保持适度的 Clean Architecture，不为简单 CRUD 引入无必要的抽象
- **本地数据库**: Room (SQLite)
- **依赖注入**: Hilt
- **导航**: Compose Navigation
- **并发与状态**: Coroutines + Flow/StateFlow
- **日历组件**: 优先使用可维护的 Compose 实现；引入第三方库前先评估许可证、维护状态和包体积
- **图表**: 优先选择 Compose 兼容且可无障碍访问的方案；图表不是 Phase 1 阻塞项
- **通知**: AlarmManager 处理用户明确设置的精确时间提醒，WorkManager 负责周期性校准、补偿和非精确后台任务
- **最低 SDK**: Android 8.0 (API 26)
- **compileSdk / targetSdk**: 开始实现时采用 Android 官方稳定版与发布要求，并在 `gradle/libs.versions.toml` 或版本目录中集中记录；不要把已过时的 SDK 版本硬编码在 Prompt 中
- **构建环境**: 使用项目实际选定的 Android Gradle Plugin、Gradle 和 JDK 兼容组合，并在 README 中记录

> 若未来确实需要同时支持 iOS，应另开技术评审，不在本 Prompt 中同时维护 Flutter 与 Android 两套实现要求。

---

## 三、功能需求详细说明

### 功能一：每日待办事项（Todo）

**核心功能:**
- 创建待办事项：支持标题、备注/描述、优先级（高/中/低/无）、截止日期与时间
- 编辑和删除待办事项
- 标记完成 / 取消完成（滑动或点击复选框）
- 待办事项分类/标签（如：工作、个人、学习，支持自定义标签及颜色）
- 支持子任务（一个待办下可挂多个子任务，独立勾选）
- 按日期筛选查看：今天、明天、本周、全部
- 按优先级 / 标签筛选
- 搜索功能（按关键词搜索标题和备注）
- 排序：按创建时间 / 截止时间 / 优先级 / 手动拖拽排序
- 本地通知提醒：到达截止时间时推送通知
- 数据统计：今日完成数、待完成数、本周完成率

**交互细节:**
- 主界面以列表形式展示当天待办，顶部显示日期和完成进度
- 左滑显示删除按钮，右滑显示完成
- 支持长按拖拽排序
- 空状态展示友好插画和引导文案
- 支持批量操作（选中多个后批量删除 / 标记完成）

---

### 功能二：订阅费用管理（Subscription Manager）

**核心功能:**
- 添加订阅记录：应用名称、订阅费用、币种（默认人民币 ¥）、计费周期（月付/年付/周付/季付）、下次扣费日期、订阅开始日期、备注
- 为每个订阅设置分类标签（如：影音娱乐、工具软件、云存储、会员服务等）
- 编辑和删除订阅记录
- 订阅列表页：按下次扣费日期排序展示，显示距离下次扣费的天数
- 即将到期提醒：在扣费前 1 天 / 3 天 / 7 天（可配置）推送通知
- 月度费用总览：当前月所有订阅的总支出
- 年度费用总览：全年订阅总支出
- 费用统计图表：
  - 月度支出柱状图（最近 6 个月）
  - 各订阅占比饼图
  - 月付 vs 年付费用对比
  - 分类支出对比
- 支持手动标记"已取消"的订阅（停止扣费提醒但保留历史记录）
- 数据导出为 CSV

**交互细节:**
- 添加订阅时提供常用应用快捷选择（如：Netflix、Spotify、iCloud、微信读书等预设列表，用户也可自定义）
- 顶部卡片显示本月总支出和同比增长
- 列表项左滑可标记取消 / 删除
- 点击列表项进入详情页，展示历史扣费记录

---

### 功能三：日程安排（日历 Calendar）

**核心功能:**
- 月视图日历：展示整月，有日程的日期显示圆点或缩略事件标记
- 周视图：展示一周的时间轴，按小时划分
- 日视图：展示当天的时间轴，按小时排列日程
- 添加日程：标题、开始/结束时间、全天事件开关、地点、参与者（文本备注）、备注、颜色标记、提醒设置
- 编辑和删除日程
- 日程冲突检测：添加日程时如果与已有日程时间重叠，提示用户
- 拖拽调整日程时间
- 月视图支持点击日期切换到日视图
- 与待办事项联动（可选）：待办事项的截止时间可在日历中显示
- 日程提醒通知

**交互细节:**
- 月视图支持双指缩放切换到周视图 / 日视图
- 底部浮动按钮（FAB）快速添加日程
- 日程卡片使用不同颜色区分类型
- 支持日程重复设置（每天/每周/每月/每年/自定义）
- 日期选择器使用原生 DatePicker / TimePicker

---

### 功能四：打卡任务（Habit Tracker）

**核心功能:**
- 创建打卡任务：任务名称、图标（emoji 或图标库选择）、目标频率（每天 / 每周 N 次 / 每月 N 次 / 自定义星期几）、打卡颜色主题、开始日期、备注
- 一键打卡 / 取消打卡
- 打卡记录日历视图：月历上展示哪些天已打卡（实心圆 = 已打卡）
- 连续打卡天数（streak）统计
- 历史打卡记录查看
- 打卡完成进度：今日已打卡 / 今日应打卡数
- 周/月打卡率统计
- 打卡提醒通知（可设置每日提醒时间）
- 支持暂停 / 归档打卡任务（不删除但停止提醒）
- 打卡热力图（类似 GitHub 贡献图的年度热力图）

**交互细节:**
- 打卡主界面以卡片形式展示所有进行中的任务
- 点击卡片上的"打卡"按钮即完成当日打卡，有动画反馈
- 长按卡片查看详情和历史
- 连续打卡天数用醒目标签展示（如"连续 30 天 🔥"）
- 达成里程碑（7天/30天/100天）时有庆祝动画

---

## 四、数据模型设计

### 4.1 Todo（待办事项）
```
Todo {
  id: Long (主键，自增)
  title: String (标题，非空)
  description: String? (备注)
  priority: Enum { NONE, LOW, MEDIUM, HIGH } (优先级)
  dueAt: Instant? (截止时间；为空表示无截止时间)
  isCompleted: Boolean (是否完成，默认 false)
  completedAt: Instant? (完成时间)
  createdAt: Instant (创建时间)
  updatedAt: Instant (更新时间)
  parentId: Long? (父任务ID，用于子任务)
  sortOrder: Int (排序顺序)
}

Tag {
  id: Long (主键，自增)
  name: String (名称，唯一，非空)
  color: Int (颜色)
  createdAt: Instant
}

TodoTagCrossRef {
  todoId: Long (外键 -> Todo.id)
  tagId: Long (外键 -> Tag.id)
  // 联合主键: (todoId, tagId)
}
```

> Todo 与标签是多对多关系；不要把多个标签拼接成逗号分隔字符串。`parentId` 只允许形成树，删除父任务时必须由产品规则明确选择级联删除或解除父子关系。

### 4.2 Subscription（订阅）
```
Subscription {
  id: Long (主键，自增)
  appName: String (应用名称，非空)
  amountMinor: Long (货币最小单位金额，例如人民币分)
  currency: String (ISO 4217 货币代码，默认 "CNY")
  billingCycle: Enum { WEEKLY, MONTHLY, QUARTERLY, YEARLY } (计费周期)
  nextBillingDate: LocalDate (下次扣费日期)
  startDate: LocalDate (订阅开始日期)
  category: String? (分类)
  note: String? (备注)
  isActive: Boolean (是否活跃，默认 true)
  cancelDate: LocalDate? (取消日期)
  createdAt: Instant
  updatedAt: Instant
}

SubscriptionPayment {
  id: Long (主键，自增)
  subscriptionId: Long (外键 -> Subscription.id)
  amountMinor: Long (实际扣费金额)
  currency: String
  paidAt: LocalDate (扣费日期)
  note: String?
}

SubscriptionReminder {
  subscriptionId: Long (外键 -> Subscription.id)
  daysBefore: Int (提前天数，例如 1、3、7)
  // 联合主键: (subscriptionId, daysBefore)
}
```

> 订阅统计使用 `amountMinor` 计算，不使用浮点数。取消订阅只停止未来提醒，不删除订阅和历史扣费记录。

### 4.3 Schedule / Event（日程）
```
Schedule {
  id: Long (主键，自增)
  title: String (标题，非空)
  startAt: Instant? (非全天事件的开始时间)
  endAt: Instant? (非全天事件的结束时间，必须晚于开始时间)
  isAllDay: Boolean (是否全天事件，默认 false)
  allDayStartDate: LocalDate? (全天事件开始日期)
  allDayEndDate: LocalDate? (全天事件结束日期，含当天)
  location: String? (地点)
  participants: String? (参与者，文本)
  note: String? (备注)
  color: Int (颜色标记)
  reminderMinutes: Int? (提前提醒分钟数，如 15/30/60；Phase 2 先支持一个提醒)
  repeatRule: Enum { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM } (重复规则)
  repeatInterval: Int (重复间隔，默认 1)
  repeatDaysOfWeek: String? (自定义星期，例如 "1,3,5")
  repeatEndDate: LocalDate? (重复结束日期)
  timeZone: String (IANA 时区 ID)
  createdAt: Instant
  updatedAt: Instant
}

ScheduleException {
  scheduleId: Long (外键 -> Schedule.id)
  occurrenceDate: LocalDate (重复规则中的发生日期)
  isCancelled: Boolean (是否取消本次发生)
  // 联合主键: (scheduleId, occurrenceDate)
}
```

> 日程必须满足两组字段之一：非全天事件使用 `startAt/endAt`，全天事件使用 `allDayStartDate/allDayEndDate`。重复日程按时间窗口展开，数据库只保存规则与例外，不能无限预生成实例。全天事件的日期语义使用 `LocalDate` 处理，不能因时区转换跨天。

### 4.4 Habit（打卡任务）
```
Habit {
  id: Long (主键，自增)
  name: String (任务名称，非空)
  icon: String (图标 emoji 或标识)
  color: Int (主题颜色)
  frequencyType: Enum { DAILY, WEEKLY, MONTHLY, CUSTOM } (频率类型)
  frequencyValue: Int (频率值，如每周3次=3；每天任务固定为 1)
  customDays: String? (自定义星期几，如 "1,3,5" 表示周一三五)
  startDate: LocalDate (开始日期)
  reminderTime: LocalTime? (提醒时间)
  isArchived: Boolean (是否归档，默认 false)
  pausedUntil: LocalDate? (暂停到某日，可为空)
  createdAt: Instant
  updatedAt: Instant
}
```

> “连续打卡”必须按频率计算：每天任务按连续日期计算；每周/月任务按目标周期是否完成计算，不能统一套用连续自然日算法。归档后保留历史记录但不再生成应打卡项和提醒。

### 4.5 HabitRecord（打卡记录）
```
HabitRecord {
  id: Long (主键，自增)
  habitId: Long (外键 -> Habit.id)
  date: LocalDate (打卡日期)
  createdAt: Instant (打卡时间)
  // 联合唯一索引: (habitId, date) 防止同一天重复打卡
}
```

---

## 五、UI/UX 设计规范

### 5.1 整体风格
- 采用 Material Design 3 (Material You) 设计语言
- 支持 Light / Dark 主题切换，跟随系统设置
- 使用动态取色（Dynamic Color, Android 12+）或预设主题色
- 主色调建议：蓝绿色系（#00695C teal）或靛蓝系（#3F51B5 indigo）
- 卡片式布局，圆角 16dp，微阴影
- 充足的留白和间距，信息密度适中
- 字体：使用系统默认字体或 Google Sans / Roboto

### 5.2 导航结构
- 底部导航栏 (Bottom Navigation Bar) 切换四大功能模块：
  - 待办 (Todo)
  - 日程 (Calendar)
  - 订阅 (Subscription)
  - 打卡 (Habit)
- 每个模块对应一个 Tab 页面
- 每个页面右上角有设置入口
- Phase 1 只显示已实现的待办入口和设置入口；后续 Phase 完成并验收后再依次加入日程、订阅和打卡 Tab，不使用空白占位页

### 5.3 动画与交互
- 页面切换使用淡入淡出 + 滑动过渡
- 列表项添加/删除使用动画
- 打卡完成使用粒子或对勾动画
- 按钮点击有涟漪效果 (Ripple Effect)
- 下拉刷新支持
- 空状态、加载状态、错误状态都要有对应 UI

### 5.4 图标
- 使用 Material Symbols (Outlined 风格)
- 待办：check_circle / checklist
- 日程：calendar_month / event
- 订阅：payments / subscriptions / credit_card
- 打卡：repeat / local_fire_department

---

## 六、通知与提醒

- 创建通知渠道，并根据 Android 版本处理通知权限；用户拒绝权限时，界面必须明确说明提醒不可用，但核心数据功能仍可使用
- Android 13 及以上处理 `POST_NOTIFICATIONS` 权限；需要精确闹钟时按系统版本处理精确闹钟权限和用户设置，不得假设权限永远可用
- 对用户明确设置的具体时间提醒，使用 AlarmManager；使用 WorkManager 做每日校准、设备重启后的补排程和非精确后台任务
- 待办截止提醒：到达截止时间前 15 分钟推送通知
- 订阅扣费提醒：扣费前 1/3/7 天推送通知（用户可配置）
- 日程提醒：按用户设置的提前时间推送
- 打卡提醒：每天固定时间推送提醒打卡
- 通知点击后直接跳转至对应详情页
- 支持在设置中统一开关各类通知
- 每条提醒必须可根据业务实体 ID 去重；编辑、完成、取消、归档或删除实体后同步取消旧提醒
- 处理时区、夏令时、设备重启、应用升级和系统省电限制；无法保证精确触发时要保持数据正确并避免重复通知

### 通知边界

- 通知不是业务数据源，提醒状态应可由数据库重新计算。
- Phase 1 只实现待办截止提醒和通知设置骨架；订阅、日程、打卡提醒在对应 Phase 实现。
- 不在当前版本引入远程推送服务，所有提醒均为本地通知。

---

## 七、设置页面

- 主题切换：浅色 / 深色 / 跟随系统
- 通知设置：分别控制待办 / 订阅 / 日程 / 打卡的通知开关
- 提醒时间设置：订阅提前几天提醒、打卡每天几点提醒
- 数据备份与恢复：导出数据库到本地文件 / 从文件导入
- 数据清除：清除所有数据（需二次确认）
- 关于页面：版本号、开源协议
- 货币单位设置
- 日期格式设置（YYYY-MM-DD / MM-DD-YYYY / DD-MM-YYYY）

---

## 八、非功能需求

1. **性能**: 应用启动时间 < 2 秒，页面切换流畅无卡顿（60fps）
2. **离线优先**: 所有数据存储在本地 Room/SQLite 数据库，无需联网即可使用全部功能
3. **数据安全**: 敏感数据可选加密存储 (SQLCipher)，应用切换到后台时遮罩敏感信息
4. **适配**: 适配不同屏幕尺寸（手机和平板），支持横屏（可选）
5. **无障碍**: 支持屏幕阅读器 (TalkBack)，按钮有 contentDescription
6. **国际化**: 预留 i18n 框架，默认中文，预留英文翻译接口
7. **最低支持**: Android 8.0 (API 26)
8. **应用体积**: APK 控制在 30MB 以内；如果第三方库导致超出，优先移除非核心依赖
9. **数据库迁移**: 每次 schema 变更都要提供 Room migration；禁止在正式数据上使用 destructive migration
10. **测试**: 为 DAO、核心 Use Case、ViewModel 状态转换和关键 Compose 交互提供测试；至少覆盖 CRUD、完成/取消、筛选、提醒去重和数据库迁移
11. **错误处理**: 数据库写入失败、通知权限不足、无效日期/金额等情况要显示可理解的错误状态，不能静默吞异常
12. **隐私**: 不采集分析数据，不申请与功能无关的权限；备份导出前明确提示文件包含个人数据

---

## 九、项目结构建议（Kotlin / Compose 方案）

```
app/
├── src/main/
│   ├── java/com/example/lifemanager/
│   │   ├── MainActivity.kt                    // 入口 Activity
│   │   ├── LifeManagerApp.kt                  // Application 类
│   │   ├── di/                                 // 依赖注入
│   │   │   ├── AppModule.kt
│   │   │   ├── DatabaseModule.kt
│   │   │   └── RepositoryModule.kt
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── LifeManagerDatabase.kt      // Room 数据库
│   │   │   │   ├── dao/
│   │   │   │   │   ├── TodoDao.kt
│   │   │   │   │   ├── TagDao.kt
│   │   │   │   │   ├── TodoTagDao.kt
│   │   │   │   │   ├── SubscriptionDao.kt
│   │   │   │   │   ├── SubscriptionPaymentDao.kt
│   │   │   │   │   ├── ScheduleDao.kt
│   │   │   │   │   ├── HabitDao.kt
│   │   │   │   │   └── HabitRecordDao.kt
│   │   │   │   └── entity/
│   │   │   │       ├── TodoEntity.kt
│   │   │   │       ├── TagEntity.kt
│   │   │   │       ├── TodoTagCrossRef.kt
│   │   │   │       ├── SubscriptionEntity.kt
│   │   │   │       ├── SubscriptionPaymentEntity.kt
│   │   │   │       ├── ScheduleEntity.kt
│   │   │   │       ├── HabitEntity.kt
│   │   │   │       └── HabitRecordEntity.kt
│   │   │   ├── repository/
│   │   │   │   ├── TodoRepository.kt
│   │   │   │   ├── SubscriptionRepository.kt
│   │   │   │   ├── ScheduleRepository.kt
│   │   │   │   └── HabitRepository.kt
│   │   │   └── mapper/                        // Entity <-> Domain Model 转换
│   │   ├── domain/
│   │   │   ├── model/
│   │   │   │   ├── Todo.kt
│   │   │   │   ├── Subscription.kt
│   │   │   │   ├── Schedule.kt
│   │   │   │   └── Habit.kt
│   │   │   └── usecase/
│   │   │       ├── todo/
│   │   │       ├── subscription/
│   │   │       ├── schedule/
│   │   │       └── habit/
│   │   ├── ui/
│   │   │   ├── theme/                         // Material3 主题
│   │   │   │   ├── Color.kt
│   │   │   │   ├── Theme.kt
│   │   │   │   └── Type.kt
│   │   │   ├── components/                     // 通用 UI 组件
│   │   │   │   ├── ConfirmDialog.kt
│   │   │   │   ├── EmptyState.kt
│   │   │   │   ├── PriorityChip.kt
│   │   │   │   └── ...
│   │   │   ├── navigation/
│   │   │   │   └── NavGraph.kt
│   │   │   ├── todo/                          // 待办模块
│   │   │   │   ├── TodoScreen.kt
│   │   │   │   ├── TodoViewModel.kt
│   │   │   │   └── components/
│   │   │   ├── subscription/                  // 订阅模块
│   │   │   │   ├── SubscriptionScreen.kt
│   │   │   │   ├── SubscriptionViewModel.kt
│   │   │   │   └── components/
│   │   │   ├── schedule/                      // 日程模块
│   │   │   │   ├── ScheduleScreen.kt
│   │   │   │   ├── ScheduleViewModel.kt
│   │   │   │   └── components/
│   │   │   ├── habit/                         // 打卡模块
│   │   │   │   ├── HabitScreen.kt
│   │   │   │   ├── HabitViewModel.kt
│   │   │   │   └── components/
│   │   │   └── settings/
│   │   │       └── SettingsScreen.kt
│   │   ├── notification/
│   │   │   ├── NotificationHelper.kt
│   │   │   ├── ReminderScheduler.kt
│   │   │   └── ReminderWorker.kt
│   │   └── util/
│   │       ├── DateUtils.kt
│   │       └── Extensions.kt
│   ├── res/
│   │   ├── drawable/
│   │   ├── values/
│   │   │   ├── strings.xml
│   │   │   ├── colors.xml
│   │   │   └── themes.xml
│   │   └── mipmap/                            // 应用图标
│   └── AndroidManifest.xml
└── build.gradle.kts
```

> 实际工程可以按模块规模调整目录；除示例目录中的基础实体外，还应为 `SubscriptionReminder` 和 `ScheduleException` 提供对应 Entity/DAO。必须保留清晰的依赖方向：`ui -> domain -> data`；`data` 不依赖 `ui`，ViewModel 不直接持有 Activity 或 DAO。通用的数据库转换器、通知调度器和日期计算应有独立单元测试。

---

## 十、构建与部署

1. **构建工具**: Gradle (Kotlin DSL)
2. **签名**: debug 使用本地调试签名；release 使用外部注入的签名配置，密钥不得提交到仓库
3. **构建命令**: `./gradlew assembleRelease` 生成 APK
4. **输出**: `app/build/outputs/apk/release/app-release.apk`
5. **部署方式**:
   - Debug 包：USB 连接手机直接 `./gradlew installDebug`
   - Release APK：生成后传到手机安装（需开启"允许安装未知来源应用"）
   - 如需上架 Google Play：需配置 App Bundle (AAB) `./gradlew bundleRelease`

---

## 十一、开发优先级（建议分阶段实现）

### Phase 1 — 基础框架 + 待办事项（核心）
- 项目搭建、主题配置、导航框架
- Room 数据库 + DAO + Repository + 首次 migration
- 待办事项 CRUD + 列表 + 详情/编辑页 + 完成标记
- 标签、优先级、截止时间、搜索和基础筛选
- 今日完成数 / 待完成数统计
- 待办截止提醒的通知渠道、权限处理和去重调度
- DAO、Use Case、ViewModel 和核心 UI 测试

**Phase 1 验收标准**：新安装后无需联网即可创建、编辑、删除和完成待办；重启应用后数据仍在；今天/全部筛选、搜索和截止时间正确；通知权限被拒绝时应用仍可正常使用；执行项目约定的构建与测试命令成功。

### Phase 2 — 日程安排
- 日历月视图 / 周视图 / 日视图
- 日程 CRUD + 提醒
- 日程冲突检测

### Phase 3 — 打卡任务
- 打卡任务 CRUD
- 打卡记录 + 日历展示
- 连续打卡统计 + 热力图

### Phase 4 — 订阅费用管理
- 订阅 CRUD + 扣费提醒
- 费用统计图表
- 数据导出

### Phase 5 — 完善
- 设置页面
- 数据备份恢复
- 深色模式适配
- 无障碍支持
- 性能优化

### 阶段边界

- Phase 1 未完成并验收前，不实现完整日历、订阅统计、打卡热力图、云同步或复杂动画。
- 每个 Phase 完成后都要更新 README、数据库版本和测试，并记录已知限制。
- “完成”指功能可运行且通过对应验收标准，不以生成大量占位页面或空 ViewModel 作为完成。

---

## 十二、给 AI 的关键指令

> 请根据以上需求文档，使用 **Kotlin + Jetpack Compose** 开发一款 Android 应用。
>
> 要求：
> 1. 严格按照 MVVM + Clean Architecture 架构组织代码
> 2. 所有数据使用 Room 本地存储，离线可用
> 3. UI 使用 Jetpack Compose + Material Design 3，支持深色模式
> 4. 最终四大功能模块通过底部导航切换、共享同一数据库；当前 Phase 只展示已经实现并验收的模块入口
> 5. 严格按当前指定 Phase 实现功能；不要为了看起来完整而提前创建未实现模块的空页面或假数据
> 6. 每个功能模块需包含：列表页、详情页/编辑页、统计页（如适用）
> 7. 提供完整的 build.gradle.kts 配置和 AndroidManifest.xml
> 8. 确保代码可编译运行，不要省略关键实现
> 9. 按照上述 Phase 1-5 的优先级分阶段实现，先完成核心功能
> 10. 为每个模块编写 ViewModel，使用 StateFlow/Flow 管理状态
> 11. 先阅读仓库现状再修改文件；不得覆盖用户已有改动，不得使用 destructive migration，不得提交密钥或个人数据
> 12. 每个 Phase 都要同步补充测试、README 和验收结果；报告中明确说明已完成、未完成和已知限制
> 13. 如果需求、现有代码或平台限制存在冲突，先指出冲突并给出选择，不要默默采用会破坏数据的方案
>
> 如果因为上下文长度限制无法一次性完成所有功能，请按 Phase 顺序逐个实现，每次完成后告诉我进度，我会让你继续。
