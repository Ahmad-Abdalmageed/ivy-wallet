plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.commands"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
    implementation(projects.shared.domain)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)

    implementation(libs.datastore)
    implementation(libs.bundles.ktor)

    testImplementation(projects.shared.ui.testing)
}
