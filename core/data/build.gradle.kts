plugins {
    id("depenses.android.library")
    id("depenses.android.hilt")
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

    testImplementation(project(":core:testing"))
    testImplementation(testFixtures(project(":capture:parsing")))
    testImplementation(libs.robolectric)
}
