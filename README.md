# Bulk SMS Campaign Manager (Android)

**Bulk SMS Campaign Manager** is an installable, carrier-SIM-based Android application designed for private deployment on an authorized Android device. It uses the device's physical or eSIM card to transmit bulk SMS messages directly through the cellular carrier network without requiring any remote cloud databases, third-party messaging gateways, or paid APIs.

All contacts, recipient lists, campaign logs, and delivery telemetry remain **strictly on the local device** inside an encrypted/sandboxed SQLite database.

---

## 📱 Architecture & Technology Stack

| Layer | Implementation | Details |
|---|---|---|
| **Frontend** | HTML5, CSS3, JavaScript | Modern Material 3 responsive interface, real-time live timer, charts, CSV/TXT/XLSX parser |
| **Android Host** | Kotlin & Android WebView | Native container with bidirectional `@JavascriptInterface` bridge (`AndroidBridge` & `BulkSmsBridge`) |
| **Telephony API** | `android.telephony.SmsManager` | Direct carrier messaging, multipart SMS handling, `SubscriptionManager` for multi-SIM support |
| **Callbacks** | `BroadcastReceiver` | Native pending intents for `SMS_SENT` and `SMS_DELIVERED` callbacks with correlation IDs |
| **Queue & Service** | `ForegroundService` | Android background service with `WakeLock`, persistent notification, and pause/resume/stop controls |
| **Persistence** | SQLite (`SQLiteOpenHelper`) | Normalized tables: `contacts`, `recipient_lists`, `campaigns`, `message_jobs`, `events` |
| **Configuration** | `SharedPreferences` | Default interval (5s), SIM slot selection, campaign safety limits, theme preferences |
| **Notifications** | Android Notification Manager | Milestone alerts: Campaign start, pause, resume, completion, and repeated failure notices |

---

## 🔑 Permissions & Security

The app requests only permissions necessary for cellular messaging:

- `android.permission.SEND_SMS`: Required to submit SMS messages via the carrier SIM.
- `android.permission.READ_PHONE_STATE`: Required to read available SIM subscriptions (SIM 1 / SIM 2) and carrier network status.
- `android.permission.POST_NOTIFICATIONS`: Android 13+ permission for background queue progress and campaign milestone alerts.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`: Required to maintain active queue processing when the app is in the background.
- `android.permission.WAKE_LOCK`: Prevents CPU sleep during message interval transitions.

> **Privacy Note:** The app contains zero trackers, analytics SDKs, or cloud synchronization. Data never leaves your device unless you explicitly export CSV reports.

---

## 🚀 Main Navigation & Screens

1. **Dashboard:**
   - Real-time statistics: Total campaigns, total contacts, messages queued, submitted, confirmed delivered, and failed.
   - Device SIM card slot status and carrier detection.
   - Quick launch cards for all major workflows.

2. **Number Generator:**
   - Sequential series generator (e.g. prefix `0992438`, start `0000`, end `0100`).
   - Strict start-inclusive, end-exclusive convention yielding exactly 100 numbers (`0000` to `0099`).
   - Zero-padding preservation (`%04d`).
   - Duplicate detection and export to CSV/TXT.
   - Explicit unverified consent disclaimer labeling.

3. **Contact Import:**
   - Storage Access Framework file chooser supporting CSV, TXT (1 number per line), and Excel XLSX spreadsheets.
   - Dynamic column mapping: Phone Number, Recipient Name, Group, Consent Status.
   - Validation & audit panel: Valid count, duplicate detection, invalid phone number format exclusion.
   - Saves to local SQLite database without sending messages.

4. **Campaign Composer:**
   - Recipient list selector and SIM card picker.
   - Personalized message content with `{name}` placeholders.
   - Real-time character counter and SMS segment estimator (GSM-7 160 vs UCS-2 70).
   - Live handset bubble preview.
   - Template library (save/load/delete).
   - Conservative sending interval slider (1,000ms – 30,000ms, default: 5,000ms).
   - Mandatory explicit confirmation modal prior to broadcast.

5. **Active Campaign Monitor:**
   - Live countdown timer synced with the native background queue.
   - Progress bar with estimated time remaining.
   - Masked recipient phone numbers for privacy.
   - Real-time status counters: Submitted, Confirmed Delivered, Failed.
   - Controls: **Pause**, **Resume**, **Stop Campaign**.
   - Live device telemetry log stream displaying carrier callback timestamps.

6. **Campaign History:**
   - Historical records of past and active broadcasts with status badges.
   - Inspect individual message jobs and correlation IDs.

7. **Reports & Analytics:**
   - Date range, campaign, and message status filters.
   - Delivery success rate % and failure rate % calculations.
   - Detailed message audit table with submission and delivery timestamps.
   - Single-click CSV export for audit records.

8. **Settings:**
   - Configure default sending intervals, maximum campaign limits, default number prefixes.
   - Theme toggle (System, Dark, Light).
   - Reset to Safe Defaults.
   - Data purging options for privacy.

---

## 🛠️ Build and Testing Instructions

### Compiling the App
To compile the Android app and verify that there are no syntax or build errors:
```bash
gradle assembleDebug
```

### Running Unit & Robolectric Tests
Unit and Robolectric tests verify the Number Generator, leading-zero formatting, placeholder replacement, SharedPreferences persistence, and SQLite transactions:
```bash
gradle :app:testDebugUnitTest
```

---

## 📋 Carrier & Android Restrictions Notice
- Standard carrier messaging rates and plan SMS allowances apply.
- Carrier anti-abuse filters and Android system SMS rate limiters may apply if sending too quickly; keeping the interval at 5.0 seconds or higher is recommended.
- Handset delivery confirmation relies on the recipient network returning a delivery receipt (`SMS_DELIVERED`).
