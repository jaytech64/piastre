plugins {
    id("depenses.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Domain types only. No Android, no framework, nothing at runtime beyond
    // :core:model — which is what keeps the parser suite a sub-second loop.
    api(project(":core:model"))

    // Test-only, and solely to read the captured fixture corpus.
    testImplementation(libs.kotlinx.serialization.json)
}
