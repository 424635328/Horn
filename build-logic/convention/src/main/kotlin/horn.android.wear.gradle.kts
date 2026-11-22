import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Wear application baseline. Device-specific feature declarations remain in :wear's manifest.
// Keeping this convention separate makes it obvious that the watch app has a different minSdk
// and packaging profile from the phone app.
extensions.configure<ApplicationExtension> {
    defaultConfig.minSdk = 30
    defaultConfig.targetSdk = 36
    buildFeatures.buildConfig = false
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
