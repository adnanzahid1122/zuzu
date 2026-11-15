package com.example.zuzuvpn.ui.home

/**
 * Connection phases shown by the home screen.
 *
 * IMPORTANT (Phase 1): these are UI demonstration states only. Nothing here reflects the real
 * WireGuard tunnel. When the screen is wired to [com.wireguard.android.backend.GoBackend] in a
 * later phase, `Connected` must only be shown after the tunnel is UP *and* a recent handshake has
 * been verified via `Backend.getStatistics(...)`.
 */
enum class ConnectionPhase { Disconnected, Connecting, Connected }

/** A server location as the UI displays it. Values come from the backend in a later phase. */
data class ServerLocation(
    /** Stable identifier used for selection. */
    val id: String,
    val countryName: String,
    val cityName: String,
    /** ISO 3166-1 alpha-2 code, used to render the flag. */
    val countryCode: String,
    /** Measured round-trip latency, or null when not measured. Never invent a value. */
    val latencyMs: Int? = null,
    /** Server load 0..100 from the control plane, or null when unknown. */
    val loadPercent: Int? = null,
    /** True for placeholder data that did not come from the backend. */
    val isSample: Boolean = false,
    /** Shown in the "Recommended" section of the Locations screen. */
    val isRecommended: Boolean = false,
)

data class HomeUiState(
    val phase: ConnectionPhase = ConnectionPhase.Disconnected,
    /** Always true in Phase 1. The UI must label every state as a demo while this is true. */
    val isDemo: Boolean = true,
    /** Seconds elapsed in the demo "connected" state (a UI timer, not a tunnel session). */
    val demoElapsedSeconds: Long = 0,
    val server: ServerLocation = SampleServers.default,
)

/**
 * SAMPLE locations for the UI preview. These are not Zuzu servers: there is no latency,
 * load or availability data, and every entry is marked [ServerLocation.isSample].
 */
object SampleServers {
    private fun sample(id: String, country: String, city: String, code: String, recommended: Boolean = false) =
        ServerLocation(
            id = id,
            countryName = country,
            cityName = city,
            countryCode = code,
            latencyMs = null,
            loadPercent = null,
            isSample = true,
            isRecommended = recommended,
        )

    val all: List<ServerLocation> = listOf(
        sample("us-nyc", "United States", "New York", "US", recommended = true),
        sample("gb-lon", "United Kingdom", "London", "GB", recommended = true),
        sample("de-fra", "Germany", "Frankfurt", "DE", recommended = true),
        sample("sg-sin", "Singapore", "Singapore", "SG", recommended = true),
        sample("ae-dxb", "United Arab Emirates", "Dubai", "AE"),
        sample("au-syd", "Australia", "Sydney", "AU"),
        sample("ca-tor", "Canada", "Toronto", "CA"),
        sample("fr-par", "France", "Paris", "FR"),
        sample("in-bom", "India", "Mumbai", "IN"),
        sample("jp-tyo", "Japan", "Tokyo", "JP"),
        sample("nl-ams", "Netherlands", "Amsterdam", "NL"),
        sample("tr-ist", "Turkey", "Istanbul", "TR"),
        sample("us-lax", "United States", "Los Angeles", "US"),
    )

    val default: ServerLocation = all.first()
}
