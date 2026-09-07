plugins {
    `kotlin-dsl`
}

group = "io.github.jaytech64.depenses.buildlogic"

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
            id = "depenses.android.application"
            implementationClass = "io.github.jaytech64.depenses.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "depenses.android.library"
            implementationClass = "io.github.jaytech64.depenses.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "depenses.android.compose"
            implementationClass = "io.github.jaytech64.depenses.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "depenses.android.hilt"
            implementationClass = "io.github.jaytech64.depenses.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "depenses.android.feature"
            implementationClass = "io.github.jaytech64.depenses.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "depenses.jvm.library"
            implementationClass = "io.github.jaytech64.depenses.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
