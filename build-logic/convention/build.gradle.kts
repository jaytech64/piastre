plugins {
    `kotlin-dsl`
}

group = "io.github.jaytech64.piastre.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "piastre.android.application"
            implementationClass = "io.github.jaytech64.piastre.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "piastre.android.library"
            implementationClass = "io.github.jaytech64.piastre.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "piastre.android.compose"
            implementationClass = "io.github.jaytech64.piastre.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "piastre.android.hilt"
            implementationClass = "io.github.jaytech64.piastre.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "piastre.android.feature"
            implementationClass = "io.github.jaytech64.piastre.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "piastre.jvm.library"
            implementationClass = "io.github.jaytech64.piastre.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
