// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import com.glycemicgpt.mobile.contract.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Drift guard for the two ways a foreground service the app declares -- on the phone and on the
 * watch -- can get the whole process killed by the platform:
 *
 * - GLY-244: every `dataSync` service must override `Service.onTimeout(int, int)`.
 * - GLY-247: every service declaring any `foregroundServiceType` must override `onStartCommand`,
 *   so a `startForegroundService` aimed at it reaches `startForeground` rather than being left on
 *   the platform's stopwatch.
 *
 * Android 15 grants a single cumulative 6 h-per-24 h `dataSync` budget shared across all of
 * them, and a service that does not stop when the system calls `onTimeout` gets the whole
 * process killed with `RemoteServiceException` -- taking `PumpConnectionService` and the pump
 * connection with it. One service added later without the override reopens exactly that crash,
 * and nothing else in the build would catch it, so the manifest is checked against the sources
 * the way [com.glycemicgpt.mobile.contract.SafetyConstantDriftGuardTest] checks constants.
 *
 * Deliberately source-text based: it has to see `:wear-device` too, which has no Robolectric and
 * is not on this module's classpath. That is also why the watch services' foreground-counter
 * bookkeeping is pinned here rather than behaviourally -- there is no way to construct a
 * `WearableListenerService` in a `:wear-device` unit test.
 */
class DataSyncTimeoutCoverageTest {

    private data class Module(
        val name: String,
        val manifestPath: String,
        val namespace: String,
        val sourceRoot: String,
    )

    private val modules = listOf(
        Module(
            name = ":app",
            manifestPath = "app/src/main/AndroidManifest.xml",
            namespace = "com.glycemicgpt.mobile",
            sourceRoot = "app/src/main/java",
        ),
        Module(
            name = ":wear-device",
            manifestPath = "wear-device/src/main/AndroidManifest.xml",
            namespace = "com.glycemicgpt.weardevice",
            sourceRoot = "wear-device/src/main/java",
        ),
    )

