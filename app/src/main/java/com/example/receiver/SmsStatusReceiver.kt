package com.example.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.example.BulkSmsApp
import com.example.db.BulkSmsDatabaseHelper
import com.example.service.SmsQueueForegroundService
import com.example.telephony.SmsManagerHelper

class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return
        val correlationId = intent.getStringExtra(SmsManagerHelper.EXTRA_CORRELATION_ID) ?: return
        val jobId = intent.getLongExtra(SmsManagerHelper.EXTRA_JOB_ID, -1L)
        val campaignId = intent.getLongExtra(SmsManagerHelper.EXTRA_CAMPAIGN_ID, -1L)

        val dbHelper = BulkSmsApp.instance.dbHelper

        when (action) {
            SmsManagerHelper.ACTION_SMS_SENT -> {
                val resultCode = resultCode
                if (resultCode == Activity.RESULT_OK) {
                    dbHelper.markJobByCorrelation(correlationId, BulkSmsDatabaseHelper.STATUS_SUBMITTED)
                    SmsQueueForegroundService.notifySmsResult(jobId, correlationId, true, "SMS accepted by Android telephony system")
                } else {
                    val errorDesc = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic carrier or device error"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off / Airplane mode active"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU received"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service / Out of range"
                        else -> "Telephony failure code $resultCode"
                    }
                    dbHelper.markJobByCorrelation(correlationId, BulkSmsDatabaseHelper.STATUS_FAILED, resultCode, errorDesc)
                    SmsQueueForegroundService.notifySmsResult(jobId, correlationId, false, errorDesc)
                }
            }

            SmsManagerHelper.ACTION_SMS_DELIVERED -> {
                val resultCode = resultCode
                if (resultCode == Activity.RESULT_OK) {
                    dbHelper.markJobByCorrelation(correlationId, BulkSmsDatabaseHelper.STATUS_DELIVERED)
                    SmsQueueForegroundService.notifyDeliveryConfirmed(jobId, correlationId)
                } else {
                    val errorDesc = "Handset delivery not confirmed (code $resultCode)"
                    dbHelper.logMessageEvent(
                        dbHelper.writableDatabase,
                        jobId,
                        campaignId,
                        "DELIVERY_UNCONFIRMED",
                        errorDesc
                    )
                }
            }
        }
    }
}
