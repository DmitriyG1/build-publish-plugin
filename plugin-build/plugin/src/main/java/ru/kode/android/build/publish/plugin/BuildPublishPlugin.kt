@file:Suppress("UnstableApiUsage")

package ru.kode.android.build.publish.plugin

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.tasks.PackageAndroidArtifact
import com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION
import com.google.firebase.appdistribution.gradle.AppDistributionExtension
import com.google.firebase.appdistribution.gradle.AppDistributionPlugin
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.util.internal.VersionNumber
import ru.kode.android.build.publish.plugin.enity.BuildVariant
import ru.kode.android.build.publish.plugin.extension.BuildPublishExtension
import ru.kode.android.build.publish.plugin.extension.EXTENSION_NAME
import ru.kode.android.build.publish.plugin.extension.config.*
import ru.kode.android.build.publish.plugin.git.mapper.fromJson
import ru.kode.android.build.publish.plugin.task.*
import ru.kode.android.build.publish.plugin.task.appcenter.AppCenterDistributionTask
import ru.kode.android.build.publish.plugin.util.capitalizedName
import ru.kode.android.build.publish.plugin.task.GenerateChangelogTask
import ru.kode.android.build.publish.plugin.task.GetLastTagTask
import ru.kode.android.build.publish.plugin.task.PrintLastIncreasedTag
import java.io.File

internal const val SEND_SLACK_CHANGELOG_TASK_PREFIX = "sendSlackChangelog"
internal const val SEND_TELEGRAM_CHANGELOG_TASK_PREFIX = "sendTelegramChangelog"
internal const val GENERATE_CHANGELOG_TASK_PREFIX = "generateChangelog"
internal const val PRINT_LAST_INCREASED_TAG_TASK_PREFIX = "printLastIncreasedTag"
internal const val GET_LAST_TAG_TASK_PREFIX = "getLastTag"
internal const val DEFAULT_BUILD_VERSION = "v0.0.1"
internal const val CHANGELOG_FILENAME = "changelog.txt"
internal const val APP_CENTER_DISTRIBUTION_UPLOAD_TASK_PREFIX = "appCenterDistributionUpload"

internal object AgpVersions {
    val CURRENT: VersionNumber = VersionNumber.parse(ANDROID_GRADLE_PLUGIN_VERSION).baseVersion
    val VERSION_7_0_4: VersionNumber = VersionNumber.parse("7.0.4")
}

