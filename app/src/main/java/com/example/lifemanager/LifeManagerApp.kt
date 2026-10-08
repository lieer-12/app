package com.example.lifemanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.example.lifemanager.notification.NotificationHelper
import com.example.lifemanager.notification.ReminderReconciliationWorker
import com.example.lifemanager.domain.backup.BackupFileStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LifeManagerApp : Application() {
    @Inject lateinit var backupFiles: BackupFileStore
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        ReminderReconciliationWorker.schedule(this)
        ReminderReconciliationWorker.enqueueImmediate(this)
        applicationScope.launch {
            // A failed cleanup is retried before the next import; unrelated CRUD remains available.
            runCatching { backupFiles.initializePrivateStorage() }
        }
    }
}
