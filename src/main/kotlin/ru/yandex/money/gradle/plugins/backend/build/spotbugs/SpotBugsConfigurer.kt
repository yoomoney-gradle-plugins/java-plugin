package ru.yandex.money.gradle.plugins.backend.build.spotbugs

import com.github.spotbugs.SpotBugsExtension
import com.github.spotbugs.SpotBugsTask
import com.github.spotbugs.SpotBugsXmlReport
import org.apache.commons.io.IOUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.internal.file.TmpDirTemporaryFileProvider
import org.gradle.api.internal.resources.StringBackedTextResource
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import ru.yandex.money.gradle.plugins.backend.build.JavaModuleExtension
import ru.yandex.money.gradle.plugins.backend.build.git.GitManager
import ru.yandex.money.gradle.plugins.backend.build.staticanalysis.StaticAnalysisProperties
import java.lang.Integer.max
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Настраивает статический анализ SpotBugs
 *
 * @author Valerii Zhirnov (vazhirnov@yamoney.ru)
 * @since 19.04.2019
 */
class SpotBugsConfigurer {

    fun init(target: Project) {
        val gitManager = GitManager(target)

        val extension = target.extensions.getByType(SpotBugsExtension::class.java)
        extension.sourceSets = listOf(
                target.convention.getPlugin(JavaPluginConvention::class.java)
                        .sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        )
        extension.toolVersion = "3.1.12"
        extension.reportsDir = target.file(target.buildDir.resolve("spotbugsReports"))
        extension.effort = "default"
        extension.reportLevel = "medium"
        extension.excludeFilterConfig = StringBackedTextResource(TmpDirTemporaryFileProvider(), spotbugsExclude())
        extension.isIgnoreFailures = true

        target.tasks.withType(SpotBugsTask::class.java).forEach {
            it.reports.html.isEnabled = false
            it.reports.xml.isEnabled = true
        }

        target.afterEvaluate {
            extension.sourceSets = listOf(
                    target.convention.getPlugin(JavaPluginConvention::class.java)
                            .sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
            )

            /*
            Отключает все таски spotbugs не относящиеся к main source set'у
            Возникают проблемы с плагинами которые создают свои конфигурации, которые экстендятся из стандартных
            джавовских, на них потом пытается пройти конфигуратор spotbugs и фейлится
            Возможно починено в новых версиях плагина, но там нужен gradle версии 5 и выше
             */
            target.tasks.all { task ->
                if (task.path.contains("spotbugs") && !task.path.contains("Main")) {
                    task.enabled = false
                }
            }
        }

        target.tasks.filter { it.name.contains("spotbugs") }
                .forEach { it.onlyIf { spotbugsEnabled(target, gitManager) } }

        target.tasks.getByName("build").dependsOn("spotbugsMain")
        applyCheckTask(target)
        target.tasks.getByName("spotbugsMain").finalizedBy("checkFindBugsReport")
        target.dependencies.add(
                "spotbugsPlugins",
                "com.mebigfatguy.sb-contrib:sb-contrib:7.4.5"
        )
        target.dependencies.add(
                "spotbugsPlugins",
                "com.h3xstream.findsecbugs:findsecbugs-plugin:1.9.0"
        )
    }

    private fun applyCheckTask(target: Project) {
        target.tasks.create("checkFindBugsReport").doLast {
            val staticAnalysis = StaticAnalysisProperties.load(target)

            val findbugsLimit = staticAnalysis?.findbugs
            if (findbugsLimit == null) {
                target.logger.warn("findbugs limit not found, skipping check")
                return@doLast
            }

            val xmlReport = target.tasks.maybeCreate("spotbugsMain", SpotBugsTask::class.java).reports.xml
            // если в проекте нет исходников, то отчет создан не будет. Пропускает такие проекты
            if (!xmlReport.destination.exists()) {
                target.logger.lifecycle("SpotBugs report not found: ${xmlReport.destination}. SpotBugs skipped.")
                return@doLast
            }

            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlReport.destination)
            val bugsFound = document.getElementsByTagName("BugInstance").length

            when {
                bugsFound > findbugsLimit -> throw GradleException("Too much SpotBugs errors: actual=$bugsFound, " +
                        "limit=$findbugsLimit. See the report at: ${xmlReport.destination}")
                bugsFound < max(0, findbugsLimit - 10) -> updateIfLocalOrElseThrow(target, staticAnalysis, bugsFound,
                        findbugsLimit, xmlReport)
                else -> logSuccess(target, bugsFound, findbugsLimit, xmlReport)
            }
        }
    }

    private fun spotbugsEnabled(target: Project, gitManager: GitManager): Boolean {
        if (!gitManager.isDevelopmentBranch()) {
            target.logger.warn("SpotBugs check is enabled on feature/ and hotfix/ and bugfix/ branches. Skipping.")
            return false
        }
        val module = target.extensions.getByType(JavaModuleExtension::class.java)
        return module.spotbugsEnabled
    }

    private fun spotbugsExclude(): String {
        val inputStream = this.javaClass.getResourceAsStream("/findbugs-exclude.xml")
        return IOUtils.toString(inputStream, StandardCharsets.UTF_8)
    }

    private fun updateIfLocalOrElseThrow(target: Project, staticAnalysis: StaticAnalysisProperties, bugsFound: Int, bugsLimit: Int, xmlReport: SpotBugsXmlReport) {
        if (!target.hasProperty("ci")) {
            staticAnalysis.findbugs = bugsFound
            staticAnalysis.store()

            logSuccess(target, bugsFound, bugsLimit, xmlReport)
        } else {
            throw GradleException("SpotBugs limit is too high, " +
                    "must be $bugsFound. Decrease it in file static-analysis.properties.")
        }
    }

    private fun logSuccess(target: Project, bugsFound: Int, bugsLimit: Int, xmlReport: SpotBugsXmlReport) {
        target.logger.lifecycle("SpotBugs successfully passed with $bugsFound (limit=$bugsLimit) errors." +
                " See the report at: ${xmlReport.destination}")
    }
}