abstract class BuildPublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.stopExecutionIfNotSupported()

        val buildPublishExtension = project.extensions
            .create(EXTENSION_NAME, BuildPublishExtension::class.java)
        val androidExtension = project.extensions
            .getByType(ApplicationAndroidComponentsExtension::class.java)
        val changelogFile = File(project.buildDir, CHANGELOG_FILENAME)

        androidExtension.onVariants(
            callback = { variant ->
                val output = variant.outputs
                    .find { it is VariantOutputImpl && it.fullName == variant.name }
                    as? VariantOutputImpl
                if (output != null) {
                    val outputFile = output.outputFileName.flatMap { fileName ->
                        project.tasks.withType(PackageAndroidArtifact::class.java)
                            .firstOrNull { it.variantName == variant.name }
                            ?.outputDirectory
                            ?.flatMap { directory ->
                                project.objects
                                    .fileProperty()
                                    .apply { set(File(directory.asFile, fileName)) }
                            }
                            ?: throw GradleException("no output for variant ${variant.name}")
                    }
                    val buildVariant = BuildVariant(variant.name, variant.flavorName, variant.buildType)
                    project.registerVariantTasks(buildPublishExtension, buildVariant, changelogFile, outputFile)
                    val getLastTagTask = project.tasks
                        .findByName("${GET_LAST_TAG_TASK_PREFIX}${buildVariant.capitalizedName()}")
                        as GetLastTagTask
                    output.versionCode.set(
                        getLastTagTask.tagBuildFile.map {
                            val file = it.asFile
                            if (file.exists()) fromJson(file).buildNumber else 1
                        }
                    )
                    output.versionName.set(
                        getLastTagTask.tagBuildFile.map {
                            val file = it.asFile
                            if (file.exists()) fromJson(file).name else "$DEFAULT_BUILD_VERSION-${buildVariant.name}"
                        }
                    )
                }
            }
        )
        androidExtension.finalizeDsl {
            project.configurePlugins(buildPublishExtension, changelogFile)
        }
    }

    private fun Project.registerVariantTasks(
        buildPublishExtension: BuildPublishExtension,
        buildVariant: BuildVariant,
        changelogFile: File,
        outputFile: Provider<RegularFile>,
    ) {
        tasks.apply {
            val getLastTagTaskProvider = registerGetLastTagTask(buildVariant)
            registerPrintLastIncreasedTagTask(
                buildVariant,
                getLastTagTaskProvider.flatMap { it.tagBuildFile }
            )
            val changelogConfig = buildPublishExtension.changelog.getByName("default") ?: return
            println("changelogConfig ${changelogConfig.commitMessageKey.get()}")
            val generateChangelogTaskProvider = registerGenerateChangelogTask(
                changelogConfig,
                buildVariant,
                changelogFile,
                getLastTagTaskProvider.flatMap { it.tagBuildFile }
            )
            val telegramConfig = buildPublishExtension.telegram.findByName("default")
            if (telegramConfig != null) {
                registerSendTelegramChangelogTask(
                    changelogConfig,
                    telegramConfig,
                    buildVariant,
                    generateChangelogTaskProvider.flatMap { it.changelogFile },
                    getLastTagTaskProvider.flatMap { it.tagBuildFile }
                )
            }
            val slackConfig = buildPublishExtension.slack.findByName("default")
            if (slackConfig != null) {
                registerSendSlackChangelogTask(
                    changelogConfig,
                    slackConfig,
                    buildVariant,
                    generateChangelogTaskProvider.flatMap { it.changelogFile },
                    getLastTagTaskProvider.flatMap { it.tagBuildFile }
                )
            }
            val appCenterDistributionConfig = buildPublishExtension.appCenterDistribution.findByName("default")
            if (appCenterDistributionConfig != null) {
                registerAppCenterDistributionTask(
                    appCenterDistributionConfig,
                    buildVariant,
                    generateChangelogTaskProvider.flatMap { it.changelogFile },
                    outputFile,
                )
            }
        }
    }

    private fun Project.registerGetLastTagTask(
        buildVariant: BuildVariant,
    ): Provider<GetLastTagTask> {
        val capitalizedBuildVariant = buildVariant.capitalizedName()
        return tasks.register(
            "$GET_LAST_TAG_TASK_PREFIX$capitalizedBuildVariant",
            GetLastTagTask::class.java
        ) { task ->
            val tagBuildFile = project.layout.buildDirectory
                .file("tag-build-${buildVariant.name}.json")
            task.tagBuildFile.set(tagBuildFile)
            task.buildVariant.set(buildVariant.name)
        }
    }

    private fun TaskContainer.registerPrintLastIncreasedTagTask(
        buildVariant: BuildVariant,
        tagBuildFileProvider: Provider<RegularFile>
    ) {
        val capitalizedBuildVariant = buildVariant.capitalizedName()
        register(
            "$PRINT_LAST_INCREASED_TAG_TASK_PREFIX$capitalizedBuildVariant",
            PrintLastIncreasedTag::class.java
        ) { task ->
            task.tagBuildFile.set(tagBuildFileProvider)
        }
    }

    private fun TaskContainer.registerGenerateChangelogTask(
        changelogConfig: ChangelogSettingsConfig,
        buildVariant: BuildVariant,
        changelogFile: File,
        tagBuildFileProvider: Provider<RegularFile>
    ): Provider<GenerateChangelogTask> {
        val capitalizedBuildVariant = buildVariant.capitalizedName()
        return register(
            "$GENERATE_CHANGELOG_TASK_PREFIX$capitalizedBuildVariant",
            GenerateChangelogTask::class.java
        ) {
            it.commitMessageKey.set(changelogConfig.commitMessageKey)
            it.buildVariant.set(buildVariant.name)
            it.changelogFile.set(changelogFile)
            it.tagBuildFile.set(tagBuildFileProvider)
        }
    }

    private fun TaskContainer.registerSendTelegramChangelogTask(
        changelogConfig: ChangelogSettingsConfig,
        telegramConfig: TelegramConfig,
        buildVariant: BuildVariant,
        changelogFileProvider: Provider<RegularFile>,
        tagBuildFileProvider: Provider<RegularFile>,
    ) {
        val capitalizedBuildVariant = buildVariant.capitalizedName()
        register(
            "$SEND_SLACK_CHANGELOG_TASK_PREFIX$capitalizedBuildVariant",
            SendTelegramChangelogTask::class.java
        ) {
            it.changelogFile.set(changelogFileProvider)
            it.tagBuildFile.set(tagBuildFileProvider)
            it.issueUrlPrefix.set(changelogConfig.issueUrlPrefix)
            it.issueNumberPattern.set(changelogConfig.issueNumberPattern)
            it.baseOutputFileName.set(changelogConfig.baseOutputFileName)
            it.webhookUrl.set(telegramConfig.webhookUrl)
            it.botId.set(telegramConfig.botId)
            it.chatId.set(telegramConfig.chatId)
            it.userMentions.set(telegramConfig.userMentions)
        }
    }

    private fun TaskContainer.registerAppCenterDistributionTask(
        config: AppCenterDistributionConfig,
        buildVariant: BuildVariant,
        changelogFileProvider: Provider<RegularFile>,
        outputFileProvider: Provider<RegularFile>,
    ): TaskProvider<AppCenterDistributionTask> {
        return register(
            "$APP_CENTER_DISTRIBUTION_UPLOAD_TASK_PREFIX${buildVariant.capitalizedName()}",
            AppCenterDistributionTask::class.java,
        ) {
            it.apiTokenFilePath.set(config.apiTokenFilePath)
            it.ownerName.set(config.ownerName)
            it.appName.set(config.appName)
            it.testerGroups.set(config.testerGroups)
            it.buildVariantOutputFile.set(outputFileProvider)
            it.changelogFile.set(changelogFileProvider)
        }
    }

    private fun TaskContainer.registerSendSlackChangelogTask(
        changelogConfig: ChangelogSettingsConfig,
        slackConfig: SlackConfig,
        buildVariant: BuildVariant,
        changelogFileProvider: Provider<RegularFile>,
        tagBuildFileProvider: Provider<RegularFile>,
    ) {
        val capitalizedBuildVariant = buildVariant.capitalizedName()
        register(
            "$SEND_TELEGRAM_CHANGELOG_TASK_PREFIX$capitalizedBuildVariant",
            SendSlackChangelogTask::class.java
        ) {
            it.changelogFile.set(changelogFileProvider)
            it.tagBuildFile.set(tagBuildFileProvider)
            it.issueUrlPrefix.set(changelogConfig.issueUrlPrefix)
            it.issueNumberPattern.set(changelogConfig.issueNumberPattern)
            it.baseOutputFileName.set(changelogConfig.baseOutputFileName)
            it.webhookUrl.set(slackConfig.webhookUrl)
            it.iconUrl.set(slackConfig.iconUrl)
            it.userMentions.set(slackConfig.userMentions)
        }
    }

    private fun Project.configurePlugins(
        buildPublishExtension: BuildPublishExtension,
        changelogFile: File,
    ) {
        val firebaseAppDistributionConfig = buildPublishExtension.firebaseDistribution.findByName("default")
        plugins.all { plugin ->
            when (plugin) {
                is AppPlugin -> {
                    val appExtension = extensions.getByType(AppExtension::class.java)
                    appExtension.configure()
                }
                is AppDistributionPlugin -> if (firebaseAppDistributionConfig != null) {
                    val appDistributionExtension = extensions
                        .getByType(AppDistributionExtension::class.java)
                    appDistributionExtension.configure(
                        config = firebaseAppDistributionConfig,
                        changelogFile = changelogFile,
                    )
                }
            }
        }
    }
}

