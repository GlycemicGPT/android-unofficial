import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

android {
    namespace = "com.glycemicgpt.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glycemicgpt.mobile"
        minSdk = 30
        targetSdk = 35

        val appVersionName = "0.15.0" // x-release-please-version
        val parts = appVersionName.split(".")
        val major = parts.getOrElse(0) { "0" }.toInt()
        val minor = parts.getOrElse(1) { "0" }.toInt()
        val patch = parts.getOrElse(2) { "0" }.toInt()

        versionCode = major * 1_000_000 + minor * 10_000 + patch
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Shared debug keystore for consistent signatures across CI and local
        // builds.  When the env var is absent (local dev), Gradle falls back to
        // the default ~/.android/debug.keystore automatically.
        val debugKsFile = System.getenv("DEBUG_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
        if (debugKsFile != null) {
            getByName("debug") {
                storeFile = file(debugKsFile)
                storePassword = requireNotNull(System.getenv("DEBUG_KEYSTORE_PASSWORD")) {
                    "DEBUG_KEYSTORE_PASSWORD must be set when DEBUG_KEYSTORE_FILE is provided"
                }
                keyAlias = requireNotNull(System.getenv("DEBUG_KEY_ALIAS")) {
                    "DEBUG_KEY_ALIAS must be set when DEBUG_KEYSTORE_FILE is provided"
                }
                keyPassword = requireNotNull(System.getenv("DEBUG_KEY_PASSWORD")) {
                    "DEBUG_KEY_PASSWORD must be set when DEBUG_KEYSTORE_FILE is provided"
                }
            }
        }

        create("release") {
            val ksFile = System.getenv("RELEASE_KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    // Medtronic read-only BLE driver kill switch. Default ON (the driver ships BETA, flag-gated);
    // build with MEDTRONIC_DRIVER_ENABLED=false to make the plugin invisible/inert (not selectable,
    // no pairing, no polling) without a code change -- the mobile analogue of the backend's
    // MEDTRONIC_CONNECT_ENABLED operator kill switch. Anything other than "false" keeps it enabled.
    val medtronicDriverEnabled = System.getenv("MEDTRONIC_DRIVER_ENABLED")?.lowercase() != "false"

    // SHA-256 of a committed per-buildType watch face asset, injected into
    // BuildConfig so WatchFacePusher's runtime tamper check is always in
    // lockstep with the bundled APK. Regenerate the assets with:
    //   ./gradlew -Pandroid.enableResourceOptimizations=false :watchface:updateAppWatchFaceAssets
    fun watchFaceAssetSha256(buildTypeName: String, flavor: String): String {
        val asset = file("src/$buildTypeName/assets/glycemicgpt-watchface-$flavor.apk")
        require(asset.isFile) { "Missing committed watch face asset: $asset" }
        return MessageDigest.getInstance("SHA-256")
            .digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
    }

    // Single source of truth for the BuildConfig-field-to-flavor mapping,
    // consumed by BOTH buildType blocks below so a field can never be wired
    // to the wrong flavor in just one of them. The drift gate
    // (WatchFacePusherTest) validates this mapping against the debug assets.
    val watchFaceHashFields = listOf(
        "WATCHFACE_DIGITAL_SHA256" to "digitalFull",
        "WATCHFACE_ANALOG_SHA256" to "analogMechanical",
    )

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "UPDATE_CHANNEL", "\"dev\"")
            buildConfigField("boolean", "MEDTRONIC_DRIVER_ENABLED", medtronicDriverEnabled.toString())
            val devBuildNumber = (project.findProperty("devBuildNumber") as? String)?.toIntOrNull() ?: 0
            buildConfigField("int", "DEV_BUILD_NUMBER", devBuildNumber.toString())
            watchFaceHashFields.forEach { (field, flavor) ->
                buildConfigField("String", field, "\"${watchFaceAssetSha256("debug", flavor)}\"")
            }

            // Sentry DSN is compiled in ONLY when a developer explicitly provides it at build time
            // (env SENTRY_DSN or -PsentryDsn), e.g. `op run -- ./gradlew assembleDebug` for local
            // testing. It is empty otherwise -> Sentry stays disabled. CI does NOT provide it, so
            // the published debug `dev-latest` APK ships with an empty DSN even though it is
            // downloadable. A DSN baked into any distributed client APK is extractable; keeping it
            // opt-in and local-only is the guarantee. See SentryInitializer.
            // A blank env var is treated as absent (so an exported-but-empty SENTRY_DSN= still
            // falls back to the -PsentryDsn property rather than forcing it empty).
            val sentryDsn = (System.getenv("SENTRY_DSN")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("sentryDsn") as? String)).orEmpty().trim()
            // Hard guard: never let a DSN ride along in a CI-produced (publishable) artifact.
            if (sentryDsn.isNotEmpty() && System.getenv("CI") == "true") {
                throw GradleException(
                    "SENTRY_DSN must not be set for CI builds: the debug APK is published as a " +
                        "downloadable artifact and the DSN would be extractable from it.",
                )
            }
            val sentryEnv = (System.getenv("SENTRY_ENVIRONMENT")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("sentryEnvironment") as? String))
                .orEmpty().trim().ifEmpty { "development" }
            // Escape backslash/quote so an unusual value can't break the generated Java literal.
            fun toJavaStringLiteral(value: String) =
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            buildConfigField("String", "SENTRY_DSN", toJavaStringLiteral(sentryDsn))
            buildConfigField("String", "SENTRY_ENVIRONMENT", toJavaStringLiteral(sentryEnv))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksFile = System.getenv("RELEASE_KEYSTORE_FILE")
            signingConfig = if (ksFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
            buildConfigField("int", "DEV_BUILD_NUMBER", "0")
            buildConfigField("boolean", "MEDTRONIC_DRIVER_ENABLED", medtronicDriverEnabled.toString())
            watchFaceHashFields.forEach { (field, flavor) ->
                buildConfigField("String", field, "\"${watchFaceAssetSha256("release", flavor)}\"")
            }

            // Never embed a Sentry DSN in a distributed/downloadable APK (it is client-extractable).
            buildConfigField("String", "SENTRY_DSN", "\"\"")
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"production\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        // Robolectric tests (real in-memory Room, e.g. SyncDaoTest) need Android
        // resources on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Default Android test JVM heap (512MB) is too small for our
            // mockk-based tests; relaxed mocks of large interfaces and
            // coroutine-aware tests accumulate enough heap pressure to OOM.
            it.maxHeapSize = "2g"

            // The watch face drift gate (WatchFacePusherTest) reads these
            // files straight from the repo, outside the test classpath.
            // Register them as task inputs so an up-to-date or build-cached
            // test result is invalidated when any of them changes -- without
            // this, editing a committed asset would not re-run the gate.
            it.inputs.dir("src/debug/assets")
            it.inputs.dir("src/release/assets")
            it.inputs.dir("../watchface/src/digitalFull/templates")
            it.inputs.dir("../watchface/src/analogMechanical/templates")
            it.inputs.file("../watchface/committed-assets.sha256")
            it.inputs.file("../wear-device/build.gradle.kts")

            // The in-app license viewer's drift gate (LicenseAssetsTest) compares
            // the packaged assets against these repo files. Register them so an
            // edit to a license document re-runs the gate instead of reusing a
            // cached green result.
            it.inputs.file("../README.md")
            it.inputs.file("../LICENSE")
            it.inputs.file("../docs/THIRD_PARTY_LICENSES.md")
            // The redistributed-component assets are generated rather than copied, so the gate
            // tracks the canonical licence texts they draw on and the generator's own output.
            // The generated directory is referenced through the task's own output provider, not
            // as a bare path, so Gradle sees the producer/consumer edge and re-runs the gate
            // when the generated documents change (a bare path would be an undeclared dependency).
            it.inputs.dir("../docs/licenses")
            it.inputs.dir(
                tasks.named<GenerateDependencyAttribution>("generateDependencyAttribution")
                    .flatMap { task -> task.outputDir },
            )
        }
    }

    // Expose the exported Room schemas to instrumented tests so MigrationTestHelper
    // can build historical schema versions for migration tests.
    sourceSets.getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
}

