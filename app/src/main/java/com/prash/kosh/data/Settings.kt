package com.prash.kosh.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Small, non-secret preferences. Secrets live in [com.prash.kosh.security.KeyManager]. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("kosh_settings", Context.MODE_PRIVATE)

    var appLockEnabled: Boolean
        get() = p.getBoolean("app_lock", true)
        set(v) = p.edit { putBoolean("app_lock", v) }

    var backupUri: Uri?
        get() = p.getString("backup_uri", null)?.let(Uri::parse)
        set(v) = p.edit { putString("backup_uri", v?.toString()) }

    var lastBackupAt: Long
        get() = p.getLong("last_backup", 0)
        set(v) = p.edit { putLong("last_backup", v) }

    var lastBackupError: String?
        get() = p.getString("last_backup_error", null)
        set(v) = p.edit { putString("last_backup_error", v) }

    /** SYSTEM (follow the phone), LIGHT or DARK. */
    var themeMode: String
        get() = p.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(v) = p.edit { putString("theme_mode", v) }

    /** Name used in the greeting ("Good morning, Prash"). */
    var userName: String
        get() = p.getString("user_name", "") ?: ""
        set(v) = p.edit { putString("user_name", v.trim()) }

    /** Count food delivery (Swiggy, Zomato) in the discretionary burn rate as well as quick commerce. */
    var burnIncludesFoodDelivery: Boolean
        get() = p.getBoolean("burn_food", false)
        set(v) = p.edit { putBoolean("burn_food", v) }

    /** Due dates already reminded about, so each reminder is sent once. */
    fun remindedOnce(key: String): Boolean {
        val seen = p.getStringSet("reminded", emptySet()) ?: emptySet()
        if (key in seen) return true
        p.edit { putStringSet("reminded", (seen + key).toList().takeLast(200).toSet()) }
        return false
    }

    /** Confidence (percent) at or above which a capture is saved without asking. */
    var autoSaveThreshold: Int
        get() = p.getInt("auto_threshold", 95)
        set(v) = p.edit { putInt("auto_threshold", v.coerceIn(50, 100)) }

    /** Confidence below which a capture needs full review (between the two: quick Check). */
    var reviewThreshold: Int
        get() = p.getInt("review_threshold", 80)
        set(v) = p.edit { putInt("review_threshold", v.coerceIn(0, 100)) }

    /** Months to keep the text of imported SMS; 0 = forever. Transactions are never affected. */
    var smsRetentionMonths: Int
        get() = p.getInt("sms_retention", 12)
        set(v) = p.edit { putInt("sms_retention", v) }

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit { putBoolean("onboarded", v) }
}
