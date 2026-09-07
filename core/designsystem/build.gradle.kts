plugins {
    id("depenses.android.library")
    id("depenses.android.compose")
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
