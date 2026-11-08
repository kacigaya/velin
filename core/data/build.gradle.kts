plugins { alias(libs.plugins.android.library) }
android {
    namespace = "app.velin.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    testOptions.unitTests.all {
        it.jvmArgs("--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED")
    }
}
dependencies {
    api(project(":core:domain"))
    implementation(project(":core:html"))
    implementation(project(":core:network"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
