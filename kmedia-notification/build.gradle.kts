@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

fun KotlinNativeTarget.configureNativeBridge() {
    if (!name.startsWith("linux") && name != "mingwX64") return
    val targetName = name
    val windows = name == "mingwX64"
    val suffix = name.replaceFirstChar(Char::uppercaseChar)
    val stem = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
    val output = layout.buildDirectory.dir("native/system-media/$name")
    val objectFile = output.map { it.file("kmedia_notification.o") }
    val archive = output.map { it.file("libkmedia_notification.a") }
    val hostWindows = System.getProperty("os.name").startsWith("Windows")
    val konan = File(System.getProperty("user.home"), ".konan/dependencies")
    val llvm = konan.listFiles()?.filter { it.name.matches(Regex("llvm-\\d+-x86_64-linux-essentials-.*")) }
        ?.maxByOrNull { it.name }?.resolve("bin")
    val compilerOverride = providers.environmentVariable("KMEDIA_NOTIFICATION_${stem}_CC")
    val archiverOverride = providers.environmentVariable("KMEDIA_NOTIFICATION_${stem}_AR")
    val hostArm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
    val defaultCompiler = if (!windows) listOf(
        if (name == "linuxArm64" && !hostArm) konan.resolve("aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2/bin/aarch64-unknown-linux-gnu-gcc").takeIf { it.isFile }?.absolutePath ?: "aarch64-linux-gnu-gcc"
        else if (name == "linuxX64" && hostArm) "x86_64-linux-gnu-gcc" else "cc"
    ) else if (hostWindows) listOf("clang++") else listOf(
        llvm?.resolve("clang")?.absolutePath ?: "clang++", "--target=x86_64-w64-windows-gnu",
        "--sysroot=${konan.resolve("msys2-mingw-w64-x86_64-2").absolutePath}")
    val defaultArchiver = if (windows && !hostWindows) llvm?.resolve("llvm-ar")?.absolutePath ?: "llvm-ar" else "ar"
    val gioFlags = providers.exec {
        commandLine("pkg-config", "--cflags", "gio-2.0")
    }.standardOutput.asText.map { it.trim().split(Regex("\\s+")) }
    val source = file("src/nativeInterop/cinterop/kmedia_${if (windows) "windows.cpp" else "linux.c"}")
    val include = file("src/nativeInterop/cinterop/include")
    val compile = tasks.register<Exec>("compileSystemMedia$suffix") {
        inputs.file(source)
        inputs.dir(include)
        inputs.property("compiler", compilerOverride.orElse(defaultCompiler.joinToString(" ")))
        outputs.file(objectFile)
        doFirst {
            output.get().asFile.mkdirs()
            val compiler = compilerOverride.orNull?.split(Regex("\\s+")) ?: defaultCompiler
            commandLine(compiler + listOf("-O2", "-fPIC", "-I${include.absolutePath}") +
                (if (windows) listOf("-std=c++17", "-fno-exceptions", "-fno-rtti", "-D_WIN32_WINNT=0x0A00", "-DWIN32_LEAN_AND_MEAN", "-DNOMINMAX") else gioFlags.get()) +
                listOf("-c", source.absolutePath, "-o", objectFile.get().asFile.absolutePath))
        }
    }
    val prebuilt = providers.environmentVariable("KMEDIA_NOTIFICATION_${stem}_PREBUILT_ARCHIVE").orNull?.let(::file)
    val pack = if (prebuilt != null) tasks.register<Copy>("archiveSystemMedia$suffix") {
        from(prebuilt)
        into(output)
        rename { archive.get().asFile.name }
        doFirst { require(prebuilt.isFile) { "Missing prebuilt media bridge: $prebuilt" } }
    } else tasks.register<Exec>("archiveSystemMedia$suffix") {
        dependsOn(compile)
        inputs.file(objectFile)
        outputs.file(archive)
        doFirst {
            commandLine(listOf(archiverOverride.orNull ?: defaultArchiver,
                "rcs", archive.get().asFile.absolutePath, objectFile.get().asFile.absolutePath))
        }
    }
    val definition = output.map { it.file("kmedia-notification.def") }
    val configuredDirs = providers.environmentVariable("KMEDIA_NOTIFICATION_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)?.filter(String::isNotBlank)
    val defaultDirs = listOf(if (targetName == "linuxArm64") "/usr/lib/aarch64-linux-gnu" else "/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib")
    val linkDirs = configuredDirs ?: defaultDirs
    val generateDefinition = tasks.register("generateSystemMediaDefinition$suffix") {
        val base = file("src/nativeInterop/cinterop/kmedia-notification.def")
        inputs.file(base)
        inputs.property("linkDirs", linkDirs)
        outputs.file(definition)
        doLast {
            output.get().asFile.mkdirs()
            val text = base.readText()
            definition.get().asFile.writeText(if (windows) text else text.replace(
                "linkerOpts.linux =", "linkerOpts.linux = " + linkDirs.joinToString(" ") { "-L$it" }))
        }
    }
    compilations.getByName("main").cinterops.create("KMediaNotification") {
        defFile(definition.get().asFile)
        includeDirs(include)
        extraOpts("-libraryPath", output.get().asFile.absolutePath)
    }
    tasks.matching { it.name == "cinteropKMediaNotification$suffix" }.configureEach {
        dependsOn(pack, generateDefinition)
        inputs.file(archive)
    }
    compilations.getByName("main").defaultSourceSet.kotlin.srcDir("src/nativeDesktopMain/kotlin")
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm()
    android {
        namespace = "dev.brahmkshatriya.kmedianotification"
        compileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()
        minSdk = providers.gradleProperty("androidMinSdk").get().toInt()
        androidResources.enable = true
    }
    linuxX64(); linuxArm64(); mingwX64()
    macosX64(); macosArm64(); iosArm64(); iosX64(); iosSimulatorArm64()
    js { browser() }
    wasmJs { browser() }
    targets.withType<KotlinNativeTarget>().configureEach { configureNativeBridge() }
    sourceSets {
        val desktopMain by creating { dependsOn(commonMain.get()) }
        jvmMain { dependsOn(desktopMain) }
        listOf("linuxX64Main", "linuxArm64Main", "mingwX64Main", "macosX64Main", "macosArm64Main").forEach {
            getByName(it).dependsOn(desktopMain)
        }
        val webMain by creating { dependsOn(commonMain.get()) }
        jsMain { dependsOn(webMain) }
        wasmJsMain { dependsOn(webMain) }
        commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
        desktopMain.dependencies { implementation("com.squareup.okio:okio:3.18.2") }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies {
            api("androidx.media3:media3-session:1.11.1")
            implementation("androidx.core:core:1.9.0")
        }
        jvmMain.dependencies { implementation("net.java.dev.jna:jna:5.17.0") }
        matching { it.name.startsWith("linux") && it.name.endsWith("Main") }.configureEach {
            kotlin.srcDir("src/linuxMain/kotlin")
        }
        matching { (it.name.startsWith("macos") || it.name.startsWith("ios")) && it.name.endsWith("Main") }.configureEach {
            kotlin.srcDir("src/appleMain/kotlin")
            kotlin.srcDir(if (name.startsWith("ios")) "src/iosMain/kotlin" else "src/macosMain/kotlin")
        }
    }
}

// JVM reuses the same provider ABI, with a native bridge packaged for the build host.
// Release automation can supply the other hosts' binaries through KMEDIA_JVM_NATIVE_DIR.
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
val bridgeOs = when { hostOs.contains("win") -> "windows"; hostOs.contains("mac") -> "macos"; else -> "linux" }
val libraryName = when (bridgeOs) { "windows" -> "kmedia_notification.dll"; "macos" -> "libkmedia_notification.dylib"; else -> "libkmedia_notification.so" }
val jvmNative = layout.buildDirectory.dir("generated/jvmNative/native/$bridgeOs-$hostArch")
val jvmCompiler = providers.environmentVariable("KMEDIA_JVM_CC")
    .orElse(if (bridgeOs == "windows") "g++" else "cc")
val compileJvmBridge = tasks.register<Exec>("compileJvmBridge") {
    val source = file("src/nativeInterop/cinterop/kmedia_${when(bridgeOs) { "windows" -> "windows.cpp"; "macos" -> "macos.m"; else -> "linux.c" }}")
    inputs.file(source); inputs.dir("src/nativeInterop/cinterop/include")
    inputs.property("compiler", jvmCompiler)
    outputs.file(jvmNative.map { it.file(libraryName) })
    doFirst {
        jvmNative.get().asFile.mkdirs()
        val flags = when (bridgeOs) {
            "linux" -> providers.exec { commandLine("pkg-config", "--cflags", "--libs", "gio-2.0") }.standardOutput.asText.get().trim().split(Regex("\\s+"))
            "macos" -> listOf("-fobjc-arc", "-framework", "Foundation", "-framework", "MediaPlayer", "-framework", "AppKit")
            else -> listOf("-std=c++17", "-fno-exceptions", "-fno-rtti", "-D_WIN32_WINNT=0x0A00", "-DWIN32_LEAN_AND_MEAN", "-DNOMINMAX", "-static-libgcc", "-static-libstdc++", "-lruntimeobject", "-lole32", "-luser32", "-luuid")
        }
        commandLine(jvmCompiler.get().trim().split(Regex("\\s+")) + listOf("-shared", "-O2", "-fPIC",
            "-Isrc/nativeInterop/cinterop/include", source.absolutePath, "-o", jvmNative.get().file(libraryName).asFile.absolutePath) + flags)
    }
}
tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(compileJvmBridge)
    from(layout.buildDirectory.dir("generated/jvmNative"))
    providers.environmentVariable("KMEDIA_JVM_NATIVE_DIR").orNull?.let {
        from(it) {
            into("native")
            exclude("$bridgeOs-$hostArch/**")
        }
    }
}
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(rootProject.file("README.md"))
}
publishing.publications.withType<MavenPublication>().configureEach {
    artifact(javadocJar)
    pom {
        name.set("KMediaNotification")
        description.set("Player-independent Kotlin Multiplatform system media sessions and remote controls")
        url.set("https://github.com/brahmkshatriya/kmedianotification")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("brahmkshatriya")
                name.set("Brahm Kshatriya")
                url.set("https://github.com/brahmkshatriya")
            }
        }
        scm {
            url.set("https://github.com/brahmkshatriya/kmedianotification")
            connection.set("scm:git:https://github.com/brahmkshatriya/kmedianotification.git")
            developerConnection.set("scm:git:ssh://git@github.com/brahmkshatriya/kmedianotification.git")
        }
    }
}
