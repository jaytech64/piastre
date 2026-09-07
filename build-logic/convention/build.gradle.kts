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
            implementationClass = "io.github.jaytech64.depenses.build.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "depenses.android.library"
            implementationClass = "io.github.jaytech64.depenses.build.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "depenses.android.compose"
            implementationClass = "io.github.jaytech64.depenses.build.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "depenses.android.hilt"
            implementationClass = "io.github.jaytech64.depenses.build.AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "depenses.android.feature"
            implementationClass = "io.github.jaytech64.depenses.build.AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "depenses.jvm.library"
            implementationClass = "io.github.jaytech64.depenses.build.JvmLibraryConventionPlugin"
        }
    }
}
