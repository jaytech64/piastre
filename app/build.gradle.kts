import com.android.build.api.artifact.SingleArtifact

plugins {
    id("piastre.android.application")
    id("piastre.android.compose")
    id("piastre.android.hilt")
}

android {
    namespace = "io.github.jaytech64.piastre"

    defaultConfig {
        applicationId = "io.github.jaytech64.piastre"
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += setOf("en", "fr")
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
