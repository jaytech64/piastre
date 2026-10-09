plugins {
    id("piastre.android.feature")
}

android {
    namespace = "io.github.jaytech64.piastre.feature.settings"
}

dependencies {
    // Capture health is the capture module's own question — whether its listener
    // is still bound — so the answer lives there and this screen only shows it.
    implementation(project(":capture:listener"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
