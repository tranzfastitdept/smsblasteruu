package com.example.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class BulkSmsDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "bulk_sms_campaigns.db"
        const val DATABASE_VERSION = 1

        // Table names
        const val TABLE_CONTACTS = "contacts"
        const val TABLE_RECIPIENT_LISTS = "recipient_lists"
        const val TABLE_RECIPIENT_LIST_MEMBERS = "recipient_list_members"
        const val TABLE_CAMPAIGNS = "campaigns"
        const val TABLE_MESSAGE_JOBS = "message_jobs"
        const val TABLE_MESSAGE_EVENTS = "message_events"
        const val TABLE_MESSAGE_TEMPLATES = "message_templates"
        const val TABLE_APP_EVENTS = "app_events"

        // Status Constants
        const val STATUS_DRAFT = "Draft"
        const val STATUS_QUEUED = "Queued"
        const val STATUS_PROCESSING = "Processing"
        const val STATUS_SUBMITTED = "Submitted"
        const val STATUS_DELIVERED = "Delivered"
        const val STATUS_FAILED = "Failed"
        const val STATUS_CANCELLED = "Cancelled"
        const val STATUS_UNKNOWN = "Unknown"
        const val STATUS_PAUSED = "Paused"
        const val STATUS_COMPLETED = "Completed"
        const val STATUS_STOPPED = "Stopped"

        // Consent Constants
        const val CONSENT_NONE = 0
        const val CONSENT_VERIFIED = 1
        const val CONSENT_OPTED_OUT = -1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CONTACTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone_number TEXT NOT NULL UNIQUE,
                original_phone_number TEXT,
                display_name TEXT,
                group_name TEXT,
                consent_status INTEGER DEFAULT 0,
                consent_source TEXT,
                consent_timestamp INTEGER,
                created_at INTEGER,
                updated_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_RECIPIENT_LISTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                source_type TEXT,
                created_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_RECIPIENT_LIST_MEMBERS (
                recipient_list_id INTEGER,
                contact_id INTEGER,
                PRIMARY KEY (recipient_list_id, contact_id),
                FOREIGN KEY (recipient_list_id) REFERENCES $TABLE_RECIPIENT_LISTS(id) ON DELETE CASCADE,
                FOREIGN KEY (contact_id) REFERENCES $TABLE_CONTACTS(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_CAMPAIGNS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                message_template TEXT NOT NULL,
                recipient_list_id INTEGER,
                interval_ms INTEGER DEFAULT 5000,
                selected_subscription_id INTEGER DEFAULT -1,
                status TEXT DEFAULT '$STATUS_DRAFT',
                total_recipients INTEGER DEFAULT 0,
                created_at INTEGER,
                started_at INTEGER,
                completed_at INTEGER,
                FOREIGN KEY (recipient_list_id) REFERENCES $TABLE_RECIPIENT_LISTS(id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGE_JOBS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                campaign_id INTEGER,
                contact_id INTEGER,
                destination_number TEXT NOT NULL,
                rendered_message TEXT NOT NULL,
                queue_position INTEGER,
                status TEXT DEFAULT '$STATUS_QUEUED',
                attempt_count INTEGER DEFAULT 0,
                created_at INTEGER,
                scheduled_at INTEGER,
                submitted_at INTEGER,
                delivered_at INTEGER,
                failed_at INTEGER,
                error_code INTEGER DEFAULT 0,
                error_message TEXT,
                correlation_id TEXT,
                FOREIGN KEY (campaign_id) REFERENCES $TABLE_CAMPAIGNS(id) ON DELETE CASCADE,
                FOREIGN KEY (contact_id) REFERENCES $TABLE_CONTACTS(id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGE_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_job_id INTEGER,
                campaign_id INTEGER,
                event_type TEXT,
                event_timestamp INTEGER,
                details TEXT,
                FOREIGN KEY (message_job_id) REFERENCES $TABLE_MESSAGE_JOBS(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGE_TEMPLATES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER,
                updated_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_APP_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT,
                event_timestamp INTEGER,
                campaign_id INTEGER,
                message_job_id INTEGER,
                details TEXT
            )
            """.trimIndent()
        )

        // Performance indexes
        db.execSQL("CREATE INDEX idx_jobs_campaign ON $TABLE_MESSAGE_JOBS(campaign_id)")
        db.execSQL("CREATE INDEX idx_jobs_status ON $TABLE_MESSAGE_JOBS(status)")
        db.execSQL("CREATE INDEX idx_jobs_corr ON $TABLE_MESSAGE_JOBS(correlation_id)")
        db.execSQL("CREATE INDEX idx_events_job ON $TABLE_MESSAGE_EVENTS(message_job_id)")
        db.execSQL("CREATE INDEX idx_members_list ON $TABLE_RECIPIENT_LIST_MEMBERS(recipient_list_id)")

        // Seed initial templates
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO $TABLE_MESSAGE_TEMPLATES (name, content, created_at, updated_at)
            VALUES 
            ('Account Alert', 'Hello {name}, this is an urgent notification regarding your account.', $now, $now),
            ('Appointment Reminder', 'Dear {name}, this is a reminder for your upcoming appointment. Please reply to confirm.', $now, $now),
            ('Service Notice', 'Notice for {name}: Scheduled maintenance will occur tonight. Service will resume shortly.', $now, $now)
            """.trimIndent()
        )

        // Seed initial sample list for easy first-run testing
        db.execSQL(
            """
            INSERT INTO $TABLE_RECIPIENT_LISTS (name, source_type, created_at)
            VALUES ('Demo Verified List', 'manual', $now)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle migrations in future versions
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // ==========================================
    // RECONCILIATION & RECOVERY
    // ==========================================
    fun reconcileInterruptedJobs() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // Find jobs stuck in 'Processing' when app died
            val cursor = db.rawQuery(
                "SELECT id, campaign_id FROM $TABLE_MESSAGE_JOBS WHERE status = '$STATUS_PROCESSING'",
                null
            )
            val stuckJobIds = mutableListOf<Pair<Long, Long>>()
            while (cursor.moveToNext()) {
                stuckJobIds.add(Pair(cursor.getLong(0), cursor.getLong(1)))
            }
            cursor.close()

            for (pair in stuckJobIds) {
                val jobId = pair.first
                val campaignId = pair.second

                val cv = ContentValues().apply {
                    put("status", STATUS_UNKNOWN)
                    put("error_message", "Interrupted during send - marked Unknown to prevent duplicate submission")
                    put("failed_at", now)
                }
                db.update(TABLE_MESSAGE_JOBS, cv, "id = ?", arrayOf(jobId.toString()))

                // Record event
                logMessageEvent(db, jobId, campaignId, "RECONCILED_UNKNOWN", "App restarted while message was processing; preserved as Unknown.")
            }

            // Check if any campaign was left in 'Processing'
            val campCursor = db.rawQuery(
                "SELECT id FROM $TABLE_CAMPAIGNS WHERE status = '$STATUS_PROCESSING'",
                null
            )
            while (campCursor.moveToNext()) {
                val cId = campCursor.getLong(0)
                val cv = ContentValues().apply {
                    put("status", STATUS_PAUSED)
                }
                db.update(TABLE_CAMPAIGNS, cv, "id = ?", arrayOf(cId.toString()))
                logAppEvent(db, "CAMPAIGN_RECOVERY_PAUSED", cId, null, "Campaign was in progress during app termination; restored to Paused state.")
            }
            campCursor.close()

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ==========================================
    // LOGGING HELPERS
    // ==========================================
    fun logMessageEvent(db: SQLiteDatabase, jobId: Long, campaignId: Long?, eventType: String, details: String) {
        val cv = ContentValues().apply {
            put("message_job_id", jobId)
            if (campaignId != null) put("campaign_id", campaignId)
            put("event_type", eventType)
            put("event_timestamp", System.currentTimeMillis())
            put("details", details)
        }
        db.insert(TABLE_MESSAGE_EVENTS, null, cv)
    }

    fun logAppEvent(db: SQLiteDatabase, eventType: String, campaignId: Long?, jobId: Long?, details: String) {
        val cv = ContentValues().apply {
            put("event_type", eventType)
            put("event_timestamp", System.currentTimeMillis())
            if (campaignId != null) put("campaign_id", campaignId)
            if (jobId != null) put("message_job_id", jobId)
            put("details", details)
        }
        db.insert(TABLE_APP_EVENTS, null, cv)
    }

    // ==========================================
    // DASHBOARD STATS
    // ==========================================
    fun getDashboardStats(): JSONObject {
        val db = readableDatabase
        val result = JSONObject()

        var totalCampaigns = 0
        var totalRecipients = 0
        var queued = 0
        var submitted = 0
        var delivered = 0
        var failed = 0
        var awaiting = 0
        var sentToday = 0
        var mostRecentCampaignName = "None"
        var lastActivityTime: Long = 0

        // Campaigns count
        val cCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CAMPAIGNS", null)
        if (cCursor.moveToFirst()) totalCampaigns = cCursor.getInt(0)
        cCursor.close()

        // Most recent campaign
        val mrcCursor = db.rawQuery("SELECT name FROM $TABLE_CAMPAIGNS ORDER BY created_at DESC LIMIT 1", null)
        if (mrcCursor.moveToFirst()) mostRecentCampaignName = mrcCursor.getString(0)
        mrcCursor.close()

        // Total contacts/recipients
        val conCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CONTACTS", null)
        if (conCursor.moveToFirst()) totalRecipients = conCursor.getInt(0)
        conCursor.close()

        // Message job counts by status
        val statusCursor = db.rawQuery(
            "SELECT status, COUNT(*) FROM $TABLE_MESSAGE_JOBS GROUP BY status",
            null
        )
        while (statusCursor.moveToNext()) {
            val status = statusCursor.getString(0)
            val count = statusCursor.getInt(1)
            when (status) {
                STATUS_QUEUED -> queued += count
                STATUS_PROCESSING -> awaiting += count
                STATUS_SUBMITTED -> submitted += count
                STATUS_DELIVERED -> delivered += count
                STATUS_FAILED -> failed += count
                STATUS_CANCELLED -> { /* counted in reports */ }
            }
        }
        statusCursor.close()

        // Sent today (submitted or delivered with timestamp >= start of today)
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis

        val todayCursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGE_JOBS WHERE submitted_at >= ?",
            arrayOf(startOfToday.toString())
        )
        if (todayCursor.moveToFirst()) sentToday = todayCursor.getInt(0)
        todayCursor.close()

        // Last activity timestamp from events
        val eventCursor = db.rawQuery(
            "SELECT MAX(event_timestamp) FROM $TABLE_MESSAGE_EVENTS",
            null
        )
        if (eventCursor.moveToFirst() && !eventCursor.isNull(0)) {
            lastActivityTime = eventCursor.getLong(0)
        }
        eventCursor.close()

        result.put("totalCampaigns", totalCampaigns)
        result.put("totalRecipients", totalRecipients)
        result.put("queued", queued)
        result.put("submitted", submitted)
        result.put("delivered", delivered)
        result.put("failed", failed)
        result.put("awaiting", awaiting)
        result.put("sentToday", sentToday)
        result.put("mostRecentCampaign", mostRecentCampaignName)
        result.put("lastActivityTimestamp", lastActivityTime)

        return result
    }

    // ==========================================
    // CONTACTS & LISTS
    // ==========================================
    fun getRecipientLists(): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val cursor = db.rawQuery(
            """
            SELECT l.id, l.name, l.source_type, l.created_at, COUNT(m.contact_id) as member_count
            FROM $TABLE_RECIPIENT_LISTS l
            LEFT JOIN $TABLE_RECIPIENT_LIST_MEMBERS m ON l.id = m.recipient_list_id
            GROUP BY l.id
            ORDER BY l.created_at DESC
            """.trimIndent(),
            null
        )
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("name", cursor.getString(1))
            obj.put("sourceType", cursor.getString(2) ?: "")
            obj.put("createdAt", cursor.getLong(3))
            obj.put("memberCount", cursor.getInt(4))
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun saveRecipientList(name: String, sourceType: String, contacts: List<Map<String, Any>>): Long {
        val db = writableDatabase
        db.beginTransaction()
        var listId: Long = -1
        try {
            val now = System.currentTimeMillis()
            val listCv = ContentValues().apply {
                put("name", name)
                put("source_type", sourceType)
                put("created_at", now)
            }
            listId = db.insert(TABLE_RECIPIENT_LISTS, null, listCv)

            for (contact in contacts) {
                val phone = contact["phone_number"] as? String ?: continue
                val origPhone = contact["original_phone_number"] as? String ?: phone
                val dispName = contact["display_name"] as? String ?: ""
                val group = contact["group_name"] as? String ?: ""
                val consent = (contact["consent_status"] as? Number)?.toInt() ?: CONSENT_NONE
                val consentSrc = contact["consent_source"] as? String ?: sourceType

                // Check if contact already exists
                var contactId: Long = -1
                val check = db.rawQuery(
                    "SELECT id FROM $TABLE_CONTACTS WHERE phone_number = ?",
                    arrayOf(phone)
                )
                if (check.moveToFirst()) {
                    contactId = check.getLong(0)
                    // If existing contact was already opted-out, preserve opt-out!
                }
                check.close()

                if (contactId == -1L) {
                    val cCv = ContentValues().apply {
                        put("phone_number", phone)
                        put("original_phone_number", origPhone)
                        put("display_name", dispName)
                        put("group_name", group)
                        put("consent_status", consent)
                        put("consent_source", consentSrc)
                        put("consent_timestamp", now)
                        put("created_at", now)
                        put("updated_at", now)
                    }
                    contactId = db.insert(TABLE_CONTACTS, null, cCv)
                }

                if (contactId != -1L) {
                    val mCv = ContentValues().apply {
                        put("recipient_list_id", listId)
                        put("contact_id", contactId)
                    }
                    db.insertWithOnConflict(
                        TABLE_RECIPIENT_LIST_MEMBERS,
                        null,
                        mCv,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return listId
    }

    fun getContactsForList(listId: Long, limit: Int = 100, offset: Int = 0): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val query = if (listId > 0) {
            """
            SELECT c.id, c.phone_number, c.original_phone_number, c.display_name, c.group_name, c.consent_status, c.consent_source, c.created_at
            FROM $TABLE_CONTACTS c
            INNER JOIN $TABLE_RECIPIENT_LIST_MEMBERS m ON c.id = m.contact_id
            WHERE m.recipient_list_id = ?
            ORDER BY c.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        } else {
            """
            SELECT c.id, c.phone_number, c.original_phone_number, c.display_name, c.group_name, c.consent_status, c.consent_source, c.created_at
            FROM $TABLE_CONTACTS c
            ORDER BY c.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        }

        val args = if (listId > 0) arrayOf(listId.toString(), limit.toString(), offset.toString())
        else arrayOf(limit.toString(), offset.toString())

        val cursor = db.rawQuery(query, args)
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("phoneNumber", cursor.getString(1))
            obj.put("originalPhoneNumber", cursor.getString(2) ?: "")
            obj.put("displayName", cursor.getString(3) ?: "")
            obj.put("groupName", cursor.getString(4) ?: "")
            obj.put("consentStatus", cursor.getInt(5))
            obj.put("consentSource", cursor.getString(6) ?: "")
            obj.put("createdAt", cursor.getLong(7))
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun toggleContactOptOut(contactId: Long, optOut: Boolean): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("consent_status", if (optOut) CONSENT_OPTED_OUT else CONSENT_VERIFIED)
            put("updated_at", System.currentTimeMillis())
        }
        val rows = db.update(TABLE_CONTACTS, cv, "id = ?", arrayOf(contactId.toString()))
        return rows > 0
    }

    fun deleteContact(contactId: Long): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_CONTACTS, "id = ?", arrayOf(contactId.toString()))
        return rows > 0
    }

    // ==========================================
    // TEMPLATES
    // ==========================================
    fun getTemplates(): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val cursor = db.rawQuery(
            "SELECT id, name, content, created_at, updated_at FROM $TABLE_MESSAGE_TEMPLATES ORDER BY id DESC",
            null
        )
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("name", cursor.getString(1))
            obj.put("content", cursor.getString(2))
            obj.put("createdAt", cursor.getLong(3))
            obj.put("updatedAt", cursor.getLong(4))
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun saveTemplate(id: Long, name: String, content: String): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("name", name)
            put("content", content)
            put("updated_at", now)
        }
        return if (id > 0) {
            db.update(TABLE_MESSAGE_TEMPLATES, cv, "id = ?", arrayOf(id.toString()))
            id
        } else {
            cv.put("created_at", now)
            db.insert(TABLE_MESSAGE_TEMPLATES, null, cv)
        }
    }

    fun deleteTemplate(id: Long): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_MESSAGE_TEMPLATES, "id = ?", arrayOf(id.toString())) > 0
    }

    // ==========================================
    // CAMPAIGNS & JOBS
    // ==========================================
    fun createCampaign(
        name: String,
        template: String,
        recipientListId: Long,
        intervalMs: Long,
        subId: Int,
        allowUnverifiedConsent: Boolean = false
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        var campaignId: Long = -1
        try {
            val now = System.currentTimeMillis()

            // 1. Fetch eligible contacts from recipient list
            // Exclude opted-out contacts (consent_status = -1) always!
            // If !allowUnverifiedConsent, exclude unverified (consent_status = 0)
            val consentFilter = if (allowUnverifiedConsent) {
                "c.consent_status != ${CONSENT_OPTED_OUT}"
            } else {
                "c.consent_status = ${CONSENT_VERIFIED}"
            }

            val query = """
                SELECT c.id, c.phone_number, c.display_name
                FROM $TABLE_CONTACTS c
                INNER JOIN $TABLE_RECIPIENT_LIST_MEMBERS m ON c.id = m.contact_id
                WHERE m.recipient_list_id = ? AND $consentFilter
                ORDER BY c.id ASC
            """.trimIndent()

            val cursor = db.rawQuery(query, arrayOf(recipientListId.toString()))
            val contacts = mutableListOf<Triple<Long, String, String>>()
            while (cursor.moveToNext()) {
                contacts.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2) ?: ""))
            }
            cursor.close()

            if (contacts.isEmpty()) {
                // No eligible recipients
                db.endTransaction()
                return -1
            }

            // 2. Insert Campaign record
            val cCv = ContentValues().apply {
                put("name", name)
                put("message_template", template)
                put("recipient_list_id", recipientListId)
                put("interval_ms", intervalMs)
                put("selected_subscription_id", subId)
                put("status", STATUS_QUEUED)
                put("total_recipients", contacts.size)
                put("created_at", now)
            }
            campaignId = db.insert(TABLE_CAMPAIGNS, null, cCv)

            // 3. Insert Message Jobs transactionally
            var pos = 1
            for (contact in contacts) {
                val cId = contact.first
                val phone = contact.second
                val dispName = contact.third.ifBlank { "Valued Customer" }

                // Placeholder personalization: {name}
                val rendered = template.replace("{name}", dispName, ignoreCase = true)
                val corrId = "job-${campaignId}-$pos-${System.currentTimeMillis()}"

                val jCv = ContentValues().apply {
                    put("campaign_id", campaignId)
                    put("contact_id", cId)
                    put("destination_number", phone)
                    put("rendered_message", rendered)
                    put("queue_position", pos)
                    put("status", STATUS_QUEUED)
                    put("attempt_count", 0)
                    put("created_at", now)
                    put("scheduled_at", now + (pos - 1) * intervalMs)
                    put("correlation_id", corrId)
                }
                val jId = db.insert(TABLE_MESSAGE_JOBS, null, jCv)
                logMessageEvent(db, jId, campaignId, "QUEUED", "Position $pos in queue. Scheduled for transmission.")
                pos++
            }

            logAppEvent(db, "CAMPAIGN_CREATED", campaignId, null, "Campaign '$name' created with ${contacts.size} jobs.")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return campaignId
    }

    fun getCampaigns(limit: Int = 50, offset: Int = 0): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val cursor = db.rawQuery(
            """
            SELECT c.id, c.name, c.message_template, c.recipient_list_id, c.interval_ms,
                   c.selected_subscription_id, c.status, c.total_recipients, c.created_at,
                   c.started_at, c.completed_at, l.name as list_name,
                   (SELECT COUNT(*) FROM $TABLE_MESSAGE_JOBS WHERE campaign_id = c.id AND status = '$STATUS_SUBMITTED') as submitted_count,
                   (SELECT COUNT(*) FROM $TABLE_MESSAGE_JOBS WHERE campaign_id = c.id AND status = '$STATUS_DELIVERED') as delivered_count,
                   (SELECT COUNT(*) FROM $TABLE_MESSAGE_JOBS WHERE campaign_id = c.id AND status = '$STATUS_FAILED') as failed_count
            FROM $TABLE_CAMPAIGNS c
            LEFT JOIN $TABLE_RECIPIENT_LISTS l ON c.recipient_list_id = l.id
            ORDER BY c.created_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(limit.toString(), offset.toString())
        )
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("name", cursor.getString(1))
            obj.put("template", cursor.getString(2))
            obj.put("recipientListId", cursor.getLong(3))
            obj.put("intervalMs", cursor.getLong(4))
            obj.put("selectedSubId", cursor.getInt(5))
            obj.put("status", cursor.getString(6))
            obj.put("totalRecipients", cursor.getInt(7))
            obj.put("createdAt", cursor.getLong(8))
            obj.put("startedAt", cursor.getLong(9))
            obj.put("completedAt", cursor.getLong(10))
            obj.put("listName", cursor.getString(11) ?: "Direct")
            obj.put("submittedCount", cursor.getInt(12))
            obj.put("deliveredCount", cursor.getInt(13))
            obj.put("failedCount", cursor.getInt(14))
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun getCampaignDetails(campaignId: Long): JSONObject? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT c.id, c.name, c.message_template, c.recipient_list_id, c.interval_ms,
                   c.selected_subscription_id, c.status, c.total_recipients, c.created_at,
                   c.started_at, c.completed_at, l.name as list_name
            FROM $TABLE_CAMPAIGNS c
            LEFT JOIN $TABLE_RECIPIENT_LISTS l ON c.recipient_list_id = l.id
            WHERE c.id = ?
            """.trimIndent(),
            arrayOf(campaignId.toString())
        )
        if (!cursor.moveToFirst()) {
            cursor.close()
            return null
        }
        val obj = JSONObject()
        obj.put("id", cursor.getLong(0))
        obj.put("name", cursor.getString(1))
        obj.put("template", cursor.getString(2))
        obj.put("recipientListId", cursor.getLong(3))
        obj.put("intervalMs", cursor.getLong(4))
        obj.put("selectedSubId", cursor.getInt(5))
        obj.put("status", cursor.getString(6))
        obj.put("totalRecipients", cursor.getInt(7))
        obj.put("createdAt", cursor.getLong(8))
        obj.put("startedAt", cursor.getLong(9))
        obj.put("completedAt", cursor.getLong(10))
        obj.put("listName", cursor.getString(11) ?: "Direct")
        cursor.close()

        // Job stats
        val statCursor = db.rawQuery(
            "SELECT status, COUNT(*) FROM $TABLE_MESSAGE_JOBS WHERE campaign_id = ? GROUP BY status",
            arrayOf(campaignId.toString())
        )
        val stats = JSONObject()
        while (statCursor.moveToNext()) {
            stats.put(statCursor.getString(0), statCursor.getInt(1))
        }
        statCursor.close()
        obj.put("stats", stats)

        return obj
    }

    fun updateCampaignStatus(campaignId: Long, status: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", status)
            if (status == STATUS_PROCESSING) {
                // If started_at is 0/null, set it
                put("started_at", System.currentTimeMillis())
            } else if (status == STATUS_COMPLETED || status == STATUS_STOPPED) {
                put("completed_at", System.currentTimeMillis())
            }
        }
        db.update(TABLE_CAMPAIGNS, cv, "id = ?", arrayOf(campaignId.toString()))
        logAppEvent(db, "CAMPAIGN_STATUS_CHANGED", campaignId, null, "Status updated to $status")
    }

    // ==========================================
    // ACTIVE CAMPAIGN QUEUE OPERATIONS
    // ==========================================
    fun getNextQueuedJob(campaignId: Long): JSONObject? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT id, campaign_id, contact_id, destination_number, rendered_message, queue_position, attempt_count, correlation_id
            FROM $TABLE_MESSAGE_JOBS
            WHERE campaign_id = ? AND status = '$STATUS_QUEUED'
            ORDER BY queue_position ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignId.toString())
        )
        if (!cursor.moveToFirst()) {
            cursor.close()
            return null
        }
        val obj = JSONObject().apply {
            put("id", cursor.getLong(0))
            put("campaignId", cursor.getLong(1))
            put("contactId", cursor.getLong(2))
            put("destinationNumber", cursor.getString(3))
            put("renderedMessage", cursor.getString(4))
            put("queuePosition", cursor.getInt(5))
            put("attemptCount", cursor.getInt(6))
            put("correlationId", cursor.getString(7))
        }
        cursor.close()
        return obj
    }

    fun markJobProcessing(jobId: Long, campaignId: Long) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", STATUS_PROCESSING)
            put("attempt_count", 1)
        }
        db.update(TABLE_MESSAGE_JOBS, cv, "id = ?", arrayOf(jobId.toString()))
        logMessageEvent(db, jobId, campaignId, "SEND_ATTEMPT_STARTED", "Send attempt initiated via native SMS layer")
    }

    fun markJobSubmitted(jobId: Long, campaignId: Long) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("status", STATUS_SUBMITTED)
            put("submitted_at", now)
        }
        db.update(TABLE_MESSAGE_JOBS, cv, "id = ?", arrayOf(jobId.toString()))
        logMessageEvent(db, jobId, campaignId, "SMS_ACCEPTED_BY_ANDROID", "SMS accepted by Android telephony system")
    }

    fun markJobDelivered(jobId: Long, campaignId: Long) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("status", STATUS_DELIVERED)
            put("delivered_at", now)
        }
        db.update(TABLE_MESSAGE_JOBS, cv, "id = ?", arrayOf(jobId.toString()))
        logMessageEvent(db, jobId, campaignId, "SMS_CONFIRMED_DELIVERED", "Carrier confirmed delivery to recipient handset")
    }

    fun markJobFailed(jobId: Long, campaignId: Long, errorCode: Int, errorMessage: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("status", STATUS_FAILED)
            put("failed_at", now)
            put("error_code", errorCode)
            put("error_message", errorMessage)
        }
        db.update(TABLE_MESSAGE_JOBS, cv, "id = ?", arrayOf(jobId.toString()))
        logMessageEvent(db, jobId, campaignId, "SMS_FAILED", "Failed (Code $errorCode): $errorMessage")
    }

    fun markJobByCorrelation(corrId: String, status: String, errorCode: Int = 0, errorMessage: String = "") {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT id, campaign_id, status FROM $TABLE_MESSAGE_JOBS WHERE correlation_id = ?",
            arrayOf(corrId)
        )
        if (cursor.moveToFirst()) {
            val jobId = cursor.getLong(0)
            val campaignId = cursor.getLong(1)
            val currentStatus = cursor.getString(2)
            cursor.close()

            // Guard against duplicate callback overriding final state
            if (currentStatus == STATUS_DELIVERED && status != STATUS_DELIVERED) {
                return
            }

            when (status) {
                STATUS_SUBMITTED -> markJobSubmitted(jobId, campaignId)
                STATUS_DELIVERED -> markJobDelivered(jobId, campaignId)
                STATUS_FAILED -> markJobFailed(jobId, campaignId, errorCode, errorMessage)
            }
        } else {
            cursor.close()
        }
    }

    fun getMessageJobs(
        campaignId: Long?,
        statusFilter: String?,
        limit: Int = 100,
        offset: Int = 0
    ): JSONArray {
        val db = readableDatabase
        val array = JSONArray()

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (campaignId != null && campaignId > 0) {
            whereClauses.add("j.campaign_id = ?")
            args.add(campaignId.toString())
        }
        if (!statusFilter.isNullOrBlank() && statusFilter != "ALL") {
            whereClauses.add("j.status = ?")
            args.add(statusFilter)
        }

        val where = if (whereClauses.isEmpty()) "" else "WHERE " + whereClauses.joinToString(" AND ")
        val query = """
            SELECT j.id, j.campaign_id, j.destination_number, j.rendered_message, j.queue_position,
                   j.status, j.attempt_count, j.created_at, j.scheduled_at, j.submitted_at,
                   j.delivered_at, j.failed_at, j.error_code, j.error_message, j.correlation_id,
                   c.name as campaign_name
            FROM $TABLE_MESSAGE_JOBS j
            LEFT JOIN $TABLE_CAMPAIGNS c ON j.campaign_id = c.id
            $where
            ORDER BY j.id DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        args.add(limit.toString())
        args.add(offset.toString())

        val cursor = db.rawQuery(query, args.toTypedArray())
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("campaignId", cursor.getLong(1))
            obj.put("destinationNumber", cursor.getString(2))
            obj.put("renderedMessage", cursor.getString(3))
            obj.put("queuePosition", cursor.getInt(4))
            obj.put("status", cursor.getString(5))
            obj.put("attemptCount", cursor.getInt(6))
            obj.put("createdAt", cursor.getLong(7))
            obj.put("scheduledAt", cursor.getLong(8))
            obj.put("submittedAt", cursor.getLong(9))
            obj.put("deliveredAt", cursor.getLong(10))
            obj.put("failedAt", cursor.getLong(11))
            obj.put("errorCode", cursor.getInt(12))
            obj.put("errorMessage", cursor.getString(13) ?: "")
            obj.put("correlationId", cursor.getString(14) ?: "")
            obj.put("campaignName", cursor.getString(15) ?: "")
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun getMessageEvents(jobId: Long): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val cursor = db.rawQuery(
            "SELECT id, event_type, event_timestamp, details FROM $TABLE_MESSAGE_EVENTS WHERE message_job_id = ? ORDER BY event_timestamp ASC",
            arrayOf(jobId.toString())
        )
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("eventType", cursor.getString(1))
            obj.put("timestamp", cursor.getLong(2))
            obj.put("details", cursor.getString(3) ?: "")
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun getRecentLiveEvents(limit: Int = 20): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val cursor = db.rawQuery(
            """
            SELECT e.id, e.event_type, e.event_timestamp, e.details, j.destination_number, c.name
            FROM $TABLE_MESSAGE_EVENTS e
            LEFT JOIN $TABLE_MESSAGE_JOBS j ON e.message_job_id = j.id
            LEFT JOIN $TABLE_CAMPAIGNS c ON e.campaign_id = c.id
            ORDER BY e.event_timestamp DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("eventType", cursor.getString(1))
            obj.put("timestamp", cursor.getLong(2))
            obj.put("details", cursor.getString(3) ?: "")
            obj.put("destinationNumber", cursor.getString(4) ?: "")
            obj.put("campaignName", cursor.getString(5) ?: "")
            array.put(obj)
        }
        cursor.close()
        return array
    }

    // ==========================================
    // REPORTS & ANALYTICS
    // ==========================================
    fun getReportsData(startDate: Long, endDate: Long, campaignId: Long?): JSONObject {
        val db = readableDatabase
        val result = JSONObject()

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (startDate > 0) {
            whereClauses.add("created_at >= ?")
            args.add(startDate.toString())
        }
        if (endDate > 0) {
            whereClauses.add("created_at <= ?")
            args.add(endDate.toString())
        }
        if (campaignId != null && campaignId > 0) {
            whereClauses.add("campaign_id = ?")
            args.add(campaignId.toString())
        }

        val where = if (whereClauses.isEmpty()) "" else "WHERE " + whereClauses.joinToString(" AND ")

        // Status counts
        val statusCursor = db.rawQuery(
            "SELECT status, COUNT(*) FROM $TABLE_MESSAGE_JOBS $where GROUP BY status",
            args.toTypedArray()
        )
        var totalJobs = 0
        var submitted = 0
        var delivered = 0
        var failed = 0
        var queued = 0
        var cancelled = 0
        var unknown = 0

        while (statusCursor.moveToNext()) {
            val st = statusCursor.getString(0)
            val cnt = statusCursor.getInt(1)
            totalJobs += cnt
            when (st) {
                STATUS_SUBMITTED -> submitted += cnt
                STATUS_DELIVERED -> delivered += cnt
                STATUS_FAILED -> failed += cnt
                STATUS_QUEUED, STATUS_PROCESSING -> queued += cnt
                STATUS_CANCELLED -> cancelled += cnt
                STATUS_UNKNOWN -> unknown += cnt
            }
        }
        statusCursor.close()

        result.put("totalRecipients", totalJobs)
        result.put("submitted", submitted)
        result.put("delivered", delivered)
        result.put("failed", failed)
        result.put("queued", queued)
        result.put("cancelled", cancelled)
        result.put("unknown", unknown)

        val processed = submitted + delivered + failed
        val successRate = if (processed > 0) {
            ((submitted + delivered).toDouble() / processed.toDouble()) * 100.0
        } else 0.0
        val failureRate = if (processed > 0) {
            (failed.toDouble() / processed.toDouble()) * 100.0
        } else 0.0

        result.put("successRate", String.format(java.util.Locale.US, "%.1f", successRate))
        result.put("failureRate", String.format(java.util.Locale.US, "%.1f", failureRate))

        return result
    }

    // ==========================================
    // OPT-OUT & SUPPRESSION BLACKLIST
    // ==========================================
    fun getOptOutContacts(): JSONArray {
        val db = readableDatabase
        val array = JSONArray()
        val cursor = db.rawQuery(
            """
            SELECT id, phone_number, display_name, consent_source, updated_at
            FROM $TABLE_CONTACTS
            WHERE consent_status = $CONSENT_OPTED_OUT
            ORDER BY updated_at DESC
            """.trimIndent(),
            null
        )
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(0))
            obj.put("phoneNumber", cursor.getString(1))
            obj.put("displayName", cursor.getString(2) ?: "")
            obj.put("reason", cursor.getString(3) ?: "User Opt-Out")
            obj.put("updatedAt", cursor.getLong(4))
            array.put(obj)
        }
        cursor.close()
        return array
    }

    fun addOptOutContact(phoneNumber: String, reason: String): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var contactId: Long = -1

        val check = db.rawQuery(
            "SELECT id FROM $TABLE_CONTACTS WHERE phone_number = ?",
            arrayOf(phoneNumber)
        )
        if (check.moveToFirst()) {
            contactId = check.getLong(0)
            val cv = ContentValues().apply {
                put("consent_status", CONSENT_OPTED_OUT)
                put("consent_source", reason.ifBlank { "Suppression List" })
                put("updated_at", now)
            }
            db.update(TABLE_CONTACTS, cv, "id = ?", arrayOf(contactId.toString()))
        }
        check.close()

        if (contactId == -1L) {
            val cv = ContentValues().apply {
                put("phone_number", phoneNumber)
                put("original_phone_number", phoneNumber)
                put("display_name", "Suppressed Contact")
                put("consent_status", CONSENT_OPTED_OUT)
                put("consent_source", reason.ifBlank { "Suppression List" })
                put("consent_timestamp", now)
                put("created_at", now)
                put("updated_at", now)
            }
            contactId = db.insert(TABLE_CONTACTS, null, cv)
        }
        return contactId
    }

    fun removeOptOutContact(contactId: Long): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("consent_status", CONSENT_NONE)
            put("consent_source", "Removed from Blacklist")
            put("updated_at", System.currentTimeMillis())
        }
        return db.update(TABLE_CONTACTS, cv, "id = ?", arrayOf(contactId.toString())) > 0
    }

    // ==========================================
    // SMS BLAST TXT REPORT GENERATOR
    // ==========================================
    fun generateTxtReport(campaignId: Long): String {
        val db = readableDatabase
        val cDetails = getCampaignDetails(campaignId)

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val nowStr = sdf.format(java.util.Date())

        val stats = cDetails?.optJSONObject("stats")
        val submitted = stats?.optInt(STATUS_SUBMITTED, 0) ?: 0
        val delivered = stats?.optInt(STATUS_DELIVERED, 0) ?: 0
        val failed = stats?.optInt(STATUS_FAILED, 0) ?: 0
        val total = cDetails?.optInt("totalRecipients", 0) ?: 0

        val successful = submitted + delivered

        val sb = StringBuilder()
        sb.append("SMS BLAST REPORT ").append(nowStr).append("\n")
        sb.append("ACR SERVICES\n")
        sb.append("SUCCESSFUL: ").append(successful).append("\n")
        sb.append("UNSUCCESSFUL: ").append(failed).append("\n")
        sb.append("TOTAL OVERALL: ").append(total).append("\n\n")
        sb.append("INDIVIDUAL NUMBER REPORTS:\n\n")

        val cursor = db.rawQuery(
            """
            SELECT destination_number, status, submitted_at, delivered_at, failed_at, error_message
            FROM $TABLE_MESSAGE_JOBS
            WHERE campaign_id = ?
            ORDER BY queue_position ASC
            """.trimIndent(),
            arrayOf(campaignId.toString())
        )

        while (cursor.moveToNext()) {
            val rawNumber = cursor.getString(0) ?: ""
            val status = cursor.getString(1) ?: STATUS_QUEUED
            val subAt = cursor.getLong(2)
            val delAt = cursor.getLong(3)
            val failAt = cursor.getLong(4)
            val errMsg = cursor.getString(5) ?: ""

            val formattedNumber = formatPhoneNumberDisplay(rawNumber)

            val ts = when {
                delAt > 0 -> delAt
                subAt > 0 -> subAt
                failAt > 0 -> failAt
                else -> System.currentTimeMillis()
            }
            val dateStr = sdf.format(java.util.Date(ts))

            val statusIndicator = when (status) {
                STATUS_DELIVERED, STATUS_SUBMITTED -> "Status: ✅"
                STATUS_FAILED -> "Status: ❌"
                else -> "Status: ⏳"
            }

            sb.append(formattedNumber).append(" - ").append(dateStr).append(" - ").append(statusIndicator).append("\n")
        }
        cursor.close()

        return sb.toString()
    }

    private fun formatPhoneNumberDisplay(number: String): String {
        val clean = number.trim()
        return if (clean.length == 11 && clean.startsWith("09")) {
            clean.substring(0, 4) + " " + clean.substring(4, 7) + " " + clean.substring(7)
        } else if (clean.length == 10) {
            clean.substring(0, 3) + " " + clean.substring(3, 6) + " " + clean.substring(6)
        } else if (clean.length == 12 && clean.startsWith("+")) {
            clean.substring(0, 3) + " " + clean.substring(3, 6) + " " + clean.substring(6, 9) + " " + clean.substring(9)
        } else {
            clean
        }
    }

    fun clearLocalData(clearType: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            when (clearType) {
                "all" -> {
                    db.delete(TABLE_MESSAGE_EVENTS, null, null)
                    db.delete(TABLE_APP_EVENTS, null, null)
                    db.delete(TABLE_MESSAGE_JOBS, null, null)
                    db.delete(TABLE_CAMPAIGNS, null, null)
                    db.delete(TABLE_RECIPIENT_LIST_MEMBERS, null, null)
                    db.delete(TABLE_RECIPIENT_LISTS, null, null)
                    db.delete(TABLE_CONTACTS, null, null)
                }
                "history" -> {
                    db.delete(TABLE_MESSAGE_EVENTS, null, null)
                    db.delete(TABLE_APP_EVENTS, null, null)
                    db.delete(TABLE_MESSAGE_JOBS, null, null)
                    db.delete(TABLE_CAMPAIGNS, null, null)
                }
                "contacts" -> {
                    db.delete(TABLE_RECIPIENT_LIST_MEMBERS, null, null)
                    db.delete(TABLE_RECIPIENT_LISTS, null, null)
                    db.delete(TABLE_CONTACTS, null, null)
                }
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }
}
