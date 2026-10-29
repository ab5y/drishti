import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The decisions.md revision stamped on every record (D54): the git short hash
 * of the last commit that changed it, or, without git, its highest decision number.
 */
val decisionsRev: String = run {
    val hash = runCatching {
        providers.exec {
            commandLine("git", "log", "-1", "--format=%h", "--", "docs/decisions.md")
            workingDir = rootDir
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
    hash.ifEmpty {
        val text = rootProject.file("docs/decisions.md").takeIf { it.exists() }?.readText().orEmpty()
        Regex("""^### (D\d+)\.""", RegexOption.MULTILINE).findAll(text).lastOrNull()?.groupValues?.get(1) ?: "unknown"
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.drishti"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.drishti"
        minSdk = 24
        targetSdk = 37
        // CI derives these from the release tag vMAJOR.MINOR.PATCH (versionCode = MAJOR*10000 +
        // MINOR*100 + PATCH) and passes -PappVersionCode / -PappVersionName; local builds fall back.
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "0.1.0"
        buildConfigField("String", "DECISIONS_REV", "\"$decisionsRev\"")
    }

    // Release signing comes from environment variables (set by the GitHub workflow).
    // Without them the release APK is left unsigned and will not install.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // The Room DAO tests pass an empty Context stub; an in-memory database never uses it (D63).
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.bundled.jvm)
}
