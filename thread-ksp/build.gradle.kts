plugins {
    kotlin("jvm")
}

group = "dev.mmrlx"
version = "1.0.0"

dependencies {
    implementation(libs.symbol.processing.api)
    implementation(libs.squareup.kotlinpoet)
    implementation(libs.squareup.kotlinpoet.ksp)
}
