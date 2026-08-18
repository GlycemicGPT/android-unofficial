// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import com.glycemicgpt.mobile.contract.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Drift guard for GLY-244: every `dataSync` foreground service the app declares -- on the phone
 * and on the watch -- must override `Service.onTimeout(int, int)`.
 *
 * Android 15 grants a single cumulative 6 h-per-24 h `dataSync` budget shared across all of
 * them, and a service that does not stop when the system calls `onTimeout` gets the whole
 * process killed with `RemoteServiceException` -- taking `PumpConnectionService` and the pump
 * connection with it. One service added later without the override reopens exactly that crash,
 * and nothing else in the build would catch it, so the manifest is checked against the sources
 * the way [com.glycemicgpt.mobile.contract.SafetyConstantDriftGuardTest] checks constants.
 *
 * Deliberately source-text based: it has to see `:wear-device` too, which has no Robolectric and
 * is not on this module's classpath.
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

    private data class ServiceTag(val className: String, val foregroundServiceType: String?)

    private fun dataSyncServices(module: Module): List<String> =
        serviceTags(module)
            .filter { it.foregroundServiceType?.split("|")?.contains("dataSync") == true }
            .map { it.className }

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
    }
}
