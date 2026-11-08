plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(libs.junit)
}
