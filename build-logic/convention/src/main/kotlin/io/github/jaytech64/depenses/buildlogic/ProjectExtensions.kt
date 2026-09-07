// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.jaytech64.depenses.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(name: String): String =
    findVersion(name).get().requiredVersion

/**
 * One JVM target for every module, Android or not, so a class compiled in the
 * pure-JVM parser modules is loadable by the Android ones without surprise.
 */
internal object JavaConfig {
    val target: JavaVersion = JavaVersion.VERSION_17
    val toolchain: Int = 17
    val jvmTarget: JvmTarget = JvmTarget.JVM_17
}

internal fun Project.configureKotlinAndroid(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()

        defaultConfig {
            minSdk = libs.version("minSdk").toInt()
        }

        compileOptions {
            sourceCompatibility = JavaConfig.target
            targetCompatibility = JavaConfig.target
        }
    }

    extensions.getByType<KotlinAndroidProjectExtension>().apply {
        jvmToolchain(JavaConfig.toolchain)
        compilerOptions {
            jvmTarget.set(JavaConfig.jvmTarget)
            allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false))
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.getByType<JavaPluginExtension>().apply {
        sourceCompatibility = JavaConfig.target
        targetCompatibility = JavaConfig.target
    }
    extensions.getByType<KotlinJvmProjectExtension>().apply {
        jvmToolchain(JavaConfig.toolchain)
        compilerOptions {
            jvmTarget.set(JavaConfig.jvmTarget)
        }
    }
}
