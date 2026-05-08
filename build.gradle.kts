plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val isLinux = System.getProperty("os.name").lowercase().contains("linux")

val prepareLinuxBuild by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Runs build_linux.sh to prepare native deps on Linux before compiling."
    onlyIf { isLinux }
    workingDir = rootDir
    commandLine("bash", "./build_linux.sh", "--prepare")
}

tasks.register<Exec>("clean-native") {
    group = "build setup"
    description = "Runs build_linux.sh --clean-only to clean native build artifacts on Linux."
    onlyIf { isLinux }
    workingDir = rootDir
    commandLine("bash", "./build_linux.sh", "--clean-only")
}

subprojects {
    // Ensure native prerequisites exist before any Kotlin/Java compilation kicks off on Linux.
    tasks.matching { it.name == "preBuild" }.configureEach {
        if (isLinux) {
            dependsOn(rootProject.tasks.named("prepareLinuxBuild"))
        }
    }
}
