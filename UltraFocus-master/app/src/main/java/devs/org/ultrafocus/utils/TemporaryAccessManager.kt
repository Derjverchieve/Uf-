package devs.org.ultrafocus.utils

object TemporaryAccessManager {
    // Maps PackageName/URL -> Time when access expires
    private val allowedItems = mutableMapOf<String, Long>()

    // How long the "Type to Access" grants you (e.g., 5 minutes)
    private const val GRACE_PERIOD_MS = 5 * 60 * 1000L

    fun grantAccess(key: String) {
        allowedItems[key] = System.currentTimeMillis() + GRACE_PERIOD_MS
    }

    /** Grants access for a specific duration in milliseconds (used by soft block). */
    fun grantAccess(key: String, durationMs: Long) {
        allowedItems[key] = System.currentTimeMillis() + durationMs.coerceAtLeast(1_000L)
    }

    fun isAllowed(key: String): Boolean {
        val expiry = allowedItems[key] ?: return false
        return if (System.currentTimeMillis() < expiry) {
            true
        } else {
            allowedItems.remove(key) // Expired
            false
        }
    }
}
