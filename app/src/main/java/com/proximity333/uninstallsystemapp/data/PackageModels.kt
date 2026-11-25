package com.proximity333.uninstallsystemapp.data

/** The user's choice of how to authorize package operations. */
enum class PrivilegeBackend { SHIZUKU, ROOT }

/** The availability of the selected package operation backend. */
enum class PrivilegeStatus {
    MISSING, STOPPED, PERMISSION_REQUIRED, CONNECTING, READY, ERROR
}

/**
 * The backend selected for package operations.
 *
 * @property backend The user's selected authorization method.
 * @property status The current availability of that method.
 */
data class PrivilegeConnection(val backend: PrivilegeBackend, val status: PrivilegeStatus)

/** A user or profile visible to Android's package manager. */
data class AndroidUser(val id: Int, val name: String, val serialNumber: Long? = null)

/** The package manager's observed state for one Android user. */
enum class PackageState {
    INSTALLED,
    DISABLED,
    RESTORABLE,
    NOT_FOUND,
    UNKNOWN,
}

/** Explains why the package manager could not confirm a scan result. */
enum class PackageScanIssue {
    QUERY_FAILED, PERMISSION_DENIED, UNRECOGNIZED_RESPONSE, USER_STATE_MISSING
}

/** The supported per-user mutations. */
enum class PackageAction { UNINSTALL, DISABLE, RESTORE }

/** Whether this observed package state is a valid starting point for the action. */
fun PackageState?.permits(action: PackageAction): Boolean = when (action) {
    PackageAction.UNINSTALL -> this == PackageState.INSTALLED || this == PackageState.DISABLED
    PackageAction.DISABLE -> this == PackageState.INSTALLED
    PackageAction.RESTORE -> this == PackageState.RESTORABLE
}

/** A single exact package and Android user in a confirmed operation. */
data class ChangeTarget(
    val packageName: String,
    val userId: Int,
    val userSerialNumber: Long? = null,
)

/** Structured reason why a requested package change failed or could not be confirmed. */
sealed interface ChangeFailure {
    data object NotManaged : ChangeFailure
    data object ProtectedSystemPackage : ChangeFailure
    data object ShizukuDisconnected : ChangeFailure
    data object SpaceOperationDenied : ChangeFailure
    data object StateUnverified : ChangeFailure
    data object IdentityUnverified : ChangeFailure
    data object SystemIdentityNotVerified : ChangeFailure
    data object SpaceStateUnverified : ChangeFailure
    data object DisabledBySystem : ChangeFailure
    data object NotEnabledInSpace : ChangeFailure
    data object NoRestorableCopy : ChangeFailure
    data object PostChangeStateUnverified : ChangeFailure
    data object OperationDenied : ChangeFailure
    data object StateUnchanged : ChangeFailure
    data class SystemError(val detail: String) : ChangeFailure
}

/** The verified result of attempting one mutation. */
data class ChangeOutcome(
    val target: ChangeTarget,
    val success: Boolean,
    val failure: ChangeFailure? = null,
    val denialLog: String? = null,
    val disabledInstead: Boolean = false,
)

/** The active package and its state in each visible user. */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long?,
    val systemPath: String?,
    val isSystem: Boolean,
    val isUpdatedSystemApp: Boolean,
    val hasVerifiedSystemIdentity: Boolean,
    val users: Map<Int, PackageState>,
    val userDisabledIds: Set<Int> = emptySet(),
    val scanIssues: Map<Int, PackageScanIssue> = emptyMap(),
) {
    fun stateFor(userId: Int): PackageState? = users[userId]

    fun permits(action: PackageAction, userId: Int): Boolean {
        val state = users[userId]
        return when (action) {
            PackageAction.UNINSTALL -> state.permits(action)
            PackageAction.DISABLE -> state.permits(action)
            PackageAction.RESTORE -> state.permits(action) || (state == PackageState.DISABLED && userId in userDisabledIds)
        }
    }

    /** Whether this package still exists in any actionable state on the device. */
    fun hasAnyActionableState(): Boolean = users.values.any {
        it == PackageState.INSTALLED || it == PackageState.DISABLED || it == PackageState.RESTORABLE
    }
}

/** A fresh scan scoped to the calling application's user and any visible profiles. */
data class ScanSnapshot(
    val currentUserId: Int,
    val users: List<AndroidUser>,
    val packages: List<InstalledPackage>,
)

/** The list filter used by the home screen. */
enum class PackageFilter { SYSTEM, ALL, USER }