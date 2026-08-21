# Phase 2 Calendar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an offline calendar module with month/week/day views, schedule CRUD, recurrence, conflict warnings and local reminders.

**Architecture:** Keep the existing single-app MVVM/Clean Architecture boundary: Compose talks only to `ScheduleViewModel`, which uses domain use cases/repositories; Room entities and DAOs remain behind `ScheduleRepository`. Store timed events as UTC epoch milliseconds and all-day values as `LocalDate.toEpochDay()`. Expand recurring events only for the visible range.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, Hilt, WorkManager, AlarmManager, Flow/StateFlow, java.time.

**Spec:** `D:\codex_work\app\app\android-app-prompt.md`

## Global Constraints

- Phase 2 adds only the Calendar module and its completed navigation entry; do not create Subscription or Habit UI/placeholder data.
- All business data stays in the shared Room database and must work offline.
- Use a Room `1 -> 2` migration; never use destructive migration.
- Keep Gradle running on JDK 25, but preserve Android-compatible Java/Kotlin target 17.
- Recurrent schedules store a rule and exceptions, not pre-generated infinite instances.
- Add unit tests for domain rules and ViewModel state, an instrumentation DAO/migration test, README status and an acceptance report.

---

## File Structure

- `domain/model/Schedule.kt`: schedule, repeat-rule, occurrence and editor-independent value types.
- `domain/usecase/ScheduleRules.kt`: validation, visible-range recurrence expansion, conflict detection and next reminder calculation.
- `data/local/entity/ScheduleEntity.kt`, `ScheduleExceptionEntity.kt`, `dao/ScheduleDao.kt`: persistent schedule records.
- `data/repository/ScheduleRepositoryImpl.kt`, `domain/repository/ScheduleRepository.kt`: Room/domain mapping and CRUD boundary.
- `notification/ScheduleReminder*.kt`: deterministic schedule alarm dispatch and notification receiver.
- `ui/schedule/*`: StateFlow ViewModel and Compose calendar/list/editor surfaces.
- Existing database, Hilt, worker, boot receiver and navigation files: migration/wiring only.

### Task 1: JDK 25 and domain behaviour

**Files:**
- Create: `app/src/main/java/com/example/lifemanager/domain/model/Schedule.kt`
- Create: `app/src/main/java/com/example/lifemanager/domain/usecase/ScheduleRules.kt`
- Test: `app/src/test/java/com/example/lifemanager/domain/usecase/ScheduleRulesTest.kt`
- Modify: `README.md`, `app/build.gradle.kts`

- [ ] Write tests for blank titles, invalid timed/all-day ranges, daily recurrence bounded by the query/end date, and timed overlap detection.
- [ ] Implement immutable domain models and pure `ScheduleRules`; an event overlaps when `a.start < b.end && b.start < a.end`, and all-day occurrences use date intersection.
- [ ] State in the build file/readme that JDK 25 is the Gradle runtime while target 17 is deliberately retained for Android compatibility.
- [ ] Run `gradle test --tests '*ScheduleRulesTest'`; record the toolchain blocker if Gradle is unavailable.

### Task 2: Room v2 and repository

**Files:**
- Create: `data/local/entity/ScheduleEntity.kt`, `data/local/entity/ScheduleExceptionEntity.kt`, `data/local/dao/ScheduleDao.kt`
- Create: `domain/repository/ScheduleRepository.kt`, `data/repository/ScheduleRepositoryImpl.kt`
- Modify: `data/local/Converters.kt`, `data/local/LifeManagerDatabase.kt`, `di/AppModule.kt`
- Test: `app/src/androidTest/java/com/example/lifemanager/data/local/ScheduleDaoTest.kt`

- [ ] Write DAO tests that insert, update, observe and delete a schedule and preserve an exception row.
- [ ] Define Room v2 tables and indexes; add explicit `Migration(1, 2)` SQL with foreign-key cascade for exceptions.
- [ ] Map entities to domain values and expose `observeSchedules`, `getSchedules`, `saveSchedule`, `deleteSchedule`, and exception persistence from `ScheduleRepository`.
- [ ] Run the focused instrumentation test when an Android SDK/emulator is available; otherwise keep its source and record that it could not execute locally.

### Task 3: Reminder and lifecycle integration

**Files:**
- Create: `notification/ScheduleReminderScheduler.kt`, `ScheduleReminderSchedulerContract.kt`, `ScheduleReminderReceiver.kt`
- Modify: `notification/ReminderKey.kt`, `NotificationHelper.kt`, `ReminderReconciliationWorker.kt`, `BootReceiver.kt`, `AndroidManifest.xml`, `di/AppModule.kt`
- Test: `app/src/test/java/com/example/lifemanager/notification/ReminderKeyTest.kt`

- [ ] Extend the failing reminder-key test with a todo/schedule collision case.
- [ ] Add a dedicated schedule pending-intent namespace, a receiver notification and an AlarmManager scheduler that schedules only the next future occurrence.
- [ ] Reconcile schedule alarms daily and after boot/app replacement; cancel old alarms on edit/delete.
- [ ] Run focused unit tests when Gradle is available.

### Task 4: Calendar ViewModel and Compose UI

**Files:**
- Create: `ui/schedule/ScheduleUiState.kt`, `ScheduleViewModel.kt`, `ScheduleScreen.kt`
- Modify: `ui/navigation/NavGraph.kt`
- Test: `app/src/test/java/com/example/lifemanager/ui/schedule/ScheduleViewModelTest.kt`

- [ ] Write ViewModel tests that reject invalid input, display conflicts before saving, and save when the user confirms a conflict.
- [ ] Build StateFlow state for a selected date and month/week/day mode, deriving only visible occurrences from the repository Flow.
- [ ] Provide a usable month grid with event markers; day/week chronological cards; a FAB; date/time pickers; and a scrollable create/edit/delete dialog for all Phase 2 fields.
- [ ] Add Calendar to bottom navigation beside implemented Todo and Settings only; no Subscription/Habit destinations.
- [ ] Run focused ViewModel tests when Gradle is available.

### Task 5: Acceptance artefacts and static verification

**Files:**
- Create: `tools/check-phase2-structure.ps1`, `docs/phase2-acceptance.md`
- Modify: `README.md`

- [ ] Check that Phase 2 files and the v1→v2 migration are present, while Subscription/Habit UI packages remain absent.
- [ ] Parse XML and run the structure checker.
- [ ] Run `gradle test` and `gradle assembleDebug`; include actual output and distinguish executable checks from missing-toolchain limitations in the acceptance report.
- [ ] Update README with Phase 2 scope, build runtime, test commands and known limitations.

## Self-review

- Spec coverage: Tasks 1-4 cover calendar views, CRUD, recurrence, conflict detection, Room storage, reminders, MVVM/StateFlow and Material 3 navigation; Task 5 records verification.
- Intentional Phase 2 limitations: drag-to-reschedule and pinch zoom are not added because Compose has no built-in calendar interaction primitive in the current dependency set; date-mode buttons and explicit editing remain fully functional. The acceptance report must disclose this.
- No destructive schema reset or unimplemented future-module placeholders are allowed.
