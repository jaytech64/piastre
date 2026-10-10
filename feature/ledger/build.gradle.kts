plugins {
    id("piastre.android.feature")
}

android {
    namespace = "io.github.jaytech64.piastre.feature.ledger"
}

dependencies {
    // The view model is exercised against a real database rather than a fake
    // repository: what it has to get right is grouping and totalling real rows.
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
}
