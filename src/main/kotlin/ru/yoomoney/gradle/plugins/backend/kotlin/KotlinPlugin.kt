package ru.yoomoney.gradle.plugins.backend.kotlin

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.apache.commons.io.IOUtils
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.KtlintPlugin
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import ru.yoomoney.gradle.plugins.backend.build.JavaPlugin
import ru.yoomoney.gradle.plugins.backend.build.getStaticAnalysisLimit
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Плагин для сборки kotlin модулей компонента
 *
 * @author Valerii Zhirnov
 * @since 22.03.2019
 */
class KotlinPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(JavaPlugin::class.java)
        target.pluginManager.apply(KotlinPluginWrapper::class.java)
        target.pluginManager.apply(KtlintPlugin::class.java)
        target.pluginManager.apply(DetektPlugin::class.java)

        configureKtlint(target)
        configureDetekt(target)

        target.afterEvaluate {
            target.tasks.getByName("compileJava").dependsOn(target.tasks.getByName("compileKotlin"))
        }
    }

    private fun configureKtlint(target: Project) {
        target.extensions.getByType(KtlintExtension::class.java).apply {
            reporters(Action { listOf(ReporterType.PLAIN_GROUP_BY_FILE, ReporterType.CHECKSTYLE) })
            disabledRules.set(setOf("import-ordering", "final-newline"))
            android.set(false)
        }
    }

    private fun configureDetekt(target: Project) {
        target.extensions.getByType(DetektExtension::class.java).apply {
            source = target.files(DetektExtension.DEFAULT_SRC_DIR_KOTLIN)
            config = target.files(target.layout.buildDirectory.get().file("detekt.yml").asFile)
        }
        target.tasks.create("copyDetektConfig") {
            it.doLast {
                val config = File.createTempFile("detekt", ".yml")
                config.writeText(detektConfig().replace("%MAX_ISSUES%", getDetektLimit(target).toString(), false))
                val targetConfig = Paths.get(target.buildDir.absolutePath, "detekt.yml")
                Files.deleteIfExists(targetConfig)
                Files.move(config.toPath(), targetConfig)
                Files.deleteIfExists(config.toPath())
            }
            it.dependsOn(target.tasks.findByName(org.gradle.api.plugins.JavaPlugin.PROCESS_RESOURCES_TASK_NAME))
        }
        target.tasks.withType(Detekt::class.java).forEach {
            it.dependsOn("copyDetektConfig")
        }
    }

    private fun getDetektLimit(target: Project): Int {
        return getStaticAnalysisLimit(target, "detekt").orElse(99999) + 1
    }

    private fun detektConfig(): String {
        val inputStream = this.javaClass.getResourceAsStream("/detekt.yml")
        return IOUtils.toString(inputStream, StandardCharsets.UTF_8)
    }
}
