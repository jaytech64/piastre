plugins {
    id("piastre.android.library")
    id("piastre.android.compose")
}

android {
    namespace = "io.github.jaytech64.depenses.core.designsystem"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
