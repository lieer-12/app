$ErrorActionPreference = 'Stop'

$required = @(
    'app/src/main/java/com/example/lifemanager/domain/model/Habit.kt',
    'app/src/main/java/com/example/lifemanager/domain/usecase/HabitRules.kt',
    'app/src/main/java/com/example/lifemanager/domain/repository/HabitRepository.kt',
    'app/src/main/java/com/example/lifemanager/data/local/entity/HabitEntity.kt',
    'app/src/main/java/com/example/lifemanager/data/local/entity/HabitRecordEntity.kt',
    'app/src/main/java/com/example/lifemanager/data/local/dao/HabitDao.kt',
    'app/src/main/java/com/example/lifemanager/data/repository/HabitRepositoryImpl.kt',
    'app/src/main/java/com/example/lifemanager/ui/habit/HabitUiState.kt',
    'app/src/main/java/com/example/lifemanager/ui/habit/HabitViewModel.kt',
    'app/src/main/java/com/example/lifemanager/ui/habit/HabitScreen.kt',
    'app/src/test/java/com/example/lifemanager/domain/usecase/HabitRulesTest.kt',
    'app/src/test/java/com/example/lifemanager/ui/habit/HabitViewModelTest.kt',
    'app/src/androidTest/java/com/example/lifemanager/data/local/HabitDaoTest.kt',
    'app/src/androidTest/java/com/example/lifemanager/data/local/HabitMigrationTest.kt',
    'app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/3.json'
)

$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { throw "Missing Phase 3 files: $($missing -join ', ')" }

$database = Get-Content -LiteralPath 'app/src/main/java/com/example/lifemanager/data/local/LifeManagerDatabase.kt' -Raw
foreach ($token in @('version = 3', 'Migration(1, 2)', 'Migration(2, 3)', 'abstract fun habitDao')) {
    if ($database -notmatch [regex]::Escape($token)) { throw "Room database is missing: $token" }
}

$navigation = Get-Content -LiteralPath 'app/src/main/java/com/example/lifemanager/ui/navigation/NavGraph.kt' -Raw
foreach ($token in @('HabitRoute', 'HabitScreen')) {
    if ($navigation -notmatch [regex]::Escape($token)) { throw "Habit navigation is missing: $token" }
}

if (Test-Path -LiteralPath 'app/src/main/java/com/example/lifemanager/ui/subscription') {
    throw 'Subscription UI must not exist before its Phase is implemented.'
}

Write-Host 'Phase 3 structure check passed.'
