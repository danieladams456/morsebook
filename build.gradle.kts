import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

val buildJdk = libs.versions.jdk.get().toInt()
val javaTarget = libs.versions.java.target.get()

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(buildJdk)
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(javaTarget))
                freeCompilerArgs.add("-Xjdk-release=$javaTarget")
            }
        }
        extensions.configure<JavaPluginExtension> {
            targetCompatibility = JavaVersion.toVersion(javaTarget)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(javaTarget.toInt())
        }
    }
}

val validatorTests = tasks.register<Exec>("validatorTests") {
    commandLine("python3", "-m", "unittest", "discover", "-s", "validator/tests")
}
val audioAcceptance = tasks.register<Exec>("audioAcceptance") {
    dependsOn(":cli:installDist")
    commandLine("python3", "validator/acceptance.py",
        project(":cli").layout.buildDirectory.file("install/morsebook/bin/morsebook").get().asFile,
        layout.buildDirectory.dir("validator").get().asFile)
}
tasks.check {
    dependsOn(":core:check", ":cli:check", validatorTests, audioAcceptance)
}