// Attribution for what the binaries redistribute, as opposed to the source lineage recorded
// in docs/THIRD_PARTY_LICENSES.md. Built from the release runtime graph of every module that
// ships an APK, so the set of components can never be a stale hand-maintained list.
//
// The three modules are read here rather than each generating its own document because the
// licence viewer lives in :app and has to speak for the whole release: five of the six
// upstream NOTICE files this discharges are reachable only through :wear-device.
val runtimeAttributionSources = listOf(
    AttributionSource(":app", "licenseeAndroidRelease", "releaseRuntimeClasspath"),
    AttributionSource(":wear-device", "licenseeAndroidRelease", "releaseRuntimeClasspath"),
    AttributionSource(
        ":watchface",
        "licenseeAndroidDigitalFullRelease",
        "digitalFullReleaseRuntimeClasspath",
    ),
    AttributionSource(
        ":watchface",
        "licenseeAndroidAnalogMechanicalRelease",
        "analogMechanicalReleaseRuntimeClasspath",
    ),
)

// Licence resolution for components whose POM declares no SPDX identifier. Licensee lets these
// through via the allowlist in the root build file, but that only settles policy; attribution
// still needs to know which terms to reproduce. Each maps a component to the licence text that
// covers it: an SPDX identifier for the ones whose licence simply was not machine-readable in
// the POM, or a bundled text of its own (BouncyCastle, CUP) for the two that are neither SPDX
// nor ship their text in the artifact. The generator fails on any unmapped component not listed
// here or in [unreproducedRuntimeLicenses], so a new dependency with an unreadable licence
// cannot slip through unattributed.
val unmappedRuntimeLicenses = mapOf(
    // JitPack builds publish no licence metadata; Apache-2.0 upstream.
    "com.github.jeziellago:Markwon:58aa5aba6a" to "Apache-2.0",
    "com.github.jeziellago:compose-markdown:0.5.8" to "Apache-2.0",
    "com.github.xgouchet:AXML:v1.0.1" to "Apache-2.0",
    // POM declares "The BSD License" as an unmapped name; upstream LICENSE is BSD-3-Clause.
    "com.twelvemonkeys.common:common-image:3.9.4" to "BSD-3-Clause",
    "com.twelvemonkeys.common:common-io:3.9.4" to "BSD-3-Clause",
    "com.twelvemonkeys.common:common-lang:3.9.4" to "BSD-3-Clause",
    "com.twelvemonkeys.imageio:imageio-core:3.9.4" to "BSD-3-Clause",
    "com.twelvemonkeys.imageio:imageio-metadata:3.9.4" to "BSD-3-Clause",
    "com.twelvemonkeys.imageio:imageio-webp:3.9.4" to "BSD-3-Clause",
    // POM declares the licence by URL rather than SPDX identifier.
    "com.atlassian.commonmark:commonmark:0.13.0" to "BSD-2-Clause",
    "com.atlassian.commonmark:commonmark-ext-gfm-strikethrough:0.13.0" to "BSD-2-Clause",
    "com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0" to "BSD-2-Clause",
    // Non-SPDX licences that ship no text in the artifact -- bundled under docs/licenses.
    "org.bouncycastle:bcprov-jdk18on:1.84" to "BouncyCastle",
    "edu.princeton.cup:java-cup:10k" to "CUP",
)

