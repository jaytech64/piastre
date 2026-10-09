plugins {
    id("piastre.android.feature")
}

android {
    namespace = "io.github.jaytech64.piastre.feature.reports"
}

dependencies {
    // Every figure on this screen is the result of a GROUP BY, so the view model
    // is exercised against a real SQLite rather than a fake repository.
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
}
