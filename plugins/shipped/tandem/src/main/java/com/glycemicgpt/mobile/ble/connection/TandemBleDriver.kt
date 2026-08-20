package com.glycemicgpt.mobile.ble.connection

import com.glycemicgpt.mobile.ble.messages.StatusResponseParser
import com.glycemicgpt.mobile.ble.protocol.TandemProtocol
import com.glycemicgpt.mobile.domain.pump.DebugLogger
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BatteryStatus
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.CgmTrend
import com.glycemicgpt.mobile.domain.model.ConnectionState
import com.glycemicgpt.mobile.domain.model.PumpActivityMode
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import com.glycemicgpt.mobile.domain.model.IoBReading
import com.glycemicgpt.mobile.domain.model.PumpHardwareInfo
import com.glycemicgpt.mobile.domain.model.PumpSettings
import com.glycemicgpt.mobile.domain.model.ReservoirReading
import com.glycemicgpt.mobile.domain.pump.PumpDriver
import com.glycemicgpt.mobile.domain.pump.SafetyLimits
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tandem BLE driver implementing the PumpDriver interface.
 *
 * Delegates connection lifecycle to [BleConnectionManager] and uses
 * [StatusResponseParser] to decode pump responses into domain models.
 *
 * All data read methods are READ-ONLY status queries. No control
 * operations are implemented or invocable through this class.
 */
