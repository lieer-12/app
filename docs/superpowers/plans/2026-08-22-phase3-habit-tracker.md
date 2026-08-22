# Phase 3 打卡模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在共享 Room 数据库中交付离线打卡模块：习惯 CRUD、每日记录、月历、频率感知的连续统计和年度热力图。

**Architecture:** 新增 `Habit` 和 `HabitRecord` 沿用 `domain → data → ui`。DAO 只保存习惯与不可重复的按日记录；`HabitRules` 用纯函数推导频率、目标和连续次数；`HabitViewModel` 通过 Flow/StateFlow 向 Compose 提供单一状态。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Room、Hilt、Coroutines Flow/StateFlow、java.time、JUnit、AndroidX Room testing。

**Spec:** `docs/superpowers/specs/2026-08-22-habit-tracker-design.md`

## Global Constraints

- 只实现 Phase 3 打卡；不创建 Subscription、提醒、暂停、归档的字段、导航、页面或假数据。
- 所有数据保存在共享 Room；仅增加显式 `Migration(2, 3)`，不使用 destructive migration。
- `DAILY`/`CUSTOM` 按应打卡日计算连续；`WEEKLY`/`MONTHLY` 按连续达成目标的自然周/自然月计算。
- Gradle 使用 JDK 25，Android 字节码目标保持 Java/Kotlin 17。
- 使用 Compose 绘制日历与热力图；深色主题与辅助功能不依赖颜色作为唯一信息。
- 每项任务按 RED → GREEN → 验证 → 原子提交执行，最终更新 README、验收报告和 Room schema。

---

## File Structure

- `domain/model/Habit.kt`：频率、习惯、记录和进度值对象。
- `domain/usecase/HabitRules.kt`：校验、应打卡日、周期进度与连续计算。
- `domain/repository/HabitRepository.kt`：领域读写边界。
- `data/local/entity/HabitEntity.kt`、`HabitRecordEntity.kt`、`dao/HabitDao.kt`：Room 表、唯一索引、级联和事务打卡。
- `data/repository/HabitRepositoryImpl.kt`：Room/领域映射。
- `ui/habit/HabitUiState.kt`、`HabitViewModel.kt`、`HabitScreen.kt`：状态、事件和 Material 3 页面。
- 现有数据库、转换器、Hilt、导航、设置、构建与文档：仅做必要接线。

### Task 1: 领域模型、频率规则和单元测试

**Files:**
- Create: `app/src/main/java/com/example/lifemanager/domain/model/Habit.kt`
- Create: `app/src/main/java/com/example/lifemanager/domain/usecase/HabitRules.kt`
- Test: `app/src/test/java/com/example/lifemanager/domain/usecase/HabitRulesTest.kt`

**Interfaces:**
- Produces `HabitFrequencyType`, `Habit`, `HabitRecord`, `HabitPeriodProgress`.
- Produces `HabitRules.validate(name, frequencyType, frequencyValue, customDays): String?`, `isExpectedOn(habit, date)`, `periodProgress(habit, records, referenceDate)`, `currentStreak(habit, records, today)` and `longestStreak(habit, records)`.

- [ ] **Step 1: Write the failing rules tests**

```kotlin
@Test fun `custom habit only expects selected weekdays`() {
    val habit = habit(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
    assertTrue(HabitRules.isExpectedOn(habit, LocalDate.of(2026, 8, 24)))
    assertFalse(HabitRules.isExpectedOn(habit, LocalDate.of(2026, 8, 25)))
}

@Test fun `weekly streak counts completed target weeks`() {
    val habit = habit(frequencyType = HabitFrequencyType.WEEKLY, frequencyValue = 2)
    assertEquals(2, HabitRules.currentStreak(habit, recordsOn("2026-08-10", "2026-08-12", "2026-08-17", "2026-08-19"), LocalDate.of(2026, 8, 22)))
}
```

Add tests for blank name, empty custom weekday selection, weekly/monthly target bounds, daily/custom streaks, monthly completion and longest streak.

- [ ] **Step 2: Run the focused test to confirm RED**

Run: `& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitRulesTest'`

Expected: compilation fails because habit types and `HabitRules` are absent.

- [ ] **Step 3: Implement the minimal complete rule layer**

