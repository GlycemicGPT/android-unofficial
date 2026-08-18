package com.glycemicgpt.mobile.presentation.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.glycemicgpt.mobile.data.local.BleDebugStore
import com.glycemicgpt.mobile.domain.model.ConnectionState
import com.glycemicgpt.mobile.service.PollLoop
import com.glycemicgpt.mobile.service.PollLoopHealth
import com.glycemicgpt.mobile.service.PollStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())

@Composable
fun BleDebugScreen(
    viewModel: BleDebugViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val loopHealth by viewModel.pollLoopHealth.collectAsState()
    val armedFault by viewModel.armedPollFault.collectAsState()
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "BLE Debug",
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Debug harness: seed a fresh CGM reading so the emulator (no live
                // pump feed) can render the glucose hero and exercise the staleness/de-emphasis path.
                OutlinedButton(
                    onClick = viewModel::injectTestCgm,
                    modifier = Modifier.testTag("inject_test_cgm_button"),
                ) {
                    Text("Inject CGM")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = viewModel::clearEntries) {
                    Text("Clear")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Alert-floor harness (GLY-115): with the backend fault toggles on, a fresh LOW must
        // fire the floor alarm; a stale LOW must be suppressed with the "NOT watching" surface.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = viewModel::injectTestLowCgm,
                modifier = Modifier.testTag("inject_low_cgm_button"),
            ) {
                Text("Inject LOW (fresh)")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = viewModel::injectTestStaleLowCgm,
                modifier = Modifier.testTag("inject_stale_low_cgm_button"),
            ) {
                Text("Inject LOW (stale)")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Outage-retention harness (GLY-152): seed sync-queue rows the emulator can't
        // produce (no BLE pump), so the survives-an-outage / drains-on-reconnect E2E
        // is reproducible with the unreachable fault toggle.
        OutlinedButton(
            onClick = viewModel::seedSyncQueue,
            modifier = Modifier.testTag("seed_sync_queue_button"),
        ) {
            Text("Seed sync queue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Connection state indicator
        ConnectionStateBar(connectionState)

        Spacer(modifier = Modifier.height(8.dp))

        // Poll-loop liveness + fault injection (GLY-249): watch a chosen step (or a whole loop
        // body) throw on a real device and confirm the loop keeps its heartbeat / gets restarted.
        PollLoopHealthPanel(
            health = loopHealth,
            armedFault = armedFault,
            onArmFault = viewModel::setArmedPollFault,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${entries.size} entries",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("debug_entry_count"),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Entries list (newest first)
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("debug_entries_list"),
        ) {
            items(entries.reversed()) { entry ->
                DebugEntryCard(entry)
            }
        }
    }
}

/**
 * Liveness readout for the three poll loops plus the fault-injection selector.
 *
 * The heartbeat line is the point: a loop whose steps keep throwing shows a frozen "last ok",
 * a restarted loop shows a non-zero restart count, and a stopped loop says so instead of looking
 * dead. This is the same state the polling watchdog judges liveness by.
 */
@Composable
private fun PollLoopHealthPanel(
    health: Map<PollLoop, PollLoopHealth>,
    armedFault: String,
    onArmFault: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("poll_loop_health_panel"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = "Poll loops", style = MaterialTheme.typography.labelLarge)
            PollLoop.entries.forEach { loop ->
                val loopHealth = health[loop] ?: PollLoopHealth(loop)
                Text(
                    text = buildString {
                        append(loop.telemetryName)
                        append(if (loopHealth.running) " · running" else " · stopped")
                        append(" · last ok ")
                        append(
                            loopHealth.lastSuccessAtMs
                                ?.let { timeFormatter.format(Instant.ofEpochMilli(it)) }
                                ?: "never",
                        )
                        append(" · ok=${loopHealth.successCount}")
                        append(" fail=${loopHealth.failureCount}")
                        // Failures since the heartbeat last moved: on a broken loop this is the
                        // length of the CURRENT outage, which the lifetime count alone hides.
                        if (loopHealth.failuresSinceLastSuccess > 0) {
                            append("(${loopHealth.failuresSinceLastSuccess} since ok)")
                        }
                        append(" restarts=${loopHealth.restartCount}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("poll_loop_health_${loop.telemetryName}"),
                )
                loopHealth.lastFailureMessage?.let { message ->
                    Text(
                        text = "  last failure" +
                            (loopHealth.lastFailureStep?.let { " @${it.telemetryName}" } ?: "") +
                            ": $message",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                text = "Inject fault (armed: ${armedFault.ifEmpty { "none" }})",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .testTag("poll_fault_selector"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PollFaultChip("none", "", armedFault, onArmFault)
                PollLoop.entries.forEach { loop ->
                    PollFaultChip(
                        label = "${loop.telemetryName} loop",
                        key = loop.loopFaultKey,
                        armedFault = armedFault,
                        onArmFault = onArmFault,
                    )
                }
                PollStep.entries.forEach { step ->
                    PollFaultChip(
                        label = step.telemetryName,
                        key = step.telemetryName,
                        armedFault = armedFault,
                        onArmFault = onArmFault,
                    )
                }
            }
        }
    }
}

@Composable
private fun PollFaultChip(
    label: String,
    key: String,
    armedFault: String,
    onArmFault: (String) -> Unit,
) {
    FilterChip(
        selected = armedFault == key,
        onClick = { onArmFault(key) },
        label = { Text(text = label, fontSize = 11.sp) },
        modifier = Modifier.testTag("poll_fault_chip_${key.ifEmpty { "none" }}"),
    )
}

@Composable
private fun ConnectionStateBar(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.SCANNING, ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING ->
            MaterialTheme.colorScheme.tertiary
        ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.secondary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
        ConnectionState.AUTH_FAILED -> MaterialTheme.colorScheme.error
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("debug_connection_state"),
    ) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.name,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun DebugEntryCard(entry: BleDebugStore.Entry) {
    val dirColor = when (entry.direction) {
        BleDebugStore.Direction.TX -> MaterialTheme.colorScheme.primary
        BleDebugStore.Direction.RX -> MaterialTheme.colorScheme.tertiary
    }
    val dirLabel = when (entry.direction) {
        BleDebugStore.Direction.TX -> "TX"
        BleDebugStore.Direction.RX -> "RX"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header row: direction, opcode, time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dirLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = dirColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.opcodeName,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "(0x${entry.opcode.toString(16).padStart(2, '0')})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    text = timeFormatter.format(entry.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }

            // txId + cargo size
            Text(
                text = "txId=${entry.txId}  cargo=${entry.cargoSize}B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )

            // Cargo hex (horizontally scrollable for long payloads)
            if (entry.cargoHex.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = entry.cargoHex,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            // Parsed value or error
            entry.parsedValue?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            entry.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