@Singleton
class TandemBleDriver @Inject constructor(
    private val connectionManager: BleConnectionManager,
    private val debugLogger: DebugLogger,
) : PumpDriver {

    /** ACKNOWLEDGED progressive scan position for history log fetching (pump record INDEX):
     *  the index the next fetch resumes from. Persists across poll cycles so each cycle
     *  continues where the last left off. Reset to 0 when the pump's index range shifts beyond
     *  the lookback window.
     *
     *  Only [acknowledgeHistoryLogs] moves it. Moving it at fetch time -- which is what this
     *  driver used to do -- meant a BLE flap between the fetch and the caller's commit resumed
     *  the next scan ABOVE a batch that was never persisted, losing it for good (GLY-250). */
    @Volatile
    private var nextHistoryIndex: Int = 0

    /** Where the last fetch got to, held back until the caller says the batch is durable. Null
     *  when there is nothing outstanding. A fetch whose records never get acknowledged simply
     *  leaves this stale, and the next fetch overwrites it. */
    @Volatile
    private var pendingHistoryIndex: Int? = null

    /** Cached activity mode from ControlIQInfoV1 to avoid an extra BLE round-trip
     *  on every poll cycle. Refreshed every [ACTIVITY_MODE_REFRESH_CYCLES] calls. */
    @Volatile
    private var cachedActivityMode: PumpActivityMode = PumpActivityMode.NONE
    private val activityModePollCount = AtomicInteger(0)

    override suspend fun connect(deviceAddress: String): Result<Unit> {
        return try {
            // Reset cached state to avoid serving stale data from a previous session
            cachedActivityMode = PumpActivityMode.NONE
            activityModePollCount.set(0)
            connectionManager.connect(deviceAddress)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return try {
            connectionManager.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getIoB(): Result<IoBReading> = runStatusRequest(
        opcode = TandemProtocol.OPCODE_CONTROL_IQ_IOB_REQ,
    ) { cargo ->
        StatusResponseParser.parseIoBResponse(cargo)
            ?: throw IllegalStateException("Failed to parse IoB response")
    }

    override suspend fun getBasalRate(): Result<BasalReading> {
        // Get basal rate from CurrentBasalStatus
        val basalResult = runStatusRequest(
            opcode = TandemProtocol.OPCODE_CURRENT_BASAL_STATUS_REQ,
        ) { cargo ->
            StatusResponseParser.parseBasalStatusResponse(cargo)
                ?: throw IllegalStateException("Failed to parse basal status response")
        }
        if (basalResult.isFailure) return basalResult

        // Refresh activity mode periodically (not every call) to avoid doubling
        // BLE round-trips and eating into the pump's 30s idle timeout budget.
        // getAndIncrement is atomic. floorMod handles negative values after
        // Int overflow correctly (plain % preserves sign in Kotlin/JVM).
        val shouldRefreshMode = Math.floorMod(activityModePollCount.getAndIncrement(), ACTIVITY_MODE_REFRESH_CYCLES) == 0
        if (shouldRefreshMode) {
            val modeResult = runStatusRequest(
                opcode = TandemProtocol.OPCODE_CONTROL_IQ_INFO_V1_REQ,
            ) { cargo ->
                StatusResponseParser.parseControlIqInfoV1Response(cargo)
            }
            cachedActivityMode = modeResult.getOrDefault(cachedActivityMode)
        }

        val basal = basalResult.getOrThrow()
        return Result.success(basal.copy(activityMode = cachedActivityMode))
    }

    override suspend fun getBolusHistory(since: Instant, limits: SafetyLimits): Result<List<BolusEvent>> = runStatusRequest(
        opcode = TandemProtocol.OPCODE_LAST_BOLUS_STATUS_REQ,
    ) { cargo ->
        StatusResponseParser.parseLastBolusStatusResponse(cargo, since, limits)
    }

    override suspend fun getPumpSettings(): Result<PumpSettings> = runStatusRequest(
        opcode = TandemProtocol.OPCODE_PUMP_SETTINGS_REQ,
    ) { cargo ->
        StatusResponseParser.parsePumpSettingsResponse(cargo)
            ?: throw IllegalStateException("Failed to parse pump settings response")
    }

    override suspend fun getBatteryStatus(): Result<BatteryStatus> {
        // Use V1 first (universally supported). V2 (opcode 144) is only for
        // Mobi pumps and causes GATT_ERROR (133) on tslim X2, killing the
        // connection before fallback can execute.
        val v1Result = runStatusRequest(
            opcode = TandemProtocol.OPCODE_CURRENT_BATTERY_V1_REQ,
        ) { cargo ->
            StatusResponseParser.parseBatteryV1Response(cargo)
                ?: throw IllegalStateException("Failed to parse battery V1 response")
        }
        if (v1Result.isSuccess) return v1Result
        // Fall back to V2 only if V1 fails (e.g., on Mobi pumps)
        return runStatusRequest(
            opcode = TandemProtocol.OPCODE_CURRENT_BATTERY_V2_REQ,
        ) { cargo ->
            StatusResponseParser.parseBatteryV2Response(cargo)
                ?: throw IllegalStateException("Failed to parse battery V2 response")
        }
    }

    override suspend fun getReservoirLevel(): Result<ReservoirReading> = runStatusRequest(
        opcode = TandemProtocol.OPCODE_INSULIN_STATUS_REQ,
    ) { cargo ->
        StatusResponseParser.parseInsulinStatusResponse(cargo)
            ?: throw IllegalStateException("Failed to parse insulin status response")
    }

    override suspend fun getCgmStatus(): Result<CgmReading> {
        // Get glucose value from CurrentEGVGuiData
        val egvResult = runStatusRequest(
            opcode = TandemProtocol.OPCODE_CGM_EGV_REQ,
        ) { cargo ->
            StatusResponseParser.parseCgmEgvResponse(cargo)
                ?: throw IllegalStateException("Failed to parse CGM EGV response")
        }
        if (egvResult.isFailure) return egvResult

        // Get trend arrow from HomeScreenMirror (failure degrades to UNKNOWN)
        val mirrorResult = runStatusRequest(
            opcode = TandemProtocol.OPCODE_HOME_SCREEN_MIRROR_REQ,
        ) { cargo ->
            StatusResponseParser.parseHomeScreenMirrorResponse(cargo)
                ?: throw IllegalStateException("Failed to parse HomeScreenMirror response")
        }

        val egv = egvResult.getOrThrow()
        val trend = mirrorResult.getOrNull()?.trendArrow ?: CgmTrend.UNKNOWN

        return Result.success(egv.copy(trendArrow = trend))
    }

    override suspend fun getHistoryLogs(sinceSequence: Int): Result<List<HistoryLogRecord>> =
        fetchHistoryLogs(sinceSequence, fullSync = false)

    override suspend fun getFullHistoryLogs(sinceSequence: Int): Result<List<HistoryLogRecord>> =
        fetchHistoryLogs(sinceSequence, fullSync = true)

    private suspend fun fetchHistoryLogs(sinceSequence: Int, fullSync: Boolean): Result<List<HistoryLogRecord>> {
        // Step 1: Get the available index range from the pump (opcode 58).
        //
        // The pump paginates history by record INDEX, and the same index appears in all three
        // messages: opcode 59 reports the first and last one it holds, opcode 60 takes a start
        // index plus a count, and bytes 6-9 of each 26-byte FFF8 record carry the index of that
        // record -- which is exactly what the parser stores as `HistoryLogRecord.sequenceNumber`
        // and what the caller's durable cursor is measured in. (An older comment here claimed the
        // two were unrelated; the parser has always read the same field, and the cursor has always
        // been compared against it.)
        //
        // One index space across all three is what lets this driver check a window's answer
        // against the window it asked for, which is the only thing standing between a misframed
        // FFF8 packet and a permanently poisoned cursor (GLY-250). A pump that ever contradicts
        // it fails that check, which stalls the backfill loudly rather than advancing on faith.
        val rangeResult = runStatusRequest(
            opcode = TandemProtocol.OPCODE_HISTORY_LOG_STATUS_REQ,
        ) { cargo ->
            StatusResponseParser.parseHistoryLogStatusResponse(cargo)
                ?: throw IllegalStateException("Failed to parse history log status (need 12 bytes, got ${cargo.size})")
        }
        if (rangeResult.isFailure) return Result.failure(rangeResult.exceptionOrNull()!!)

        val range = rangeResult.getOrThrow()
        // Full sync: start from the very first available index (no lookback limit).
        // Normal sync: cap lookback to ~24h of indices.
        //
        // Never index 0: the stream parser reads a zero record index as padding and drops it, so
        // index 0 is the one index a window can never be shown to have delivered. Requesting it
        // would leave the scan unable to prove its first index forever on a pump whose range
        // starts at 0. Its record was discarded before this change too — it is skipped here
        // rather than fetched and thrown away.
        val windowStart = maxOf(
            FIRST_USABLE_HISTORY_INDEX,
            if (fullSync) {
                range.firstSeq
            } else {
                maxOf(range.firstSeq, range.lastSeq - HISTORY_LOOKBACK_INDICES + 1)
            },
        )
        // Resume from previous scan position if still within the window,
        // otherwise start from the beginning of the window (new session / range shift).
        val fetchStart = if (nextHistoryIndex in windowStart..range.lastSeq) {
            nextHistoryIndex
        } else {
            windowStart
        }
        Timber.d("History log range: firstIdx=%d lastIdx=%d numEntries=%d windowStart=%d fetchStart=%d resumed=%s sinceSeq=%d fullSync=%s",
            range.firstSeq, range.lastSeq, range.lastSeq - range.firstSeq + 1,
            windowStart, fetchStart, fetchStart != windowStart, sinceSequence, fullSync)

        if (range.lastSeq < range.firstSeq) {
            // Nothing to serve, and nothing proven, so there is nothing to promote. Clearing the
            // proposal matters because the caller acknowledges an empty answer: an earlier
            // fetch's unacknowledged position left standing here would be promoted on the
            // strength of a window this call never even requested.
            pendingHistoryIndex = null
            return Result.success(emptyList())
        }

        // Step 2: Fetch records in batches via opcode 60.
        // Opcode 60 sends a 2-byte ACK on FFF6 and streams records on FFF8.
        // Cap total time to avoid blocking the slow poll loop (pump drops
        // idle connections at ~30s; other reads need time to execute too).
        val maxRecords = if (fullSync) MAX_FULL_SYNC_RECORDS_PER_POLL else MAX_HISTORY_RECORDS_PER_POLL
        val maxDurationMs = if (fullSync) MAX_FULL_SYNC_FETCH_DURATION_MS else MAX_HISTORY_FETCH_DURATION_MS
        val allRecords = mutableListOf<HistoryLogRecord>()
        var currentIndex = fetchStart
        val deadline = System.currentTimeMillis() + maxDurationMs
        // Set when the scan stops because a window could not be verified, rather than because it
        // ran out of range, records or time. A cycle that proves nothing then fails instead of
        // returning an empty success -- an empty success reads as "caught up", which is how a
        // packet this build cannot decode halts the backfill indefinitely with nothing noticing.
        var stall: String? = null

        while (currentIndex <= range.lastSeq &&
            allRecords.size < maxRecords &&
            System.currentTimeMillis() < deadline
        ) {
            val batchSize = minOf(HISTORY_BATCH_SIZE, range.lastSeq - currentIndex + 1)
            val windowEnd = currentIndex + batchSize - 1
            val cargo = buildHistoryLogCargo(currentIndex, batchSize)

            val fff8Packets = try {
                connectionManager.requestHistoryLogStream(
                    cargo = cargo,
                    timeoutMs = TandemProtocol.HISTORY_LOG_TIMEOUT_MS,
                )
            } catch (e: Exception) {
                Timber.w(e, "History log stream request failed at index=%d", currentIndex)
                stall = "the stream request for indices $currentIndex..$windowEnd failed " +
                    "(${e.javaClass.simpleName})"
                break
            }

            // Each FFF8 packet carries its own header and records; a packet this build cannot
            // decode parses to nothing and so contributes no indices, which is all the detection
            // the checks below need. Dedup of the records themselves is the insert layer's job
            // (IGNORE on a unique index).
            val decoded = fff8Packets.flatMap { StatusResponseParser.parseHistoryLogStreamCargo(it) }

            // Nothing outside the window we asked for is believable. This is the bound on the
            // caller's cursor: it advances to max(sequenceNumber) of what we hand over, so a
            // misframed packet -- 26-byte framing "structurally matches" any cargo whose length
            // divides by 26, including 18-byte-layout cargo -- could otherwise decode to garbage
            // indices near Int.MAX_VALUE and poison the cursor for good. The pump's own reported
            // range is the only thing here that can bound it, so refuse the whole answer and let
            // the window be re-requested (GLY-250).
            val outOfWindow = decoded.count { it.sequenceNumber !in currentIndex..windowEnd }
            if (outOfWindow > 0) {
                Timber.w(
                    "%d of %d record(s) in the answer for indices %d..%d carry an index outside " +
                        "it; refusing the whole answer",
                    outOfWindow, decoded.size, currentIndex, windowEnd,
                )
                stall = "$outOfWindow of ${decoded.size} record(s) answering indices " +
                    "$currentIndex..$windowEnd fell outside the requested window"
                break
            }

            // Only the run of indices actually delivered from currentIndex upward is proven. The
            // window request is not proof: it asks for `batchSize` indices and used to advance by
            // `batchSize` whatever came back, so a missed notification -- one dropped in the
            // middle, an undecodable last one, or simply a short answer -- moved the scan, and
            // later the cursor, over records that were never received (GLY-250). Anything past
            // the first hole is dropped here and re-requested rather than handed over: the
            // caller's cursor cannot step over a hole it never sees.
            val verified = verifiedPrefix(decoded, currentIndex, batchSize)
            if (verified.isEmpty()) {
                Timber.w(
                    "The answer for indices %d..%d contains no record at %d (%d packet(s), " +
                        "%d record(s) decoded); the window is re-requested next cycle",
                    currentIndex, windowEnd, currentIndex, fff8Packets.size, decoded.size,
                )
                stall = "indices $currentIndex..$windowEnd came back without index $currentIndex " +
                    "(${decoded.size} record(s) decoded from ${fff8Packets.size} packet(s))"
                break
            }
            if (verified.size < decoded.size) {
                Timber.w(
                    "Indices %d..%d delivered %d of %d record(s) contiguously; keeping the " +
                        "verified run and re-requesting from %d",
                    currentIndex, windowEnd, verified.size, decoded.size,
                    currentIndex + verified.size,
                )
            }

            allRecords.addAll(verified)
            // Advance over what the pump proved it delivered, never over what we asked for.
            currentIndex += verified.size
            delay(HISTORY_BATCH_STAGGER_MS)
        }

        if (allRecords.isEmpty() && stall != null) {
            // Nothing proven, so nothing to propose: the scan stays where the last
            // acknowledgement put it, and the window is requested again next cycle.
            pendingHistoryIndex = null
            Timber.w("History log scan stalled at index %d: %s", fetchStart, stall)
            return Result.failure(IllegalStateException("History log scan stalled: $stall"))
        }

        // Scan progress is only PROPOSED here. It becomes the resume point in
        // [acknowledgeHistoryLogs], once the caller has these records on disk.
        pendingHistoryIndex = currentIndex
        Timber.d(
            "Fetched %d history log records (fetchStart=%d pendingIndex=%d ackedIndex=%d)",
            allRecords.size, fetchStart, currentIndex, nextHistoryIndex,
        )
        return Result.success(allRecords)
    }

    /**
     * The records covering `windowStart, windowStart + 1, ...` in [decoded], stopping at the
     * first index missing from it -- the only part of a window's answer this driver may pass on.
     *
     * [decoded] is checked for out-of-window indices before this runs, so everything here is
     * within `windowStart until windowStart + windowSize`; duplicates collapse.
     */
    private fun verifiedPrefix(
        decoded: List<HistoryLogRecord>,
        windowStart: Int,
        windowSize: Int,
    ): List<HistoryLogRecord> {
        val byIndex = decoded.associateBy { it.sequenceNumber }
        val prefix = ArrayList<HistoryLogRecord>(minOf(windowSize, decoded.size))
        for (offset in 0 until windowSize) {
            prefix += byIndex[windowStart + offset] ?: break
        }
        return prefix
    }

    /**
     * Promotes the last fetch's scan position now that the caller has committed those records.
     *
     * Everything before this point is re-servable: if the poll loop is cancelled mid-batch (a
     * BLE flap does that on every non-CONNECTED state), the position stays where it was and the
     * next scan hands the same records over again, which the caller's inserts dedupe away.
     */
    override suspend fun acknowledgeHistoryLogs() {
        val pending = pendingHistoryIndex ?: return
        pendingHistoryIndex = null
        // Assigned, not max()'d: the fetch this acknowledges either resumed from the current
        // position or deliberately restarted at the window start (a pump whose index range
        // shifted or reset), and in the second case the lower value is the correct one.
        nextHistoryIndex = pending
        Timber.d("History scan position acknowledged: nextIndex=%d", pending)
    }

    override suspend fun getPumpHardwareInfo(): Result<PumpHardwareInfo> {
        val versionResult = runStatusRequest(
            opcode = TandemProtocol.OPCODE_PUMP_VERSION_REQ,
        ) { cargo ->
            StatusResponseParser.parsePumpVersionResponse(cargo)
                ?: throw IllegalStateException("Failed to parse pump version response")
        }
        if (versionResult.isFailure) return versionResult

        // Fetch features separately; degrade gracefully to empty map on failure
        val featuresResult = runStatusRequest(
            opcode = TandemProtocol.OPCODE_PUMP_FEATURES_V1_REQ,
        ) { cargo ->
            StatusResponseParser.parsePumpFeaturesResponse(cargo)
        }
        val features = featuresResult.getOrDefault(emptyMap())

        return Result.success(versionResult.getOrThrow().copy(pumpFeatures = features))
    }

    override fun observeConnectionState(): Flow<ConnectionState> =
        connectionManager.connectionState

    companion object {
        /** Max records to request per opcode 60 batch. */
        const val HISTORY_BATCH_SIZE = 20

        /** Lowest record index a history window may start at. Zero is the stream parser's
         *  padding sentinel, so no window can ever prove it received index 0. */
        const val FIRST_USABLE_HISTORY_INDEX = 1

        /** Delay between consecutive batch requests to avoid BLE congestion. */
        const val HISTORY_BATCH_STAGGER_MS = 200L

        /** Safety cap on total records fetched per poll cycle. */
        const val MAX_HISTORY_RECORDS_PER_POLL = 200

        /** Max time (ms) to spend fetching history logs per poll cycle.
         *  Keeps the slow loop responsive and avoids pump idle-timeout. */
        const val MAX_HISTORY_FETCH_DURATION_MS = 15_000L

        /** Fetch activity mode every N getBasalRate() calls to avoid doubling
         *  BLE round-trips. Mode changes (sleep/exercise) are infrequent; 3
         *  cycles (~45s at 15s poll) is responsive enough. */
        const val ACTIVITY_MODE_REFRESH_CYCLES = 3

        /** How many indices from the end of the range to scan for gap-fill.
         *  ~2000 indices covers roughly 12-24h of pump events depending on
         *  Control-IQ activity. Progressive scanning across poll cycles ensures
         *  the entire window is covered even with the 200 records/cycle cap. */
        const val HISTORY_LOOKBACK_INDICES = 2000

        /** Safety cap on total records fetched per poll cycle during full sync. */
        const val MAX_FULL_SYNC_RECORDS_PER_POLL = 500

        /** Max time (ms) to spend fetching history logs per poll cycle during
         *  full sync. Allows more time to download the full pump history. */
        const val MAX_FULL_SYNC_FETCH_DURATION_MS = 25_000L

        /**
         * Build the 5-byte cargo for opcode 60 (HistoryLogRequest).
         * Layout: uint32 LE startIndex + uint8 count.
         * Note: the pump treats this as a record INDEX, not an event sequence number.
         */
        fun buildHistoryLogCargo(startIndex: Int, count: Int): ByteArray {
            val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(startIndex)
            buf.put(count.coerceIn(1, 255).toByte())
            return buf.array()
        }
    }

    /**
     * Send a status request and parse the response. Wraps the entire
     * send-receive-parse cycle in a Result for safe error handling.
     * Logs parsed values and errors to the debug store.
     */
    private suspend fun <T> runStatusRequest(
        opcode: Int,
        cargo: ByteArray = ByteArray(0),
        timeoutMs: Long = TandemProtocol.STATUS_READ_TIMEOUT_MS,
        parser: (ByteArray) -> T,
    ): Result<T> {
        return try {
            val responseCargo = connectionManager.sendStatusRequest(opcode, cargo, timeoutMs)
            val result = parser(responseCargo)
            val parsedStr = result.toString()
            Timber.d("BLE_RAW PARSED opcode=0x%02x result=%s", opcode, parsedStr)
            debugLogger.updateLastPacket(opcode, direction = DebugLogger.Direction.RX, parsedValue = parsedStr)
            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "BLE_RAW PARSE_ERROR opcode=0x%02x", opcode)
            debugLogger.updateLastPacket(opcode, direction = DebugLogger.Direction.RX, error = e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }
}
