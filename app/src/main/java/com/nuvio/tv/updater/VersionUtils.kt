package com.nuvio.tv.updater

internal data class SemanticVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>,
    val autoSyncRevision: Long? = null
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        comparePrerelease(prerelease, other.prerelease)
            .takeIf { it != 0 }
            ?.let { return it }

        // AutoSync revisions are ordered after the exact upstream version they extend.
        // Example: 1.0.0 < 1.0.0-autosync.1 < 1.0.0-autosync.2.
        return compareValues(autoSyncRevision ?: 0L, other.autoSyncRevision ?: 0L)
    }

    private fun comparePrerelease(left: List<String>, right: List<String>): Int {
        if (left.isEmpty() && right.isEmpty()) return 0
        if (left.isEmpty()) return 1
        if (right.isEmpty()) return -1

        val sharedSize = minOf(left.size, right.size)
        for (index in 0 until sharedSize) {
            comparePrereleaseIdentifier(left[index], right[index])
                .takeIf { it != 0 }
                ?.let { return it }
        }
        return compareValues(left.size, right.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)

        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValues(normalizedLeft.length, normalizedRight.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric) return -1
        if (rightNumeric) return 1
        return left.compareTo(right)
    }
}

internal object VersionUtils {
    private val versionPattern = Regex(
        "^(\\d+)\\.(\\d+)\\.(\\d+)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )
    private val autoSyncSuffixPattern = Regex(
        "^(.*)-autosync(?:\\.(\\d+))?$",
        RegexOption.IGNORE_CASE
    )
    private val canonicalAutoSyncPattern = Regex(
        "^v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?-autosync\\.\\d+(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
        RegexOption.IGNORE_CASE
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
    }

    fun parse(raw: String?): SemanticVersion? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return null

        val autoSyncMatch = autoSyncSuffixPattern.matchEntire(normalized)
        val baseVersion = autoSyncMatch?.groupValues?.get(1) ?: normalized
        val autoSyncRevision = autoSyncMatch
            ?.groupValues
            ?.get(2)
            ?.takeIf(String::isNotEmpty)
            ?.toLongOrNull()
            ?: if (autoSyncMatch != null) 0L else null

        val match = versionPattern.matchEntire(baseVersion) ?: return null
        return SemanticVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty(),
            autoSyncRevision = autoSyncRevision
        )
    }

    fun isCanonicalAutoSync(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return canonicalAutoSyncPattern.matches(raw.trim())
    }

    fun isPrerelease(raw: String?): Boolean = parse(raw)?.prerelease?.isNotEmpty() == true

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteVersion = parse(remote) ?: return false
        val localVersion = parse(local) ?: return false
        return remoteVersion > localVersion
    }
}
