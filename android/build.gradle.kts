import java.util.zip.ZipFile
import org.gradle.api.artifacts.dsl.LockMode

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
        lockAllConfigurations()
    }
}

val googleServiceGroups = listOf(
    listOf("com", "google", "android", "gms").joinToString("."),
    listOf("com", "google", "firebase").joinToString("."),
    listOf("com", "google", "android", "play").joinToString("."),
    listOf("com", "google", "mlkit").joinToString("."),
)

tasks.register("verifyNoGoogleServices") {
    group = "verification"
    description = "Rejects Google Play services, Firebase, Play and ML Kit runtime modules."
    doLast {
        subprojects.flatMap { project ->
            project.configurations.filter {
                it.isCanBeResolved && it.name in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
            }
        }.forEach { configuration ->
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val group = component.moduleVersion?.group.orEmpty()
                check(googleServiceGroups.none { group == it || group.startsWith("$it.") }) {
                    "Google service dependency is forbidden in ${configuration.name}."
                }
            }
        }
    }
}

tasks.register("verifyNoNativeRuntimeArtifacts") {
    group = "verification"
    description = "Rejects native libraries embedded by resolved runtime artifacts."
    doLast {
        subprojects.flatMap { project ->
            project.configurations.filter {
                it.isCanBeResolved && it.name in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
            }
        }.forEach { configuration ->
            configuration.files.filter {
                it.isFile && it.extension in setOf("jar", "aar", "zip")
            }.forEach { artifact ->
                ZipFile(artifact).use { archive ->
                    val nativeEntry = archive.entries().asSequence().firstOrNull { entry ->
                        val name = entry.name.replace('\\', '/')
                        name.endsWith(".so", ignoreCase = true) ||
                            name.endsWith(".dll", ignoreCase = true) ||
                            name.endsWith(".dylib", ignoreCase = true)
                    }
                    check(nativeEntry == null) {
                        "Native runtime artifact is forbidden in ${configuration.name}: ${artifact.name}."
                    }
                }
            }
        }
    }
}

tasks.register("verifyDebugApkNoNativeArtifacts") {
    group = "verification"
    description = "Rejects native libraries anywhere in the packaged debug APK."
    dependsOn(":app:assembleDebug")
    doLast {
        val apk = file("app/build/outputs/apk/debug/app-debug.apk")
        check(apk.isFile) { "Debug APK is missing." }
        ZipFile(apk).use { archive ->
            val nativeEntry = archive.entries().asSequence().firstOrNull { entry ->
                val name = entry.name
                name.endsWith(".so", ignoreCase = true) ||
                    name.endsWith(".dll", ignoreCase = true) ||
                    name.endsWith(".dylib", ignoreCase = true)
            }
            check(nativeEntry == null) { "Native library is forbidden in the debug APK." }
        }
    }
}

tasks.register("verifyResolvedVersionsLocked") {
    group = "verification"
    description = "Rejects dynamic, changing or unresolved runtime dependencies."
    doLast {
        subprojects.flatMap { project ->
            project.configurations.filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
        }.forEach { configuration ->
            configuration.incoming.resolutionResult.allDependencies.forEach { dependency ->
                check(dependency.javaClass.simpleName != "DefaultUnresolvedDependencyResult") {
                    "Unresolved dependency in ${configuration.name}."
                }
                val requested = dependency.requested.displayName
                check(!Regex("(?i)(\\+|latest|snapshot|[\\[(].*[,;].*[\\])])").containsMatchIn(requested)) {
                    "Dynamic or changing dependency is forbidden in ${configuration.name}."
                }
            }
            configuration.resolve()
        }
    }
}
