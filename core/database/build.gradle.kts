plugins {
    id("depenses.android.library")
    id("depenses.android.hilt")
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.jaytech64.depenses.core.database"
}

room {
    // Committed to the repository: a schema you can diff is what makes a
    // migration reviewable instead of a leap of faith.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))

    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
