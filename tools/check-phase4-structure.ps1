$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
function Read-ProjectFile([string]$relative) { Get-Content -Raw -LiteralPath (Join-Path $projectRoot $relative) }
$prefix = 'app/src/main/java/com/example/lifemanager'
$required = @(
    "$prefix/domain/model/Subscription.kt",
    "$prefix/domain/usecase/SubscriptionRules.kt",
    "$prefix/domain/repository/SubscriptionRepository.kt",
    "$prefix/data/local/dao/SubscriptionDao.kt",
    "$prefix/data/local/entity/SubscriptionEntity.kt",
    "$prefix/data/local/entity/SubscriptionPaymentEntity.kt",
    "$prefix/data/local/entity/SubscriptionReminderEntity.kt",
    "$prefix/data/repository/SubscriptionRepositoryImpl.kt",
    "$prefix/notification/SubscriptionReminderScheduler.kt",
    "$prefix/notification/SubscriptionReminderReceiver.kt",
    "$prefix/ui/subscription/SubscriptionUiState.kt",
    "$prefix/ui/subscription/SubscriptionViewModel.kt",
    "$prefix/ui/subscription/SubscriptionScreen.kt",
    'app/src/test/java/com/example/lifemanager/domain/usecase/SubscriptionRulesTest.kt',
    'app/src/test/java/com/example/lifemanager/ui/subscription/SubscriptionViewModelTest.kt',
    'app/src/androidTest/java/com/example/lifemanager/data/local/SubscriptionDaoTest.kt',
    'app/src/androidTest/java/com/example/lifemanager/data/local/SubscriptionMigrationTest.kt',
    'app/src/androidTest/java/com/example/lifemanager/ui/subscription/SubscriptionScreenTest.kt',
    'app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/4.json'
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $path))) { throw "Missing Phase 4 file: $path" }
}
$database = Read-ProjectFile "$prefix/data/local/LifeManagerDatabase.kt"
foreach ($token in @('version = 4', 'Migration(1, 2)', 'Migration(2, 3)', 'Migration(3, 4)', 'abstract fun subscriptionDao')) {
    if (-not $database.Contains($token)) { throw "Missing database requirement: $token" }
}
if ($database -match 'fallbackToDestructiveMigration') { throw 'Destructive migration is forbidden.' }
$schema3 = (Read-ProjectFile 'app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/3.json' | ConvertFrom-Json).database
$schema4 = (Read-ProjectFile 'app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/4.json' | ConvertFrom-Json).database
foreach ($entity in $schema3.entities) {
    $next = $schema4.entities | Where-Object tableName -eq $entity.tableName
    if (($entity | ConvertTo-Json -Depth 20 -Compress) -ne ($next | ConvertTo-Json -Depth 20 -Compress)) {
        throw "Existing table changed: $($entity.tableName)"
    }
}
$nav = Read-ProjectFile "$prefix/ui/navigation/NavGraph.kt"
foreach ($route in @('TodoRoute', 'ScheduleRoute', 'HabitRoute', 'SubscriptionRoute')) {
    if (-not $nav.Contains($route)) { throw "Missing module route: $route" }
}
$viewModel = Read-ProjectFile "$prefix/ui/subscription/SubscriptionViewModel.kt"
if ($viewModel -match 'data\.local|SubscriptionDao') { throw 'Subscription ViewModel must use the domain repository.' }
if (Test-Path -LiteralPath (Join-Path $projectRoot "$prefix/ui/backup")) { throw 'Phase 5 backup UI is out of scope.' }
Write-Output 'Phase 4 structure check passed (static check only; not a device test).'
