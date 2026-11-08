import java.io.File
import java.security.MessageDigest

plugins { alias(libs.plugins.android.library) }
android {
    namespace = "app.velin.core.sdk"
    compileSdk = 37
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].apply {
        kotlin.srcDir("build/native/artifacts/kotlin")
        resources.srcDir("build/native/artifacts/notices")
        jniLibs.srcDir("build/native/artifacts/jniLibs")
    }
}
dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    implementation("net.java.dev.jna:jna:5.18.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(libs.junit)
    testRuntimeOnly("net.java.dev.jna:jna:5.18.0@jar")
}
val checkUpstreamPin = tasks.register<Exec>("checkUpstreamPin") {
    commandLine("python3", rootProject.file("tools/backend/check-pin.py"), file("build/native/source"))
}
val checkNativeArtifacts = tasks.register("checkNativeArtifacts") {
    val directory = layout.projectDirectory.dir("build/native/artifacts").asFile
    val repositoryDirectory = rootProject.layout.projectDirectory.asFile
    val pinnedCommit = Regex(""""commit":\s*"([^"]+)"""")
        .find(providers.fileContents(rootProject.layout.projectDirectory.file("tools/backend/upstream.json")).asText.get())!!.groupValues[1]
    dependsOn(checkUpstreamPin)
    doLast {
        check(directory.resolve("UPSTREAM_COMMIT").readText().trim() == pinnedCommit)
        val manifest = directory.resolve("SHA256SUMS")
        check(manifest.isFile) {
            "Run tools/backend/build-sdk.sh before building :core:sdk"
        }
        for ((base, checksums) in listOf(directory to manifest,
            repositoryDirectory to directory.resolve("OVERLAY-SHA256"))) {
            checksums.forEachLine { line ->
                val (hash, path) = line.split("  ", limit = 2)
                val artifact = base.resolve(path)
                check(artifact.canonicalPath.startsWith(base.canonicalPath + File.separator))
                val digest = MessageDigest.getInstance("SHA-256")
                artifact.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "SDK checksum mismatch: $path" }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNativeArtifacts) }

tasks.withType<Test>().configureEach {
    systemProperty("velin.upstream.source", file("build/native/source").absolutePath)
    systemProperty("jna.library.path", file("build/native/artifacts/host").absolutePath)
}