```kotlin
enum class HabitFrequencyType { DAILY, WEEKLY, MONTHLY, CUSTOM }
data class Habit(
    val id: Long = 0, val name: String, val iconKey: String = "Check",
    val color: Int = 0xFF00695C.toInt(), val frequencyType: HabitFrequencyType = HabitFrequencyType.DAILY,
    val frequencyValue: Int = 1, val customDaysOfWeek: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate, val note: String? = null,
    val createdAt: Instant = Instant.now(), val updatedAt: Instant = Instant.now(),
)
data class HabitRecord(val id: Long = 0, val habitId: Long, val date: LocalDate, val createdAt: Instant)
data class HabitPeriodProgress(val completed: Int, val target: Int, val isComplete: Boolean)
```

Use ISO Monday–Sunday weeks, count unique dates, exclude dates before `startDate`, and accept custom records only on selected weekdays. Daily/custom walk backward over expected dates; weekly/monthly walk backward over complete calendar periods. Return `习惯名称不能为空` for blank input, and precise messages for invalid targets/custom-day selections.

- [ ] **Step 4: Run focused tests to confirm GREEN**

Run: `& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitRulesTest'`

Expected: all `HabitRulesTest` cases pass.

- [ ] **Step 5: Commit domain work**

```powershell
git add app/src/main/java/com/example/lifemanager/domain/model/Habit.kt app/src/main/java/com/example/lifemanager/domain/usecase/HabitRules.kt app/src/test/java/com/example/lifemanager/domain/usecase/HabitRulesTest.kt
git commit -m "feat: add habit domain rules"
```

### Task 2: Room v3、仓库和迁移测试

