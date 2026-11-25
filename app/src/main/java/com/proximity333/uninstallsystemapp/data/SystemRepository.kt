package com.proximity333.uninstallsystemapp.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.MainThread
import com.proximity333.uninstallsystemapp.data.packagemanager.PackageFallback
import com.proximity333.uninstallsystemapp.data.packagemanager.PackageFallbackResult
import com.proximity333.uninstallsystemapp.data.packagemanager.PrivilegeSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Coordinates package scans and confirmed mutations through the selected privilege backend. */
internal class SystemRepository(
    context: Context,
    private val settings: SettingsStore,
    private val sessionManager: PrivilegeSessionManager,
) {
    private val appContext = context.applicationContext

    /** Resolves stable Android profile identities independently of the package dump. */
    private val userManager = appContext.getSystemService(UserManager::class.java)

    /** The APK that app_process loads for the privileged uninstall fallback. */
    private val fallbackApkPath: String? by lazy {
        try {
            appContext.packageManager.getApplicationInfo(appContext.packageName, 0).sourceDir
        } catch (_: Exception) {
            // The fallback is best-effort; an unreadable path must not mask the system error.
            null
        }
    }

    /** The selected backend and its current authorization state. */
    val connection: StateFlow<PrivilegeConnection> get() = sessionManager.connection

    /** Rechecks Shizuku or the existing Root session without requesting authorization. */
    @MainThread
    fun refreshConnection() = sessionManager.refreshConnection()

    /** Saves the foreground user's choice and opens Root when selected. */
    suspend fun selectBackend(backend: PrivilegeBackend): Boolean = sessionManager.selectBackend(backend)

    /** Starts the selected authorization flow from the foreground. */
    suspend fun requestPermission() = sessionManager.requestPermission()

    /** Reads every package and its state in this app's user and other visible spaces. */
    suspend fun scan(): ScanSnapshot = sessionManager.withPackageCommands { commands, _ ->
        // Android allocates a range of 100,000 UIDs to each user, including profiles.
        val snapshot = commands.scanAll(Process.myUid() / PER_USER_RANGE)
        snapshot.copy(
            users = snapshot.users.map { user -> user.copy(serialNumber = userSerialNumber(user.id)) },
            packages = snapshot.packages.map { detected ->
                detected.copy(label = applicationLabel(detected.packageName))
            },
        )
    }

    /** Applies one confirmed manual action with the same verification rules as automatic cleanup. */
    suspend fun change(target: ChangeTarget, action: PackageAction): ChangeOutcome {
        if (action != PackageAction.RESTORE && target.packageName in CriticalSystemPackages.names) {
            return ChangeOutcome(target, false, ChangeFailure.ProtectedSystemPackage)
        }
        return try {
            sessionManager.withPackageCommands { commands, backend ->
                if (backend == PrivilegeBackend.SHIZUKU && !sessionManager.isShizukuBinderAlive()) {
                    ChangeOutcome(target, false, ChangeFailure.ShizukuDisconnected)
                } else {
                    commands.change(
                        target = target,
                        action = action,
                        disableOnUninstallFailure = action == PackageAction.UNINSTALL,
                        canSubmit = {
                            val expectedSerial = target.userSerialNumber
                            expectedSerial == null || userSerialNumber(target.userId) == expectedSerial
                        },
                        deleteThroughAppProcess = if (backend == PrivilegeBackend.SHIZUKU) {
                            ::uninstallThroughShizuku
                        } else {
                            null
                        },
                    )
                }
            }
        } catch (_: SecurityException) {
            ChangeOutcome(target, false, ChangeFailure.SpaceOperationDenied)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ChangeOutcome(target, false, ChangeFailure.StateUnverified)
        }
    }

    /** Runs the shell UID uninstall fallback through Shizuku with an exact 32-bit version constraint. */
    private fun uninstallThroughShizuku(
        packageName: String,
        userId: Int,
        expectedVersionCode: Int,
    ): PackageFallbackResult =
        fallbackApkPath?.let { PackageFallback.uninstall(it, packageName, userId, expectedVersionCode) }
            ?: PackageFallbackResult(false, "Helper APK unavailable")

    /** Resolves an optional application label for a package reported by the privileged service. */
    private fun applicationLabel(packageName: String): String {
        val info = try {
            appContext.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        return info?.loadLabel(appContext.packageManager)?.toString()?.takeIf(String::isNotBlank)
            ?: packageName
    }

    /** Returns null when the platform cannot identify this profile. */
    private fun userSerialNumber(userId: Int): Long? = try {
        val handle = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        userManager?.getSerialNumberForUser(handle)?.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}