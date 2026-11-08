plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation(libs.junit)
}
