plugins {
    id("piastre.android.library")
    id("piastre.android.hilt")
}

android {
    namespace = "io.github.jaytech64.piastre.premium.play"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.android.billing)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
