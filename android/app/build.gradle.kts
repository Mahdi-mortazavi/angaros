import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The product name lives here only: it becomes @string/brand (app_name aliases it) and BuildConfig.BRAND.
// applicationId, the namespace and the pb1: protocol keep the old "phonebridge" identifiers on purpose.
val brand = "Angaros"
val appVersion = "3.0.3" // x-release-please-version
// versionCode = major*10000 + minor*100 + patch (3.0.1 -> 30001). v3.0.0 shipped as 3, below 2.1.0's code, which
// Android refused to install as a downgrade; a derived code can only go up.
val appVersionCode = appVersion.split('.').map(String::toInt).let { (ma, mi, pa) ->
    require(mi < 100 && pa < 100) { "minor/patch must stay below 100: $appVersion" }
    ma * 10000 + mi * 100 + pa
}

// Release signing: android/keystore.properties (gitignored; see keystore.properties.example) or the SIGNING_* environment
// variables the release workflow sets from repository secrets. Without either, assembleRelease produces an UNSIGNED
// APK: published builds are never signed with the debug key.
val signing: Map<String, String> = run {
    val f = rootProject.file("keystore.properties")
    val p = Properties().apply { if (f.exists()) f.inputStream().use(::load) }
    fun v(prop: String, env: String) = (p.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    listOfNotNull(
        v("storeFile", "SIGNING_STORE_FILE")?.let { "storeFile" to it },
        v("storePassword", "SIGNING_STORE_PASSWORD")?.let { "storePassword" to it },
        v("keyAlias", "SIGNING_KEY_ALIAS")?.let { "keyAlias" to it },
        v("keyPassword", "SIGNING_KEY_PASSWORD")?.let { "keyPassword" to it },
    ).toMap()
}

android {
    namespace = "app.phonebridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.phonebridge"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        resValue("string", "brand", brand)
        buildConfigField("String", "BRAND", "\"$brand\"")
    }

    signingConfigs {
        if (signing.size == 4) create("release") {
            storeFile = rootProject.file(signing.getValue("storeFile"))
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
        // The app speaks en + fa only; drop the ~90 translations the AndroidX libraries carry.
        localeFilters += setOf("en", "fa")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // androidx's ExperimentalDetector crashes inside lint's Kotlin analysis on this toolchain (AGP 9.4.1 +
        // KGP 2.4.21, KaFirKotlinPropertySymbol). Opt-ins are still enforced by the compiler (optIn list below).
        disable += setOf("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
        // Style-only (edit {} / toUri()); the v2 persistence code it flags is proven on the device and stays as is.
        disable += "UseKtx"
        // Versions are pinned on purpose (Google Maven is reachable only through a mirror here); APK-only distribution.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "AppBundleLocaleChanges")
        // targetSdk stays 36: the phone this was verified on runs Android 16; API 37 behaviour changes are untested.
        disable += "OldTargetApi"
        warningsAsErrors = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric fetches its android-all jars at test time from Maven Central. Behind a filtered network, point it
        // at a mirror: -ProbolectricRepo=https://maven.aliyun.com/repository/public (or set it in ~/.gradle/gradle.properties).
        // README/docs screenshots (synthetic data only, see ScreenshotTest): -PscreenshotDir=../docs/assets/screens
        providers.gradleProperty("screenshotDir").orNull?.let { dir ->
            unitTests.all { it.systemProperty("angaros.screenshots", rootProject.file(dir).absolutePath) }
        }
        providers.gradleProperty("robolectricRepo").orNull?.let { repo ->
            unitTests.all { it.systemProperty("robolectric.dependency.repo.url", repo) }
        }
    }
}

// `./gradlew assembleRelease -PcomposeReports`: stability/skippability reports in build/compose_reports.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_reports")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    // Material 3 Expressive APIs (MotionScheme.expressive, ButtonGroup, LoadingIndicator, FloatingToolbar,
    // MaterialShapes) are only in 1.5; the BOM pins 1.4.0.
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-beta02")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.work:work-runtime:2.10.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
