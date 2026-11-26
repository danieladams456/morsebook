plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
    implementation(project(":core"))
}

application {
    applicationName = "morsebook"
    mainClass.set("com.github.danieladams456.morsebook.cli.MainKt")
}
