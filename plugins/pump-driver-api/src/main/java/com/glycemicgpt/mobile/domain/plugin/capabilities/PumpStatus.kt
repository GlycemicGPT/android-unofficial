package com.glycemicgpt.mobile.domain.plugin.capabilities

import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BatteryStatus
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import com.glycemicgpt.mobile.domain.model.PumpHardwareInfo
import com.glycemicgpt.mobile.domain.model.PumpSettings
import com.glycemicgpt.mobile.domain.model.ReservoirReading
import com.glycemicgpt.mobile.domain.plugin.PluginCapabilityInterface
import com.glycemicgpt.mobile.domain.pump.SafetyLimits

/**
 * Capability for plugins that provide pump hardware status and history data (read-only).
 */
interface PumpStatus : PluginCapabilityInterface {
    suspend fun getBatteryStatus(): Result<BatteryStatus>
    suspend fun getReservoirLevel(): Result<ReservoirReading>
    suspend fun getPumpSettings(): Result<PumpSettings>
    suspend fun getPumpHardwareInfo(): Result<PumpHardwareInfo>
    /**
     * One batch of history records at or above the caller's resume position.
     *
     * Same two contracts as [com.glycemicgpt.mobile.domain.pump.PumpDriver.getHistoryLogs], and
     * for the same reason -- the caller advances a persisted cursor to `max(sequenceNumber)` of
     * what comes back (GLY-250):
     *
     *  1. **No interior gaps, and only records the pump actually delivered.** A record dropped
     *     from the middle of a batch is skipped by the cursor forever and its raw bytes are never
     *     stored, so nothing can recover it. Requesting a window is not the same as receiving it:
     *     return only the part you can account for, never a sequence number the pump did not send.
     *     Shorten the batch or fail the call instead.
     *  2. **Nothing is consumed until [acknowledgeHistoryLogs].**
     */
    suspend fun getHistoryLogs(sinceSequence: Int): Result<List<HistoryLogRecord>>

    /** Full sync variant that fetches all available history without lookback limits.
     *  Default implementation delegates to the standard incremental fetch. Both contracts on
     *  [getHistoryLogs] apply here too. */
    suspend fun getFullHistoryLogs(sinceSequence: Int): Result<List<HistoryLogRecord>> =
        getHistoryLogs(sinceSequence)

    /**
     * Confirms the last history batch is durably persisted, so a plugin that tracks its own scan
     * position may move it past those records (GLY-250).
     *
     * Advancing that position at fetch time instead loses a batch outright when a BLE flap
     * cancels the poll loop between the fetch and the commit. Re-serving records the caller
     * already has is free; skipping them is not recoverable. The default no-op is correct for
     * plugins that resume purely from the `sinceSequence` they are given.
     */
    suspend fun acknowledgeHistoryLogs() = Unit

    /**
     * Extracts CGM readings from [records], applying [limits] to filter out any reading
     * whose glucose value falls outside [SafetyLimits.glucoseLow]..[SafetyLimits.glucoseHigh]
     * (or at minimum the absolute range 20-500 mg/dL). Out-of-range records must be dropped,
     * not clamped.
     */
    fun extractCgmFromHistoryLogs(
        records: List<HistoryLogRecord>,
        limits: SafetyLimits,
    ): List<CgmReading>

    /**
     * Extracts bolus events from [records]. Implementations MUST reject any event whose
     * [BolusEvent.units] exceeds [SafetyLimits.maxBolus]; such records must be dropped
     * and never returned to the caller.
     */
    fun extractBolusesFromHistoryLogs(
        records: List<HistoryLogRecord>,
        limits: SafetyLimits,
    ): List<BolusEvent>

    /**
     * Extracts basal readings from [records], applying any applicable rate limits from
     * [limits]. Out-of-range entries must be dropped.
     */
    fun extractBasalFromHistoryLogs(
        records: List<HistoryLogRecord>,
        limits: SafetyLimits,
    ): List<BasalReading>

    /**
     * Clear pairing credentials and disconnect.
     *
     * TODO: Make suspend once deprecated PumpConnectionManager is removed.
     * Current implementations (BleConnectionManager) do not require suspension.
     */
    fun unpair()

    /**
     * Attempt to reconnect to a previously paired device.
     *
     * TODO: Make suspend once deprecated PumpConnectionManager is removed.
     * Current implementations (BleConnectionManager) do not require suspension.
     */
    fun autoReconnectIfPaired()
}
