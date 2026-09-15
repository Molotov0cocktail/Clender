import java.util.Properties
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

val signingPropertyNames = mapOf(
    "CLENDER_ANDROID_KEYSTORE_FILE" to "storeFile",
    "CLENDER_ANDROID_KEYSTORE_PASSWORD" to "storePassword",
    "CLENDER_ANDROID_KEY_ALIAS" to "keyAlias",
    "CLENDER_ANDROID_KEY_PASSWORD" to "keyPassword"
)
val localSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}
fun signingValue(environmentName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty(signingPropertyNames.getValue(environmentName))
            ?.takeIf { it.isNotBlank() }

val releaseSigningValues = signingPropertyNames.keys.associateWith(::signingValue)
fun releaseKeystoreFile(): File? =
    releaseSigningValues["CLENDER_ANDROID_KEYSTORE_FILE"]?.let(rootProject::file)
val robolectricFrameworkApi26 by configurations.creating
val robolectricFrameworkApi36 by configurations.creating

android {
    namespace = "com.molotov.clender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.molotov.clender"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            releaseKeystoreFile()?.let { storeFile = it }
            storePassword = providers.provider {
                releaseSigningValues["CLENDER_ANDROID_KEYSTORE_PASSWORD"]
            }.orNull
            keyAlias = releaseSigningValues["CLENDER_ANDROID_KEY_ALIAS"]
            keyPassword = providers.provider {
                releaseSigningValues["CLENDER_ANDROID_KEY_PASSWORD"]
            }.orNull
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    androidResources {
        localeFilters += listOf("zh", "en")
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // AGP/Gradle and Jetpack are deliberately pinned to the verified no-native compatibility matrix.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

dependencies {
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore.preferences) {
        exclude(group = "androidx.datastore", module = "datastore-core")
    }
    implementation(libs.androidx.datastore.core.jvm)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.mockwebserver3)
    robolectricFrameworkApi26(libs.robolectric.android.api26)
    robolectricFrameworkApi36(libs.robolectric.android.api36)
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
    arg("room.generateKotlin", "true")
}

detekt {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = false
}

ktlint {
    version.set(libs.versions.ktlint.core.get())
    android.set(true)
    ignoreFailures.set(false)
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails closed when release signing credentials are incomplete."
    doLast {
        val values = signingPropertyNames.keys.associateWith(::signingValue)
        val configuredFile = values.getValue(
            "CLENDER_ANDROID_KEYSTORE_FILE"
        )?.let(rootProject::file)
        val androidRoot = rootProject.projectDir.canonicalFile.toPath()
        val configuredPath = configuredFile?.canonicalFile?.toPath()
        val ignored = configuredFile?.let { file ->
            ProcessBuilder("git", "check-ignore", "-q", "--no-index", file.absolutePath)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.readAllBytes() }
                .waitFor() == 0
        } ?: false
        check(
            values.values.all { !it.isNullOrBlank() } &&
                configuredFile?.isFile == true &&
                configuredPath?.startsWith(androidRoot) == true &&
                ignored
        ) {
            "Release signing credentials are required"
        }
    }
}

tasks.register("verifyReleaseSigningPolicy") {
    group = "verification"
    description = "Checks that release is independently signed and hardened."
    doLast {
        val release = android.buildTypes.getByName("release")
        check(release.signingConfig?.name == "release") {
            "Release must use the independent release signing config."
        }
        check(!release.isDebuggable) { "Release must not be debuggable." }
        check(release.isMinifyEnabled && release.isShrinkResources) {
            "Release hardening must remain enabled."
        }
    }
}

tasks.configureEach {
    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease", "signReleaseBundle")) {
        dependsOn(validateReleaseSigning)
    }
}

tasks.withType<Test>().configureEach {
    val frameworkDirectory = layout.buildDirectory.dir("robolectric-frameworks")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
    dependsOn("prepareRobolectricFrameworks")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", frameworkDirectory.get().asFile.absolutePath)
}

tasks.register<Sync>("prepareRobolectricFrameworks") {
    // Keep separate configurations so Gradle does not resolve both framework versions to API 36.
    from(robolectricFrameworkApi26)
    from(robolectricFrameworkApi36)
    into(layout.buildDirectory.dir("robolectric-frameworks"))
}
