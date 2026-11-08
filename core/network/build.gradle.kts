plugins { alias(libs.plugins.android.library) }
android {
    namespace = "app.velin.core.network"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(libs.okhttp)
    api(project(":core:sdk"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
