package com.proximity333.uninstallsystemapp

import android.app.Application
import com.proximity333.uninstallsystemapp.data.SettingsStore
import com.proximity333.uninstallsystemapp.data.SystemRepository
import com.proximity333.uninstallsystemapp.data.packagemanager.PrivilegeSessionManager

/** Owns the process-wide privilege session so it survives configuration changes. */
class UninstallerApplication : Application() {
    lateinit var settings: SettingsStore
        private set

    internal lateinit var repository: SystemRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = SystemRepository(this, settings, PrivilegeSessionManager(this, settings))
    }
}