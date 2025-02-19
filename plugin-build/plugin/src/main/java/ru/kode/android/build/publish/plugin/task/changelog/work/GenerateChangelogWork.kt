package ru.kode.android.build.publish.plugin.task.changelog.work

import org.ajoberstar.grgit.gradle.GrgitService
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import ru.kode.android.build.publish.plugin.command.GitCommandExecutor
import ru.kode.android.build.publish.plugin.enity.mapper.fromJson
import ru.kode.android.build.publish.plugin.task.changelog.git.ChangelogBuilder
import ru.kode.android.build.publish.plugin.task.changelog.git.GitRepository
import ru.kode.android.build.publish.plugin.util.ellipsizeAt
import javax.inject.Inject

interface GenerateChangelogParameters : WorkParameters {
    val commitMessageKey: Property<String>
    val buildVariant: Property<String>
    val tagBuildFile: RegularFileProperty
    val changelogFile: RegularFileProperty
    val grgitService: Property<GrgitService>
}

abstract class GenerateChangelogWork @Inject constructor() : WorkAction<GenerateChangelogParameters> {

    private val logger = Logging.getLogger(this::class.java)

    override fun execute() {
        val messageKey = parameters.commitMessageKey.get()
        val currentBuildTag = fromJson(parameters.tagBuildFile.asFile.get())
        val buildVariants = setOf(parameters.buildVariant.get())
        val gitCommandExecutor = GitCommandExecutor(parameters.grgitService.get())
        val gitRepository = GitRepository(gitCommandExecutor, buildVariants)
        val changelog = ChangelogBuilder(gitRepository, gitCommandExecutor, logger, messageKey)
            .buildForBuildTag(
                currentBuildTag,
                defaultValueSupplier = { tagRange ->
                    val previousBuildName = tagRange.previousBuildTag?.name?.let { "(**$it**)" }
                    "No changes in comparison with a previous build $previousBuildName"
                }
            )
            ?.ellipsizeAt(MAX_CHANGELOG_SYMBOLS)
        val changelogOutput = parameters.changelogFile.asFile.get()
        if (changelog.isNullOrBlank()) {
            logger.debug("changelog not generated")
        } else {
            logger.debug("generate changelog")
            changelogOutput.writeText(changelog)
        }
    }
}

private const val MAX_CHANGELOG_SYMBOLS = 2000