**Files:**
- Create: `app/src/main/java/com/example/lifemanager/data/local/entity/HabitEntity.kt`
- Create: `app/src/main/java/com/example/lifemanager/data/local/entity/HabitRecordEntity.kt`
- Create: `app/src/main/java/com/example/lifemanager/data/local/dao/HabitDao.kt`
- Create: `app/src/main/java/com/example/lifemanager/domain/repository/HabitRepository.kt`
- Create: `app/src/main/java/com/example/lifemanager/data/repository/HabitRepositoryImpl.kt`
- Test: `app/src/androidTest/java/com/example/lifemanager/data/local/HabitDaoTest.kt`
- Test: `app/src/androidTest/java/com/example/lifemanager/data/local/HabitMigrationTest.kt`
- Modify: `data/local/Converters.kt`, `data/local/LifeManagerDatabase.kt`, `di/AppModule.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create/update generated: `app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/3.json`

**Interfaces:**
- Produces `HabitRepository.observeHabits(): Flow<List<Habit>>`, `observeRecords(start, end): Flow<List<HabitRecord>>`, `saveHabit(habit): Long`, `deleteHabit(id)`, `toggleRecord(habitId, date): Boolean`.
- `toggleRecord` returns `true` when it inserted the record and `false` when it removed one.

- [ ] **Step 1: Write failing DAO and migration tests**

```kotlin
@Test fun duplicateDayIsStoredOnceAndDeletingHabitCascades() = runBlocking {
    val habitId = database.habitDao().upsert(habitEntity())
    database.habitDao().insertRecord(HabitRecordEntity(habitId = habitId, date = day.toEpochDay(), createdAt = 1L))
    database.habitDao().insertRecord(HabitRecordEntity(habitId = habitId, date = day.toEpochDay(), createdAt = 2L))
    assertEquals(1, database.habitDao().recordsBetween(day.toEpochDay(), day.toEpochDay()).first().size)
    database.habitDao().deleteHabit(habitId)
    assertEquals(0, database.habitDao().recordsBetween(day.toEpochDay(), day.toEpochDay()).first().size)
}
```

Use `MigrationTestHelper` to create v2, insert a `todos` row, apply `LifeManagerDatabase.MIGRATIONS`, then assert the prior row and the `habits`/`habit_records` tables exist. Add Room testing to the version catalog and Android-test dependencies.

- [ ] **Step 2: Run the DAO test to confirm RED**

Run: `& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:connectedDebugAndroidTest --tests '*HabitDaoTest'`

Expected: source fails to compile before implementation. If no device/emulator is attached, retain the test source and record only the no-device limitation.

- [ ] **Step 3: Implement tables, transaction, mapper and migration**

Persist local dates as epoch days and timestamps as epoch millis. `HabitEntity` stores `HabitFrequencyType`, nullable comma-separated sorted ISO weekdays and a `color` Int. `HabitRecordEntity` has an auto-ID, cascade FK to `HabitEntity`, `Index("habitId")`, and `Index(value = ["habitId", "date"], unique = true)`. `HabitDao` uses `@Transaction` to read the record, delete it if present, otherwise `INSERT IGNORE` a new record.

Append (never replace) the following v2→v3 schema creation to `MIGRATIONS`, then register both entities, DAO and `version = 3`:

```sql
CREATE TABLE IF NOT EXISTS habits (
 id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, iconKey TEXT NOT NULL,
 color INTEGER NOT NULL, frequencyType TEXT NOT NULL, frequencyValue INTEGER NOT NULL,
 customDaysOfWeek TEXT, startDate INTEGER NOT NULL, note TEXT,
 createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
)
CREATE TABLE IF NOT EXISTS habit_records (
 id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, habitId INTEGER NOT NULL, date INTEGER NOT NULL,
 createdAt INTEGER NOT NULL, FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE
)
CREATE INDEX IF NOT EXISTS index_habit_records_habitId ON habit_records (habitId)
CREATE UNIQUE INDEX IF NOT EXISTS index_habit_records_habitId_date ON habit_records (habitId, date)
```

Map in `HabitRepositoryImpl`, trim nullable note, preserve original `createdAt` on updates, and bind the implementation with Hilt. Generate and commit Room schema v3.

- [ ] **Step 4: Verify persistence checks**

Run:

```powershell
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitRulesTest'
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:assembleDebug
```

Then run `HabitDaoTest` and `HabitMigrationTest` on an attached device/emulator; do not mark unrun instrumentation tests as passed.

- [ ] **Step 5: Commit persistence work**

```powershell
git add app/src/main/java/com/example/lifemanager/data app/src/main/java/com/example/lifemanager/domain/repository/HabitRepository.kt app/src/main/java/com/example/lifemanager/di/AppModule.kt app/src/androidTest/java/com/example/lifemanager/data/local/HabitDaoTest.kt app/src/androidTest/java/com/example/lifemanager/data/local/HabitMigrationTest.kt app/schemas gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: persist habits with Room migration"
```

### Task 3: StateFlow ViewModel 和测试

**Files:**
- Create: `app/src/main/java/com/example/lifemanager/ui/habit/HabitUiState.kt`
- Create: `app/src/main/java/com/example/lifemanager/ui/habit/HabitViewModel.kt`
- Test: `app/src/test/java/com/example/lifemanager/ui/habit/HabitViewModelTest.kt`

**Interfaces:**
- Consumes `HabitRepository`, `HabitRules`, `@IoDispatcher` and Task 1 values.
- Produces `uiState` and `openEditor`, `closeEditor`, field setters, `saveHabit`, `deleteHabit`, `toggleToday`, `selectStatsHabit`, `previousMonth`, `nextMonth`, `selectTab`.

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
@Test fun `save rejects blank habit name`() = runTest {
    val model = HabitViewModel(FakeHabitRepository(), dispatcher)
    model.openEditor(); model.onNameChanged("  "); model.saveHabit(); advanceUntilIdle()
    assertEquals("习惯名称不能为空", model.uiState.value.editor.validationMessage)
}
@Test fun `toggle updates completed state from repository flow`() = runTest {
    val model = HabitViewModel(FakeHabitRepository(habits = listOf(habit(id = 1))), dispatcher)
    model.toggleToday(1); advanceUntilIdle()
    assertTrue(model.uiState.value.cards.single().completedToday)
}
```

The fake repository uses `MutableStateFlow` for habits and records and toggles one record per `(habitId, date)`.

- [ ] **Step 2: Run focused test to confirm RED**

Run: `& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitViewModelTest'`

Expected: compilation fails because state and ViewModel are absent.

- [ ] **Step 3: Implement state composition and user events**

Use `visibleMonth.flatMapLatest { repository.observeRecords(rangeStart, rangeEnd) }`, where the range includes the month, today and preceding 53 weeks. Combine habits, records, tab, editor, selected stats habit and errors with `stateIn(viewModelScope, SharingStarted.Eagerly, HabitUiState())`. Derive cards/calendar/heatmap summaries in pure helpers, not Composables. Select the first habit if the selected statistics habit disappears. Validate before save; keep the editor open on errors.

- [ ] **Step 4: Run focused tests to confirm GREEN**

Run:

```powershell
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitViewModelTest'
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:assembleDebug
```

Expected: focused ViewModel tests pass and debug compilation succeeds.

- [ ] **Step 5: Commit state work**

```powershell
git add app/src/main/java/com/example/lifemanager/ui/habit/HabitUiState.kt app/src/main/java/com/example/lifemanager/ui/habit/HabitViewModel.kt app/src/test/java/com/example/lifemanager/ui/habit/HabitViewModelTest.kt
git commit -m "feat: add habit state management"
```

