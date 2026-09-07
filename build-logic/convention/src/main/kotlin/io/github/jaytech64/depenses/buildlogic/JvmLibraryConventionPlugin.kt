package io.github.jaytech64.depenses.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * A module with no Android SDK on its classpath. That is the point: the parser
 * layer is tested on the JVM in milliseconds, with no emulator and no Robolectric.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        // for the api configuration: a module re-exporting a domain type must say so
        pluginManager.apply("java-library")
        configureKotlinJvm()

        dependencies {
            add("testImplementation", "org.jetbrains.kotlin:kotlin-test:${libs.version("kotlin")}")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