    @Test
    fun `every declared dataSync foreground service overrides onTimeout`() {
        val checked = mutableListOf<String>()
        val missing = mutableListOf<String>()

        modules.forEach { module ->
            dataSyncServices(module).forEach { className ->
                val source = sourceFileFor(module, className)
                if (source == null) {
                    fail(
                        "${module.name} declares dataSync service $className but no Kotlin " +
                            "source was found under ${module.sourceRoot}. If it moved, update " +
                            "this guard.",
                    )
                    return@forEach
                }
                checked += "${module.name}/$className"
                if (!ON_TIMEOUT.containsMatchIn(source.readText())) {
                    missing += "${module.name}/$className (${source.path})"
                }
            }
        }

        assertTrue(
            "No dataSync services found in either manifest -- the guard stopped guarding.",
            checked.isNotEmpty(),
        )
        assertTrue(
            "These dataSync foreground services do not override onTimeout(startId, fgsType). " +
                "On Android 15 that is a fatal RemoteServiceException for the whole process " +
                "once the shared 6h/24h budget runs out (GLY-244): $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every declared foreground service answers a start command`() {
        val checked = mutableListOf<String>()
        val missing = mutableListOf<String>()

        modules.forEach { module ->
            foregroundServices(module).forEach { className ->
                val source = requireNotNull(sourceFileFor(module, className)) {
                    "${module.name} declares foreground service $className but no Kotlin source " +
                        "was found under ${module.sourceRoot}. If it moved, update this guard."
                }.readText()
                checked += "${module.name}/$className"
                if (!ON_START_COMMAND.containsMatchIn(source)) {
                    missing += "${module.name}/$className"
                }
            }
        }

        assertTrue(
            "No foreground services found in either manifest -- the guard stopped guarding.",
            checked.isNotEmpty(),
        )
        // A service declaring a foregroundServiceType can be handed a startForegroundService --
        // by the app itself, and for the exported ones (the Data Layer listeners) by anything on
        // the device. The platform then holds a stopwatch that ends in
        // ForegroundServiceDidNotStartInTimeException, killing the whole process, unless
        // onStartCommand calls startForeground. Inheriting WearableListenerService's
        // onStartCommand does not: that is how a start aimed at the chat relay killed the app ~30s
        // later while GLY-247's background wake-up paths were being validated. (Answering with a
        // bare stopSelf is not a substitute -- the platform crashes a service that stops while it
        // still owes a promotion, which took ~1.5s in the same experiment.) The pump and
        // alert-stream services promote and keep it; the Data Layer listeners promote and drop
        // straight back out, since their work only ever arrives over the GMS binding -- so the
        // guard checks that the override exists at all, and the per-service tests pin the answer.
        assertTrue(
            "These foreground services do not override onStartCommand(intent, flags, startId), " +
                "so a startForegroundService aimed at them is never answered and the platform " +
                "kills the process (GLY-247): $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the four services GLY-244 covers are still the dataSync ones`() {
        val declared = modules.flatMap { module -> dataSyncServices(module) }.toSet()

        assertEquals(
            setOf(
                "com.glycemicgpt.mobile.service.AlertStreamService",
                "com.glycemicgpt.mobile.wear.WearChatRelayService",
                "com.glycemicgpt.weardevice.push.WatchFaceReceiveService",
                "com.glycemicgpt.weardevice.update.WatchApkReceiveService",
            ),
            declared,
        )
    }

    @Test
    fun `the pump connection service is not on the dataSync budget`() {
        val pumpService = serviceTags(modules.first())
            .single { it.className == "com.glycemicgpt.mobile.service.PumpConnectionService" }

        // The pump link is what must survive a dataSync timeout. connectedDevice has its own
        // (unbounded) budget, so exhausting dataSync never reaches it -- if this ever flips to
        // dataSync, AC3 of GLY-244 is silently gone.
        assertEquals("connectedDevice", pumpService.foregroundServiceType)
    }

    @Test
    fun `the watch push counters clamp their finish path against the timeout reset`() {
        val wear = modules.single { it.name == ":wear-device" }

        WATCH_PUSH_SERVICES.forEach { className ->
            val source = requireNotNull(sourceFileFor(wear, className)) {
                "$className moved; update this guard"
            }.readText()

            // onTimeout zeroes the counter out-of-band while a push is still unwinding, so the
            // push's own finally must tolerate landing on zero. With an exact-equality test the
            // counter goes negative and stays there -- GMS keeps the instance bound, so every
            // later push skips tryPromoteToForeground and transfers with no foreground
            // protection. Silent: no crash, nothing else in the build notices.
            assertTrue(
                "$className: onTimeout must reset the push counter",
                TIMEOUT_COUNTER_RESET.containsMatchIn(source),
            )
            assertTrue(
                "$className: the finish path must clamp (decrementAndGet() <= 0, then set(0)), " +
                    "the way WearChatRelayService.finishWork does -- otherwise a timeout leaves " +
                    "the counter negative and later pushes never re-promote to foreground",
                CLAMPED_FINISH.containsMatchIn(source),
            )
            assertFalse(
                "$className: exact-equality finish path is back; see above",
                UNCLAMPED_FINISH.containsMatchIn(source),
            )
        }
    }

    @Test
    fun `the watch start commands promote without consulting the push counter`() {
        val wear = modules.single { it.name == ":wear-device" }

        WATCH_PUSH_SERVICES.forEach { className ->
            val source = requireNotNull(sourceFileFor(wear, className)) {
                "$className moved; update this guard"
            }.readText()
            val body = requireNotNull(START_COMMAND_BODY.find(source)?.groupValues?.get(1)) {
                "$className: could not read the onStartCommand body; update this guard"
            }

            assertTrue(
                "$className: onStartCommand must call tryPromoteToForeground",
                body.contains("tryPromoteToForeground()"),
            )
            // activePushCount counts transfers, not foreground state. A transfer whose promotion
            // the platform refused leaves the counter at 1 with the service in the background, so
            // a counter-gated start command skips the startForeground that answers the platform
            // and the process is killed ~30s later with ForegroundServiceDidNotStartInTimeException
            // (GLY-247). Promoting every time is free: startForeground on an already-foreground
            // service refreshes the same notification id.
            assertFalse(
                "$className: onStartCommand gates its promotion on the push counter again -- a " +
                    "transfer whose promotion was refused then costs the process",
                body.contains("getAndIncrement"),
            )
            // Naming getAndIncrement alone only rules out the form the bug arrived in: a
            // `if (activePushCount.get() == 0) tryPromoteToForeground()` reads differently and
            // fails identically. Requiring the promotion before the counter is consulted at all
            // rejects every counter-gated shape, while leaving the demotion below it free to keep
            // reading the counter -- which it must.
            val counterRead = body.indexOf("activePushCount")
            assertTrue(
                "$className: onStartCommand must keep gating its demotion on activePushCount -- " +
                    "an unconditional stopForeground drops a transfer already under way",
                counterRead >= 0,
            )
            assertTrue(
                "$className: onStartCommand must promote before it consults activePushCount",
                body.indexOf("tryPromoteToForeground()") < counterRead,
            )
        }
    }

    private data class ServiceTag(val className: String, val foregroundServiceType: String?)

    private fun dataSyncServices(module: Module): List<String> =
        serviceTags(module)
            // Trim each token: `dataSync | connectedDevice` is a legal manifest value, and an
            // untrimmed match would drop the service out of every assertion here without saying so.
            .filter { tag ->
                tag.foregroundServiceType?.split("|")?.any { it.trim() == "dataSync" } == true
            }
            .map { it.className }

    /** Every service that declares any foregroundServiceType, not just the `dataSync` ones. */
    private fun foregroundServices(module: Module): List<String> =
        serviceTags(module).filter { it.foregroundServiceType != null }.map { it.className }

    private fun serviceTags(module: Module): List<ServiceTag> {
        val manifest = ContractFixtures.readRepoFile(module.manifestPath)
        return SERVICE_TAG.findAll(manifest).mapNotNull { match ->
            val tag = match.value
            val name = NAME_ATTR.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            ServiceTag(
                // Manifest names are namespace-relative (`.service.Foo`).
                className = if (name.startsWith(".")) module.namespace + name else name,
                foregroundServiceType = FGS_TYPE_ATTR.find(tag)?.groupValues?.get(1),
            )
        }.toList()
    }

    private fun sourceFileFor(module: Module, className: String): File? =
        File(ContractFixtures.repoRoot(), sourcePathFor(module, className)).takeIf { it.isFile }

    private fun sourcePathFor(module: Module, className: String): String =
        "${module.sourceRoot}/${className.replace('.', '/')}.kt"

    private companion object {
        val SERVICE_TAG = Regex("<service\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
        val NAME_ATTR = Regex("android:name\\s*=\\s*\"([^\"]+)\"")
        val FGS_TYPE_ATTR = Regex("android:foregroundServiceType\\s*=\\s*\"([^\"]+)\"")
        val ON_TIMEOUT = Regex("override\\s+fun\\s+onTimeout\\s*\\(\\s*\\w+\\s*:\\s*Int\\s*,")
        val ON_START_COMMAND = Regex("override\\s+fun\\s+onStartCommand\\s*\\(")

        /** The override's body, up to the closing brace at method indentation. */
        val START_COMMAND_BODY = Regex(
            "override\\s+fun\\s+onStartCommand\\s*\\([^)]*\\)\\s*:\\s*Int\\s*\\{(.*?)\\n {4}\\}",
            RegexOption.DOT_MATCHES_ALL,
        )

        val WATCH_PUSH_SERVICES = listOf(
            "com.glycemicgpt.weardevice.push.WatchFaceReceiveService",
            "com.glycemicgpt.weardevice.update.WatchApkReceiveService",
        )
        val TIMEOUT_COUNTER_RESET = Regex("activePushCount\\.set\\(0\\)")
        val CLAMPED_FINISH = Regex(
            "activePushCount\\.decrementAndGet\\(\\)\\s*<=\\s*0\\s*\\)\\s*\\{" +
                "\\s*activePushCount\\.set\\(0\\)",
        )
        val UNCLAMPED_FINISH = Regex("activePushCount\\.decrementAndGet\\(\\)\\s*==\\s*0")
    }
}
