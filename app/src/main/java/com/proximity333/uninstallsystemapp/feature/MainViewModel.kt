package com.proximity333.uninstallsystemapp.feature

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.proximity333.uninstallsystemapp.UninstallerApplication
import com.proximity333.uninstallsystemapp.data.AndroidUser
import com.proximity333.uninstallsystemapp.data.ChangeFailure
import com.proximity333.uninstallsystemapp.data.ChangeOutcome
import com.proximity333.uninstallsystemapp.data.ChangeTarget
import com.proximity333.uninstallsystemapp.data.InstalledPackage
import com.proximity333.uninstallsystemapp.data.PackageAction
import com.proximity333.uninstallsystemapp.data.PackageFilter
import com.proximity333.uninstallsystemapp.data.PackageScanIssue
import com.proximity333.uninstallsystemapp.data.PrivilegeBackend
import com.proximity333.uninstallsystemapp.data.PrivilegeConnection
import com.proximity333.uninstallsystemapp.data.PrivilegeStatus
import com.proximity333.uninstallsystemapp.data.packagemanager.PackageScanException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the home screen renders. */
data class UiState(
    val connection: PrivilegeConnection = PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.CONNECTING),
    val packages: List<InstalledPackage> = emptyList(),
    val users: List<AndroidUser> = emptyList(),
    val currentUserId: Int? = null,
    val selectedUserId: Int? = null,
    val scanning: Boolean = false,
    val hasScanned: Boolean = false,
    val scanError: String? = null,
    val query: String = "",
    val filter: PackageFilter = PackageFilter.SYSTEM,
    val busyPackage: String? = null,
)

