package com.example.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "bulk_sms_preferences"

        const val KEY_SENDING_INTERVAL_MS = "sending_interval_ms"
        const val KEY_SELECTED_SIM_SUB_ID = "selected_sim_sub_id"
        const val KEY_DEFAULT_TEMPLATE_ID = "default_template_id"
        const val KEY_MAX_CAMPAIGN_SIZE = "max_campaign_size"
        const val KEY_SHOW_COUNTDOWN = "show_countdown"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_THEME_PREFERENCE = "theme_preference"
        const val KEY_DEFAULT_PREFIX = "default_prefix"
        const val KEY_IMPORT_BEHAVIOR = "import_behavior"
        const val KEY_REQUIRE_CONFIRMATION = "require_confirmation"
        const val KEY_MASK_NUMBERS_BY_DEFAULT = "mask_numbers_by_default"
        const val KEY_REPORT_EMAIL = "report_email"
        const val KEY_AUTO_EMAIL_REPORT = "auto_email_report"
        const val KEY_COMPATIBILITY_MODE = "compatibility_mode"
        const val KEY_SIMULATION_MODE = "simulation_mode"

        // Safe Defaults
        const val DEFAULT_INTERVAL_MS = 5000L
        const val DEFAULT_SIM_SUB_ID = -1
        const val DEFAULT_TEMPLATE_ID = 1L
        const val DEFAULT_MAX_CAMPAIGN_SIZE = 500
        const val DEFAULT_SHOW_COUNTDOWN = true
        const val DEFAULT_NOTIFICATIONS_ENABLED = true
        const val DEFAULT_THEME = "system"
        const val DEFAULT_PREFIX = "0992438"
        const val DEFAULT_IMPORT_BEHAVIOR = "skip_duplicates"
        const val DEFAULT_REQUIRE_CONFIRMATION = true
        const val DEFAULT_MASK_NUMBERS = true
        const val DEFAULT_REPORT_EMAIL = ""
        const val DEFAULT_AUTO_EMAIL_REPORT = false
        const val DEFAULT_COMPATIBILITY_MODE = true
        const val DEFAULT_SIMULATION_MODE = false
    }

    var compatibilityMode: Boolean
        get() = prefs.getBoolean(KEY_COMPATIBILITY_MODE, DEFAULT_COMPATIBILITY_MODE)
        set(value) = prefs.edit().putBoolean(KEY_COMPATIBILITY_MODE, value).apply()

    var simulationMode: Boolean
        get() = prefs.getBoolean(KEY_SIMULATION_MODE, DEFAULT_SIMULATION_MODE)
        set(value) = prefs.edit().putBoolean(KEY_SIMULATION_MODE, value).apply()

    var reportEmail: String
        get() = prefs.getString(KEY_REPORT_EMAIL, DEFAULT_REPORT_EMAIL) ?: DEFAULT_REPORT_EMAIL
        set(value) = prefs.edit().putString(KEY_REPORT_EMAIL, value.trim()).apply()

    var autoEmailReport: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EMAIL_REPORT, DEFAULT_AUTO_EMAIL_REPORT)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_EMAIL_REPORT, value).apply()

    var sendingIntervalMs: Long
        get() = prefs.getLong(KEY_SENDING_INTERVAL_MS, DEFAULT_INTERVAL_MS).coerceIn(1000L, 60000L)
        set(value) = prefs.edit().putLong(KEY_SENDING_INTERVAL_MS, value.coerceIn(1000L, 60000L)).apply()

    var selectedSimSubId: Int
        get() = prefs.getInt(KEY_SELECTED_SIM_SUB_ID, DEFAULT_SIM_SUB_ID)
        set(value) = prefs.edit().putInt(KEY_SELECTED_SIM_SUB_ID, value).apply()

    var defaultTemplateId: Long
        get() = prefs.getLong(KEY_DEFAULT_TEMPLATE_ID, DEFAULT_TEMPLATE_ID)
        set(value) = prefs.edit().putLong(KEY_DEFAULT_TEMPLATE_ID, value).apply()

    var maxCampaignSize: Int
        get() = prefs.getInt(KEY_MAX_CAMPAIGN_SIZE, DEFAULT_MAX_CAMPAIGN_SIZE).coerceIn(1, 5000)
        set(value) = prefs.edit().putInt(KEY_MAX_CAMPAIGN_SIZE, value.coerceIn(1, 5000)).apply()

    var showCountdown: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COUNTDOWN, DEFAULT_SHOW_COUNTDOWN)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COUNTDOWN, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, DEFAULT_NOTIFICATIONS_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var themePreference: String
        get() = prefs.getString(KEY_THEME_PREFERENCE, DEFAULT_THEME) ?: DEFAULT_THEME
        set(value) = prefs.edit().putString(KEY_THEME_PREFERENCE, value).apply()

    var defaultPrefix: String
        get() = prefs.getString(KEY_DEFAULT_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        set(value) = prefs.edit().putString(KEY_DEFAULT_PREFIX, value).apply()

    var importBehavior: String
        get() = prefs.getString(KEY_IMPORT_BEHAVIOR, DEFAULT_IMPORT_BEHAVIOR) ?: DEFAULT_IMPORT_BEHAVIOR
        set(value) = prefs.edit().putString(KEY_IMPORT_BEHAVIOR, value).apply()

    var requireConfirmation: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_CONFIRMATION, DEFAULT_REQUIRE_CONFIRMATION)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_CONFIRMATION, value).apply()

    var maskNumbersByDefault: Boolean
        get() = prefs.getBoolean(KEY_MASK_NUMBERS_BY_DEFAULT, DEFAULT_MASK_NUMBERS)
        set(value) = prefs.edit().putBoolean(KEY_MASK_NUMBERS_BY_DEFAULT, value).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(KEY_SENDING_INTERVAL_MS, sendingIntervalMs)
            put(KEY_SELECTED_SIM_SUB_ID, selectedSimSubId)
            put(KEY_DEFAULT_TEMPLATE_ID, defaultTemplateId)
            put(KEY_MAX_CAMPAIGN_SIZE, maxCampaignSize)
            put(KEY_SHOW_COUNTDOWN, showCountdown)
            put(KEY_NOTIFICATIONS_ENABLED, notificationsEnabled)
            put(KEY_THEME_PREFERENCE, themePreference)
            put(KEY_DEFAULT_PREFIX, defaultPrefix)
            put(KEY_IMPORT_BEHAVIOR, importBehavior)
            put(KEY_REQUIRE_CONFIRMATION, requireConfirmation)
            put(KEY_MASK_NUMBERS_BY_DEFAULT, maskNumbersByDefault)
            put(KEY_REPORT_EMAIL, reportEmail)
            put(KEY_AUTO_EMAIL_REPORT, autoEmailReport)
            put(KEY_COMPATIBILITY_MODE, compatibilityMode)
            put(KEY_SIMULATION_MODE, simulationMode)
        }
    }

    fun updateFromJson(json: JSONObject) {
        val editor = prefs.edit()
        if (json.has(KEY_SENDING_INTERVAL_MS)) {
            val interval = json.getLong(KEY_SENDING_INTERVAL_MS).coerceIn(1000L, 60000L)
            editor.putLong(KEY_SENDING_INTERVAL_MS, interval)
        }
        if (json.has(KEY_SELECTED_SIM_SUB_ID)) {
            editor.putInt(KEY_SELECTED_SIM_SUB_ID, json.getInt(KEY_SELECTED_SIM_SUB_ID))
        }
        if (json.has(KEY_DEFAULT_TEMPLATE_ID)) {
            editor.putLong(KEY_DEFAULT_TEMPLATE_ID, json.getLong(KEY_DEFAULT_TEMPLATE_ID))
        }
        if (json.has(KEY_MAX_CAMPAIGN_SIZE)) {
            val max = json.getInt(KEY_MAX_CAMPAIGN_SIZE).coerceIn(1, 5000)
            editor.putInt(KEY_MAX_CAMPAIGN_SIZE, max)
        }
        if (json.has(KEY_SHOW_COUNTDOWN)) {
            editor.putBoolean(KEY_SHOW_COUNTDOWN, json.getBoolean(KEY_SHOW_COUNTDOWN))
        }
        if (json.has(KEY_NOTIFICATIONS_ENABLED)) {
            editor.putBoolean(KEY_NOTIFICATIONS_ENABLED, json.getBoolean(KEY_NOTIFICATIONS_ENABLED))
        }
        if (json.has(KEY_THEME_PREFERENCE)) {
            editor.putString(KEY_THEME_PREFERENCE, json.getString(KEY_THEME_PREFERENCE))
        }
        if (json.has(KEY_DEFAULT_PREFIX)) {
            editor.putString(KEY_DEFAULT_PREFIX, json.getString(KEY_DEFAULT_PREFIX))
        }
        if (json.has(KEY_IMPORT_BEHAVIOR)) {
            editor.putString(KEY_IMPORT_BEHAVIOR, json.getString(KEY_IMPORT_BEHAVIOR))
        }
        if (json.has(KEY_REQUIRE_CONFIRMATION)) {
            editor.putBoolean(KEY_REQUIRE_CONFIRMATION, json.getBoolean(KEY_REQUIRE_CONFIRMATION))
        }
        if (json.has(KEY_MASK_NUMBERS_BY_DEFAULT)) {
            editor.putBoolean(KEY_MASK_NUMBERS_BY_DEFAULT, json.getBoolean(KEY_MASK_NUMBERS_BY_DEFAULT))
        }
        if (json.has(KEY_REPORT_EMAIL)) {
            editor.putString(KEY_REPORT_EMAIL, json.getString(KEY_REPORT_EMAIL).trim())
        }
        if (json.has(KEY_AUTO_EMAIL_REPORT)) {
            editor.putBoolean(KEY_AUTO_EMAIL_REPORT, json.getBoolean(KEY_AUTO_EMAIL_REPORT))
        }
        if (json.has(KEY_COMPATIBILITY_MODE)) {
            editor.putBoolean(KEY_COMPATIBILITY_MODE, json.getBoolean(KEY_COMPATIBILITY_MODE))
        }
        if (json.has(KEY_SIMULATION_MODE)) {
            editor.putBoolean(KEY_SIMULATION_MODE, json.getBoolean(KEY_SIMULATION_MODE))
        }
        editor.apply()
    }
}