// Components whose licence terms are not reproduced as a bundled text here, with where they are.
// Google Play services ship under Google's proprietary Android SDK licence, which is not an
// open-source text to bundle; SQLCipher is reproduced in full under Third-Party Licenses.
val unreproducedRuntimeLicenses = mapOf(
    "com.google.android.gms:play-services-base:18.5.0"
        to "Android Software Development Kit License -- https://developer.android.com/studio/terms",
    "com.google.android.gms:play-services-basement:18.4.0"
        to "Android Software Development Kit License -- https://developer.android.com/studio/terms",
    "com.google.android.gms:play-services-tasks:18.2.0"
        to "Android Software Development Kit License -- https://developer.android.com/studio/terms",
    "com.google.android.gms:play-services-wearable:18.2.0"
        to "Android Software Development Kit License -- https://developer.android.com/studio/terms",
    "net.zetetic:sqlcipher-android:4.13.0"
        to "BSD-style Zetetic licence, reproduced in full under Third-Party Licenses above",
    "org.openminimed:javasake:0.2.0"
        to "GNU General Public License v3.0 -- the license this application ships under; " +
        "its full text is the GPL-3.0 document in this screen",
)

val generateDependencyAttribution =
    tasks.register<GenerateDependencyAttribution>("generateDependencyAttribution") {
        group = "build"
        description =
            "Builds the redistributed-dependency attribution documents from the release " +
                "runtime graph of every module that ships an APK."
        licenseTexts.set(rootProject.layout.projectDirectory.dir("docs/licenses"))
        outputDir.set(layout.buildDirectory.dir("generated/attribution"))
        unmappedLicenses.set(unmappedRuntimeLicenses)
        unreproducedLicenses.set(unreproducedRuntimeLicenses)

        runtimeAttributionSources.forEach { source ->
            // Cross-project reads: the sibling modules are applications, so :app cannot pull
            // their graphs in as ordinary dependencies. evaluationDependsOn guarantees AGP has
            // created the variant configurations before they are looked up here. This couples the
            // task to sibling internals and is not configuration-cache compatible, which is an
            // accepted tradeoff for keeping one viewer that speaks for the whole release.
            evaluationDependsOn(source.projectPath)
            val sourceProject = project(source.projectPath)

            licenseeReports.from(
                sourceProject.tasks.named(source.licenseeTask).map { it.outputs.files.asFileTree },
            )

            // Published archives only. Project dependencies are this repository's own GPL-3.0
            // modules -- already covered by the project's own notice, and excluded by Licensee
            // for the same reason -- and asking for their artifacts without naming an
            // artifactType is ambiguous across the variants AGP publishes for them. Resolution is
            // strict (no lenient view): an artifact that failed to resolve would silently drop its
            // NOTICE, so a resolution failure should break the build, not the attribution.
            val resolvedArtifacts = sourceProject.configurations
                .named(source.runtimeConfiguration)
                .flatMap { configuration ->
                    configuration.incoming
                        .artifactView { componentFilter { it is ModuleComponentIdentifier } }
                        .artifacts
                        .resolvedArtifacts
                }

            // Content-tracked for up-to-date checks. The coordinate-to-path map below is derived
            // from the same provider and used only to label notices at execution time, so it is
            // not itself a task input (absolute paths would defeat build-cache relocatability).
            artifactFiles.from(resolvedArtifacts.map { artifacts -> artifacts.map { it.file } })
            artifactsByCoordinates.putAll(
                resolvedArtifacts.map { artifacts ->
                    artifacts.associate { it.id.componentIdentifier.displayName to it.file.path }
                },
            )
        }
    }

