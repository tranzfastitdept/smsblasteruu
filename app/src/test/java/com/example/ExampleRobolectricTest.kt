package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.db.BulkSmsDatabaseHelper
import com.example.generator.NumberGenerator
import com.example.prefs.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string app_name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Bulk SMS", appName)
    }

    @Test
    fun `test number generator preserves leading zeros and start-inclusive end-exclusive`() {
        val result = NumberGenerator.generateNumbers("0992438", "0000", "0100", 1000)
        assertTrue(result.success)
        assertEquals(100, result.totalCount)
        assertEquals("09924380000", result.numbers.first())
        assertEquals("09924380099", result.numbers.last())
        assertEquals("09924380005", result.numbers[5])
    }

    @Test
    fun `test number generator invalid range validation`() {
        // Start >= End should fail
        val result = NumberGenerator.generateNumbers("0992438", "0500", "0100", 1000)
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `test placeholder replacement`() {
        val template = "Hello {name}, your code is 1234."
        val rendered = template.replace("{name}", "Alice Smith", ignoreCase = true)
        assertEquals("Hello Alice Smith, your code is 1234.", rendered)
    }

    @Test
    fun `test shared preferences persistence and defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)
        prefs.resetToDefaults()

        assertEquals(AppPreferences.DEFAULT_INTERVAL_MS, prefs.sendingIntervalMs)
        assertEquals("system", prefs.themePreference)

        prefs.sendingIntervalMs = 8000L
        assertEquals(8000L, prefs.sendingIntervalMs)

        prefs.resetToDefaults()
        assertEquals(AppPreferences.DEFAULT_INTERVAL_MS, prefs.sendingIntervalMs)
    }

    @Test
    fun `test sqlite database contacts and lists creation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = BulkSmsDatabaseHelper(context)

        val contacts = listOf(
            mapOf(
                "phone_number" to "+15551234567",
                "original_phone_number" to "555-123-4567",
                "display_name" to "Test User",
                "group_name" to "VIP",
                "consent_status" to BulkSmsDatabaseHelper.CONSENT_VERIFIED,
                "consent_source" to "Unit Test"
            )
        )

        val listId = dbHelper.saveRecipientList("Unit Test List", "test", contacts)
        assertTrue(listId > 0)

        val lists = dbHelper.getRecipientLists()
        assertTrue(lists.length() > 0)

        val members = dbHelper.getContactsForList(listId)
        assertEquals(1, members.length())
        assertEquals("+15551234567", members.getJSONObject(0).getString("phoneNumber"))
    }

    @Test
    fun `test opt-out and suppression blacklist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = BulkSmsDatabaseHelper(context)

        val id = dbHelper.addOptOutContact("+19998887777", "Replied STOP")
        assertTrue(id > 0)

        val optOuts = dbHelper.getOptOutContacts()
        assertTrue(optOuts.length() > 0)

        val removed = dbHelper.removeOptOutContact(id)
        assertTrue(removed)
    }

    @Test
    fun `test email report generation format and acr services header`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = BulkSmsDatabaseHelper(context)

        val contacts = listOf(
            mapOf(
                "phone_number" to "09123450000",
                "original_phone_number" to "09123450000",
                "display_name" to "Sample Recipient",
                "consent_status" to BulkSmsDatabaseHelper.CONSENT_VERIFIED
            )
        )
        val listId = dbHelper.saveRecipientList("Report Test List", "test", contacts)
        val campaignId = dbHelper.createCampaign(
            name = "Test Blast Campaign",
            template = "Hello {name}",
            recipientListId = listId,
            intervalMs = 5000L,
            subId = -1,
            allowUnverifiedConsent = true
        )

        assertTrue(campaignId > 0)

        val reportTxt = dbHelper.generateTxtReport(campaignId)
        assertTrue(reportTxt.contains("SMS BLAST REPORT"))
        assertTrue(reportTxt.contains("ACR SERVICES"))
        assertTrue(reportTxt.contains("SUCCESSFUL:"))
        assertTrue(reportTxt.contains("UNSUCCESSFUL:"))
        assertTrue(reportTxt.contains("TOTAL OVERALL:"))
        assertTrue(reportTxt.contains("INDIVIDUAL NUMBER REPORTS:"))
        assertTrue(reportTxt.contains("0912 345 0000"))
    }

    @Test
    fun `test report email preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)
        prefs.reportEmail = "reports@acrservices.com"
        prefs.autoEmailReport = true
        prefs.compatibilityMode = true

        assertEquals("reports@acrservices.com", prefs.reportEmail)
        assertTrue(prefs.autoEmailReport)
        assertTrue(prefs.compatibilityMode)
    }
}
