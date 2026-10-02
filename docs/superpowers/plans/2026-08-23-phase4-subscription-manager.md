# Phase 4 Subscription Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver a Room-backed, offline subscription management module with CNY forecast/actual analytics, per-subscription reminders, and privacy-conscious CSV export.

**Architecture:** Add a Subscription Clean Architecture slice (domain models/rules/repository, Room DAO/entity/repository implementation, SubscriptionViewModel state, Compose screen). The v3→v4 Room migration only creates new tables. Forecasts are pure calculations from subscription anchors; payments remain explicit historical facts. Notification scheduling extends the existing AlarmManager/BootReceiver/WorkManager pattern with subscription-specific identities.

**Tech Stack:** Kotlin, Coroutines Flow/StateFlow, Jetpack Compose Material 3/Canvas, Room 2.8.2, Hilt, AlarmManager, WorkManager, Activity Result CreateDocument, JUnit/Truth, AndroidX Room migration tests.

**Spec:** docs/superpowers/specs/2026-08-23-subscription-manager-design.md

## Global Constraints

- Maintain UI → domain → data dependency direction; ViewModels never access Room DAOs.
- Use Long minor units for every persisted or calculated amount; never use Double.
- Migrate only with explicit Migration(3, 4) and preserve all v1-v3 tables/data; never use destructive migration.
- Add the Subscription navigation item only with the working SubscriptionScreen; do not create Phase 5 placeholders.
- CNY alone is eligible for totals/charts; other currency data remains editable and exportable.
- Forecast and actual amounts are separate CNY series and never overwrite one another.
- Add/update/cancel/delete must reconcile alarms by stable subscription-and-reminder-day keys.
- Follow RED → GREEN → REFACTOR for each implementation task and commit the completed task.

## File Structure

