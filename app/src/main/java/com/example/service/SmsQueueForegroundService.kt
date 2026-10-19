package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.example.BulkSmsApp
import com.example.db.BulkSmsDatabaseHelper
import com.example.telephony.SmsManagerHelper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class SmsQueueForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var queueJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.aistudio.bulksms.action.START"
        const val ACTION_PAUSE = "com.aistudio.bulksms.action.PAUSE"
        const val ACTION_RESUME = "com.aistudio.bulksms.action.RESUME"
        const val ACTION_STOP = "com.aistudio.bulksms.action.STOP"

        const val EXTRA_CAMPAIGN_ID = "campaign_id"

        // State variables visible to bridge and UI
        @Volatile var activeCampaignId: Long = -1L
        @Volatile var activeCampaignName: String = "No Active Campaign"
        @Volatile var campaignStatus: String = "IDLE" // IDLE, PROCESSING, PAUSED, STOPPED, COMPLETED
        @Volatile var countdownSecondsRemaining: Int = 0
        @Volatile var currentDestinationNumber: String = ""
        @Volatile var totalRecipients: Int = 0
        @Volatile var processedCount: Int = 0
        @Volatile var submittedCount: Int = 0
        @Volatile var deliveredCount: Int = 0
        @Volatile var failedCount: Int = 0
        @Volatile var configuredIntervalMs: Long = 5000L
        @Volatile var lastActivityTimestamp: Long = 0L

        // Thread-safe live telemetry log buffer for UI
        val liveLogs = CopyOnWriteArrayList<JSONObject>()

        fun addLog(message: String, type: String = "INFO") {
            val log = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("message", message)
                put("type", type)
            }
            liveLogs.add(0, log)
            // Keep buffer capped at 50 entries
            while (liveLogs.size > 50) {
                liveLogs.removeAt(liveLogs.size - 1)
            }
        }

        fun notifySmsResult(jobId: Long, corrId: String, success: Boolean, details: String) {
            lastActivityTimestamp = System.currentTimeMillis()
            if (success) {
                submittedCount++
                addLog("Job #$jobId: SMS accepted by Android system.", "SUCCESS")
            } else {
                failedCount++
                addLog("Job #$jobId: Delivery attempt failed - $details", "ERROR")
            }
        }

        fun notifyDeliveryConfirmed(jobId: Long, corrId: String) {
            lastActivityTimestamp = System.currentTimeMillis()
            deliveredCount++
            addLog("Job #$jobId: Carrier confirmed delivery to handset.", "DELIVERY")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BulkSms:QueueWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val campaignId = intent.getLongExtra(EXTRA_CAMPAIGN_ID, -1L)
                if (campaignId > 0) {
                    startCampaignProcessing(campaignId)
                }
            }
            ACTION_PAUSE -> pauseCampaignProcessing()
            ACTION_RESUME -> resumeCampaignProcessing()
            ACTION_STOP -> stopCampaignProcessing()
        }

        return START_NOT_STICKY
    }

    private fun startCampaignProcessing(campaignId: Long) {
        val dbHelper = BulkSmsApp.instance.dbHelper
        val campaign = dbHelper.getCampaignDetails(campaignId) ?: return

        activeCampaignId = campaignId
        activeCampaignName = campaign.getString("name")
        configuredIntervalMs = campaign.getLong("intervalMs")
        totalRecipients = campaign.getInt("totalRecipients")
        campaignStatus = BulkSmsDatabaseHelper.STATUS_PROCESSING

        val stats = campaign.optJSONObject("stats")
        submittedCount = stats?.optInt(BulkSmsDatabaseHelper.STATUS_SUBMITTED, 0) ?: 0
        deliveredCount = stats?.optInt(BulkSmsDatabaseHelper.STATUS_DELIVERED, 0) ?: 0
        failedCount = stats?.optInt(BulkSmsDatabaseHelper.STATUS_FAILED, 0) ?: 0
        processedCount = submittedCount + deliveredCount + failedCount

        dbHelper.updateCampaignStatus(campaignId, BulkSmsDatabaseHelper.STATUS_PROCESSING)
        dbHelper.logAppEvent(dbHelper.writableDatabase, "CAMPAIGN_STARTED", campaignId, null, "Queue started")

        // Acquire wake lock
        wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max

        // Post foreground notification
        val notification = BulkSmsApp.instance.notificationHelper.buildForegroundNotification(
            activeCampaignName,
            processedCount,
            totalRecipients,
            0,
            false
        )
        startForeground(com.example.notification.NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)

        BulkSmsApp.instance.notificationHelper.showEventNotification(
            "Campaign Started",
            "Broadcasting '$activeCampaignName' to $totalRecipients recipients."
        )

        addLog("Campaign '$activeCampaignName' started. Processing queue...", "INFO")

        launchQueueLoop()
    }

    private fun launchQueueLoop() {
        queueJob?.cancel()
        queueJob = serviceScope.launch {
            val dbHelper = BulkSmsApp.instance.dbHelper
            val smsHelper = SmsManagerHelper(applicationContext)
            var consecutiveFailures = 0

            while (isActive && campaignStatus == BulkSmsDatabaseHelper.STATUS_PROCESSING) {
                // Fetch next Queued job
                val nextJob = dbHelper.getNextQueuedJob(activeCampaignId)
                if (nextJob == null) {
                    // Campaign completed!
                    campaignStatus = BulkSmsDatabaseHelper.STATUS_COMPLETED
                    dbHelper.updateCampaignStatus(activeCampaignId, BulkSmsDatabaseHelper.STATUS_COMPLETED)
                    addLog("All messages processed. Campaign '$activeCampaignName' completed successfully!", "SUCCESS")

                    BulkSmsApp.instance.notificationHelper.showEventNotification(
                        "Campaign Completed",
                        "Finished sending '$activeCampaignName' ($totalRecipients total)."
                    )

                    // Automated Email Blast Reporting via Gmail SMTP
                    try {
                        val prefs = BulkSmsApp.instance.preferences
                        val targetEmail = prefs.reportEmail.trim()
                        if (targetEmail.isNotBlank() && prefs.autoEmailReport) {
                            addLog("Auto-sending blast report to $targetEmail via Gmail SMTP (itdepttranzfast@gmail.com)...", "INFO")
                            val reportTxt = dbHelper.generateTxtReport(activeCampaignId)
                            val subject = "SMS BLAST REPORT - $activeCampaignName"
                            val emailResult = com.example.email.GmailSmtpHelper.sendEmailSync(targetEmail, subject, reportTxt)
                            if (emailResult.isSuccess) {
                                addLog("Email report successfully dispatched to $targetEmail via Gmail SMTP! ✅", "SUCCESS")
                                BulkSmsApp.instance.notificationHelper.showEventNotification(
                                    "Email Report Sent",
                                    "Report for '$activeCampaignName' delivered to $targetEmail."
                                )
                            } else {
                                val err = emailResult.exceptionOrNull()?.message ?: "Unknown error"
                                addLog("Automated email dispatch failed: $err", "ERROR")
                            }
                        }
                    } catch (e: Exception) {
                        addLog("Error in automated email reporting: ${e.message}", "ERROR")
                    }
                    break
                }

                val jobId = nextJob.getLong("id")
                val destination = nextJob.getString("destinationNumber")
                val renderedMessage = nextJob.getString("renderedMessage")
                val correlationId = nextJob.getString("correlationId")
                val queuePosition = nextJob.getInt("queuePosition")

                currentDestinationNumber = destination
                lastActivityTimestamp = System.currentTimeMillis()

                // Mark processing
                dbHelper.markJobProcessing(jobId, activeCampaignId)
                addLog("Attempting message #${queuePosition} to ${maskPhoneNumber(destination)}", "INFO")

                // Send via SmsManagerHelper
                val campaign = dbHelper.getCampaignDetails(activeCampaignId)
                val subId = campaign?.optInt("selectedSubId", -1) ?: -1

                val sendResult = smsHelper.sendSms(
                    destinationNumber = destination,
                    message = renderedMessage,
                    subId = subId,
                    jobId = jobId,
                    campaignId = activeCampaignId,
                    correlationId = correlationId
                )

                if (sendResult.isSuccess) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    val err = sendResult.exceptionOrNull()?.message ?: "Unknown SMS exception"
                    dbHelper.markJobFailed(jobId, activeCampaignId, -1, err)
                    notifySmsResult(jobId, correlationId, false, err)

                    if (consecutiveFailures >= 3) {
                        addLog("Warning: 3 consecutive SMS failures detected. Pausing campaign for review.", "ERROR")
                        pauseCampaignProcessing()
                        BulkSmsApp.instance.notificationHelper.showEventNotification(
                            "Campaign Auto-Paused",
                            "Repeated SMS errors on '$activeCampaignName'. Check SIM balance or cellular signal."
                        )
                        break
                    }
                }

                processedCount = queuePosition

                // Update ongoing notification
                updateNotification(false)

                // Native countdown interval before next eligible send attempt
                val intervalSec = (configuredIntervalMs / 1000L).toInt().coerceAtLeast(1)
                for (s in intervalSec downTo 1) {
                    if (!isActive || campaignStatus != BulkSmsDatabaseHelper.STATUS_PROCESSING) break
                    countdownSecondsRemaining = s
                    delay(1000L)
                }
                countdownSecondsRemaining = 0
            }

            if (campaignStatus == BulkSmsDatabaseHelper.STATUS_COMPLETED || campaignStatus == BulkSmsDatabaseHelper.STATUS_STOPPED) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                wakeLock?.release()
                stopSelf()
            }
        }
    }

    private fun pauseCampaignProcessing() {
        campaignStatus = BulkSmsDatabaseHelper.STATUS_PAUSED
        queueJob?.cancel()
        BulkSmsApp.instance.dbHelper.updateCampaignStatus(activeCampaignId, BulkSmsDatabaseHelper.STATUS_PAUSED)
        addLog("Campaign '$activeCampaignName' paused by user.", "WARNING")
        updateNotification(true)
        BulkSmsApp.instance.notificationHelper.showEventNotification(
            "Campaign Paused",
            "Queue paused for '$activeCampaignName'."
        )
    }

    private fun resumeCampaignProcessing() {
        if (campaignStatus == BulkSmsDatabaseHelper.STATUS_PAUSED) {
            campaignStatus = BulkSmsDatabaseHelper.STATUS_PROCESSING
            BulkSmsApp.instance.dbHelper.updateCampaignStatus(activeCampaignId, BulkSmsDatabaseHelper.STATUS_PROCESSING)
            addLog("Campaign '$activeCampaignName' resumed.", "INFO")
            updateNotification(false)
            launchQueueLoop()
        }
    }

    private fun stopCampaignProcessing() {
        campaignStatus = BulkSmsDatabaseHelper.STATUS_STOPPED
        queueJob?.cancel()
        BulkSmsApp.instance.dbHelper.updateCampaignStatus(activeCampaignId, BulkSmsDatabaseHelper.STATUS_STOPPED)
        addLog("Campaign '$activeCampaignName' stopped by user.", "WARNING")
        BulkSmsApp.instance.notificationHelper.showEventNotification(
            "Campaign Stopped",
            "Broadcast cancelled for '$activeCampaignName'."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.release()
        stopSelf()
    }

    private fun updateNotification(isPaused: Boolean) {
        val notification = BulkSmsApp.instance.notificationHelper.buildForegroundNotification(
            activeCampaignName,
            processedCount,
            totalRecipients,
            countdownSecondsRemaining,
            isPaused
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(com.example.notification.NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun maskPhoneNumber(phone: String): String {
        return if (phone.length > 5) {
            val prefix = phone.take(3)
            val suffix = phone.takeLast(2)
            val masked = "*".repeat(phone.length - 5)
            "$prefix$masked$suffix"
        } else {
            phone
        }
    }
}
