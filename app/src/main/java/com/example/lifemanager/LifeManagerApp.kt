package com.example.lifemanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.example.lifemanager.notification.NotificationHelper
import com.example.lifemanager.notification.ReminderReconciliationWorker

@HiltAndroidApp
class LifeManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        ReminderReconciliationWorker.schedule(this)
        ReminderReconciliationWorker.enqueueImmediate(this)
    }
}
