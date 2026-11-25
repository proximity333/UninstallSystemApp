package com.proximity333.uninstallsystemapp.data

import android.content.Context
import androidx.core.content.edit

/** Persists the selected privilege backend and the last chosen Android space. */
class SettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("uninstaller_settings", Context.MODE_PRIVATE)

    /** The authorization method used for package operations. */
    var backend: PrivilegeBackend
        get() = runCatching {
            PrivilegeBackend.valueOf(
                preferences.getString(KEY_BACKEND, PrivilegeBackend.SHIZUKU.name)
                    ?: PrivilegeBackend.SHIZUKU.name,
            )
        }.getOrDefault(PrivilegeBackend.SHIZUKU)
        set(value) = preferences.edit { putString(KEY_BACKEND, value.name) }

    /** The last selected Android user, or null to follow the current user. */
    var selectedUserId: Int?
        get() = preferences.getInt(KEY_SELECTED_USER, NO_USER).takeIf { it >= 0 }
        set(value) = preferences.edit {
            if (value == null) remove(KEY_SELECTED_USER) else putInt(KEY_SELECTED_USER, value)
        }

    private companion object {
        const val KEY_BACKEND = "privilege_backend"
        const val KEY_SELECTED_USER = "selected_user"
        const val NO_USER = -1
    }
}