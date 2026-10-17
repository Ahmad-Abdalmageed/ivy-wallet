plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.voicecommands"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.data.model)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)

    implementation(libs.androidx.security)

    testImplementation(projects.shared.ui.testing)
}
