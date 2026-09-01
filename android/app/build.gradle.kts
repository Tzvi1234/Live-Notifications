import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * A `-P` flag or gradle.properties entry, falling back to the same key in
 * local.properties.
 *
 * local.properties is the one file in an Android checkout that is git-ignored on every
 * machine, which makes it the obvious home for a per-instance key - but Gradle does not
 * load it into project properties itself, so it is read here. Missing is not an error:
 * every caller has a defined behaviour for an empty value.
 */
fun keyProperty(name: String): String =
    project.findProperty(name)?.toString()
        ?: Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }.getProperty(name)
        ?: ""

// Firebase is optional: the app builds and runs (polling only, no push) without a
// google-services.json. Drop the file in app/ and the plugin wires itself up.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "com.tzvi.kickoff"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tzvi.kickoff"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("boolean", "HAS_FIREBASE", hasFirebaseConfig.toString())

        // The backend this build ships pointed at. A fork changes one line here rather
        // than asking every user to paste a URL during onboarding.
        buildConfigField(
            "String",
            "DEFAULT_BACKEND_URL",
            "\"${project.findProperty("kickoff.backendUrl") ?: "https://kickoff-api-tato.onrender.com"}\"",
        )

        // Clerk's publishable key is not a secret, but it is per-instance, so it is not
        // committed either: set clerk.publishableKey in local.properties or in
        // ~/.gradle/gradle.properties. Left empty the app asks its backend for the key
        // instead - see AuthRepository - and runs without accounts if that fails too.
        buildConfigField(
            "String",
            "CLERK_PUBLISHABLE_KEY",
            "\"${keyProperty("clerk.publishableKey")}\"",
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Signed with the debug key so a release build is installable without a
            // keystore. The debug build is now over 30 MB unshrunk - the auth SDK's UI
            // module alone is three dex files - so this is the build that actually gets
            // handed to anyone. Replace this with a real signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
    }

    lint {
        // The app ships one language for now; a missing translation is not a finding.
        disable += setOf("MissingTranslation")
        // targetSdk deliberately trails compileSdk: 37 is compiled against for the newest
        // APIs, but opting the app into Android 17's behaviour changes is a separate,
        // reviewed decision.
        disable += setOf("OldTargetApi")
        abortOnError = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Clerk hosts accounts, sessions and token refresh; the server only ever verifies a
    // JWT, so no password ever reaches our own backend.
    implementation(libs.clerk.api)
    implementation(libs.clerk.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
