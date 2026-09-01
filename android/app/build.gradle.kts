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

/**
 * The side-by-side flavours, and the one thing that stops them building.
 *
 * Firebase issues its config per package name and the google-services plugin fails the
 * build outright when no client matches - so `com.tzvi.kickoff.alpha` has nowhere to read
 * a config from and takes the whole build down with it. These flavours do not need push:
 * they exist to sit beside the real app and play the prediction game, and each one's own
 * poller keeps its live cards ticking without a single notification from a server.
 *
 * So each gets a copy of the real config with its own package name written in, purely to
 * satisfy the plugin. The app id inside it is not registered with Firebase, which means a
 * token request fails and push quietly never starts - exactly the intended behaviour, and
 * why HAS_FIREBASE is false for them below.
 */
fun writeFlavourFirebaseConfig(flavour: String, suffix: String) {
    if (!hasFirebaseConfig) return
    val source = file("google-services.json").readText()
    val target = file("src/$flavour/google-services.json")
    target.parentFile.mkdirs()
    val rewritten = source.replace(
        "\"package_name\": \"com.tzvi.kickoff\"",
        "\"package_name\": \"com.tzvi.kickoff$suffix\"",
    )
    if (!target.exists() || target.readText() != rewritten) target.writeText(rewritten)
}

writeFlavourFirebaseConfig("alpha", ".alpha")
writeFlavourFirebaseConfig("beta", ".beta")

android {
    namespace = "com.tzvi.kickoff"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tzvi.kickoff"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "2.5.2"
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

    /**
     * A key per flavour, so the three can genuinely coexist.
     *
     * Different application ids are what let Android install them side by side; different
     * KEYS are what keep them apart afterwards. Clerk ships a shared-session content
     * provider whose reads are restricted to callers signed with the same certificate, so
     * three builds sharing one key would share one signed-in session - and the whole point
     * of the exercise is three members with three accounts.
     *
     * The passwords are in the repository on purpose: these sign throwaway test builds
     * that must never reach a store, and a keystore nobody can open is a keystore that
     * stops the next person from building. The real release key is not one of these.
     */
    signingConfigs {
        create("alpha") {
            storeFile = rootProject.file("keystores/alpha.jks")
            storePassword = "matchup"
            keyAlias = "alpha"
            keyPassword = "matchup"
        }
        create("beta") {
            storeFile = rootProject.file("keystores/beta.jks")
            storePassword = "matchup"
            keyAlias = "beta"
            keyPassword = "matchup"
        }
    }

    /**
     * Three installable copies of the app, so one phone can hold a whole prediction group.
     *
     * The game is the one feature that cannot be tested alone: a leaderboard needs
     * opponents, a chat needs somebody to talk to, and an invite link needs somewhere to
     * land. Android keys an installation by applicationId, so three ids means three icons
     * side by side, each with its own account, its own storage and its own notifications.
     *
     * They are also SIGNED DIFFERENTLY on purpose. Two APKs with the same id cannot both
     * be installed whatever else differs, and two with different ids but the same key can
     * still read each other's shared-session provider - which would let the "friend" share
     * the owner's Clerk session and defeat the whole exercise.
     */
    flavorDimensions += "identity"

    productFlavors {
        create("standard") {
            dimension = "identity"
            // No suffix: this is the real app, and its id is the one Firebase and Clerk
            // are configured for.
            //
            // Still the debug key, so a release build stays installable without a keystore.
            // Replace this with a real signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
        create("alpha") {
            dimension = "identity"
            applicationIdSuffix = ".alpha"
            versionNameSuffix = "-alpha"
            signingConfig = signingConfigs.getByName("alpha")
            // Push is Firebase's, and Firebase does not know this id. Saying so here keeps
            // the app from waiting on a token that will never arrive.
            buildConfigField("boolean", "HAS_FIREBASE", "false")
        }
        create("beta") {
            dimension = "identity"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            signingConfig = signingConfigs.getByName("beta")
            buildConfigField("boolean", "HAS_FIREBASE", "false")
        }
    }

    buildTypes {
        debug {
            // No applicationId suffix. Firebase issues its config per package name, and
            // Clerk's OAuth redirect host is derived from the same value, so a suffixed
            // debug build would need its own client registered in both - and, side by
            // side on a phone, two matchUP icons that are impossible to tell apart. One
            // id, one install; the version name is what says which build you are on.
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // NO signingConfig here on purpose. A build type's signing config OVERRIDES
            // the flavour's, so setting it at this level silently signed all three
            // flavours with one key - which is exactly the thing the flavours exist to
            // avoid, and it is invisible until you compare certificates. Each flavour
            // names its own key instead; see productFlavors.
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
