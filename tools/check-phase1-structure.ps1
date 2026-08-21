$projectRoot = Split-Path -Parent $PSScriptRoot
$required = @(
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'gradle/libs.versions.toml',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/example/lifemanager/MainActivity.kt',
    'app/src/main/java/com/example/lifemanager/LifeManagerApp.kt',
    'app/src/main/java/com/example/lifemanager/data/local/LifeManagerDatabase.kt',
    'app/src/main/java/com/example/lifemanager/domain/model/Todo.kt',
    'app/src/main/java/com/example/lifemanager/ui/todo/TodoScreen.kt',
    'app/src/main/java/com/example/lifemanager/notification/ReminderScheduler.kt'
    'app/src/main/java/com/example/lifemanager/notification/ReminderReconciliationWorker.kt'
)

$missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $projectRoot $_)) })
$futureUi = @(
    'app/src/main/java/com/example/lifemanager/ui/schedule',
    'app/src/main/java/com/example/lifemanager/ui/subscription',
    'app/src/main/java/com/example/lifemanager/ui/habit'
)
$unexpected = @($futureUi | Where-Object { Test-Path -LiteralPath (Join-Path $projectRoot $_) })

if ($missing.Count -gt 0) {
    Write-Error ("Missing required paths:`n" + ($missing -join "`n"))
    exit 1
}

if ($unexpected.Count -gt 0) {
    Write-Error ("Future-module UI paths must not exist in Phase 1:`n" + ($unexpected -join "`n"))
    exit 1
}

$manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'app/src/main/AndroidManifest.xml') -Raw
foreach ($token in @('POST_NOTIFICATIONS', 'TodoReminderReceiver', 'BootReceiver', 'LifeManagerApp')) {
    if ($manifest -notmatch [regex]::Escape($token)) {
        Write-Error "Manifest is missing required token: $token"
        exit 1
    }
}

Write-Output 'Phase 1 structure check passed.'
