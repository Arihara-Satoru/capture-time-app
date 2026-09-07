package local.capturetime.settings

import java.io.File

internal object BackupSessionRules {
    private val prefixes = listOf("capture-time-app-", "duplicate-cleanup-")

    fun isManagedSession(directory: File): Boolean =
        directory.isDirectory && isManagedName(directory.name)

    internal fun isManagedName(name: String): Boolean =
        prefixes.any { prefix -> name.startsWith(prefix) && name.length > prefix.length }
}
