import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.io.StringReader
import java.util.Properties

plugins {
    id("piastre.android.application")
    id("piastre.android.compose")
    id("piastre.android.hilt")
}

/**
 * The key a Play release is uploaded with, read from outside the repository.
 *
 * Play App Signing holds the key users' installs are signed with; this one only
 * proves an upload came from the maintainer, and Play Console can reset it if it
 * is lost. Without the file, as on CI, a release builds unsigned, which Play
 * refuses — so a missing key cannot reach a tester by accident.
 */
val uploadKeystore: Properties? =
    providers
        .fileContents(
            layout.projectDirectory.file(
                "${System.getProperty("user.home")}/.config/piastre/upload-keystore.properties",
            ),
        ).asText.orNull
        ?.let { text -> Properties().apply { load(StringReader(text)) } }

fun Properties.required(key: String): String =
    checkNotNull(getProperty(key)) { "upload-keystore.properties has no $key." }

android {
    namespace = "io.github.jaytech64.piastre"

    defaultConfig {
        applicationId = "io.github.jaytech64.piastre"
        // 1 is the billing spike's bundle, still on Play's internal track.
        versionCode = 2
        versionName = "0.1.0"
        resourceConfigurations += setOf("en", "fr")
    }

    signingConfigs {
        uploadKeystore?.let { keystore ->
            create("upload") {
                storeFile = file(keystore.required("storeFile"))
                storePassword = keystore.required("storePassword")
                keyAlias = keystore.required("keyAlias")
                keyPassword = keystore.required("keyPassword")
            }
        }
    }

    // One codebase, two stores (ADR-0022). Each flavour brings the one module
    // that answers for its store, so the billing library is on the Play build's
    // classpath by construction, never by care. Both keep the same applicationId,
    // because Android files the ledger under it (ADR-0021).
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            // Debug keeps its own key: a build type's signing wins over a flavour's.
            signingConfig = signingConfigs.findByName("upload")
        }
        create("foss") { dimension = "store" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":capture:listener"))
    implementation(project(":feature:ledger"))
    implementation(project(":feature:reports"))
    implementation(project(":feature:review"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:premium"))
    "playImplementation"(project(":premium:play"))
    "fossImplementation"(project(":premium:foss"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}

/**
 * The privacy claim, enforced instead of documented.
 *
 * This app has no network code and must never gain any — not directly, and not
 * by a dependency quietly merging a permission into the manifest. Checking the
 * *merged* manifest is what catches the second case.
 */
val forbiddenPermissions =
    listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

androidComponents.onVariants { variant ->
    val verify =
        tasks.register("verify${variant.name.replaceFirstChar(Char::uppercase)}HasNoNetworkPermission") {
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            val forbidden = forbiddenPermissions
            inputs.file(manifest)
            outputs.upToDateWhen { true }
            doLast {
                val text = manifest.get().asFile.readText()
                val found = forbidden.filter { it in text }
                check(found.isEmpty()) {
                    "Merged manifest requests ${found.joinToString()}. This app stores financial data " +
                        "locally and ships with no network access; remove the permission or the dependency " +
                        "that pulls it in."
                }
            }
        }
    tasks.named("check").configure { dependsOn(verify) }
}

/**
 * The F-Droid build carries no Google store code and asks for none of what the
 * Play build needs in order to sell. F-Droid refuses a proprietary library, and
 * the people who install from it chose it to be rid of exactly that, so a
 * dependency that drags any of it in fails the build rather than the listing.
 */
val googleStoreGroups =
    listOf(
        "com.android.billingclient",
        "com.google.android.gms",
        "com.google.android.datatransport",
        "com.google.firebase",
    )
val playOnlyPermissions =
    listOf(
        "com.android.vending.BILLING",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    )

androidComponents.onVariants(androidComponents.selector().withFlavor("store" to "foss")) { variant ->
    val verify =
        tasks.register("verify${variant.name.replaceFirstChar(Char::uppercase)}HasNoGoogleStoreCode") {
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            val groups = googleStoreGroups
            val permissions = playOnlyPermissions
            val modules =
                variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                    val seen = mutableSetOf<ResolvedComponentResult>()
                    val pending = ArrayDeque(listOf(root))
                    while (pending.isNotEmpty()) {
                        val component = pending.removeFirst()
                        if (seen.add(component)) {
                            component.dependencies
                                .filterIsInstance<ResolvedDependencyResult>()
                                .forEach { pending.add(it.selected) }
                        }
                    }
                    seen
                        .map { it.id }
                        .filterIsInstance<ModuleComponentIdentifier>()
                        .map { "${it.group}:${it.module}" }
                }
            inputs.file(manifest)
            inputs.property("modules", modules)
            outputs.upToDateWhen { true }
            doLast {
                val text = manifest.get().asFile.readText()
                val asked = permissions.filter { it in text }
                val google = modules.get().filter { module -> groups.any { module.startsWith("$it:") } }
                check(asked.isEmpty() && google.isEmpty()) {
                    "The F-Droid build must carry no Google store code. " +
                        "Permissions: ${asked.ifEmpty { listOf("none") }.joinToString()}. " +
                        "Modules: ${google.ifEmpty { listOf("none") }.joinToString()}."
                }
            }
        }
    tasks.named("check").configure { dependsOn(verify) }
}
