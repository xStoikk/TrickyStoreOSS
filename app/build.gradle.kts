/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.variant.ApplicationVariant

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

plugins { alias(libs.plugins.android.application) }

/** Product version string (module packaging + runtime diagnostics). */
val verName = "v3.1.6-auto-tee-passthrough"

/** Runtime architecture milestone embedded in BUILD_ID; not incremented for build-only phases. */
val teeBuildPhase = "4C"

val gitCommitCountProvider =
    providers.exec {
        commandLine("git", "rev-list", "HEAD", "--count")
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }

val gitCommitHashProvider =
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifBlank { "unknown" } }

val gitDirtyTreeProvider =
    providers.exec {
        commandLine("git", "diff-index", "--quiet", "HEAD", "--")
        workingDir = rootDir
        isIgnoreExitValue = true
    }.result.map { it.exitValue != 0 }

/** HEAD short SHA for packaging (module.prop / ZIP); never includes -dirty suffix. */
val gitCommitHash = gitCommitHashProvider.get()

val gitCommitCount = gitCommitCountProvider.get()

/** TeeBuildInfo.GIT: short SHA, plus -dirty when tracked files differ from HEAD. */
val teeBuildInfoGitProvider =
    gitCommitHashProvider.zip(gitDirtyTreeProvider) { hash, dirty ->
        when {
            hash == "unknown" -> "unknown"
            dirty -> "$hash-dirty"
            else -> hash
        }
    }

val teeBuildInfoOutputRelativePath =
    "generated/source/teeBuildInfo/kotlin/io/github/beakthoven/TrickyStoreOSS/tee/TeeBuildInfo.kt"

