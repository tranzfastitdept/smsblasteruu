package com.example.bridge

import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import com.example.BulkSmsApp
import com.example.MainActivity
import com.example.db.BulkSmsDatabaseHelper
import com.example.generator.NumberGenerator
import com.example.service.SmsQueueForegroundService
import com.example.telephony.SmsManagerHelper
import org.json.JSONArray
import org.json.JSONObject

class BulkSmsJsBridge(private val activity: MainActivity) {

    private val dbHelper = BulkSmsApp.instance.dbHelper
    private val prefs = BulkSmsApp.instance.preferences
    private val smsHelper = SmsManagerHelper(activity)

    @JavascriptInterface
    fun getDashboardStats(): String {
        return try {
            dbHelper.getDashboardStats().toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun getSimCards(): String {
        return try {
            smsHelper.getSimSubscriptions().toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun checkPermissions(): String {
        val obj = JSONObject()
        obj.put("smsHardware", smsHelper.hasSmsHardware())
        obj.put("smsPermissions", smsHelper.hasSmsPermissions())
        obj.put("notificationPermission", BulkSmsApp.instance.notificationHelper.hasNotificationPermission())
        obj.put("cellularAvailable", smsHelper.isCellularServiceAvailable())
        return obj.toString()
    }

    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread {
            activity.requestAppPermissions()
        }
    }

    @JavascriptInterface
    fun generateNumbers(prefix: String, startSuffix: String, endSuffix: String, maxLimit: Int): String {
        val limit = if (maxLimit > 0) maxLimit else prefs.maxCampaignSize
        val result = NumberGenerator.generateNumbers(prefix, startSuffix, endSuffix, limit)
        return NumberGenerator.resultToJson(result).toString()
    }

    @JavascriptInterface
    fun saveRecipientList(name: String, sourceType: String, contactsJson: String): String {
        return try {
            val arr = JSONArray(contactsJson)
            val list = mutableListOf<Map<String, Any>>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val map = mutableMapOf<String, Any>()
                map["phone_number"] = item.getString("phoneNumber")
                map["original_phone_number"] = item.optString("originalPhoneNumber", item.getString("phoneNumber"))
                map["display_name"] = item.optString("displayName", "")
                map["group_name"] = item.optString("groupName", "")
                map["consent_status"] = item.optInt("consentStatus", BulkSmsDatabaseHelper.CONSENT_NONE)
                map["consent_source"] = item.optString("consentSource", sourceType)
                list.add(map)
            }
            val id = dbHelper.saveRecipientList(name, sourceType, list)
            val resp = JSONObject()
            resp.put("success", id > 0)
            resp.put("recipientListId", id)
            resp.put("savedCount", list.size)
            resp.toString()
        } catch (e: Exception) {
            val resp = JSONObject()
            resp.put("success", false)
            resp.put("errorMessage", e.message ?: "Failed to save contacts")
            resp.toString()
        }
    }

    @JavascriptInterface
    fun getRecipientLists(): String {
        return try {
            dbHelper.getRecipientLists().toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun getContactsForList(listId: Long, limit: Int, offset: Int): String {
        return try {
            dbHelper.getContactsForList(listId, if (limit > 0) limit else 100, offset).toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun toggleContactOptOut(contactId: Long, optOut: Boolean): String {
        val success = dbHelper.toggleContactOptOut(contactId, optOut)
        val obj = JSONObject()
        obj.put("success", success)
        return obj.toString()
    }

    @JavascriptInterface
    fun deleteContact(contactId: Long): String {
        val success = dbHelper.deleteContact(contactId)
        val obj = JSONObject()
        obj.put("success", success)
        return obj.toString()
    }

    @JavascriptInterface
    fun getTemplates(): String {
        return try {
            dbHelper.getTemplates().toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun saveTemplate(id: Long, name: String, content: String): String {
        return try {
            val savedId = dbHelper.saveTemplate(id, name, content)
            val obj = JSONObject()
            obj.put("success", savedId > 0)
            obj.put("id", savedId)
            obj.toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("errorMessage", e.message).toString()
        }
    }

    @JavascriptInterface
    fun deleteTemplate(id: Long): String {
        val success = dbHelper.deleteTemplate(id)
        return JSONObject().put("success", success).toString()
    }

    @JavascriptInterface
    fun createCampaign(
        name: String,
        template: String,
        recipientListId: Long,
        intervalMs: Long,
        subId: Int,
        allowUnverified: Boolean
    ): String {
        return try {
            val safeInterval = intervalMs.coerceIn(1000L, 60000L)
            val campaignId = dbHelper.createCampaign(
                name = name,
                template = template,
                recipientListId = recipientListId,
                intervalMs = safeInterval,
                subId = subId,
                allowUnverifiedConsent = allowUnverified
            )
            val obj = JSONObject()
            if (campaignId > 0) {
                obj.put("success", true)
                obj.put("campaignId", campaignId)
            } else {
                obj.put("success", false)
                obj.put("errorMessage", "No eligible recipients found in this list (or all opted-out/unverified).")
            }
            obj.toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("errorMessage", e.message).toString()
        }
    }

    @JavascriptInterface
    fun startCampaign(campaignId: Long): String {
        return try {
            val intent = Intent(activity, SmsQueueForegroundService::class.java).apply {
                action = SmsQueueForegroundService.ACTION_START
                putExtra(SmsQueueForegroundService.EXTRA_CAMPAIGN_ID, campaignId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
            JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("errorMessage", e.message).toString()
        }
    }

    @JavascriptInterface
    fun pauseCampaign(): String {
        val intent = Intent(activity, SmsQueueForegroundService::class.java).apply {
            action = SmsQueueForegroundService.ACTION_PAUSE
        }
        activity.startService(intent)
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun resumeCampaign(): String {
        val intent = Intent(activity, SmsQueueForegroundService::class.java).apply {
            action = SmsQueueForegroundService.ACTION_RESUME
        }
        activity.startService(intent)
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun stopCampaign(): String {
        val intent = Intent(activity, SmsQueueForegroundService::class.java).apply {
            action = SmsQueueForegroundService.ACTION_STOP
        }
        activity.startService(intent)
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun getActiveCampaignState(): String {
        val obj = JSONObject().apply {
            put("activeCampaignId", SmsQueueForegroundService.activeCampaignId)
            put("campaignName", SmsQueueForegroundService.activeCampaignName)
            put("status", SmsQueueForegroundService.campaignStatus)
            put("countdownSeconds", SmsQueueForegroundService.countdownSecondsRemaining)
            put("destinationNumber", SmsQueueForegroundService.currentDestinationNumber)
            put("totalRecipients", SmsQueueForegroundService.totalRecipients)
            put("processedCount", SmsQueueForegroundService.processedCount)
            put("submittedCount", SmsQueueForegroundService.submittedCount)
            put("deliveredCount", SmsQueueForegroundService.deliveredCount)
            put("failedCount", SmsQueueForegroundService.failedCount)
            put("configuredIntervalMs", SmsQueueForegroundService.configuredIntervalMs)
            put("lastActivityTimestamp", SmsQueueForegroundService.lastActivityTimestamp)
        }
        return obj.toString()
    }

    @JavascriptInterface
    fun getLiveLogs(): String {
        val arr = JSONArray()
        SmsQueueForegroundService.liveLogs.forEach { arr.put(it) }
        return arr.toString()
    }

    @JavascriptInterface
    fun getCampaigns(limit: Int, offset: Int): String {
        return try {
            dbHelper.getCampaigns(if (limit > 0) limit else 50, offset).toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun getCampaignDetails(campaignId: Long): String {
        return dbHelper.getCampaignDetails(campaignId)?.toString() ?: JSONObject().toString()
    }

    @JavascriptInterface
    fun getMessageJobs(campaignId: Long, statusFilter: String, limit: Int, offset: Int): String {
        return try {
            dbHelper.getMessageJobs(
                campaignId = if (campaignId > 0) campaignId else null,
                statusFilter = statusFilter,
                limit = if (limit > 0) limit else 100,
                offset = offset
            ).toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun getMessageEvents(jobId: Long): String {
        return try {
            dbHelper.getMessageEvents(jobId).toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun getReportsData(startDate: Long, endDate: Long, campaignId: Long): String {
        return try {
            dbHelper.getReportsData(
                startDate,
                endDate,
                if (campaignId > 0) campaignId else null
            ).toString()
        } catch (e: Exception) {
            JSONObject().toString()
        }
    }

    @JavascriptInterface
    fun getPreferences(): String {
        return prefs.toJsonObject().toString()
    }

    @JavascriptInterface
    fun savePreferences(prefJson: String): String {
        return try {
            val json = JSONObject(prefJson)
            prefs.updateFromJson(json)
            JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("errorMessage", e.message).toString()
        }
    }

    @JavascriptInterface
    fun resetPreferences(): String {
        prefs.resetToDefaults()
        return JSONObject().put("success", true).put("preferences", prefs.toJsonObject()).toString()
    }

    @JavascriptInterface
    fun clearLocalData(clearType: String): String {
        val success = dbHelper.clearLocalData(clearType)
        return JSONObject().put("success", success).toString()
    }

    @JavascriptInterface
    fun sendTestSms(destinationNumber: String, message: String, subId: Int): String {
        return try {
            val corrId = "test-sms-${System.currentTimeMillis()}"
            val res = smsHelper.sendSms(
                destinationNumber = destinationNumber,
                message = message,
                subId = subId,
                jobId = -1L,
                campaignId = -1L,
                correlationId = corrId
            )
            val obj = JSONObject()
            if (res.isSuccess) {
                obj.put("success", true)
                obj.put("correlationId", corrId)
            } else {
                obj.put("success", false)
                obj.put("errorMessage", res.exceptionOrNull()?.message ?: "Send failed")
            }
            obj.toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("errorMessage", e.message).toString()
        }
    }

    @JavascriptInterface
    fun getOptOutContacts(): String {
        return try {
            dbHelper.getOptOutContacts().toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun addOptOutContact(phoneNumber: String, reason: String): String {
        return try {
            val id = dbHelper.addOptOutContact(phoneNumber, reason)
            JSONObject().put("success", id > 0).put("id", id).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("errorMessage", e.message).toString()
        }
    }

    @JavascriptInterface
    fun removeOptOutContact(contactId: Long): String {
        val success = dbHelper.removeOptOutContact(contactId)
        return JSONObject().put("success", success).toString()
    }

    @JavascriptInterface
    fun getFormattedCampaignReport(campaignId: Long): String {
        return try {
            dbHelper.generateTxtReport(campaignId)
        } catch (e: Exception) {
            "SMS BLAST REPORT\nACR SERVICES\nError generating report: ${e.message}"
        }
    }

    @JavascriptInterface
    fun sendCampaignEmailReport(campaignId: Long, customEmail: String): String {
        val email = if (customEmail.isNotBlank()) customEmail.trim() else prefs.reportEmail.trim()
        if (email.isBlank()) {
            return JSONObject().put("success", false).put("errorMessage", "No recipient email address specified. Please configure your email address in Settings.").toString()
        }

        val reportTxt = dbHelper.generateTxtReport(campaignId)
        val campaign = dbHelper.getCampaignDetails(campaignId)
        val name = campaign?.optString("name", "Campaign #$campaignId") ?: "Campaign #$campaignId"
        val subject = "SMS BLAST REPORT - $name"

        val result = com.example.email.GmailSmtpHelper.sendEmailSync(email, subject, reportTxt)
        return if (result.isSuccess) {
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, "Report sent to $email via Gmail SMTP", android.widget.Toast.LENGTH_SHORT).show()
            }
            JSONObject().put("success", true).put("email", email).put("message", "Report sent via Gmail SMTP").toString()
        } else {
            val err = result.exceptionOrNull()?.message ?: "Unknown SMTP error"
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, "SMTP Error: $err", android.widget.Toast.LENGTH_LONG).show()
            }
            JSONObject().put("success", false).put("errorMessage", err).toString()
        }
    }

    @JavascriptInterface
    fun sendEmailReport(email: String, subject: String, body: String): String {
        val target = if (email.isNotBlank()) email.trim() else prefs.reportEmail.trim()
        if (target.isBlank()) {
            return JSONObject().put("success", false).put("errorMessage", "No recipient email address specified.").toString()
        }
        val result = com.example.email.GmailSmtpHelper.sendEmailSync(target, subject, body)
        return if (result.isSuccess) {
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, "Report sent to $target via Gmail SMTP", android.widget.Toast.LENGTH_SHORT).show()
            }
            JSONObject().put("success", true).put("email", target).put("message", "Report sent via Gmail SMTP").toString()
        } else {
            val err = result.exceptionOrNull()?.message ?: "Unknown SMTP error"
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, "SMTP Error: $err", android.widget.Toast.LENGTH_LONG).show()
            }
            JSONObject().put("success", false).put("errorMessage", err).toString()
        }
    }

    @JavascriptInterface
    fun testSmtpConnection(targetEmail: String): String {
        val email = if (targetEmail.isNotBlank()) targetEmail.trim() else prefs.reportEmail.trim()
        if (email.isBlank()) {
            return JSONObject().put("success", false).put("errorMessage", "Please provide a valid destination email address to test.").toString()
        }
        val subject = "Gmail SMTP Connection Test - ACR Services"
        val body = "Hello,\n\nThis is a test notification confirming that Gmail SMTP dispatch (itdepttranzfast@gmail.com) is operational.\nYour SMS Blast reports will be delivered here automatically.\n\nACR SERVICES"
        val result = com.example.email.GmailSmtpHelper.sendEmailSync(email, subject, body)
        return if (result.isSuccess) {
            JSONObject().put("success", true).put("message", "Test email sent successfully to $email via Gmail SMTP!").toString()
        } else {
            JSONObject().put("success", false).put("errorMessage", result.exceptionOrNull()?.message ?: "Unknown error").toString()
        }
    }

    @JavascriptInterface
    fun openFilePicker() {
        activity.runOnUiThread {
            activity.launchFilePicker()
        }
    }
}
