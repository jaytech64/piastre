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
}
