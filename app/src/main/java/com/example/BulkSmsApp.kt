package com.example

import android.app.Application
import com.example.db.BulkSmsDatabaseHelper
import com.example.notification.NotificationHelper
import com.example.prefs.AppPreferences

class BulkSmsApp : Application() {

    lateinit var dbHelper: BulkSmsDatabaseHelper
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        dbHelper = BulkSmsDatabaseHelper(this)
        preferences = AppPreferences(this)
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannels()

        // Reconcile any message jobs that were left in Processing when the app was interrupted
        dbHelper.reconcileInterruptedJobs()
    }

    companion object {
        lateinit var instance: BulkSmsApp
            private set
    }
}
