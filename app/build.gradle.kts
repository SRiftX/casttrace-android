import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.streamcap.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streamcap.mobile"
        providers.gradleProperty("applicationIdSuffix").orNull?.takeIf { it.isNotBlank() }?.let {
            applicationIdSuffix = it
        }
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.2.14"

        // The pinned FFmpegKit AAR ships only these ABIs. Do not advertise
        // partial 32-bit support through unrelated AndroidX native libraries.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "com.streamcap.mobile.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    val releaseStore = providers.environmentVariable("CASTTRACE_KEYSTORE").orNull
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("CASTTRACE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("CASTTRACE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("CASTTRACE_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val verifyFfmpegArtifact by tasks.registering {
    val artifact = layout.projectDirectory.file("libs/ffmpeg-kit-https-7.1.6.aar")
    inputs.file(artifact)
    doLast {
        val binary = artifact.asFile
        check(binary.isFile) { "Missing FFmpegKit AAR: see app/libs/README.md; do not download an unverified replacement." }
        val digest = MessageDigest.getInstance("SHA-256")
        binary.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02X".format(it) }
        check(actual == "E80E5EE52248A3DCB1DDA0524DD784FE4BBB212DCD4D575E82E15D21A002D860") {
            "FFmpegKit AAR checksum differs from the reviewed local artifact. Verify provenance before updating the pin."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyFfmpegArtifact) }

val compatibleFfmpegArtifact = layout.buildDirectory.file("ffmpeg/ffmpeg-kit-https-7.1.6-compatible.aar")
val prepareCompatibleFfmpeg by tasks.registering {
    dependsOn(verifyFfmpegArtifact)
    inputs.file(layout.projectDirectory.file("libs/ffmpeg-kit-https-7.1.6.aar"))
    outputs.file(compatibleFfmpegArtifact)
    doLast {
        val target = compatibleFfmpegArtifact.get().asFile
        target.parentFile.mkdirs()
        ZipFile(layout.projectDirectory.file("libs/ffmpeg-kit-https-7.1.6.aar").asFile).use { source ->
            ZipOutputStream(target.outputStream()).use { output ->
                source.entries().asSequence().forEach { entry ->
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (entry.name.startsWith("jni/") && entry.name.endsWith(".so")) {
                        val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        check(elf.getInt(0) == 0x464c457f && bytes[4] == 2.toByte() && bytes[5] == 1.toByte())
                        val table = elf.getLong(32).toInt()
                        val stride = elf.getShort(54).toInt() and 0xffff
                        val count = elf.getShort(56).toInt() and 0xffff
                        val headers = (0 until count).map { table + it * stride }
                        val mappedLimit = headers.filter { elf.getInt(it) == 1 }
                            .maxOf { elf.getLong(it + 16) + elf.getLong(it + 40) }
                        headers.filter { elf.getInt(it) == 0x6474e552 }.forEach { relro ->
                            val start = elf.getLong(relro + 16)
                            val end = start + elf.getLong(relro + 40)
                            val load = headers.single { header ->
                                elf.getInt(header) == 1 && start >= elf.getLong(header + 16) &&
                                    start < elf.getLong(header + 16) + elf.getLong(header + 40)
                            }
                            val mappedEnd = elf.getLong(load + 16) + elf.getLong(load + 40)
                            // NDK's 16 KiB RELRO padding can extend past PT_LOAD. Android 10
                            // mprotects those unmapped pages and fails with ENOMEM. Only trim
                            // padding; all file-backed RELRO data and load segments stay intact.
                            if ((end + 4095L) / 4096L > (mappedLimit + 4095L) / 4096L) {
                                check(start + elf.getLong(relro + 32) <= mappedEnd)
                                elf.putLong(relro + 40, mappedEnd - start)
                                logger.lifecycle("Trimmed unmapped RELRO padding: ${entry.name}")
                            }
                        }
                    }
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation(files(compatibleFfmpegArtifact).builtBy(prepareCompatibleFfmpeg))
    // 本地 AAR 不会自动解析其 POM 中的传递依赖；FFmpegKit 初始化和错误回调会直接使用该类库。
    implementation("com.arthenica:smart-exception-java:0.2.1")
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    val roomVersion = "2.8.5"

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.brotli:dec:0.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60.1")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.60.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
