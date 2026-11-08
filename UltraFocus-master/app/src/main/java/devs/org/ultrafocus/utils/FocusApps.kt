package devs.org.ultrafocus.utils

/**
 * Helpers for sessions with more than one focus app (up to [MAX_APPS]).
 *
 * Rather than changing the Room schema, the session model, the CSV export/import
 * or the prefs, ALL focus apps are packed into the existing `primaryAppPackage`
 * string, joined with "|" (a character that can never appear in a package name).
 * A plain single package like "com.ichi2.anki" is simply a list of one, so every
 * session already saved in the database keeps working untouched.
 *
 * The FIRST package in the list is the "primary" one: it's what gets launched
 * when a session or cycle starts.
 */
object FocusApps {
    const val MAX_APPS = 3

    private const val PKG_SEP = "|"
    private const val NAME_SEP = " + "

    /** Splits a stored value into package names. Null/blank gives an empty list. */
    fun packages(joined: String?): List<String> =
        joined?.split(PKG_SEP)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** True if [pkg] is any one of the focus apps in [joined]. */
    fun contains(joined: String?, pkg: String?): Boolean =
        pkg != null && packages(joined).contains(pkg)

    /** The primary (first) focus app, or null if there is none. */
    fun first(joined: String?): String? = packages(joined).firstOrNull()

    fun joinPackages(pkgs: List<String>): String = pkgs.joinToString(PKG_SEP)

    /** Display-only: "Anki + Lichess + Notes". */
    fun joinNames(names: List<String>): String = names.joinToString(NAME_SEP)
}
