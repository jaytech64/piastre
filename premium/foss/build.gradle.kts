plugins {
    id("piastre.android.library")
    id("piastre.android.hilt")
}

android {
    namespace = "io.github.jaytech64.piastre.premium.foss"
}

dependencies {
    implementation(project(":core:data"))
}