@Suppress("ThrowsCount") // block to throws exceptions on apply
private fun Project.stopExecutionIfNotSupported() {
    if (AgpVersions.CURRENT < AgpVersions.VERSION_7_0_4) {
        throw StopExecutionException(
            "Must only be used with with Android Gradle Plugin >= 7.4 "
        )
    }
    if (!plugins.hasPlugin(AppPlugin::class.java)) {
        throw StopExecutionException(
            "Must only be used with Android application projects." +
                " Please apply the 'com.android.application' plugin."
        )
    }

    if (!plugins.hasPlugin(AppDistributionPlugin::class.java)) {
        throw StopExecutionException(
            "Must only be used with Firebase App Distribution." +
                " Please apply the 'com.google.firebase.appdistribution' plugin."
        )
    }
}

private fun AppExtension.configure() {
    defaultConfig {
        it.versionCode = 1
        it.versionName = "$DEFAULT_BUILD_VERSION-dev"
    }
}

private fun AppDistributionExtension.configure(
    config: FirebaseAppDistributionConfig,
    changelogFile: File,
) {
    val serviceCredentialsFilePath = config
        .serviceCredentialsFilePath.orNull
        ?.takeIf { it.isNotBlank() }
    val applicationId = config
        .appId.orNull
        ?.takeIf { it.isNotBlank() }
    val testerGroups = config.testerGroups.get()
    val artifactType = config.artifactType.orNull ?: "APK"

    if (applicationId != null) {
        appId = applicationId
    }
    serviceCredentialsFile = serviceCredentialsFilePath.orEmpty()
    releaseNotesFile = changelogFile.path
    this.artifactType = artifactType
    this.groups = testerGroups.joinToString(",")
}
