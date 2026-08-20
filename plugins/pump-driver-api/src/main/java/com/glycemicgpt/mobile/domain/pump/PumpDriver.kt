package com.glycemicgpt.mobile.domain.pump

import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BatteryStatus
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.ConnectionState
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import com.glycemicgpt.mobile.domain.model.IoBReading
import com.glycemicgpt.mobile.domain.model.PumpHardwareInfo
import com.glycemicgpt.mobile.domain.model.PumpSettings
import com.glycemicgpt.mobile.domain.model.ReservoirReading
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Abstract pump driver interface for read-only pump data access.
 *
 * This interface intentionally has NO methods for insulin delivery,
 * pump setting changes, or any control operations. All methods are
 * strictly read-only status queries.
 *
 * Implementations:
 * - [com.glycemicgpt.mobile.ble.connection.TandemBleDriver] for Tandem pumps (t:slim X2, Mobi)
 * - Future: OmnipodDriver, MedtronicDriver, etc.
 */
@Deprecated("Use Plugin/DevicePlugin with capabilities. This interface will be removed in a future version.", ReplaceWith("DevicePlugin"))
interface PumpDriver {
    suspend fun connect(deviceAddress: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun getIoB(): Result<IoBReading>
    suspend fun getBasalRate(): Result<BasalReading>
    suspend fun getBolusHistory(since: Instant, limits: SafetyLimits = SafetyLimits()): Result<List<BolusEvent>>
    suspend fun getPumpSettings(): Result<PumpSettings>
    suspend fun getBatteryStatus(): Result<BatteryStatus>
    suspend fun getReservoirLevel(): Result<ReservoirReading>
    suspend fun getCgmStatus(): Result<CgmReading>

    /**
     * One batch of history records at or above the caller's resume position.
     *
     * Two contracts the caller's persisted cursor depends on (GLY-250), because it advances to
     * `max(sequenceNumber)` of what comes back:
     *
     *  1. **No interior gaps, and only records the pump actually delivered.** Every record the
     *     pump holds between the lowest and the highest sequence number returned must be in the
     *     returned list. A record dropped from the middle of a batch is skipped by the cursor
     *     forever, and its raw bytes are never stored, so nothing can recover it later. Requesting
     *     a window is not the same as receiving it: an implementation that asks for a range and
     *     gets part of it back must return (and resume from) only the part it can account for, and
     *     must not return a sequence number the pump never sent it. Shortening the batch or
     *     failing the call are both fine; returning it with a hole in it is not.
     *  2. **Nothing is consumed until [acknowledgeHistoryLogs].** Returning records is not
     *     delivery; see that method.
     *
     * The caller verifies contract 1 as far as it can — a batch whose span exceeds its own record
     * count is refused — but it cannot see what the pump was asked for, so a driver that breaks
     * the contract in a way that stays span-consistent is only caught by the driver's own checks.
     * Failing the call is always safe: the cursor stays put and the batch is re-fetched.
     */
    suspend fun getHistoryLogs(sinceSequence: Int): Result<List<HistoryLogRecord>>

    /** Full sync variant that fetches all available history without lookback limits.
     *  Default implementation delegates to the standard incremental fetch. Both contracts
     *  on [getHistoryLogs] apply here too. */
    suspend fun getFullHistoryLogs(sinceSequence: Int): Result<List<HistoryLogRecord>> =
        getHistoryLogs(sinceSequence)

    /**
     * Confirms that everything the last [getHistoryLogs] / [getFullHistoryLogs] returned is now
     * durably accounted for, so a driver that tracks its own scan position may move it past
     * those records (GLY-250).
     *
     * A driver MUST NOT advance that position when it hands the records over. Between the fetch
     * and the caller's commit sits a BLE flap: the connection watcher cancels the poll loops on
     * any non-CONNECTED state, and a fetch position that had already moved would resume ABOVE a
     * batch that was never persisted -- permanent, silent loss of exactly the reconnect data
     * this app exists to keep. Re-serving records the caller already has is free (raw inserts
     * ignore duplicate sequence numbers and the derived rows collapse on their unique indices);
     * skipping records is not recoverable.
     *
     * The default is a no-op, which is correct for drivers that resume purely from the
     * `sinceSequence` the caller passes -- their position IS the caller's cursor.
     */
    suspend fun acknowledgeHistoryLogs() = Unit

    suspend fun getPumpHardwareInfo(): Result<PumpHardwareInfo>
    fun observeConnectionState(): Flow<ConnectionState>
}
