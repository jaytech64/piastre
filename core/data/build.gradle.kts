plugins {
    id("depenses.android.library")
    id("depenses.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.jaytech64.depenses.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":capture:parsing"))
    implementation(project(":core:database"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // The export is a file you keep: merchant names carry quotes and accents, and
    // hand-rolled escaping is the bug you find a year later in a file that will
    // not load. The library is already in the catalog and in the listener module.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(testFixtures(project(":capture:parsing")))
    testImplementation(libs.robolectric)
}
