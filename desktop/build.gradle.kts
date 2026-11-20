import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.koin.core)
    implementation(libs.filekit.dialogs)  // modern native open dialogs (Windows IFileDialog, macOS NSOpenPanel, Linux portal)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "com.example.kmptest.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g")
        // ProGuard (release) cannot read this Kotlin version's metadata and gains nothing here: the size is native libraries
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "KmpTest"
            packageVersion = providers.gradleProperty("app.version").get()
            description = "Removes duplicate frames from laggy camera recordings"
            vendor = "Example"
            // Only the JDK modules the app needs (from ./gradlew :desktop:suggestRuntimeModules) instead of the whole
            // JDK; jdk.unsupported is what JavaCPP and JNA use to load native code.
            modules("java.instrument", "java.management", "jdk.security.auth", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Kmp Test"
                shortcut = true
                dirChooser = true
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "com.example.kmptest"
            }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

// Windows portable build as ONE exe: packaging/windows/Launcher.cs with the createDistributable folder appended as
// a zip. Output: build/compose/binaries/main/single/KmpTest.exe
val packageSingleExe by tasks.registering {
    group = "compose desktop"
    description = "Packs the portable Windows app into a single exe (Windows only)"
    dependsOn("createDistributable")
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app/KmpTest").get().asFile
    val source = file("packaging/windows/Launcher.cs")
    val icon = file("icons/icon.ico")
    val work = layout.buildDirectory.dir("tmp/singleExe").get().asFile
    val out = layout.buildDirectory.file("compose/binaries/main/single/KmpTest.exe").get().asFile
    val version = providers.gradleProperty("app.version").get()
    inputs.dir(appDir)
    inputs.files(source, icon)
    outputs.file(out)
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    doLast {
        work.deleteRecursively()
        work.mkdirs()
        // the C# 5 compiler that ships with the .NET Framework 4.x on every Windows 10 / 11
        val csc = File(System.getenv("WINDIR"), "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
        check(csc.isFile) { "C# compiler not found: $csc" }
        val launcher = File(work, "launcher.exe")
        val compile = ProcessBuilder(
            csc.path, "/nologo", "/target:winexe", "/optimize+", "/win32icon:${icon.path}", "/out:${launcher.path}",
            "/r:System.IO.Compression.dll", "/r:System.IO.Compression.FileSystem.dll",
            "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", source.path,
        ).redirectErrorStream(true).start()
        val log = compile.inputStream.bufferedReader().readText()
        check(compile.waitFor() == 0) { "csc failed:\n$log" }

        val zip = File(work, "app.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            appDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(appDir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        // the id names the unpack folder: a new version or a changed build gets a fresh one
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val id = "$version-" + digest.digest().take(6).joinToString("") { "%02x".format(it) }
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val trailer = ByteBuffer.allocate(4 + 8 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(idBytes.size).putLong(launcher.length()).putLong(zip.length()).put("KMTPACK1".toByteArray())
        out.parentFile.mkdirs()
        out.outputStream().buffered().use { o ->
            launcher.inputStream().use { it.copyTo(o) }
            zip.inputStream().use { it.copyTo(o) }
            o.write(idBytes)
            o.write(trailer.array())
        }
        logger.lifecycle("Single exe: $out (${out.length() / 1_000_000} MB, id $id)")
    }
}
