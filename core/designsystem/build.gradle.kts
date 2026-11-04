plugins {
    id("piastre.android.library")
    id("piastre.android.compose")
}

android {
    namespace = "io.github.jaytech64.piastre.core.designsystem"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    // The window a brand screen sets its system bar icons on.
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
