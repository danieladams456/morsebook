plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    applicationName = "morsebook"
    mainClass.set("com.github.danieladams456.morsebook.cli.MainKt")
}
