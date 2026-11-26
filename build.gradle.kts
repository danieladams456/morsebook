import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
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