### Task 4: Compose UI、导航和现有文案

**Files:**
- Create: `app/src/main/java/com/example/lifemanager/ui/habit/HabitScreen.kt`
- Modify: `app/src/main/java/com/example/lifemanager/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/example/lifemanager/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes only `HabitViewModel` state and events through `hiltViewModel()`.
- Produces `HabitRoute`; no Subscription route or UI package.

- [ ] **Step 1: Add a failing navigation-to-screen reference**

Add `HabitRoute` (`habit`), `LocalFireDepartment` or `Repeat` icon and title `打卡` to the existing bottom navigation. Add the destination invocation, run `:app:assembleDebug`, and confirm it fails until the screen is implemented.

- [ ] **Step 2: Implement task list and editor/detail UI**

Create a Material 3 `Scaffold` with top bar, `TabRow` (`任务`, `统计`), `LazyColumn` cards and FAB. Cards expose icon/name, frequency, streak, applicable target progress, and a content-described control changing between `打卡` and `撤销打卡`. Card click/FAB opens an editor with name, icon, color, frequency, weekly/monthly target, custom weekday chips, start-date picker, note, save/cancel, validation text and editing-only delete.

- [ ] **Step 3: Implement statistics UI**

Add a habit selector, month navigation and seven-column month grid. Render completed valid dates as filled circles with labels such as `2026年8月22日，已打卡`. Show current/longest streak and period progress. Render a 53×7 selected-habit heatmap with theme-safe intensity plus textual legend `无记录`、`1 次`、`2 次及以上`; color never stands alone.

- [ ] **Step 4: Apply only Phase 3 wiring**

Do not modify `AndroidManifest.xml` because this Phase has no habit receiver or permission. Update settings copy to identify Todo, Schedule and Habit as enabled and Subscription as future scope.

- [ ] **Step 5: Build and commit UI**

Run: `& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:assembleDebug`

Expected: debug APK compiles and no Subscription source/destination exists.

```powershell
git add app/src/main/java/com/example/lifemanager/ui/habit app/src/main/java/com/example/lifemanager/ui/navigation/NavGraph.kt app/src/main/java/com/example/lifemanager/ui/settings/SettingsScreen.kt
git commit -m "feat: add habit tracker UI"
```

### Task 5: 验收工件、验证和发布提交

**Files:**
- Create: `tools/check-phase3-structure.ps1`
- Create: `docs/phase3-acceptance.md`
- Modify: `README.md`

**Interfaces:**
- Produces a repeatable static check and evidence-based Phase 3 acceptance record.

- [ ] **Step 1: Create structural checker**

Require the Task 1–3 source/test paths, database `version = 3`, both `Migration(1, 2)` and `Migration(2, 3)`, Habit navigation and exported `3.json`. Fail if `ui/subscription` exists.

- [ ] **Step 2: Run deterministic verification**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase3-structure.ps1
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitRulesTest'
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:testDebugUnitTest --tests '*HabitViewModelTest'
& '.\\.tools\\gradle-9.4.1\\bin\\gradle.bat' :app:assembleDebug
```

Run focused connected tests only with an attached device/emulator. Record exact output or the no-device blocker.

- [ ] **Step 3: Update documentation**

README must list Habit CRUD, records, monthly calendar, streaks and heatmap as delivered Phase 3 scope. It must explicitly say Subscription, habit reminders, pause and archive are absent. `docs/phase3-acceptance.md` must list every Phase 3 requirement, command/result, instrumentation status and known limitations without calling unrun work passed.

- [ ] **Step 4: Verify diff, commit and push**

```powershell
git diff --check
git status --short
git add tools/check-phase3-structure.ps1 docs/phase3-acceptance.md README.md
git commit -m "docs: record Phase 3 acceptance"
git push origin main
```

If push fails due to credentials or network, preserve local commits, report the command error, and do not rewrite history.

## Self-review

- Spec coverage: Task 1 covers all frequency/streak semantics; Task 2 shared offline Room, uniqueness, cascade and migration; Task 3 MVVM/StateFlow; Task 4 list/editor/calendar/statistics/heatmap; Task 5 evidence and documentation.
- Scope remains limited to Habit; no early subscription, reminder, pause or archive code is planned.
- All task interfaces consistently use `LocalDate` (epoch day) and `Instant` (epoch millis); no destructive migration or third-party calendar/chart dependency is planned.