- domain/model/Subscription.kt: subscription, payment, reminder, cycle and statistic value objects.
- domain/repository/SubscriptionRepository.kt: storage boundary for streams and mutations.
- domain/usecase/SubscriptionRules.kt: validation, effective due date, occurrence and aggregation rules.
- data/local/entity/Subscription*.kt and data/local/dao/SubscriptionDao.kt: Room v4 persistence.
- data/repository/SubscriptionRepositoryImpl.kt: entity/domain mappings and transactional writes.
- notification/SubscriptionReminder*.kt: alarm identity, scheduling and broadcast presentation.
- ui/subscription/*: state, ViewModel and Material 3 list/detail/statistics/export UI.

### Task 1: Subscription domain model and pure calculation rules

**Files:**
- Create: app/src/main/java/com/example/lifemanager/domain/model/Subscription.kt
- Create: app/src/main/java/com/example/lifemanager/domain/usecase/SubscriptionRules.kt
- Test: app/src/test/java/com/example/lifemanager/domain/usecase/SubscriptionRulesTest.kt

**Interfaces:**
- Produces enum class BillingCycle { WEEKLY, MONTHLY, QUARTERLY, YEARLY }.
- Produces Subscription, SubscriptionPayment, SubscriptionReminder and SubscriptionStats value types.
- Produces validate(subscription): String?, effectiveNextBillingDate(subscription, today): LocalDate?, forecastOccurrences(subscription, rangeStart, rangeEnd): List<LocalDate>, calculateStats(subscriptions, payments, today): SubscriptionStats and exportCsv(subscriptions, payments): String.

- [x] **Step 1: Write the failing domain tests**

~~~kotlin
@Test fun monthlyForecastKeepsActualPaymentsSeparate() {
    val stats = SubscriptionRules.calculateStats(listOf(monthlyCny), listOf(actualCny), LocalDate.of(2026, 8, 23))
    assertThat(stats.currentMonth.forecastMinor).isEqualTo(1_500L)
    assertThat(stats.currentMonth.actualMinor).isEqualTo(1_200L)
}
@Test fun nonCnyValuesAreExcludedFromStatisticsButRetainedByCsv() {
    assertThat(SubscriptionRules.calculateStats(listOf(monthlyUsd), emptyList(), LocalDate.of(2026, 8, 23)).currentMonth.forecastMinor).isEqualTo(0L)
    assertThat(SubscriptionRules.exportCsv(listOf(monthlyUsd), emptyList())).contains("USD")
}
@Test fun csvEscapesCommasQuotesAndNewlines() {
    assertThat(SubscriptionRules.exportCsv(listOf(monthlyCny.copy(note = "a,\"b\"\nc")), emptyList())).contains("\"a,\"\"b\"\"\nc\"")
}
~~~

- [x] **Step 2: Run the focused test to verify RED**

Run: $env:JAVA_HOME='D:\jdk'; & 'D:\codex_work\app\app\.tools\gradle-9.4.1\bin\gradle.bat' --no-daemon :app:testDebugUnitTest --tests '*SubscriptionRulesTest'

Expected: compilation/test failure because subscription rules do not exist.

- [x] **Step 3: Implement minimal immutable domain types and rules**

Derive every occurrence independently from the original nextBillingDate anchor plus its cycle index, never by advancing the previously clamped date. Use LocalDate.plusWeeks, plusMonths, and plusYears; reject blank names and non-positive amounts; ignore occurrences strictly after cancelDate; total only records whose currency == "CNY". Totals use exact Long arithmetic and surface overflow rather than wrapping negative.

- [x] **Step 4: Run focused domain tests to verify GREEN**

Run the Step 2 command. Expected: all SubscriptionRulesTest cases pass.

- [x] **Step 5: Commit**

~~~powershell
git add app/src/main/java/com/example/lifemanager/domain app/src/test/java/com/example/lifemanager/domain/usecase/SubscriptionRulesTest.kt
git commit -m "feat: add subscription domain rules"
~~~

### Task 2: Room v4 storage and non-destructive migration

**Files:**
- Create: app/src/main/java/com/example/lifemanager/data/local/entity/SubscriptionEntity.kt
- Create: app/src/main/java/com/example/lifemanager/data/local/entity/SubscriptionPaymentEntity.kt
- Create: app/src/main/java/com/example/lifemanager/data/local/entity/SubscriptionReminderEntity.kt
- Create: app/src/main/java/com/example/lifemanager/data/local/dao/SubscriptionDao.kt
- Create: app/src/main/java/com/example/lifemanager/data/repository/SubscriptionRepositoryImpl.kt
- Create: app/src/main/java/com/example/lifemanager/domain/repository/SubscriptionRepository.kt
- Modify: app/src/main/java/com/example/lifemanager/data/local/LifeManagerDatabase.kt
- Modify: app/src/main/java/com/example/lifemanager/di/AppModule.kt
- Test: app/src/androidTest/java/com/example/lifemanager/data/local/SubscriptionDaoTest.kt
- Test: app/src/androidTest/java/com/example/lifemanager/data/local/SubscriptionMigrationTest.kt

**Interfaces:**
- SubscriptionRepository.observeSubscriptions(): Flow<List<Subscription>>, observePayments(subscriptionId: Long): Flow<List<SubscriptionPayment>>, observeReminders(subscriptionId: Long): Flow<Set<Int>>.
- saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long, savePayment(payment: SubscriptionPayment): Long, deletePayment(id: Long), deleteSubscription(id: Long).
- SubscriptionDao.replaceReminders(subscriptionId: Long, days: Set<Int>) is transactional: delete old rows, insert 1/3/7 only.

- [x] **Step 1: Write failing DAO/migration tests**

~~~kotlin
@Test fun deletingSubscriptionCascadesPaymentsAndReminders() = runTest {
    val id = dao.upsert(subscriptionEntity)
    dao.insertPayment(paymentEntity(subscriptionId = id)); dao.insertReminders(listOf(SubscriptionReminderEntity(id, 3)))
    dao.deleteSubscription(id)
    assertThat(dao.paymentsFor(id).first()).isEmpty(); assertThat(dao.reminderDaysFor(id).first()).isEmpty()
}
@Test fun migrationFrom3To4PreservesHabitAndCreatesSubscriptionTables() {
    helper.createDatabase(TEST_DB, 3).apply { execSQL("INSERT INTO habits (id,name,iconKey,color,frequencyType,frequencyValue,startDate,createdAt,updatedAt) VALUES (4,'读书','book',0,'DAILY',1,0,1,1)"); close() }
    helper.runMigrationsAndValidate(TEST_DB, 4, true, *LifeManagerDatabase.MIGRATIONS).use { assertThat(it.query("SELECT name FROM habits WHERE id = 4").count).isEqualTo(1) }
}
~~~

- [x] **Step 2: Build Android test APK to verify RED**

Run: :app:assembleDebugAndroidTest --no-daemon

Expected: test compilation fails because subscription entities/DAO and migration do not exist.

- [x] **Step 3: Add entities, DAO, mappings, repository binding and Migration(3, 4)**

The migration creates subscriptions, subscription_payments and subscription_reminders with foreign keys/indexes and no data-altering SQL for prior tables. Set database version = 4, register all entities/DAO and update Room schema JSON. Repository writes trim optional text and preserve createdAt for edits.

- [x] **Step 4: Verify storage compilation and unit tests**

Run: :app:assembleDebugAndroidTest --no-daemon and :app:testDebugUnitTest --tests '*SubscriptionRulesTest' --no-daemon.

Expected: Android-test APK compiles; JVM rules stay green. Record device execution separately.

- [x] **Step 5: Commit**

~~~powershell
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: persist subscriptions with Room migration"
~~~

### Task 3: Subscription reminder scheduling and rehydration

**Files:**
- Create: app/src/main/java/com/example/lifemanager/notification/SubscriptionReminderSchedulerContract.kt
- Create: app/src/main/java/com/example/lifemanager/notification/SubscriptionReminderScheduler.kt
- Create: app/src/main/java/com/example/lifemanager/notification/SubscriptionReminderReceiver.kt
- Modify: app/src/main/java/com/example/lifemanager/notification/ReminderKey.kt
- Modify: app/src/main/java/com/example/lifemanager/notification/NotificationHelper.kt
- Modify: app/src/main/java/com/example/lifemanager/notification/BootReceiver.kt
- Modify: app/src/main/java/com/example/lifemanager/notification/ReminderReconciliationWorker.kt
- Modify: app/src/main/AndroidManifest.xml
- Modify: app/src/main/java/com/example/lifemanager/MainActivity.kt
- Test: app/src/test/java/com/example/lifemanager/notification/ReminderKeyTest.kt

**Interfaces:**
- schedule(subscription: Subscription, reminderDays: Set<Int>), cancel(subscriptionId: Long, reminderDays: Set<Int>), cancelAll(subscriptionId: Long).
- ReminderKey.forSubscription(subscriptionId: Long, daysBefore: Int): Int is stable and distinct from Todo/Schedule keys.

- [x] **Step 1: Extend reminder-key test first**

~~~kotlin
@Test fun subscriptionReminderIdsDistinguishDaysAndSubscriptions() {
    assertThat(ReminderKey.forSubscription(8, 1)).isNotEqualTo(ReminderKey.forSubscription(8, 3))
}
~~~

- [x] **Step 2: Run focused test to verify RED**

Run: :app:testDebugUnitTest --tests '*ReminderKeyTest' --no-daemon.

Expected: compilation failure for forSubscription.

- [x] **Step 3: Implement alarm, receiver and notification wiring**

For each offset seek its next future local 09:00 reminder; schedule exact if permitted and inexact otherwise. Cancel matching PendingIntents before replacement, except ordinary reconciliation preserves today's valid inexact alarm still awaiting delivery. Only a validated delivered occurrence is consumed before rearming. Receiver uses dedicated SubscriptionNotificationHelper; content PendingIntent carries MainActivity.EXTRA_SUBSCRIPTION_ID. A shared domain coroutine gate serializes Room snapshots, UI mutations, scheduling and posting. BootReceiver and worker reload subscriptions/reminder days and rehydrate schedules.

- [x] **Step 4: Verify GREEN**

Run: :app:testDebugUnitTest --tests '*ReminderKeyTest' --no-daemon and :app:assembleDebug --no-daemon.

- [x] **Step 5: Commit**

~~~powershell
git add app/src/main app/src/test/java/com/example/lifemanager/notification
git commit -m "feat: schedule subscription reminders"
~~~

### Task 4: Subscription ViewModel state and actions

**Files:**
- Create: app/src/main/java/com/example/lifemanager/ui/subscription/SubscriptionUiState.kt
- Create: app/src/main/java/com/example/lifemanager/ui/subscription/SubscriptionViewModel.kt
- Test: app/src/test/java/com/example/lifemanager/ui/subscription/SubscriptionViewModelTest.kt

**Interfaces:**
- SubscriptionUiState contains subscriptions, statistics, selected tab, selected detail ID, editor/payment-editor state, error and loading flags.
- Actions: openEditor, saveSubscription, cancelSubscription, restoreSubscription, deleteSubscription, openPaymentEditor, savePayment, deletePayment and prepareCsvExport.
- Saving reconciles repository data first, then SubscriptionReminderSchedulerContract.cancelAll(id) and schedule(saved, reminderDays).

- [x] **Step 1: Write failing ViewModel tests**

~~~kotlin
@Test fun savingActiveSubscriptionSchedulesEachSelectedReminderDay() = runTest {
    val scheduler = RecordingSubscriptionScheduler(); val model = SubscriptionViewModel(FakeSubscriptionRepository(), scheduler, dispatcher)
    model.openEditor(); model.onNameChanged("音乐"); model.onAmountChanged("1500"); model.onReminderDaysChanged(setOf(1, 3, 7)); model.saveSubscription(); advanceUntilIdle()
    assertThat(scheduler.scheduledDays).containsExactly(1, 3, 7)
}
@Test fun cancellingSubscriptionRemovesRemindersAndPreservesPayments() = runTest {
    val repository = FakeSubscriptionRepository(withPayment = true); val scheduler = RecordingSubscriptionScheduler(); val model = SubscriptionViewModel(repository, scheduler, dispatcher)
    model.cancelSubscription(1); advanceUntilIdle()
    assertThat(scheduler.cancelledIds).contains(1); assertThat(repository.payments.value).isNotEmpty()
}
~~~

- [x] **Step 2: Run focused test to verify RED**

Run: :app:testDebugUnitTest --tests '*SubscriptionViewModelTest' --no-daemon.

Expected: compilation failure because the ViewModel is absent.

- [x] **Step 3: Implement StateFlow composition and error states**

Combine repository flows with selected tab/editor flows and SubscriptionRules.calculateStats. Validate before dispatching I/O, expose Chinese validation/error text and leave editor open on storage failure. Do not access a DAO.

- [x] **Step 4: Run focused ViewModel test to verify GREEN**

Run the Step 2 command. Expected: all state/action tests pass.

- [x] **Step 5: Commit**

~~~powershell
git add app/src/main/java/com/example/lifemanager/ui/subscription app/src/test/java/com/example/lifemanager/ui/subscription
git commit -m "feat: add subscription state management"
~~~

### Task 5: Compose UI, navigation, charts and CSV creation

**Files:**
- Create: app/src/main/java/com/example/lifemanager/ui/subscription/SubscriptionScreen.kt
- Modify: app/src/main/java/com/example/lifemanager/ui/navigation/NavGraph.kt
- Test: app/src/androidTest/java/com/example/lifemanager/ui/subscription/SubscriptionScreenTest.kt

**Interfaces:**
- SubscriptionScreen reads SubscriptionViewModel via Hilt.
- MainActivity reads EXTRA_SUBSCRIPTION_ID into an Activity-owned consumable navigation event; NavGraph opens SubscriptionRoute and detail, then consumes its unique token. Rotation cannot replay a consumed event, and another tap for the same ID creates a new event.

- [x] **Step 1: Write a failing Compose interaction test**

~~~kotlin
@Test fun addSubscriptionButtonOpensEditableSubscriptionForm() {
    composeRule.onNodeWithContentDescription("添加订阅").performClick()
    composeRule.onNodeWithText("订阅名称").assertExists()
}
~~~

- [x] **Step 2: Build Android test APK to verify RED**

Run: :app:assembleDebugAndroidTest --no-daemon.

Expected: test compilation failure because the subscription screen and route are absent.

- [x] **Step 3: Implement Material 3 screen and native charts**

Implement list/stats tabs, summary cards, effective-due-date sorting, empty/error states, editor/detail/payment dialogs and cancellation/deletion confirmations. Add 6-month dual bars, category segments and billing-cycle comparison with text equivalents/content descriptions. Use rememberLauncherForActivityResult(CreateDocument("text/csv")); after privacy confirmation the ViewModel owns the CSV snapshot/write/progress/result so composition recreation does not cancel the write. The application-context writer creates UTF-8 BOM output on IO.

- [x] **Step 4: Add the navigation entry only now**

Add Subscription route/icon to bottom navigation, preserving Todo/Schedule/Habit/Settings. Route notification IDs to subscription detail and do not add Phase 5 UI.

- [x] **Step 5: Verify UI compilation**

Run: :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon.

- [x] **Step 6: Commit**

~~~powershell
git add app/src/main/java/com/example/lifemanager/ui app/src/main/java/com/example/lifemanager/MainActivity.kt app/src/androidTest/java/com/example/lifemanager/ui/subscription
git commit -m "feat: add subscription manager UI"
~~~

### Task 6: Phase 4 documentation, checks and final verification

**Files:**
- Create: tools/check-phase4-structure.ps1
- Create: docs/phase4-acceptance.md
- Modify: README.md

**Interfaces:**
- Structure check validates v4 migration, subscription UI/domain/data/reminder files and rejects Phase 5 backup/restore or currency-conversion placeholders.

- [x] **Step 1: Write the static structure check**

~~~powershell
Assert-Path 'app/src/main/java/com/example/lifemanager/ui/subscription/SubscriptionScreen.kt'
Assert-Content 'LifeManagerDatabase.kt' 'Migration(3, 4)'
Assert-NoPath 'app/src/main/java/com/example/lifemanager/ui/backup'
~~~

- [x] **Step 2: Run the check to verify RED, then implement the minimal check**

Run: powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1.

Expected before the script exists: failure; after adding it: output Phase 4 structure check passed.

- [x] **Step 3: Document completed scope and unrun work honestly**

README lists Phase 4 capabilities and exact JDK 25/Gradle 9.4.1 commands. Acceptance report lists CNY-only aggregation, no exchange conversion, actual-versus-forecast behavior, instrumentation status, warning baseline and CSV privacy notice.

- [x] **Step 4: Run final verification**

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1
$env:JAVA_HOME='D:\jdk'
& 'D:\codex_work\app\app\.tools\gradle-9.4.1\bin\gradle.bat' --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
~~~

Read XML reports and Gradle exit code before reporting success. Run adb devices; if no target is attached, document Android tests as compiled but not executed.

- [x] **Step 5: Commit**

~~~powershell
git add README.md docs/phase4-acceptance.md tools/check-phase4-structure.ps1
git commit -m "docs: record Phase 4 acceptance"
~~~

## Plan Self-Review

- Spec coverage: Task 1 covers money/cycle/CNY/forecast/CSV rules; Task 2 covers Room v4, persistence and migration; Task 3 covers reminders and rehydration; Tasks 4-5 cover MVVM/Compose/navigation/accessibility/export; Task 6 covers tests, README and evidence.
- Scope: no backup, restore, theme redesign, exchange rates, sync, cancellation-history deletion or placeholder UI is planned.
- Type consistency: each UI/notification dependency is defined by Tasks 1-4 before Task 5 consumes it; v4 schema exists before rehydration reads it.
