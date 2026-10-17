plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.voicecommands"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)
    implementation(projects.widget.balance)

    implementation(libs.androidx.security)
    implementation(libs.bundles.ktor)

    testImplementation(projects.shared.ui.testing)
}
