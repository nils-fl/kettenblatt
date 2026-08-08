import java.util.Properties

/**
 * Signing material, from the environment in CI or a local keystore.properties
 * that is never committed. With neither, a release build is simply unsigned, so
 * anyone can clone and assemble one without holding the key.
 */
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

fun signingValue(env: String, property: String): String? =
    System.getenv(env) ?: keystoreProperties.getProperty(property)

val keystorePath: String? = signingValue("KEYSTORE_FILE", "storeFile")

/**
 * The release version comes from the tag that triggered the build: v1.2.3.
 *
 * versionCode is derived from it rather than counted, so it is reproducible:
 * rebuilding a tag gives the same number, and the ordering Android needs for
 * upgrades falls out of semver. Minor and patch get two digits each.
 */
val releaseVersion: String? = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { Regex("""^v\d+\.\d+\.\d+$""").matches(it) }
    ?.removePrefix("v")

/**
 * A nightly: the tip of main, signed and installable, but not a release.
 *
 * `NIGHTLY_STAMP` is yyMMddHH, which is both the versionCode and monotonic
 * without any stored counter, so a rebuild an hour later always supersedes.
 * `NIGHTLY_SHA` is the short commit, because the only useful question about a
 * nightly is which one it is.
 *
 * A nightly takes its own applicationId, so it installs *beside* a release
 * rather than replacing it. That is the whole safety of the channel: nothing
 * about trying one can take away the version you rely on, and the two keep
 * separate route libraries and ride histories.
 */
val nightlyStamp: String? = System.getenv("NIGHTLY_STAMP")
    ?.takeIf { Regex("""^\d{8}$""").matches(it) }
val nightlySha: String? = System.getenv("NIGHTLY_SHA")?.take(7)?.ifBlank { null }
val isNightly = nightlyStamp != null && releaseVersion == null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.kettenblatt"
    compileSdk = 36

    defaultConfig {
        applicationId = if (isNightly) "de.kettenblatt.nightly" else "de.kettenblatt"
        minSdk = 34
        targetSdk = 36
        versionCode = releaseVersion
            ?.split(".")
            ?.let { (major, minor, patch) ->
                major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
            }
            // Far above any release code, which is deliberate but harmless: the
            // two channels are different applications and never compare.
            ?: nightlyStamp?.toInt()
            ?: 1
        versionName = when {
            releaseVersion != null -> releaseVersion
            isNightly -> listOfNotNull("nightly", nightlyStamp, nightlySha).joinToString("-")
            else -> "dev"
        }

        // Named apart on the launcher too. Someone running both should never
        // have to guess which icon is the one they trust.
        resValue("string", "app_name", if (isNightly) "Kettenblatt Nightly" else "Kettenblatt")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not optional here. material-icons-extended compiles some five
            // thousand icons into code and the app uses 28 of them, which is
            // most of a 46 MB APK. Shrinking takes it to a size worth asking
            // someone to download over mobile data.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Assigning a config with no keystore fails at packaging time, so an
            // unsigned release stays possible for anyone without the key.
            signingConfig = keystorePath?.let { signingConfigs.getByName("release") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.osmdroid.android)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    // A real XmlPullParser -- android.util.Xml is a stub off-device, and the
    // GPX round trip is worth testing on the JVM.
    testImplementation(libs.kxml2)
}