tasks.register("generateTeeBuildInfo") {
    val outputFile = layout.buildDirectory.file(teeBuildInfoOutputRelativePath)
    val gitRevision = teeBuildInfoGitProvider
    val productVersion = verName
    val runtimePhase = teeBuildPhase

    inputs.property("gitRevision", gitRevision)
    inputs.property("productVersion", productVersion)
    inputs.property("runtimePhase", runtimePhase)
    outputs.file(outputFile)

    doLast {
        val git = gitRevision.get()
        if (git == "unknown") {
            logger.warn("generateTeeBuildInfo: git unavailable; TeeBuildInfo.GIT=unknown")
        }
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            /*
             * GENERATED FILE — do not edit.
             * Produced by the generateTeeBuildInfo Gradle task.
             */

            package io.github.beakthoven.TrickyStoreOSS.tee

            object TeeBuildInfo {
                const val VERSION = "$productVersion"
                const val GIT = "$git"
                const val PHASE = "$runtimePhase"
                const val BUILD_ID = "version=${'$'}VERSION git=${'$'}GIT phase=${'$'}PHASE"
            }
            """
                .trimIndent() + "\n",
        )
    }
}

tasks.register("verifyTeeBuildInfoGit") {
    dependsOn("generateTeeBuildInfo")
    val outputFile = layout.buildDirectory.file(teeBuildInfoOutputRelativePath)
    val expectedGit = teeBuildInfoGitProvider

    inputs.file(outputFile)
    inputs.property("expectedGit", expectedGit)

    doLast {
        val content = outputFile.get().asFile.readText()
        val embedded =
            Regex("""const val GIT = "([^"]+)"""")
                .find(content)
                ?.groupValues
                ?.get(1)
                ?: error("verifyTeeBuildInfoGit: GIT constant missing from generated TeeBuildInfo.kt")
        val expected = expectedGit.get()
        check(embedded == expected) {
            "verifyTeeBuildInfoGit: embedded GIT=$embedded expected $expected"
        }
    }
}

android {
    namespace = "io.github.beakthoven.TrickyStoreOSS"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.beakthoven.TrickyStoreOSS"
        minSdk = 29
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = verName

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=ON"
                arguments += "-DCMAKE_CXX_STANDARD=23"
                arguments += "-DCMAKE_C_STANDARD=23"
                arguments += "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON"

                cppFlags += "-std=c++23"
                cppFlags += "-fno-exceptions"
                cppFlags += "-fno-rtti"
                cppFlags += "-fvisibility=hidden"
                cppFlags += "-fvisibility-inlines-hidden"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.0+"
        }
    }
    buildFeatures { prefab = true }
    packaging { resources { pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/INDEX.LIST") } }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
    sourceSets.named("main") {
        kotlin.srcDir("$projectDir/build/generated/source/teeBuildInfo/kotlin")
    }
}

tasks.named("preBuild").configure {
    dependsOn("generateTeeBuildInfo", "verifyTeeBuildInfoGit")
}

dependencies {
    compileOnly(project(":stub"))
    compileOnly(libs.annotation)
    implementation(libs.org.bouncycastle.bcpkix.jdk18on)
    implementation(libs.org.lsposed.libcxx.libcxx)
    testImplementation("junit:junit:4.13.2")
}

androidComponents {
    onVariants { variant: ApplicationVariant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val isDebugVariant = variantName.contains("debug", ignoreCase = true)
        val tempModuleDir = layout.buildDirectory.dir("tmp/module-$variantName")
        val variantStageDir = layout.buildDirectory.dir("moduleStage/$variantName")

        tasks.register("copyFiles${capitalized}") {
            val buildDir = layout.buildDirectory
            val stageDirProvider = variantStageDir

            outputs.dir(stageDirProvider)

            doLast {
                val stageDir = stageDirProvider.get().asFile
                stageDir.deleteRecursively()
                stageDir.mkdirs()

                val buildOutput =
                    if (isDebugVariant) {
                        buildDir.get().asFile.resolve("outputs/apk/$variantName/app-$variantName.apk")
                    } else {
                        buildDir.get().asFile.resolve("intermediates/dex/release/minifyReleaseWithR8/classes.dex")
                    }

                val stagedArtifactName = if (isDebugVariant) "service.apk" else "classes.dex"
                buildOutput.copyTo(stageDir.resolve(stagedArtifactName), overwrite = true)

                val soDir =
                    buildDir
                        .get()
                        .asFile
                        .resolve(
                            "intermediates/stripped_native_libs/$variantName/strip${capitalized}DebugSymbols/out/lib"
                        )

                val allowedLibs = setOf("libinject.so", "libTrickyStoreOSS.so")
                soDir
                    .walk()
                    .filter { it.isFile && it.name in allowedLibs }
                    .forEach { soFile ->
                        val abiFolder = soFile.parentFile.name
                        val destination = stageDir.resolve("lib/$abiFolder/${soFile.name}")
                        destination.parentFile.mkdirs()
                        soFile.copyTo(destination, overwrite = true)
                    }
            }
        }

        tasks.register("prepareModuleFiles${capitalized}") {
            dependsOn("copyFiles${capitalized}")
            val sourceDir = rootProject.file("module")
            val commitCount = gitCommitCount
            val commitHash = gitCommitHash
            val versionName = verName
            val variant = variantName
            val tempDirProvider = tempModuleDir
            val stageDirProvider = variantStageDir

            inputs.dir(sourceDir)
            inputs.dir(stageDirProvider)
            outputs.dir(tempDirProvider)

            doLast {
                val tempDir = tempDirProvider.get().asFile
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                val generatedArtifacts = setOf("service.apk", "classes.dex")
                val generatedPaths = setOf("lib")
                sourceDir
                    .walkTopDown()
                    .filter { file ->
                        if (!file.isFile || file.name == "module.prop") return@filter false
                        if (file.name in generatedArtifacts) return@filter false
                        val relative = sourceDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        generatedPaths.none { relative == it || relative.startsWith("$it/") }
                    }
                    .forEach { sourceFile ->
                        val relativePath = sourceFile.relativeTo(sourceDir)
                        val destFile = tempDir.resolve(relativePath.path)
                        destFile.parentFile.mkdirs()
                        sourceFile.copyTo(destFile, overwrite = true)
                        val normalizedPath = destFile.path.replace('\\', '/')
                        val isShellScript =
                            destFile.extension == "sh" ||
                                destFile.name == "daemon" ||
                                normalizedPath.endsWith("META-INF/com/google/android/update-binary") ||
                                normalizedPath.endsWith("META-INF/com/google/android/updater-script")
                        if (isShellScript) {
                            val text = destFile.readText(StandardCharsets.UTF_8)
                            val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
                            if (text != normalized) destFile.writeText(normalized, StandardCharsets.UTF_8)
                        }
                    }

                val stageDir = stageDirProvider.get().asFile
                if (stageDir.exists()) {
                    stageDir
                        .walkTopDown()
                        .filter { it.isFile }
                        .forEach { sourceFile ->
                            val relativePath = stageDir.toPath().relativize(sourceFile.toPath())
                            val destFile = tempDir.resolve(relativePath.toString())
                            destFile.parentFile.mkdirs()
                            sourceFile.copyTo(destFile, overwrite = true)
                        }
                }

                val sourceProp = sourceDir.resolve("module.prop")
                val destProp = tempDir.resolve("module.prop")
                val content = sourceProp.readText()
                val processedContent =
                    content
                        .replace("REPLACEMEVERCODE", commitCount.toString())
                        .replace("REPLACEMEVER", "$versionName ($commitCount-$commitHash-$variant)")
                destProp.writeText(processedContent)
            }
        }

        val zipTask =
            tasks.register<Zip>("zip${capitalized}") {
                dependsOn("prepareModuleFiles${capitalized}")
                archiveFileName.set("Tricky-Store-OSS-$verName-$gitCommitCount-$gitCommitHash-${capitalized}.zip")
                destinationDirectory.set(rootProject.file("out"))
                from(tempModuleDir)
            }

        tasks.register("verify${capitalized}ModuleContents") {
            dependsOn(zipTask)
            val zipArchive = zipTask.flatMap { it.archiveFile }
            inputs.file(zipArchive)
            val expectedVariantToken = variantName.lowercase()

            doLast {
                val zipFile = zipArchive.get().asFile
                logger.lifecycle(
                    "verify${capitalized}ModuleContents: validating ${zipFile.absolutePath}"
                )

                val names = mutableSetOf<String>()
                ZipFile(zipFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory) names += entry.name
                    }
                }

                if (isDebugVariant) {
                    check("service.apk" in names) { "${zipFile.name} missing Debug service.apk" }
                    check("classes.dex" !in names) {
                        "${zipFile.name} must not contain Release classes.dex (Debug/Release staging leak)"
                    }
                } else {
                    check("classes.dex" in names) { "${zipFile.name} missing Release classes.dex" }
                    check("service.apk" !in names) {
                        "${zipFile.name} must not contain Debug service.apk (Debug/Release staging leak)"
                    }
                }

                val moduleProp =
                    ZipFile(zipFile).use { zip ->
                        zip.getEntry("module.prop")?.let { entry ->
                            zip.getInputStream(entry).bufferedReader().readText()
                        } ?: error("${zipFile.name} missing module.prop")
                    }
                check(moduleProp.contains(expectedVariantToken, ignoreCase = true)) {
                    "${zipFile.name} module.prop must reference variant '$expectedVariantToken'"
                }
            }
        }

        tasks.matching { it.name == "assemble${capitalized}" }.configureEach {
            finalizedBy("zip${capitalized}", "verify${capitalized}ModuleContents")
        }
    }
}
