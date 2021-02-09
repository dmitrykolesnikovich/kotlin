/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.cinterop

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.jetbrains.kotlin.gradle.plugin.CInteropSettings
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.enabledOnCurrentHost
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

open class CInteropExtension(internal val project: Project) {

    internal fun bind(konanTarget: KonanTarget, file: RegularFile) {
        // TODO
    }

    internal fun bind(konanTarget: KonanTarget, file: Provider<File>) {
        // TODO
    }

    fun linuxX64(file: Provider<File>) {
        bind(KonanTarget.LINUX_X64, file)
    }

    fun linuxX64(action: CInteropSettings.() -> Unit) {
        create(KonanTarget.LINUX_X64, action)
    }

    fun macosX64(file: Provider<File>) {
        bind(KonanTarget.MACOS_X64, file)
    }

    fun macosX64(action: CInteropSettings.() -> Unit) {
        create(KonanTarget.MACOS_X64, action)
    }

    internal fun create(konanTarget: KonanTarget, action: CInteropSettings.() -> Unit) {
        val cInteropDependencyConfigurationName = lowerCamelCaseName(konanTarget.name, "cinterop", "dependencies")
        val buildCInteropTaskName = lowerCamelCaseName("build", konanTarget.name, "cinterop")
        val settings = DefaultCInteropSettings(
            project, name = konanTarget.name,
            dependencyConfigurationName = cInteropDependencyConfigurationName,
            interopProcessingTaskName = buildCInteropTaskName,
            konanTarget = konanTarget
        )

        settings.action()

        val buildCInteropTask = project.tasks.register(buildCInteropTaskName, CInteropProcess::class.java) {
            it.settings = settings
            it.destinationDir = project.provider { project.buildDir.resolve("cInterop") }
            it.group = KotlinNativeTargetConfigurator.INTEROP_GROUP
            it.description = "Generates Kotlin/Native interop library '${settings.name}' " +
                    "of target '${it.konanTarget.name}'."
            it.enabled = konanTarget.enabledOnCurrentHost
            it.baseKlibName = lowerCamelCaseName(project.name, konanTarget.name, "c", "interop")
        }

        project.tasks.register(lowerCamelCaseName("provide", konanTarget.name, "c", "interop"), FileCInteropProviderTask::class.java) {
            it.konanTarget = konanTarget
            it.outputFile.set(buildCInteropTask.flatMap { it.outputFileProvider })
            it.dependsOn(buildCInteropTask)
        }
    }
}


open class FileCInteropProviderTask : DefaultTask() {
    @get:Input
    lateinit var konanTarget: KonanTarget
        internal set

    @get:OutputFile
    val outputFile: Property<File> = project.objects.property(File::class.java)
}
