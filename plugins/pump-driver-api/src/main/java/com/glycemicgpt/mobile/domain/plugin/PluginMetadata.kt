package com.glycemicgpt.mobile.domain.plugin

/** Current plugin API version. Plugins with a different version are rejected.
 *  v3: BasalReading.controlIqMode renamed to activityMode (PumpActivityMode enum).
 *  v4: BolusEvent.category field + BolusCategoryProvider capability.
 *  v5: getFullHistoryLogs() overload added to PumpDriver and PumpStatus.
 *  v6: acknowledgeHistoryLogs() added to PumpDriver and PumpStatus, plus the gap-free contract
 *      on getHistoryLogs(). A v5 driver compiles against v6 unchanged -- the new method has a
 *      no-op default -- and that is exactly why the version has to move: a driver that advances
 *      its own scan position at fetch time is silently wrong under the new contract, losing a
 *      batch whenever a BLE flap lands between the fetch and the caller's commit. Rejecting it
 *      is how that gets caught instead of shipped (GLY-250). */
const val PLUGIN_API_VERSION = 6

/**
 * Immutable metadata describing a plugin. Available before the plugin is created.
 */
data class PluginMetadata(
    /** Reverse-domain unique ID, e.g. "com.glycemicgpt.tandem". */
    val id: String,
    /** Human-readable name, e.g. "Tandem Insulin Pump". */
    val name: String,
    /** Semantic version string, e.g. "1.0.0". */
    val version: String,
    /** Plugin API version this plugin was built against. */
    val apiVersion: Int,
    /** Short description of the plugin. */
    val description: String = "",
    /** Author name. */
    val author: String = "",
    /** Optional drawable resource name for the plugin icon. */
    val iconResName: String? = null,
    /**
     * Optional BLE/communication protocol family name, e.g. "Tandem".
     * Combined with [version] for display: "Tandem v1.0.0".
     */
    val protocolName: String? = null,
)
