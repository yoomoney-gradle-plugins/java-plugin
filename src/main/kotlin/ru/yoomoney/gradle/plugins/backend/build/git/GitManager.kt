package ru.yoomoney.gradle.plugins.backend.build.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.Project

/**
 * Клиент для работы с гитом
 *
 * @author Valerii Zhirnov
 * @since 23.04.2019
 */
class GitManager(project: Project) : AutoCloseable {

    private val git: Git = Git(
            FileRepositoryBuilder()
                    .readEnvironment()
                    .findGitDir(project.projectDir)
                    .build()
    )

    private fun describe(): String? = git.describe().setTags(true).call()

    private fun isMasterBranch(): Boolean = branchName().equals("master", true)

    private fun isDevBranch(): Boolean = branchName().equals("dev", true)

    private fun isMasterOrDev(): Boolean = isMasterBranch() || isDevBranch()

    private fun isReleaseBranch(): Boolean = branchName().matches(Regex("(release)/.*"))

    private fun isHotfixBranch(): Boolean = branchName().matches(Regex("(hotfix)/.*"))

    private fun isReleaseTag(): Boolean = describe()?.matches(Regex("\\d+\\.\\d+\\.\\d+")) ?: false

    private fun isStableBranch(): Boolean = isMasterOrDev() || isReleaseBranch() || isHotfixBranch() || isReleaseTag()

    fun isDevelopmentBranch(): Boolean = !isStableBranch()

    fun branchName(): String = git.repository.branch

    fun branchFullName(): String = branchName().replace(Regex("[^a-zA-Z0-9\\-\\.]+"), "-")

    override fun close() = git.close()
}
