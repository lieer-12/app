$ErrorActionPreference = 'Stop'

$required = @(
    'app/src/main/java/com/example/lifemanager/domain/model/Schedule.kt',
    'app/src/main/java/com/example/lifemanager/domain/usecase/ScheduleRules.kt',
    'app/src/main/java/com/example/lifemanager/data/local/entity/ScheduleEntity.kt',
    'app/src/main/java/com/example/lifemanager/data/local/entity/ScheduleExceptionEntity.kt',
    'app/src/main/java/com/example/lifemanager/data/local/dao/ScheduleDao.kt',
    'app/src/main/java/com/example/lifemanager/data/repository/ScheduleRepositoryImpl.kt',
    'app/src/main/java/com/example/lifemanager/ui/schedule/ScheduleScreen.kt',
    'app/src/main/java/com/example/lifemanager/ui/schedule/ScheduleViewModel.kt',
    'app/src/main/java/com/example/lifemanager/notification/ScheduleReminderScheduler.kt',
    'app/src/test/java/com/example/lifemanager/domain/usecase/ScheduleRulesTest.kt',
    'app/src/test/java/com/example/lifemanager/ui/schedule/ScheduleViewModelTest.kt',
    'app/src/androidTest/java/com/example/lifemanager/data/local/ScheduleDaoTest.kt'
)

$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { throw "Missing Phase 2 files: $($missing -join ', ')" }

$database = Get-Content -LiteralPath 'app/src/main/java/com/example/lifemanager/data/local/LifeManagerDatabase.kt' -Raw
if ($database -notmatch 'version = 2' -or $database -notmatch 'Migration\(1, 2\)') {
    throw 'Room v1 -> v2 migration is missing.'
}

$futureUi = @('app/src/main/java/com/example/lifemanager/ui/subscription', 'app/src/main/java/com/example/lifemanager/ui/habit')
$unexpected = $futureUi | Where-Object { Test-Path -LiteralPath $_ }
if ($unexpected) { throw "Future module UI must not exist in Phase 2: $($unexpected -join ', ')" }

Write-Host 'Phase 2 structure check passed.'
