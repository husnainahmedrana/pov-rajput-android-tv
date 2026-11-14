plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// OneDrive can lock generated test directories. Script builds keep all outputs local.
providers.gradleProperty("povBuildRoot").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "dev.pov.tv"
    compileSdk = 36
    // Match Kotlin internal symbols when auditing a locally signed release on a TV.
    testBuildType = providers.gradleProperty("povTestBuildType").orElse("debug").get()
    defaultConfig {
        applicationId = "dev.pov.tv.rajput"
        manifestPlaceholders["appLabel"] = providers.gradleProperty("povAppLabel").orElse("Nabrio").get()
        minSdk = 26
        targetSdk = 35
        versionCode = 67
        // Test builds can raise it (-PpovVersionCode=…) so a TV launcher reloads a changed icon; releases leave it alone.
        providers.gradleProperty("povVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        versionName = "0.4.1-rajput"
        testInstrumentationRunner = providers.gradleProperty("povTestRunner")
            .orElse("dev.pov.tv.PlaybackAuditInstrumentation").get()
    }
    buildFeatures { buildConfig = true }
    // Optional Seekr key for local preview builds only (-PpovSeekrKey=...). Never committed; releases ship without one.
    // Brand colours, overridable for test builds (-PpovAccent=A3E635 -PpovAccent2=3B82F6 -PpovOnAccent=000000).
    // Defaults are the current red, so normal builds look exactly as before.
    val brandAccent = providers.gradleProperty("povAccent").orElse("E50914").get()
    val brandAccent2 = providers.gradleProperty("povAccent2").orElse("B20710").get()
    val brandOnAccent = providers.gradleProperty("povOnAccent").orElse("FFFFFF").get()
    defaultConfig.buildConfigField("int", "BRAND_ACCENT", "(int) 0xFF${brandAccent}L")
    defaultConfig.buildConfigField("int", "BRAND_ACCENT_2", "(int) 0xFF${brandAccent2}L")
    defaultConfig.buildConfigField("int", "BRAND_ON_ACCENT", "(int) 0xFF${brandOnAccent}L")
    defaultConfig.resValue("color", "brand_accent", "#$brandAccent")
    // The app's name in its own text (About, updates, "Make POV yours."), following -PpovAppLabel for test builds.
    val appName = providers.gradleProperty("povAppLabel").orElse("Nabrio").get()
    val appShort = providers.gradleProperty("povAppShortName").orElse(appName).get()
    defaultConfig.buildConfigField("String", "APP_NAME", "\"${appName.replace("\"", "")}\"")
    defaultConfig.buildConfigField("String", "APP_SHORT", "\"${appShort.replace("\"", "")}\"")
    defaultConfig.buildConfigField("String", "SEEKR_KEY", "\"${providers.gradleProperty("povSeekrKey").orNull.orEmpty()}\"")
    // Account backup server (server/backup in this repo). Forks set their own: -PpovBackupServer=https://backup.example.com
    defaultConfig.buildConfigField("String", "BACKUP_SERVER", "\"${providers.gradleProperty("povBackupServer").orElse("https://backup.rajput.cc").get()}\"")
    // One codebase, two APKs: the TV app keeps its package (existing installs update in place),
    // the phone app is a separate install with its own release asset and update manifest.
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "MOBILE", "false")
            buildConfigField("String", "UPDATE_APK", "\"POV-Rajput-release.apk\"")
            buildConfigField("String", "UPDATE_MANIFEST", "\"update.json\"")
            buildConfigField("String", "UPDATE_PACKAGE", "\"dev.pov.tv.rajput\"")
            manifestPlaceholders += mapOf("mainOrientation" to "landscape", "playerOrientation" to "landscape")
        }
        create("mobile") {
            dimension = "device"
            applicationId = "dev.pov.rajput.mobile"
            buildConfigField("boolean", "MOBILE", "true")
            buildConfigField("String", "UPDATE_APK", "\"POV-Rajput-Mobile.apk\"")
            buildConfigField("String", "UPDATE_MANIFEST", "\"update-mobile.json\"")
            buildConfigField("String", "UPDATE_PACKAGE", "\"dev.pov.rajput.mobile\"")
            manifestPlaceholders += mapOf("mainOrientation" to "portrait", "playerOrientation" to "sensorLandscape")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    val releaseStore = providers.environmentVariable("POV_RELEASE_STORE_FILE").orNull
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("distribution") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("POV_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("POV_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("POV_RELEASE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        // Optional side-by-side install for UI previews, e.g. -PpovAppIdSuffix=.preview
        debug { providers.gradleProperty("povAppIdSuffix").orNull?.let { applicationIdSuffix = it } }
        release {
            isMinifyEnabled = false
            providers.gradleProperty("povAppIdSuffix").orNull?.let { applicationIdSuffix = it }
            // Opt-in local sideload release: retain the installed preview certificate and data.
            // Normal release builds remain unsigned for signing with the distributor's own key.
            if (providers.gradleProperty("povPreviewRelease").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    // Optional test branding, e.g. -PpovBrand=nebrix: brand/<name>/res replaces the launcher icon and TV banner.
    // Package names, signing and update channels stay the same, so it installs over the matching build.
    providers.gradleProperty("povBrand").orNull?.let { brand ->
        listOf("debug", "release").forEach { sourceSets.getByName(it).res.srcDir("brand/$brand/res") }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Translated text uses %1$s placeholders for every value, numbers included: Android formats them correctly,
    // and translators only ever see one kind of placeholder.
    lint { disable += "StringFormatMatches" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Phones (and many TVs for DTS/TrueHD) lack these decoders; FFmpeg decodes them to PCM in software.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    // Artwork only: HTTP/2 shares one connection per image server, with a disk cache for posters.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR code for device sign-in (encoder only).
    implementation("com.google.zxing:core:3.5.3")
    // Installs the baseline profile (src/main/baseline-prof.txt) so sideloaded installs run pre-compiled code early.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Seekr (seekr.tv) ready-made seek preview thumbnails, with the viewer's own API key.
    implementation("tv.seekr:seekr-android:0.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