/** Runs scans, privilege setup and confirmed package changes off the main thread. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as UninstallerApplication).repository
    private val settings = (application as UninstallerApplication).settings

    private val mutableState = MutableStateFlow(
        UiState(connection = repository.connection.value, selectedUserId = settings.selectedUserId),
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    /** Cancels superseded scans so only the newest result reaches the screen. */
    private var scanJob: Job? = null

    /** Whether the current backend already produced a scan. */
    private var scannedForBackend: PrivilegeBackend? = null

    init {
        viewModelScope.launch {
            repository.connection.collect { connection ->
                val previous = mutableState.value.connection
                mutableState.update { it.copy(connection = connection) }
                val becameReady = connection.status == PrivilegeStatus.READY && previous.status != PrivilegeStatus.READY
                val backendChanged = connection.status == PrivilegeStatus.READY &&
                    scannedForBackend != connection.backend
                if (becameReady || backendChanged) {
                    scannedForBackend = connection.backend
                    scan()
                }
            }
        }
    }

    /** Saves the user's backend choice and opens the matching authorization flow. */
    fun selectBackend(backend: PrivilegeBackend) {
        if (backend == mutableState.value.connection.backend) return
        viewModelScope.launch {
            try {
                repository.selectBackend(backend)
            } catch (_: Exception) {
                messageChannel.send("切换权限方式失败")
            }
        }
    }

    /** Requests Shizuku permission or opens the Root session. */
    fun requestAccess() {
        viewModelScope.launch {
            try {
                repository.requestPermission()
                repository.refreshConnection()
            } catch (_: Exception) {
                messageChannel.send("无法启动授权流程")
            }
        }
    }

    /** Rechecks the current backend without requesting new authorization. */
    fun refreshConnection() {
        repository.refreshConnection()
    }

    /** Scans on demand, reporting package-service failures as a retryable banner. */
    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch { scanOnce() }
    }

    fun setQuery(value: String) {
        mutableState.update { it.copy(query = value) }
    }

    fun setFilter(value: PackageFilter) {
        mutableState.update { it.copy(filter = value) }
    }

    fun selectUser(userId: Int) {
        settings.selectedUserId = userId
        mutableState.update { it.copy(selectedUserId = userId) }
    }

    /** Submits one confirmed action for the selected space, then refreshes its state. */
    fun perform(packageInfo: InstalledPackage, action: PackageAction) {
        val current = mutableState.value
        val userId = current.selectedUserId ?: return
        val user = current.users.firstOrNull { it.id == userId }
        val target = ChangeTarget(packageInfo.packageName, userId, user?.serialNumber)
        viewModelScope.launch {
            mutableState.update { it.copy(busyPackage = packageInfo.packageName) }
            val outcome = repository.change(target, action)
            mutableState.update { it.copy(busyPackage = null) }
            messageChannel.send(outcomeMessage(packageInfo.label, action, outcome))
            scanOnce(showProgress = false)
        }
    }

    /** Called after a successful authorization request to dismiss stale errors. */
    fun clearScanError() {
        mutableState.update { it.copy(scanError = null) }
    }

    private suspend fun scanOnce(showProgress: Boolean = true) {
        if (mutableState.value.connection.status != PrivilegeStatus.READY) {
            mutableState.update {
                it.copy(
                    scanning = false,
                    scanError = "请先连接 Shizuku 或 Root 权限",
                    hasScanned = true,
                )
            }
            return
        }
        if (showProgress) mutableState.update { it.copy(scanning = true, scanError = null) }
        try {
            val snapshot = repository.scan()
            val savedUser = settings.selectedUserId
            val selected = savedUser
                ?.takeIf { id -> snapshot.users.any { it.id == id } }
                ?: snapshot.currentUserId
            mutableState.update {
                it.copy(
                    scanning = false,
                    hasScanned = true,
                    scanError = null,
                    packages = snapshot.packages,
                    users = snapshot.users,
                    currentUserId = snapshot.currentUserId,
                    selectedUserId = selected,
                )
            }
        } catch (failure: PackageScanException) {
            mutableState.update {
                it.copy(scanning = false, hasScanned = true, scanError = scanErrorMessage(failure.issue))
            }
        } catch (_: Exception) {
            mutableState.update {
                it.copy(scanning = false, hasScanned = true, scanError = "扫描失败，请刷新后重试")
            }
        }
    }

    private fun scanErrorMessage(issue: PackageScanIssue): String = when (issue) {
        PackageScanIssue.QUERY_FAILED -> "读取包信息失败，请重试"
        PackageScanIssue.PERMISSION_DENIED -> "权限不足，请检查 Shizuku 授权"
        PackageScanIssue.UNRECOGNIZED_RESPONSE -> "系统返回了无法识别的包信息"
        PackageScanIssue.USER_STATE_MISSING -> "无法确认当前空间，请重试"
    }

    private fun outcomeMessage(label: String, action: PackageAction, outcome: ChangeOutcome): String {
        if (outcome.disabledInstead) return "$label 无法卸载，已改为停用"
        if (outcome.success) {
            return when (action) {
                PackageAction.UNINSTALL -> "$label 已卸载"
                PackageAction.DISABLE -> "$label 已停用"
                PackageAction.RESTORE -> "$label 已恢复"
            }
        }
        return "$label 操作失败：${failureText(outcome.failure)}"
    }

    private fun failureText(failure: ChangeFailure?): String = when (failure) {
        is ChangeFailure.SystemError -> "系统拒绝（${failure.detail}）"
        ChangeFailure.ProtectedSystemPackage -> "受保护的系统包，已阻止操作"
        ChangeFailure.ShizukuDisconnected -> "Shizuku 连接已断开"
        ChangeFailure.SpaceOperationDenied -> "当前空间拒绝该操作"
        ChangeFailure.IdentityUnverified -> "无法确认应用身份"
        ChangeFailure.SpaceStateUnverified -> "无法确认空间状态，请刷新后重试"
        ChangeFailure.DisabledBySystem -> "该应用由系统停用，无法直接启用"
        ChangeFailure.NotEnabledInSpace -> "当前空间未启用该应用"
        ChangeFailure.NoRestorableCopy -> "设备上没有可恢复的 APK 副本"
        ChangeFailure.PostChangeStateUnverified -> "操作后无法确认应用状态"
        ChangeFailure.OperationDenied -> "系统拒绝该操作"
        ChangeFailure.StateUnchanged -> "命令已执行，但状态没有变化"
        ChangeFailure.StateUnverified -> "无法确认操作结果，请刷新后重试"
        ChangeFailure.SystemIdentityNotVerified -> "未通过系统应用身份校验"
        ChangeFailure.NotManaged -> "该应用不在管理范围内"
        null -> "未知错误"
    }
}