plugins {
    id("depenses.android.library")
    id("depenses.android.hilt")
}

android {
    namespace = "io.github.jaytech64.depenses.capture.listener"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The stored payload is user text: merchant names carry quotes, apostrophes
    // and accents. Hand-rolled escaping is the kind of bug you find months later
    // in a fixture that will not parse, and the library is already in the catalog.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
