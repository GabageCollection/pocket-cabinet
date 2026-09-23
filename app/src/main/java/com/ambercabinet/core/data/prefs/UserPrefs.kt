package com.ambercabinet.core.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("amber_prefs", Context.MODE_PRIVATE)

    var lastBackupAt: Long
        get() = sp.getLong("last_backup_at", 0L)
        set(value) { sp.edit().putLong("last_backup_at", value).apply() }

    var flavorFilterTouched: Boolean
        get() = sp.getBoolean("flavor_filter_touched", false)
        set(value) { sp.edit().putBoolean("flavor_filter_touched", value).apply() }
}
