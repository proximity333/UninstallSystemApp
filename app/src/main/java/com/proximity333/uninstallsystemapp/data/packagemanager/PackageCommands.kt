package com.proximity333.uninstallsystemapp.data.packagemanager

import android.content.pm.PackageManager
import android.os.SystemClock
import com.proximity333.uninstallsystemapp.data.AndroidUser
import com.proximity333.uninstallsystemapp.data.ChangeFailure
import com.proximity333.uninstallsystemapp.data.ChangeOutcome
import com.proximity333.uninstallsystemapp.data.ChangeTarget
import com.proximity333.uninstallsystemapp.data.InstalledPackage
import com.proximity333.uninstallsystemapp.data.PackageAction
import com.proximity333.uninstallsystemapp.data.PackageScanIssue
import com.proximity333.uninstallsystemapp.data.PackageState
import com.proximity333.uninstallsystemapp.data.ScanSnapshot
import com.proximity333.uninstallsystemapp.data.permits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The state of one active package-manager record, excluding old system versions.
 *
 * @property hasVerifiedSystemIdentity Whether the record identifies a system package with an application-range ID.
 * @property enabledSettings The raw Android enabled settings keyed by user ID.
 */
internal data class PackageRecord(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val isSystem: Boolean,
    val isUpdatedSystemApp: Boolean,
    val hasVerifiedSystemIdentity: Boolean,
    val users: Map<Int, PackageState>,
    val enabledSettings: Map<Int, Int>,
    /** Whether dump fields positively confirm or deny that an APK still exists on disk, or null if ambiguous. */
    val hasVerifiedApk: Boolean? = null,
)

/** Distinguishes confirmed absence from permission, transport, or format failures. */
internal sealed interface PackageReading {
    data class Found(val record: PackageRecord) : PackageReading
    data object Missing : PackageReading
    data class Unavailable(val issue: PackageScanIssue) : PackageReading
}

/** Indicates why a package scan could not produce a usable snapshot. */
internal class PackageScanException(val issue: PackageScanIssue) : Exception()

/**
 * Queries packages and applies changes to eligible entries through the selected connection.
 *
 * @property executor The connection used for every read and write within the operation.
 */
