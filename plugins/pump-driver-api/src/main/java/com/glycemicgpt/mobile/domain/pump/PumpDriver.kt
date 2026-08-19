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
     *  1. **No interior gaps.** Every record the pump holds between the lowest and the highest
     *     sequence number returned must be in the returned list. A record dropped from the middle
     *     of a batch is skipped by the cursor forever, and its raw bytes are never stored, so
     *     nothing can recover it later. Implementations that cannot decode part of a batch must
     *     shorten the batch (or fail the call) rather than return it with a hole in it.
     *  2. **Nothing is consumed until [acknowledgeHistoryLogs].** Returning records is not
     *     delivery; see that method.
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
