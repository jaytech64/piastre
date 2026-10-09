plugins {
    id("piastre.android.library")
}

android {
    namespace = "io.github.jaytech64.piastre.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.room.testing)
    api(libs.androidx.test.core)
}
