plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

sourceSets.main {
    resources.srcDir("bundled-content")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core"))
    testImplementation(libs.kotlin.test.junit)
}
