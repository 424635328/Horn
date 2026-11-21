plugins {
    id("autohorn.android.library")
    id("autohorn.android.compose")
}

android {
    namespace = "com.industrial.autohorn.feature"
}

dependencies {
    implementation(project(":core:audio"))
    implementation(project(":core:data"))
    implementation(project(":core:haptic"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
