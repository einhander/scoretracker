import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. Reads credentials from Gradle properties so secrets never
// land in version control. Sources (in lookup order):
//   - local.properties (local dev — gitignored)
//   - gradle.properties / project properties (CI injects them here)
// The keystore file (app/temposcore-release.jks) is gitignored; CI
// materializes it from the SCORE_KEYSTORE_BASE64 secret. When the properties
// are absent the release build stays unsigned, so debug builds and CI without
// secrets keep working.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(name: String): String? =
    (localProps.getProperty(name) ?: project.findProperty(name) as String?)

// ── Versioning ──
//
// Releases are cut from git tags (the CI `release` job fires on `v*` tag
// pushes). The git tag is the single source of truth for the release
// version: when HEAD is exactly a tag (e.g. v0.1.0), versionName is the
// tag with its leading "v" stripped — "0.1.0" — regardless of baseVersion
// below, so a tag can never ship a mismatched version even if baseVersion
// was not bumped in lockstep.
//
// Every other build (CI branch/PR builds, local dev) is NOT on a tag, so it
// appends the short commit hash to baseVersion so the exact source of an
// APK is identifiable at a glance: "0.1.0~abc1234". baseVersion therefore
// only governs the dev/CI version string, not the released one.
//
// "Release" is detected by `git describe --tags --exact-match HEAD` — it
// only succeeds when HEAD is exactly a tagged commit, which is the case for
// tag-triggered CI runs (actions/checkout checks out the tag in detached
// HEAD). Branch/PR runs are never on a tag, so they get the hash suffix.
// All git calls are defensive: if git is unavailable they fall back to
// baseVersion so the build never fails on a missing tool.
val baseVersion = "0.1.0"

fun runGit(vararg args: String): String? {
    return try {
        val p = ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}

// The tag HEAD sits on (e.g. "v0.1.0"), or null when HEAD is not a tag.
val gitExactTag: String? by lazy {
    runGit("describe", "--tags", "--exact-match", "HEAD")
}

val gitShortHash: String? by lazy { runGit("rev-parse", "--short=7", "HEAD") }

val resolvedVersionName: String by lazy {
    // Release: version comes from the tag itself (single source of truth).
    // Strip a leading "v" (tags are v0.1.0); tolerate bare numeric tags too.
    val tagVersion = gitExactTag?.removePrefix("v")
    if (tagVersion != null) {
        tagVersion
    } else if (gitShortHash != null) {
        "$baseVersion~$gitShortHash"
    } else {
        baseVersion
    }
}

android {
    namespace = "com.einhander.temposcore"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    signingConfigs {
        create("release") {
            storeFile = file("temposcore-release.jks")
            storePassword = signingProp("temposcore.storePassword")
            // The keystore is shared with einhander/piano; its key alias is "piano".
            keyAlias = signingProp("temposcore.keyAlias") ?: "piano"
            keyPassword = signingProp("temposcore.keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.einhander.temposcore"
        minSdk = 26
        targetSdk = 29
        versionCode = 1
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply the signing config when credentials are available;
            // otherwise fall back to the default (unsigned) release so builds
            // without secrets don't fail.
            val hasCreds = signingProp("temposcore.storePassword") != null &&
                signingProp("temposcore.keyPassword") != null
            if (hasCreds) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Native low-latency input backend. Oboe 1.10.0 is consumed through Prefab.
    implementation("com.google.oboe:oboe:1.10.0")

    testImplementation("junit:junit:4.13.2")
}