// The in-app license viewer reads its text out of the APK's assets rather than from a
// hand-maintained copy in source, so the screen and the repository's license documents
// cannot drift apart: the assets are regenerated from the documents on every build.
val generateLicenseAssets = tasks.register<GenerateLicenseAssets>("generateLicenseAssets") {
    group = "build"
    description = "Copies the repository's license documents into the APK assets."
    readme.set(rootProject.layout.projectDirectory.file("README.md"))
    fullLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
    thirdPartyLicenses.set(rootProject.layout.projectDirectory.file("docs/THIRD_PARTY_LICENSES.md"))
    runtimeDependencies.set(
        generateDependencyAttribution.flatMap { it.componentsDocument },
    )
    runtimeDependencyLicenses.set(
        generateDependencyAttribution.flatMap { it.licenseTextDocument },
    )
}

androidComponents {
    onVariants { variant ->
        // Not a safe call: a variant that silently lost its asset sources would ship an APK
        // with no license text at all, and only the debug variant is covered by a test.
        val assets = checkNotNull(variant.sources.assets) {
            "No asset source for variant ${variant.name}; the license viewer needs one."
        }
        assets.addGeneratedSourceDirectory(generateLicenseAssets, GenerateLicenseAssets::outputDir)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Pump driver modules
    implementation(project(":pump-driver-api"))
    implementation(project(":tandem-pump-driver"))
    implementation(project(":medtronic-pump-driver"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room (local database)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (encrypted settings)
    implementation(libs.datastore.preferences)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Markdown rendering
    implementation(libs.compose.markdown)

    // Security
    implementation(libs.security.crypto)

    // Database encryption (SQLCipher)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)

    // Background work
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Logging
    implementation(libs.timber)

    // Crash/error reporting. The DSN is injected only into debug builds (see buildTypes); it is
    // never embedded in a distributed/release APK, where it would be client-extractable.
    implementation(libs.sentry.android)
    implementation(libs.sentry.android.timber)

    // Wearable Data Layer (phone-to-watch sync)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation("org.json:json:20240303")

    // Android tests
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.room.testing)
}

/**
 * Bundles the repository's license documents into the APK for the in-app license viewer.
 *
 * The copy happens on every build, so the documents stay the single source of truth and the
 * shipped screen cannot silently fall behind an edit to them. Two assets are whole files; the
 * third is the README's `## License` section excerpted unaltered. Nothing here rewrites content
 * for display -- that normalisation belongs to the UI layer (`stripUnresolvableLinks`), which
 * keeps every asset an exact substring of its source and lets `LicenseAssetsTest` gate them
 * with a plain equality check.
 */
@CacheableTask
abstract class GenerateLicenseAssets : DefaultTask() {

    /** Source of the project's own copyright and licensing notice (its `## License` section). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readme: RegularFileProperty

    /** Full GPL-3.0 text, as shipped at the repository root. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fullLicense: RegularFileProperty

    /** Components the APKs redistribute, generated by [GenerateDependencyAttribution]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeDependencies: RegularFileProperty

    /** License texts and upstream NOTICE content for those components. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeDependencyLicenses: RegularFileProperty

    /** Attributions for the upstream work this app ports, depends on, or was informed by. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thirdPartyLicenses: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val assetDir = outputDir.get().asFile.resolve(ASSET_DIR)
        assetDir.mkdirs()
        assetDir.resolve("project_license_notice.md")
            .writeText(licenseSectionOf(readme.get().asFile.readText()))
        assetDir.resolve("third_party_licenses.md")
            .writeText(contentOf(thirdPartyLicenses))
        assetDir.resolve("gpl-3.0.txt")
            .writeText(contentOf(fullLicense))
        assetDir.resolve("runtime_dependencies.md")
            .writeText(contentOf(runtimeDependencies))
        assetDir.resolve("runtime_dependency_licenses.txt")
            .writeText(contentOf(runtimeDependencyLicenses))
    }

    /**
     * Reads a document that ships whole. An empty one would be copied to an empty asset and
     * render as a blank section, so it fails the build here rather than in a test: this runs
     * for every variant, whereas `LicenseAssetsTest` only gates the ones that run unit tests.
     */
    private fun contentOf(document: RegularFileProperty): String {
        val file = document.get().asFile
        return file.readText().also {
            if (it.isBlank()) {
                throw GradleException(
                    "${file.name} is empty. Refusing to ship an APK whose licence screen " +
                        "would show a blank section in its place.",
                )
            }
        }
    }

    /**
     * Returns the README's `## License` section -- heading included, verbatim -- up to the
     * next second-level heading. A missing heading fails the build rather than shipping an
     * app whose only in-product licensing notice is empty.
     */
    private fun licenseSectionOf(readme: String): String {
        val lines = readme.lines()
        val heading = lines.indexOfFirst { it.trimEnd() == LICENSE_HEADING }
        if (heading < 0) {
            throw GradleException(
                "README.md has no '$LICENSE_HEADING' section. The in-app license viewer reads " +
                    "the project's licensing notice from it; restore the heading or update " +
                    "GenerateLicenseAssets to match the new structure.",
            )
        }
        val body = lines.drop(heading + 1)
        val next = body.indexOfFirst { it.startsWith("## ") }
        val section = if (next < 0) body else body.take(next)
        return (listOf(LICENSE_HEADING) + section).joinToString("\n").trimEnd() + "\n"
    }

    private companion object {
        const val ASSET_DIR = "licenses"
        const val LICENSE_HEADING = "## License"
    }
}

/** One APK-shipping variant whose resolved runtime graph must be attributed. */
data class AttributionSource(
    val projectPath: String,
    val licenseeTask: String,
    val runtimeConfiguration: String,
)

/**
 * Builds the attribution for everything the release APKs redistribute.
 *
 * The component list comes from Licensee's resolved-graph reports rather than from a document
 * someone remembers to update, so a dependency cannot enter the build without entering the
 * attribution: adding one changes the graph, which changes this task's inputs, which rewrites
 * the assets in the same build. Licensee's own allowlist (see the root build file) is the other
 * half of that gate -- it fails the build on a licence this project has not ruled on, so a
 * Renovate bump cannot quietly introduce a component whose terms nobody has read.
 *
 * Two documents come out, split the way the viewer already splits its assets: a markdown list
 * of components, and a plain-text file of license texts. License texts must not reach the
 * markdown renderer -- their numbered clauses parse as list markers and their indented lines as
 * code blocks, which is why the GPL is handled as plain text too.
 */
@CacheableTask
abstract class GenerateDependencyAttribution : DefaultTask() {

    /** Licensee `artifacts.json` reports, one per attributed variant. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseeReports: ConfigurableFileCollection

    /** Canonical license texts, one file per identifier bundled under `docs/licenses`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTexts: DirectoryProperty

    /** Resolved artifacts, tracked as files so a rebuilt dependency re-runs the scan. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifactFiles: ConfigurableFileCollection

    /**
     * Coordinates of components whose POM carries no SPDX identifier, mapped to the license text
     * that covers them (an SPDX identifier, or a bundled name such as `BouncyCastle`/`CUP`). The
     * build fails on any such component absent from this map and [unreproducedLicenses].
     */
    @get:Input
    abstract val unmappedLicenses: MapProperty<String, String>

    /** Coordinates whose terms are not reproduced here, mapped to a note on where they are. */
    @get:Input
    abstract val unreproducedLicenses: MapProperty<String, String>

    /**
     * Component coordinates to artifact paths, used only to attribute a NOTICE to its component
     * at execution time. Not a task input: the artifact *content* is already tracked by
     * [artifactFiles], and absolute paths would defeat build-cache relocatability.
     */
    @get:Internal
    abstract val artifactsByCoordinates: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    val componentsDocument: Provider<RegularFile>
        get() = outputDir.file("runtime_dependencies.md")

    @get:Internal
    val licenseTextDocument: Provider<RegularFile>
        get() = outputDir.file("runtime_dependency_licenses.txt")

    @TaskAction
    fun generate() {
        val components = readComponents()
        if (components.isEmpty()) {
            throw GradleException(
                "No components were read from the Licensee reports. The attribution document " +
                    "would ship empty, which reads as 'this app redistributes nothing'.",
            )
        }
        val notices = collectNotices()
        val destination = outputDir.get().asFile.apply { mkdirs() }
        destination.resolve("runtime_dependencies.md")
            .writeText(renderComponents(components, notices.keys))
        destination.resolve("runtime_dependency_licenses.txt")
            .writeText(renderLicenseTexts(components, notices))
    }

    /** A component as Licensee resolved it, keyed by coordinates so variants can overlap. */
    private data class Component(
        val coordinates: String,
        val name: String?,
        /** The heading the component is grouped under: its primary license, or a fixed label. */
        val heading: String,
        val scmUrl: String?,
        /** Every license identifier whose text must be bundled for this component. */
        val licenseIdentifiers: List<String>,
        /** For components not reproduced here, a note on where their terms live. */
        val externalTermsNote: String?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun readComponents(): List<Component> {
        val overrides = unmappedLicenses.get()
        val external = unreproducedLicenses.get()
        val slurper = JsonSlurper()
        val components = mutableMapOf<String, Component>()
        licenseeReports.files
            .filter { it.isFile && it.name == "artifacts.json" }
            .forEach { report ->
                val entries = slurper.parse(report) as List<Map<String, Any?>>
                entries.forEach { entry ->
                    val coordinates = listOf("groupId", "artifactId", "version")
                        .joinToString(":") { entry[it] as? String ?: "" }
                    val spdx = (entry["spdxLicenses"] as? List<Map<String, Any?>>).orEmpty()
                    // A component can declare more than one license (xml-apis is Apache-2.0 plus
                    // two SAX public-domain grants). All of them are terms it ships under, so all
                    // of their texts must be bundled; the first is only what it is grouped under.
                    val declared = spdx.mapNotNull { it["identifier"] as? String }.distinct()
                    val note = external[coordinates]
                    // Fall back to the human-classified override only when the POM declared no
                    // SPDX identifier; a component that is neither SPDX-declared nor classified is
                    // a fault to surface, not to paper over with an "undeclared" placeholder.
                    val identifiers = when {
                        declared.isNotEmpty() -> declared
                        overrides.containsKey(coordinates) -> listOf(overrides.getValue(coordinates))
                        note != null -> emptyList()
                        else -> throw GradleException(
                            "$coordinates declares no SPDX license and is not classified. Add it " +
                                "to unmappedLicenses (with the license whose text covers it) or " +
                                "unreproducedLicenses in app/build.gradle.kts so it cannot ship " +
                                "unattributed.",
                        )
                    }
                    components[coordinates] = Component(
                        coordinates = coordinates,
                        name = entry["name"] as? String,
                        heading = identifiers.firstOrNull() ?: EXTERNAL_TERMS_HEADING,
                        scmUrl = (entry["scm"] as? Map<String, Any?>)?.get("url") as? String,
                        licenseIdentifiers = identifiers,
                        externalTermsNote = note,
                    )
                }
            }
        return components.values.sortedBy { it.coordinates }
    }

    /**
     * Reads the NOTICE file out of each redistributed artifact that carries one, which is what
     * Apache-2.0 section 4(d) obliges this project to pass on. Only the archive's own top level
     * and `META-INF` are considered; a path deeper than that belongs to a bundled dependency and
     * is that dependency's notice to reproduce, not ours. For an AAR the real payload lives in a
     * nested `classes.jar`, so that jar's own `META-INF/NOTICE` is read as well.
     */
    private fun collectNotices(): Map<String, String> {
        val byPath = artifactsByCoordinates.get().entries.associate { it.value to it.key }
        return artifactFiles.files
            .filter { it.isFile && (it.extension == "jar" || it.extension == "aar") }
            .distinct()
            .mapNotNull { archive ->
                val notice = runCatching { noticeIn(archive) }.getOrElse { cause ->
                    // A classpath entry that is not readable as an archive is a packaging
                    // problem in its own right, but it must not silently drop attribution.
                    throw GradleException("Could not read $archive while collecting notices", cause)
                } ?: return@mapNotNull null
                val coordinates = byPath[archive.path] ?: archive.name
                coordinates to notice
            }
            .toMap()
            .toSortedMap()
    }

    private fun noticeIn(archive: File): String? {
        val notices = mutableListOf<String>()
        ZipFile(archive).use { zip ->
            zip.entries().toList()
                .filter { entry -> NOTICE_ENTRY.matches(entry.name) }
                .sortedBy { entry -> entry.name }
                .forEach { entry ->
                    zip.getInputStream(entry).use { it.noticeText() }?.let(notices::add)
                }
            if (archive.extension == "aar") {
                zip.getEntry("classes.jar")?.let { entry ->
                    zip.getInputStream(entry).use { notices += noticesInJar(it) }
                }
            }
        }
        return notices.joinToString("\n\n").takeIf { it.isNotEmpty() }
    }

    /** NOTICE entries inside a nested jar delivered as a stream (an AAR's `classes.jar`). */
    private fun noticesInJar(stream: InputStream): List<String> {
        val found = mutableListOf<Pair<String, String>>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (NOTICE_ENTRY.matches(entry.name)) {
                    zip.noticeText()?.let { found += entry!!.name to it }
                }
                entry = zip.nextEntry
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    private fun InputStream.noticeText(): String? =
        String(readBytes(), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }

    private fun renderComponents(
        components: List<Component>,
        noticed: Set<String>,
    ): String = buildString {
        appendLine("# Redistributed Components")
        appendLine()
        appendLine(
            "The application, watch app, and watch faces are built from the components listed " +
                "here, and the installed binaries contain them. This list is generated from the " +
                "resolved release dependency graph of every module that ships an APK, so it " +
                "describes the build you are running rather than a snapshot of some earlier one.",
        )
        appendLine()
        appendLine(
            "Source lineage -- protocol work this project studied, ported, or derived code from " +
                "-- is a separate matter, recorded under Third-Party Licenses.",
        )
        appendLine()
        appendLine(
            "The full text of each license, and the notices upstream authors ask redistributors " +
                "to pass on, follow this list.",
        )
        appendLine()
        appendLine("Components: ${components.size}.")

        components.groupBy { it.heading }
            .toSortedMap()
            .forEach { (heading, group) ->
                appendLine()
                appendLine("## $heading")
                appendLine()
                group.forEach { component ->
                    val label = component.name?.takeIf { it.isNotBlank() && it != component.coordinates }
                    val notice = if (component.coordinates in noticed) " -- carries a NOTICE" else ""
                    appendLine("- `${component.coordinates}`${label?.let { " -- $it" }.orEmpty()}$notice")
                    component.scmUrl?.takeIf { it.isNotBlank() }?.let { appendLine("  $it") }
                    // A component licensed under more than one set of terms is grouped under the
                    // first; name the others so a reader can find their text further down.
                    component.licenseIdentifiers.drop(1).takeIf { it.isNotEmpty() }?.let {
                        appendLine("  Also licensed under: ${it.joinToString(", ")}")
                    }
                    component.externalTermsNote?.let { appendLine("  Terms: $it") }
                }
            }
    }

    private fun renderLicenseTexts(
        components: List<Component>,
        notices: Map<String, String>,
    ): String = buildString {
        val identifiers = components.flatMap { it.licenseIdentifiers }.toSortedSet()
        val available = licenseTexts.get().asFile.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "txt" }
            .associateBy { it.nameWithoutExtension }

        // A license with no text on disk would be listed in the component document and then
        // silently omitted from the texts, which is the exact failure this ticket exists to fix.
        // Fail the build and make someone add the text instead.
        val missing = identifiers - available.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "No license text bundled for ${missing.joinToString(", ")}. Add the canonical " +
                    "text to docs/licenses/<identifier>.txt so the components under it are not " +
                    "shipped without their terms.",
            )
        }

        appendLine("LICENSE TEXTS AND UPSTREAM NOTICES")
        appendLine()
        appendLine(
            "The terms below cover the components in the redistributed-components list. A few " +
                "components carry proprietary or externally-hosted terms that are not reproduced " +
                "here; each is noted in that list with where its terms can be read.",
        )

        identifiers.forEach { identifier ->
            appendLine()
            appendLine(SEPARATOR)
            appendLine(identifier)
            appendLine(SEPARATOR)
            appendLine()
            appendLine(available.getValue(identifier).readText().trim())
        }

        if (notices.isNotEmpty()) {
            appendLine()
            appendLine(SEPARATOR)
            appendLine("UPSTREAM NOTICES")
            appendLine(SEPARATOR)
            appendLine()
            appendLine(
                "Reproduced from the NOTICE file each of these components ships, as their " +
                    "licenses require of anyone redistributing them.",
            )
            notices.forEach { (coordinates, notice) ->
                appendLine()
                appendLine("--- $coordinates ---")
                appendLine()
                appendLine(notice)
            }
        }
    }

    private companion object {
        const val EXTERNAL_TERMS_HEADING = "License terms available elsewhere"
        const val SEPARATOR = "================================================================"

        /**
         * A NOTICE at the archive root or in `META-INF`, with or without a text extension.
         * Anchored so a path such as `META-INF/notices/other/NOTICE` is not mistaken for the
         * archive's own notice.
         */
        val NOTICE_ENTRY = Regex("""(META-INF/)?NOTICE(\.(txt|md))?""", RegexOption.IGNORE_CASE)
    }
}
