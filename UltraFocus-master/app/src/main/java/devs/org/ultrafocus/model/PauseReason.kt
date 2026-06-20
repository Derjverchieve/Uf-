package devs.org.ultrafocus.model

/**
 * Why a focus session was paused.
 *
 * AUTO_AWAY is reserved for the camera-based presence detection feature
 * (not implemented yet) — it's included now so the schema and UI don't need
 * another migration when that feature lands later.
 */
enum class PauseReason {
    MANUAL,
    APP_SWITCH,
    AUTO_AWAY
}
