plugins {
    id("piastre.android.library")
    id("piastre.android.hilt")
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.jaytech64.piastre.core.database"

    // MigrationTestHelper reads the exported schemas through the asset manager, so
    // the committed JSON has to be reachable as an asset for the JVM tests too.
    // The Room plugin wires this up for instrumented tests only.
    sourceSets.getByName("test") { assets.srcDir("$projectDir/schemas") }
}

// Without this the asset merge can run before the schema for the version being
// added has been written, and the migration test fails on a clean checkout while
// passing on every incremental build after it — which is the worst way for a
// migration test to behave.
tasks
    .matching { it.name.startsWith("merge") && it.name.endsWith("UnitTestAssets") }
    .configureEach { dependsOn("copyRoomSchemas") }

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