internal class PackageCommands(
    private val executor: PackageExecutor,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Reads every package and its state in each visible Android user. */
    suspend fun scanAll(callerUser: Int): ScanSnapshot {
        val users = listUsers()
        if (users.none { it.id == callerUser }) throw PackageScanException(PackageScanIssue.USER_STATE_MISSING)
        val packages = readAllPackages().mapNotNull { record ->
            currentCoroutineContext().ensureActive()
            val states = users.associate { user ->
                user.id to (record.users[user.id] ?: PackageState.UNKNOWN)
            }
            // A package with no APK copy and no installed space can no longer be restored or seen.
            if (states.values.none {
                    it == PackageState.INSTALLED ||
                        it == PackageState.DISABLED ||
                        it == PackageState.RESTORABLE
                }
            ) {
                return@mapNotNull null
            }
            InstalledPackage(
                packageName = record.packageName,
                label = record.packageName,
                versionName = record.versionName,
                versionCode = record.versionCode,
                systemPath = record.codePath,
                isSystem = record.isSystem,
                isUpdatedSystemApp = record.isUpdatedSystemApp,
                hasVerifiedSystemIdentity = record.hasVerifiedSystemIdentity,
                users = states,
                userDisabledIds = record.enabledSettings.filter { (id, enabled) ->
                    states[id] == PackageState.DISABLED &&
                        enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }.keys,
                scanIssues = states.filterValues { it == PackageState.UNKNOWN }
                    .mapValues { PackageScanIssue.USER_STATE_MISSING },
            )
        }
        return ScanSnapshot(callerUser, users, packages)
    }

    /** Returns the package-manager users visible to the service. */
    private fun listUsers(): List<AndroidUser> {
        val result = query({ throw PackageScanException(it) }) {
            executor.execute(listOf("list", "users"))
        }
        result.queryIssue()?.let { throw PackageScanException(it) }
        val users = result.output.lineSequence().mapNotNull { line ->
            val match = listedUser.matchEntire(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val name = match.groupValues[2].ifBlank { id.toString() }
            AndroidUser(id, name)
        }.distinctBy(AndroidUser::id).toList()
        if (users.isEmpty()) throw PackageScanException(PackageScanIssue.UNRECOGNIZED_RESPONSE)
        return users
    }

    /**
     * Reads PMS's full `Packages:` section once and parses every package block.
     *
     * The output is bounded by [PackageBinder] and by [RootPackageExecutor]; a partial dump is
     * rejected so the UI can offer a retry instead of showing a truncated list.
     */
    private fun readAllPackages(): List<PackageRecord> {
        val result = try {
            executor.dump()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw PackageScanException(PackageScanIssue.QUERY_FAILED)
        }
        result.queryIssue()?.let { throw PackageScanException(it) }
        if (result.output.contains("SERVICE 'package' DUMP TIMEOUT")) {
            throw PackageScanException(PackageScanIssue.QUERY_FAILED)
        }
        val lines = result.output.lines()
        val section = lines.indexOfFirst { it.trimEnd() == "Packages:" }
        if (section < 0) throw PackageScanException(PackageScanIssue.UNRECOGNIZED_RESPONSE)

        val blocks = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var blockCount = 0
        for (line in lines.drop(section + 1)) {
            if (isPostPackagesSectionHeader(line)) break
            val header = packageHeader.matchEntire(line)
            if (header != null) {
                val name = header.groupValues[1]
                blockCount += 1
                current = if (name !in blocks) {
                    mutableListOf<String>().also { blocks[name] = it }
                } else {
                    null
                }
            } else {
                current?.add(line)
            }
        }
        if (blockCount == 0) throw PackageScanException(PackageScanIssue.UNRECOGNIZED_RESPONSE)
        return blocks.map { (name, block) -> parseRecord(name, block) }
    }

    /** Reads the exact package directly from PMS. */
    fun read(packageName: String): PackageReading = readDump(packageName) { executor.dump(packageName) }

    /** Converts transport failures to a reason before parsing the package response. */
    private inline fun readDump(
        packageName: String,
        request: () -> PackageShellResult,
    ): PackageReading {
        val result = query({ return PackageReading.Unavailable(it) }, request)
        return parseDump(packageName, result)
    }

    /** Classifies failures from Android without swallowing coroutine cancellation. */
    private inline fun query(
        fail: (PackageScanIssue) -> Nothing,
        request: () -> PackageShellResult,
    ): PackageShellResult = try {
        request()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SecurityException) {
        fail(PackageScanIssue.PERMISSION_DENIED)
    } catch (_: Exception) {
        fail(PackageScanIssue.QUERY_FAILED)
    }

    /** Detects access denials and failed commands even if Binder itself completed. */
    private fun PackageShellResult.queryIssue(): PackageScanIssue? {
        val denied = output.lineSequence().any { line ->
            val text = line.trimStart()
            val message = if (text.startsWith("Error:", ignoreCase = true)) text.drop(6).trimStart() else text
            message.startsWith("Permission Denial", ignoreCase = true) ||
                message.startsWith("Permission denied", ignoreCase = true) ||
                message.startsWith("Security exception:", ignoreCase = true) ||
                message.startsWith("SecurityException", ignoreCase = true) ||
                message.startsWith("java.lang.SecurityException", ignoreCase = true)
        }
        return when {
            denied -> PackageScanIssue.PERMISSION_DENIED
            exitCode != 0 -> PackageScanIssue.QUERY_FAILED
            else -> null
        }
    }

    /**
     * Checks [canSubmit] before changing one user and verifies the resulting package state.
     *
     * [disableOnUninstallFailure] permits one ordinary package disable if an uninstall leaves the
     * package installed. [deleteThroughAppProcess] retries one uninstall directly through
     * app_process when the command layer fails. The original system error still describes a failed
     * retry.
     */
    suspend fun change(
        target: ChangeTarget,
        action: PackageAction,
        disableOnUninstallFailure: Boolean,
        canSubmit: suspend () -> Boolean = { true },
        deleteThroughAppProcess: ((String, Int, Int) -> PackageFallbackResult)? = null,
    ): ChangeOutcome {
        val packageName = target.packageName
        val reading = read(packageName)
        if (action == PackageAction.UNINSTALL && reading == PackageReading.Missing) {
            return ChangeOutcome(target, true, null)
        }
        val record = (reading as? PackageReading.Found)?.record
            ?: return ChangeOutcome(target, false, ChangeFailure.IdentityUnverified)
        val state = record.users[target.userId]
        if (state == null || state == PackageState.UNKNOWN) {
            return ChangeOutcome(target, false, ChangeFailure.SpaceStateUnverified)
        }
        val reenable = action == PackageAction.RESTORE && state == PackageState.DISABLED
        if (reenable && record.enabledSettings[target.userId] != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            return ChangeOutcome(target, false, ChangeFailure.DisabledBySystem)
        }
        if (action == PackageAction.UNINSTALL && state == PackageState.DISABLED) {
            return ChangeOutcome(target, false, ChangeFailure.NotEnabledInSpace)
        }
        if (!reenable && reading.matches(target, action)) return ChangeOutcome(target, true, null)
        if (!reenable && !state.permits(action)) {
            val failure = if (action == PackageAction.DISABLE) {
                ChangeFailure.NotEnabledInSpace
            } else {
                ChangeFailure.NoRestorableCopy
            }
            return ChangeOutcome(target, false, failure)
        }

        val userId = target.userId.toString()
        val command = when (action) {
            PackageAction.UNINSTALL -> buildList {
                addAll(listOf("uninstall", "--user", userId))
                record.versionCode?.let { addAll(listOf("--versionCode", it.toString())) }
                add(packageName)
            }
            PackageAction.DISABLE -> target.disableCommand()
            PackageAction.RESTORE -> if (reenable) {
                listOf("enable", "--user", userId, packageName)
            } else {
                listOf("install-existing", "--user", userId, "--wait", packageName)
            }
        }
        currentCoroutineContext().ensureActive()
        if (!canSubmit()) return ChangeOutcome(target, false, ChangeFailure.SpaceStateUnverified)
        currentCoroutineContext().ensureActive()
        val startedAt = System.currentTimeMillis()
        val attempt = executeChange(command)
        // A rejected Binder reply can arrive after the service applied the command.
        val afterRequest = read(packageName)
        val post = readUserState(target, afterRequest)
        val completed = if (reenable) {
            post == PackageState.INSTALLED &&
                afterRequest.enabledFor(target.userId) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            afterRequest.matches(target, action)
        }
        if (completed) {
            return ChangeOutcome(target, true, null)
        }

        var submitDeniedOnRetry = false
        val postCanUninstall = post == PackageState.INSTALLED
        val fallbackVersionCode = record.versionCode?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
        val postVersionMatches = record.versionCode != null && afterRequest.versionCode() == record.versionCode
        val fallback = if (deleteThroughAppProcess != null && action == PackageAction.UNINSTALL &&
            postCanUninstall && postVersionMatches && fallbackVersionCode != null
        ) {
            if (canSubmit()) {
                currentCoroutineContext().ensureActive()
                deleteThroughAppProcess(packageName, target.userId, fallbackVersionCode)
            } else {
                submitDeniedOnRetry = true
                null
            }
        } else {
            null
        }
        if (fallback?.submitted == true && awaitUninstall(target)) {
            return ChangeOutcome(target, true, null)
        }

        var disableAttempt: Result<PackageShellResult>? = null
        var afterDisable: PackageState? = null
        if (!submitDeniedOnRetry && disableOnUninstallFailure && action == PackageAction.UNINSTALL) {
            val current = read(packageName)
            val currentState = readUserState(target, current)
            if (current.matches(target, action)) return ChangeOutcome(target, true, null)
            val enabled = current.enabledFor(target.userId)
            val currentVersionMatches = record.versionCode != null && current.versionCode() == record.versionCode
            if (currentState == PackageState.INSTALLED &&
                currentVersionMatches &&
                enabled.isExplicitlyEnabled()
            ) {
                if (canSubmit()) {
                    currentCoroutineContext().ensureActive()
                    disableAttempt = executeChange(target.disableCommand())
                    val updated = read(packageName)
                    afterDisable = readUserState(target, updated)
                    if (updated.matches(target, action)) return ChangeOutcome(target, true, null)
                    if (updated.matches(target, PackageAction.DISABLE)) {
                        return ChangeOutcome(target, false, null, disabledInstead = true)
                    }
                } else {
                    submitDeniedOnRetry = true
                }
            }
        }

        val result = attempt.getOrNull()
        val failure = attempt.exceptionOrNull()
        if (failure != null && failure !is SecurityException && disableAttempt == null && !submitDeniedOnRetry) throw failure
        val failureReason = when {
            submitDeniedOnRetry -> ChangeFailure.SpaceStateUnverified
            result != null -> failureReason(result, post)
            failure is SecurityException -> ChangeFailure.SpaceOperationDenied
            else -> ChangeFailure.StateUnverified
        }
        val denialLog = if (action == PackageAction.UNINSTALL) {
            buildDiagnostics(target, record, state, post, startedAt, attempt, fallback, disableAttempt, afterDisable)
        } else {
            null
        }
        return ChangeOutcome(target, false, failureReason, denialLog)
    }

    /** Keeps a package-service failure available for verification and diagnostics. */
    private fun executeChange(command: List<String>): Result<PackageShellResult> = try {
        Result.success(executor.execute(command))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    /** Waits until a deletion submitted through app_process becomes visible to package dumps. */
    private suspend fun awaitUninstall(target: ChangeTarget): Boolean {
        val deadline = clock() + RETRY_VERIFY_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            if (read(target.packageName).matches(target, PackageAction.UNINSTALL)) return true
            if (clock() >= deadline) return false
            delay(RETRY_VERIFY_INTERVAL_MS)
        }
    }

    /** Explains a failed change while preserving the system's own error text. */
    private fun failureReason(result: PackageShellResult, state: PackageState?): ChangeFailure {
        if (state == null || state == PackageState.UNKNOWN) {
            return ChangeFailure.PostChangeStateUnverified
        }
        val systemError = result.systemErrorLine()?.take(160)
        return when {
            systemError != null -> ChangeFailure.SystemError(systemError)
            result.exitCode != 0 -> ChangeFailure.OperationDenied
            else -> ChangeFailure.StateUnchanged
        }
    }

    /** Finds an explicit error reported by the package command. */
    private fun PackageShellResult.systemErrorLine(): String? = output.lineSequence()
        .map(String::trim)
        .firstOrNull {
            it.startsWith("Failure", ignoreCase = true) ||
                it.startsWith("Error", ignoreCase = true) ||
                it.startsWith("SecurityException", ignoreCase = true) ||
                it.startsWith("java.lang.SecurityException", ignoreCase = true) ||
                it.startsWith("DELETE_FAILED_", ignoreCase = true) ||
                it.startsWith("Permission Denial", ignoreCase = true)
        }

    /** Returns the exact package manager enable setting observed for this user. */
    private fun PackageReading.enabledFor(userId: Int): Int? =
        (this as? PackageReading.Found)?.record?.enabledSettings?.get(userId)

    /** Whether Android explicitly reported an enabled (DEFAULT or ENABLED) component state. */
    private fun Int?.isExplicitlyEnabled(): Boolean =
        this == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            this == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Builds a disable request for the exact package and user in the confirmation. */
    private fun ChangeTarget.disableCommand(): List<String> =
        listOf("disable-user", "--user", userId.toString(), packageName)

    /** Resolves a target's installation state, including when its whole package record is gone. */
    private fun readUserState(
        target: ChangeTarget,
        reading: PackageReading,
    ): PackageState? = when (reading) {
        is PackageReading.Found -> reading.record.users[target.userId]
        PackageReading.Missing -> PackageState.NOT_FOUND
        is PackageReading.Unavailable -> null
    }

    /** Reads the raw version code from a successful dump reading, when present. */
    private fun PackageReading.versionCode(): Long? =
        (this as? PackageReading.Found)?.record?.versionCode

    /** Verifies the resulting state, distinguishing a user freeze from system-disabled states. */
    private fun PackageReading.matches(target: ChangeTarget, action: PackageAction): Boolean {
        val state = readUserState(target, this)
        return when (action) {
            PackageAction.UNINSTALL -> state == PackageState.RESTORABLE || state == PackageState.NOT_FOUND
            PackageAction.DISABLE -> state == PackageState.DISABLED &&
                enabledFor(target.userId) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            PackageAction.RESTORE -> state == PackageState.INSTALLED || state == PackageState.DISABLED
        }
    }

    /** Assembles enough context to interpret a system denial without exposing app-private data. */
    private fun buildDiagnostics(
        target: ChangeTarget,
        record: PackageRecord,
        state: PackageState,
        post: PackageState?,
        startedAt: Long,
        attempt: Result<PackageShellResult>,
        fallback: PackageFallbackResult?,
        disableAttempt: Result<PackageShellResult>?,
        afterDisable: PackageState?,
    ): String = buildString {
        appendLine("Package: ${target.packageName}, user=${target.userId}")
        appendLine("Version: ${record.versionName} (${record.versionCode}), path=${record.codePath}")
        appendLine("State before: $state, after: ${post ?: "unknown"}")
        appendLine("Elapsed: ${System.currentTimeMillis() - startedAt}ms")
        attempt.fold(
            onSuccess = { appendLine("Command: exit=${it.exitCode}, output=${it.output.trim().take(1200)}") },
            onFailure = { appendLine("Command failed: $it") },
        )
        fallback?.let { appendLine("app_process: submitted=${it.submitted}, ${it.detail.take(1200)}") }
        disableAttempt?.let {
            appendLine(
                "Disable fallback: state=${afterDisable ?: "unknown"}, " +
                    it.fold(
                        onSuccess = { result -> "exit=${result.exitCode}, output=${result.output.trim().take(800)}" },
                        onFailure = { failure -> "failed=$failure" },
                    ),
            )
        }
    }

    private fun parseDump(packageName: String, result: PackageShellResult): PackageReading {
        val issue = result.queryIssue()
        if (issue == PackageScanIssue.PERMISSION_DENIED) return PackageReading.Unavailable(issue)
        val lines = result.output.lines()
        val section = lines.indexOfFirst { it.trimEnd() == "Packages:" }
        if (section >= 0) {
            val packages = lines.drop(section + 1)
                .takeWhile { !isPostPackagesSectionHeader(it) }
            val start = packages.indexOfFirst { packageHeader.matchEntire(it)?.groupValues?.get(1) == packageName }
            if (start >= 0) {
                if (issue != null) return PackageReading.Unavailable(issue)
                val block = packages.drop(start + 1).takeWhile { !packageHeader.matches(it) }
                return PackageReading.Found(parseRecord(packageName, block))
            }
        }
        val missing = lines.any {
            it.trim().equals("Unable to find package: " + packageName, ignoreCase = true)
        }
        return if (missing) {
            PackageReading.Missing
        } else {
            PackageReading.Unavailable(issue ?: PackageScanIssue.UNRECOGNIZED_RESPONSE)
        }
    }

    private fun parseRecord(packageName: String, lines: List<String>): PackageRecord {
        val fields = lines.map(String::trim)
        val flagFields = fields.filter { it.startsWith("pkgFlags=[") || it.startsWith("flags=[") }
        val flags = flagFields
            .flatMap { it.substringAfter('[').substringBefore(']').trim().split(Regex("""\s+""")) }
            .toSet()
        val isUpdatedSystemApp = "UPDATED_SYSTEM_APP" in flags
        val isSystem = "SYSTEM" in flags || isUpdatedSystemApp
        val appId = fields.firstOrNull { it.startsWith("appId=") || it.startsWith("userId=") }
            ?.substringAfter('=')?.toIntOrNull()
        val codePath = fields.firstOrNull { it.startsWith("codePath=") }
            ?.substringAfter('=')?.takeUnless { it.isBlank() || it == "null" }
        val enabledSettings = mutableMapOf<Int, Int>()
        val users = lines.mapNotNull { line ->
            val match = userHeader.matchEntire(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val data = match.groupValues[2]
            val enabled = enabledFlag.find(data)?.groupValues?.get(1)?.toIntOrNull()
            if (enabled != null) enabledSettings[id] = enabled
            val state = when (installedFlag.find(data)?.groupValues?.get(1)) {
                "true" -> when (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> PackageState.DISABLED
                    else -> PackageState.INSTALLED
                }
                "false" -> PackageState.NOT_FOUND
                else -> PackageState.UNKNOWN
            }
            id to state
        }.toMap()
        // Package settings can still report a code path after the APK is gone; bare codePath does not prove APK presence.
        val pkgValue = fields.firstOrNull { it.startsWith("pkg=") }?.substringAfter('=')
        val hasVerifiedApk: Boolean? = when {
            pkgValue == "null" -> false
            !pkgValue.isNullOrBlank() -> true
            isSystem || users.values.any { it.permits(PackageAction.UNINSTALL) } -> true
            else -> null
        }
        return PackageRecord(
            packageName = packageName,
            versionName = fields.firstOrNull { it.startsWith("versionName=") }
                ?.substringAfter('=')?.takeUnless { it == "null" },
            versionCode = fields.firstNotNullOfOrNull {
                versionCode.find(it)?.groupValues?.get(1)?.toLongOrNull()
            },
            codePath = codePath,
            isSystem = isSystem,
            isUpdatedSystemApp = isUpdatedSystemApp,
            hasVerifiedSystemIdentity = isSystem && appId != null && appId >= 10_000,
            enabledSettings = enabledSettings,
            hasVerifiedApk = hasVerifiedApk,
            users = if (hasVerifiedApk == true) {
                users.mapValues { (_, state) -> if (state == PackageState.NOT_FOUND) PackageState.RESTORABLE else state }
            } else {
                users
            },
        )
    }

    /**
     * Recognizes top-level PMS dump section boundaries that terminate `Packages:`.
     *
     * Avoids breaking on arbitrary column-0 lines because AOSP Android 12-15 omits indentation
     * before `<lib> overlay paths:` inside `User <id>:` blocks, and multiline fields or OEM lines
     * can also start at column 0.
     */
    private fun isPostPackagesSectionHeader(line: String): Boolean {
        if (line.isBlank() || line.first().isWhitespace()) return false
        val heading = line.trimEnd()
        return heading in postPackagesSectionHeaders ||
            postPackagesSectionPrefixes.any { heading.startsWith(it) }
    }

    private companion object {
        val packageHeader = Regex("""^ {2}Package \[([^\]]+)](?: \([^)]*\))?:.*$""")
        val userHeader = Regex("""^\s+User (\d+):\s+(.*)$""")
        val listedUser = Regex("""^\s*UserInfo\{(\d+):(.*):[a-fA-F0-9]+\}.*$""")
        val installedFlag = Regex("""\binstalled=(true|false)\b""")
        val enabledFlag = Regex("""\benabled=(\d+)\b""")
        val versionCode = Regex("""^versionCode=(\d+)\b""")

        val postPackagesSectionHeaders = setOf(
            "Renamed packages:",
            "Hidden system packages:",
            "Queries:",
            "Shared users:",
            "Package Changes:",
            "Frozen packages:",
            "Loaded volumes:",
            "Service permissions:",
            "Dexopt state:",
            "BgDexopt state:",
            "Compiler stats:",
            "Messages:",
            "Package warning messages:",
            "Active install sessions:",
            "Historical install sessions:",
            "Install sessions:",
            "Active staged sessions:",
            "Active APEX packages:",
            "Inactive APEX packages:",
            "Factory APEX packages:",
            "APEX session state:",
            "APEX packages:",
            "Per UID read timeouts:",
            "Snapshot statistics:",
            "Protected broadcast actions:",
            "Database versions:",
            "Known Packages:",
            "Verifiers:",
            "Domain Verifier:",
            "Features:",
            "Libraries:",
            "Shared libraries:",
            "Permissions:",
            "Registered ContentProviders:",
            "ContentProvider Authorities:",
            "Key Set Manager:",
            "Domain verification status:",
            "Activity Resolver Table:",
            "Receiver Resolver Table:",
            "Service Resolver Table:",
            "Provider Resolver Table:",
        )

        val postPackagesSectionPrefixes = listOf(
            "DUMP OF SERVICE ",
            "--------",
            "Preferred Activities",
            "Active APEX ",
            "Inactive APEX ",
            "Factory APEX ",
        )

        /** Total time allowed for an app_process deletion to appear in the package dump. */
        const val RETRY_VERIFY_TIMEOUT_MS = 3_000L

        /** Delay between package dumps while waiting for an app_process deletion. */
        const val RETRY_VERIFY_INTERVAL_MS = 150L
    }
}