/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.tasks

import com.android.SdkConstants
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.crash.PluginCrashReporter
import com.android.build.gradle.internal.errors.MessageReceiverImpl
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.workeractions.WorkerActionServiceRegistry
import com.android.build.gradle.internal.workeractions.WorkerActionServiceRegistry.Companion.INSTANCE
import com.android.build.gradle.options.SyncOptions
import com.android.builder.core.DefaultDexOptions
import com.android.builder.dexing.ClassFileEntry
import com.android.builder.dexing.ClassFileInputs
import com.android.builder.dexing.DexArchiveBuilder
import com.android.builder.dexing.DexArchiveBuilderConfig
import com.android.builder.dexing.DexArchiveBuilderException
import com.android.builder.dexing.DexerTool
import com.android.builder.dexing.r8.ClassFileProviderFactory
import com.android.builder.utils.FileCache
import com.android.dx.command.dexer.DxContext
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver
import com.android.ide.common.blame.ParsingProcessOutputHandler
import com.android.ide.common.blame.parser.DexParser
import com.android.ide.common.blame.parser.ToolOutputParser
import com.android.ide.common.internal.WaitableExecutor
import com.android.ide.common.process.ProcessException
import com.android.ide.common.process.ProcessOutput
import com.android.utils.FileUtils
import com.google.common.base.Preconditions
import com.google.common.base.Throwables
import com.google.common.collect.Iterables
import com.google.common.hash.Hashing
import org.gradle.api.logging.Logging
import org.gradle.tooling.BuildException
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.gradle.workers.IsolationMode
import org.gradle.workers.WorkerExecutor
import java.io.BufferedInputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.Serializable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.function.Supplier
import javax.inject.Inject
import kotlin.math.abs

/**
 * Delegate for the [DexArchiveBuilderTask]. This is where the actual processing happens. Using the
 * inputs in the task, the delegate instance is configured. Main processing happens in [doProcess].
 */
class DexArchiveBuilderTaskDelegate(
    private val isIncremental: Boolean,

    private val androidJarClasspath: Set<File>,

    private val projectClasses: Set<File>,
    private val projectChangedClasses: Set<FileChange> = emptySet(),

    private val subProjectClasses: Set<File>,
    private val subProjectChangedClasses: Set<FileChange> = emptySet(),

    private val externalLibClasses: Set<File>,
    private val externalLibChangedClasses: Set<FileChange> = emptySet(),

    private val mixedScopeClasses: Set<File>,
    private val mixedScopeChangedClasses: Set<FileChange> = emptySet(),

    private val projectOutputDex: File,
    private val subProjectOutputDex: File,
    private val externalLibsOutputDex: File,
    private val mixedScopeOutputDex: File,
    private val inputJarHashesFile: File,

    private val desugaringClasspathClasses: Set<File>,
    private val desugaringClasspathChangedClasses: Set<FileChange> = emptySet(),

    private val errorFormatMode: SyncOptions.ErrorFormatMode,
    private val minSdkVersion: Int,
    private val dexer: DexerTool,
    private val useGradleWorkers: Boolean,
    private val inBufferSize: Int,
    private val outBufferSize: Int,
    private val isDebuggable: Boolean,
    private val java8LangSupportType: VariantScope.Java8LangSupport,
    projectVariant: String,
    private val numberOfBuckets: Int,
    private val isDxNoOptimizeFlagPresent: Boolean,
    private val libConfiguration: String?,
    private var messageReceiver: MessageReceiver,
    private val executor: WaitableExecutor = WaitableExecutor.useGlobalSharedThreadPool(),
    userLevelCache: FileCache? = null,

    private val workerExecutor: WorkerExecutor
) {

    private val cacheHandler: DexArchiveBuilderCacheHandler =
        DexArchiveBuilderCacheHandler(
            userLevelCache,
            isDxNoOptimizeFlagPresent,
            minSdkVersion,
            isDebuggable,
            dexer
        )

    private val changedFiles =
        with(
            HashSet<File>(
                projectChangedClasses.size +
                        subProjectChangedClasses.size +
                        externalLibChangedClasses.size +
                        mixedScopeChangedClasses.size +
                        desugaringClasspathChangedClasses.size
            )
        ) {
            addAll(projectChangedClasses.map { it.file })
            addAll(subProjectChangedClasses.map { it.file })
            addAll(externalLibChangedClasses.map { it.file })
            addAll(mixedScopeChangedClasses.map { it.file })
            addAll(desugaringClasspathChangedClasses.map { it.file })

            this
        }

    private val desugarIncrementalHelper: DesugarIncrementalHelper? =
        DesugarIncrementalHelper(
            projectVariant,
            isIncremental,
            Iterables.concat(
                projectClasses,
                subProjectClasses,
                externalLibClasses,
                mixedScopeClasses,
                desugaringClasspathClasses
            ),
            Supplier { changedFiles.mapTo(HashSet<Path>(changedFiles.size)) { it.toPath() } },
            executor
        ).takeIf { java8LangSupportType == VariantScope.Java8LangSupport.D8 }

    private var inputJarHashesValues: MutableMap<File, String> = getCurrentJarInputHashes()

    /**
     * Classpath resources provider is shared between invocations, and this key uniquely identifies
     * it.
     */
    data class ClasspathServiceKey(private val id: Long) :
        WorkerActionServiceRegistry.ServiceKey<ClassFileProviderFactory> {

        override val type = ClassFileProviderFactory::class.java
    }

    /** Wrapper around the [com.android.builder.dexing.r8.ClassFileProviderFactory].  */
    class ClasspathService(override val service: ClassFileProviderFactory) :
        WorkerActionServiceRegistry.RegisteredService<ClassFileProviderFactory> {

        override fun shutdown() {
            // nothing to be done, as providerFactory is a closable
        }
    }

    fun doProcess() {
        if (isDxNoOptimizeFlagPresent) {
            loggerWrapper.warning(DefaultDexOptions.OPTIMIZE_WARNING)
        }

        loggerWrapper.verbose("Dex builder is incremental : %b ", isIncremental)
        if (!isIncremental) {
            FileUtils.cleanOutputDir(projectOutputDex)
            FileUtils.cleanOutputDir(subProjectOutputDex)
            FileUtils.cleanOutputDir(externalLibsOutputDex)
            FileUtils.cleanOutputDir(mixedScopeOutputDex)
        } else {
            deletePreviousOutputsFromJars()
            deletePreviousOutputsFromDirs()
        }

        val additionalPaths: Set<File> =
            desugarIncrementalHelper?.additionalPaths?.map { it.toFile() }?.toSet()
                ?: emptySet()

        val classpath = getClasspath(java8LangSupportType)
        val bootclasspath = getBootClasspath(androidJarClasspath, java8LangSupportType)

        var bootclasspathServiceKey: ClasspathServiceKey? = null
        var classpathServiceKey: ClasspathServiceKey? = null
        try {
            ClassFileProviderFactory(bootclasspath).use { bootClasspathProvider ->
                ClassFileProviderFactory(classpath).use { libraryClasspathProvider ->
                    bootclasspathServiceKey = ClasspathServiceKey(bootClasspathProvider.id)
                    classpathServiceKey = ClasspathServiceKey(libraryClasspathProvider.id)
                    INSTANCE.registerService(
                        bootclasspathServiceKey!!
                    ) { ClasspathService(bootClasspathProvider) }
                    INSTANCE.registerService(
                        classpathServiceKey!!
                    ) { ClasspathService(libraryClasspathProvider) }

                    val processInputType =
                        { classes: Set<File>, outputDir: File, useAndroidBuildCache: Boolean ->
                            processClassFromInput(
                                classes.filter { it.exists() }.toSet(),
                                outputDir,
                                additionalPaths,
                                bootclasspathServiceKey!!,
                                bootclasspath,
                                classpathServiceKey!!,
                                classpath,
                                enableCaching = useAndroidBuildCache
                            )
                        }

                    processInputType(projectClasses, projectOutputDex, false)
                    processInputType(subProjectClasses, subProjectOutputDex, false)
                    processInputType(mixedScopeClasses, mixedScopeOutputDex, false)
                    val cacheableItems =
                        processInputType(externalLibClasses, externalLibsOutputDex, true)

                    // all work items have been submitted, now wait for completion.
                    if (useGradleWorkers) {
                        workerExecutor.await()
                    } else {
                        executor.waitForTasksWithQuickFail<Any>(true)
                    }

                    // and finally populate the caches.
                    if (cacheableItems.isNotEmpty()) {
                        cacheHandler.populateCache(cacheableItems)
                    }

                    loggerWrapper.verbose("Done with all dex archive conversions")
                }
            }
        } catch (e: Exception) {
            PluginCrashReporter.maybeReportException(e)
            loggerWrapper.error(null, Throwables.getStackTraceAsString(e))
            throw e
        } finally {
            classpathServiceKey?.let { INSTANCE.removeService(it) }
            bootclasspathServiceKey?.let { INSTANCE.removeService(it) }
        }
    }

    /**
     * We are using file hashes to determine the output location for input jars. If the file
     * containing mapping from absolute paths to hashes exists, we will load it, and re-use its
     * content for all unchanged files. For changed jar files, we will recompute the hash, and
     * deleted files will have their entries removed from the map.
     */
    private fun getCurrentJarInputHashes(): MutableMap<File, String> {
        val (fileHashes, isPreviousLoaded) = if (!inputJarHashesFile.exists() || !isIncremental) {
            Pair(mutableMapOf(), false)
        } else {
            BufferedInputStream(inputJarHashesFile.inputStream()).use { input ->
                try {
                    ObjectInputStream(input).use {
                        @Suppress("UNCHECKED_CAST")
                        Pair(it.readObject() as MutableMap<File, String>, true)
                    }
                } catch (e: Exception) {
                    loggerWrapper.warning(
                        "Reading jar hashes from $inputJarHashesFile failed. Exception: ${e.message}"
                    )
                    Pair(mutableMapOf<File, String>(), false)
                }
            }
        }

        fun getFileHash(file: File): String = file.inputStream().buffered().use {
            Hashing.sha256()
                .hashBytes(it.readBytes())
                .toString()
        }

        if (isPreviousLoaded) {
            // Update hashes of changed files.
            listOf(
                projectChangedClasses,
                subProjectChangedClasses,
                externalLibChangedClasses,
                mixedScopeChangedClasses
            ).forEach { changes ->
                changes.asSequence().filter { it.file.extension == SdkConstants.EXT_JAR }.forEach {
                    if (it.changeType == ChangeType.REMOVED) {
                        fileHashes.remove(it.file)
                    } else {
                        fileHashes[it.file] = getFileHash(it.file)
                    }
                }
            }
        } else {
            listOf(projectClasses, subProjectClasses, externalLibClasses, mixedScopeClasses)
                .forEach { files ->
                    files.asSequence().filter { it.extension == SdkConstants.EXT_JAR }
                        .forEach { fileHashes[it] = getFileHash(it) }
                }
        }

        FileUtils.deleteIfExists(inputJarHashesFile)
        FileUtils.mkdirs(inputJarHashesFile.parentFile)
        ObjectOutputStream(inputJarHashesFile.outputStream().buffered()).use {
            it.writeObject(fileHashes)
        }
        return fileHashes
    }

    private fun processClassFromInput(
        inputFiles: Set<File>,
        outputDir: File,
        additionalPaths: Set<File>,
        bootClasspathKey: ClasspathServiceKey,
        bootClasspath: List<Path>,
        classpathKey: ClasspathServiceKey,
        classpath: List<Path>,
        enableCaching: Boolean
    ): List<DexArchiveBuilderCacheHandler.CacheableItem> {

        val itemsToCache = mutableListOf<DexArchiveBuilderCacheHandler.CacheableItem>()

        for (input in inputFiles) {
            loggerWrapper.verbose("Processing input %s", input.toString())
            if (input.isDirectory) {
                convertToDexArchive(
                    input,
                    outputDir,
                    isIncremental,
                    bootClasspathKey,
                    classpathKey,
                    additionalPaths,
                    changedFiles
                )
            } else {
                check(input.extension == SdkConstants.EXT_JAR) { "Expected jar, received $input" }

                val cacheInfo = if (enableCaching) {
                    getD8DesugaringCacheInfo(
                        desugarIncrementalHelper,
                        bootClasspath,
                        classpath,
                        input
                    )
                } else {
                    DesugaringDontCache
                }

                val dexArchives = processJarInput(
                    isIncremental,
                    input,
                    outputDir,
                    bootClasspathKey,
                    classpathKey,
                    additionalPaths,
                    changedFiles,
                    cacheInfo
                )
                if (cacheInfo != DesugaringDontCache && dexArchives.isNotEmpty()) {
                    itemsToCache.add(
                        DexArchiveBuilderCacheHandler.CacheableItem(
                            input,
                            dexArchives,
                            cacheInfo.orderedD8DesugaringDependencies
                        )
                    )
                }
            }
        }
        return itemsToCache
    }

    private fun getD8DesugaringCacheInfo(
        desugarIncrementalHelper: DesugarIncrementalHelper?,
        bootclasspath: List<Path>,
        classpath: List<Path>,
        jarInput: File
    ): D8DesugaringCacheInfo {
        if (java8LangSupportType != VariantScope.Java8LangSupport.D8) {
            return DesugaringNoInfoCache
        }
        desugarIncrementalHelper as DesugarIncrementalHelper

        val unorderedD8DesugaringDependencies =
            desugarIncrementalHelper.getDependenciesPaths(jarInput.toPath())

        // Don't cache libraries depending on class files in folders:
        // Folders content is expected to change often so probably not worth paying the cache cost
        // if we frequently need to rebuild anyway.
        // Supporting dependency to class files would also require special care to respect order.
        if (unorderedD8DesugaringDependencies
                .any { path -> !path.toString().endsWith(SdkConstants.DOT_JAR) }
        ) {
            return DesugaringDontCache
        }

        // DesugaringGraph is not calculating the bootclasspath dependencies so just keep the full
        // bootclasspath for now.
        val bootclasspathPaths = bootclasspath.distinct()

        val classpathJars =
            classpath.distinct().filter { unorderedD8DesugaringDependencies.contains(it) }

        val allDependencies = ArrayList<Path>(bootclasspathPaths.size + classpathJars.size)

        allDependencies.addAll(bootclasspathPaths)
        allDependencies.addAll(classpathJars)
        return D8DesugaringCacheInfo(allDependencies)
    }

    private fun deletePreviousOutputsFromJars() {
        removeChangedJarOutputs(
            projectClasses,
            projectChangedClasses,
            projectOutputDex
        )
        removeChangedJarOutputs(
            subProjectClasses,
            subProjectChangedClasses,
            subProjectOutputDex
        )
        removeChangedJarOutputs(
            externalLibClasses,
            externalLibChangedClasses,
            externalLibsOutputDex
        )
        removeChangedJarOutputs(
            mixedScopeClasses,
            mixedScopeChangedClasses,
            mixedScopeOutputDex
        )
    }

    private fun deletePreviousOutputsFromDirs() {
        val changedToOutput = mapOf(
            projectChangedClasses to projectOutputDex,
            subProjectChangedClasses to subProjectOutputDex,
            externalLibChangedClasses to externalLibsOutputDex,
            mixedScopeChangedClasses to mixedScopeOutputDex
        )

        // Handle dir/file deletions only. We rewrite modified files, so no need to delete those.
        for ((changed, output) in changedToOutput) {
            changed.asSequence().filter {
                it.changeType == ChangeType.REMOVED && it.file.extension != SdkConstants.EXT_JAR
            }.forEach {
                val relativePath = it.normalizedPath

                val fileToDelete = if (it.file.extension == SdkConstants.EXT_CLASS) {
                    ClassFileEntry.withDexExtension(relativePath.toString())
                } else {
                    relativePath.toString()
                }

                FileUtils.deleteRecursivelyIfExists(output.resolve(fileToDelete))
            }

        }
    }

    /**
     * In order to remove stale outputs, we find the set of unchanged jar files. For the unchanged
     * jars, we find the output jar locations. Any other output jar is removed.
     */
    private fun removeChangedJarOutputs(
        inputClasses: Set<File>,
        changes: Set<FileChange>,
        output: File
    ) {
        if (changes.isEmpty()) return

        val changedFiles = changes.map { it.file }.toSet()
        val unchangedOutputs = mutableSetOf<File>()
        inputClasses.asSequence()
            .filter { it.extension == SdkConstants.EXT_JAR && it !in changedFiles }
            .forEach { file ->
                unchangedOutputs.add(getOutputForJar(file, output, null))
                (0 until numberOfBuckets).forEach {
                    unchangedOutputs.add(getOutputForJar(file, output, it))
                }
            }
        val outputFiles = output.listFiles() as? Array<File> ?: return

        outputFiles.filter { it.extension == SdkConstants.EXT_JAR && it !in unchangedOutputs }
            .forEach { FileUtils.deleteIfExists(it) }
    }

    private fun processJarInput(
        isIncremental: Boolean,
        jarInput: File,
        outputDir: File,
        bootclasspath: ClasspathServiceKey,
        classpath: ClasspathServiceKey,
        additionalFiles: Set<File>,
        changedFiles: Set<File>,
        cacheInfo: D8DesugaringCacheInfo
    ): List<File> {
        return if (!isIncremental || jarInput in changedFiles || jarInput in additionalFiles) {
            convertJarToDexArchive(
                jarInput,
                outputDir,
                bootclasspath,
                classpath,
                cacheInfo
            )
        } else {
            listOf()
        }
    }

    private fun convertJarToDexArchive(
        jarInput: File,
        outputDir: File,
        bootclasspath: ClasspathServiceKey,
        classpath: ClasspathServiceKey,
        cacheInfo: D8DesugaringCacheInfo
    ): List<File> {

        if (cacheInfo !== DesugaringDontCache) {
            val cachedVersion = cacheHandler.getCachedVersionIfPresent(
                jarInput, cacheInfo.orderedD8DesugaringDependencies
            )
            if (cachedVersion != null) {
                val outputFile = getOutputForJar(jarInput, outputDir, null)
                Files.copy(
                    cachedVersion.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                // no need to try to cache an already cached version.
                return listOf()
            }
        }
        return convertToDexArchive(
            jarInput,
            outputDir,
            false,
            bootclasspath,
            classpath,
            setOf(),
            setOf()
        )
    }

    class DexConversionParameters(
        internal val input: File,
        private val bootClasspath: ClasspathServiceKey,
        private val classpath: ClasspathServiceKey,
        output: File,
        private val numberOfBuckets: Int,
        private val buckedId: Int,
        private val minSdkVersion: Int,
        private val isDxNoOptimizeFlagPresent: Boolean,
        private val inBufferSize: Int,
        private val outBufferSize: Int,
        private val dexer: DexerTool,
        private val isDebuggable: Boolean,
        internal val isIncremental: Boolean,
        private val java8LangSupportType: VariantScope.Java8LangSupport,
        private val libConfiguration: String?,
        internal val additionalPaths: Set<File>,
        internal val changedFiles: Set<File>,
        internal val errorFormatMode: SyncOptions.ErrorFormatMode
    ) : Serializable {
        internal val output: String = output.toURI().toString()

        fun belongsToThisBucket(path: String): Boolean {
            return getBucketForFile(input, path, numberOfBuckets) == buckedId
        }

        fun getDexArchiveBuilder(
            outStream: OutputStream,
            errStream: OutputStream,
            messageReceiver: MessageReceiver
        ): DexArchiveBuilder {
            val dexArchiveBuilder: DexArchiveBuilder
            when (dexer) {
                DexerTool.DX -> {
                    val optimizedDex = !isDxNoOptimizeFlagPresent
                    val dxContext = DxContext(outStream, errStream)
                    val config = DexArchiveBuilderConfig(
                        dxContext,
                        optimizedDex,
                        inBufferSize,
                        minSdkVersion,
                        DexerTool.DX,
                        outBufferSize,
                        DexArchiveBuilderCacheHandler.isJumboModeEnabledForDx()
                    )

                    dexArchiveBuilder = DexArchiveBuilder.createDxDexBuilder(config)
                }
                DexerTool.D8 -> dexArchiveBuilder = DexArchiveBuilder.createD8DexBuilder(
                    minSdkVersion,
                    isDebuggable,
                    INSTANCE.getService(bootClasspath).service,
                    INSTANCE.getService(classpath).service,
                    java8LangSupportType == VariantScope.Java8LangSupport.D8,
                    libConfiguration,
                    messageReceiver
                )
                else -> throw AssertionError("Unknown dexer type: " + dexer.name)
            }
            return dexArchiveBuilder
        }
    }

    class DexConversionWorkAction @Inject
    constructor(private val dexConversionParameters: DexConversionParameters) : Runnable {

        override fun run() {
            try {
                launchProcessing(
                    dexConversionParameters,
                    System.out,
                    System.err,
                    MessageReceiverImpl(
                        dexConversionParameters.errorFormatMode,
                        Logging.getLogger(DexArchiveBuilderTaskDelegate::class.java)
                    )
                )
            } catch (e: Exception) {
                throw BuildException(e.message, e)
            }
        }
    }

    private open class D8DesugaringCacheInfo constructor(val orderedD8DesugaringDependencies: List<Path>)
    private object DesugaringNoInfoCache : D8DesugaringCacheInfo(emptyList())
    private object DesugaringDontCache : D8DesugaringCacheInfo(emptyList())

    private fun convertToDexArchive(
        input: File,
        outputDir: File,
        isIncremental: Boolean,
        bootClasspath: ClasspathServiceKey,
        classpath: ClasspathServiceKey,
        additionalPaths: Set<File>,
        changedFiles: Set<File>
    ): List<File> {
        loggerWrapper.verbose("Dexing %s", input.absolutePath)

        val dexArchives = mutableListOf<File>()
        for (bucketId in 0 until numberOfBuckets) {

            val preDexOutputFile = if (input.isDirectory) {
                outputDir.also { FileUtils.mkdirs(it) }
            } else {
                getOutputForJar(input, outputDir, bucketId).also { FileUtils.mkdirs(it.parentFile) }
            }

            dexArchives.add(preDexOutputFile)
            val parameters = DexConversionParameters(
                input,
                bootClasspath,
                classpath,
                preDexOutputFile,
                numberOfBuckets,
                bucketId,
                minSdkVersion,
                isDxNoOptimizeFlagPresent,
                inBufferSize,
                outBufferSize,
                dexer,
                isDebuggable,
                isIncremental,
                java8LangSupportType,
                libConfiguration,
                additionalPaths,
                changedFiles,
                errorFormatMode
            )

            if (useGradleWorkers) {
                workerExecutor.submit(
                    DexConversionWorkAction::class.java
                ) { configuration ->
                    configuration.isolationMode = IsolationMode.NONE
                    configuration.setParams(parameters)
                }
            } else {
                executor.execute<Any> {
                    val outputHandler = ParsingProcessOutputHandler(
                        ToolOutputParser(
                            DexParser(), Message.Kind.ERROR, loggerWrapper
                        ),
                        ToolOutputParser(DexParser(), loggerWrapper),
                        messageReceiver
                    )
                    var output: ProcessOutput? = null
                    try {
                        outputHandler.createOutput().use {
                            output = it
                            launchProcessing(
                                parameters,
                                output!!.standardOutput,
                                output!!.errorOutput,
                                messageReceiver
                            )
                        }
                    } finally {
                        output?.let {
                            try {
                                outputHandler.handleOutput(it)
                            } catch (e: ProcessException) {
                                // ignore this one
                            }
                        }
                    }
                    null
                }
            }
        }
        return dexArchives
    }

    private fun getClasspath(java8LangSupportType: VariantScope.Java8LangSupport): List<Path> {
        if (java8LangSupportType != VariantScope.Java8LangSupport.D8) {
            return emptyList()
        }

        return ArrayList<Path>(
            projectClasses.size +
                    subProjectClasses.size +
                    externalLibClasses.size +
                    mixedScopeClasses.size +
                    desugaringClasspathClasses.size
        ).also { list ->
            list.addAll(projectClasses.map { it.toPath() })
            list.addAll(subProjectClasses.map { it.toPath() })
            list.addAll(externalLibClasses.map { it.toPath() })
            list.addAll(mixedScopeClasses.map { it.toPath() })
            list.addAll(desugaringClasspathClasses.map { it.toPath() })
        }
    }

    private fun getBootClasspath(
        androidJarClasspath: Set<File>,
        java8LangSupportType: VariantScope.Java8LangSupport
    ): List<Path> {
        if (java8LangSupportType != VariantScope.Java8LangSupport.D8) {
            return emptyList()
        }
        return androidJarClasspath.map { it.toPath() }
    }

    /**
     * Computes the output path without using the jar absolute path. This method will use the
     * hash of the file content to determine the final output path, and this makes sure the task is
     * relocatable.
     */
    private fun getOutputForJar(input: File, outputDir: File, bucketId: Int?): File {
        val hash = inputJarHashesValues.getValue(input)

        return if (bucketId != null) {
            outputDir.resolve("${hash}_$bucketId.jar")
        } else {
            outputDir.resolve("$hash.jar")
        }
    }
}

/**
 * Returns the bucket for the specified path. For jar inputs, path in the jar file should be
 * specified (both relative and absolute path work). For directories, absolute path should be
 * specified.
 */
private fun getBucketForFile(
    rootInput: File, path: String, numberOfBuckets: Int
): Int {
    if (!rootInput.isDirectory) {
        return abs(path.hashCode()) % numberOfBuckets
    } else {
        val filePath = Paths.get(path)
        Preconditions.checkArgument(filePath.isAbsolute, "Path should be absolute: $path")
        val packagePath = filePath.parent ?: return 0
        return abs(packagePath.toString().hashCode()) % numberOfBuckets
    }
}

private fun launchProcessing(
    dexConversionParameters: DexArchiveBuilderTaskDelegate.DexConversionParameters,
    outStream: OutputStream,
    errStream: OutputStream,
    receiver: MessageReceiver
) {
    val dexArchiveBuilder = dexConversionParameters.getDexArchiveBuilder(
        outStream,
        errStream,
        receiver
    )

    val inputPath = dexConversionParameters.input.toPath()

    val hasIncrementalInfo =
        dexConversionParameters.input.isDirectory && dexConversionParameters.isIncremental

    fun toProcess(path: String): Boolean {
        if (!dexConversionParameters.belongsToThisBucket(path)) return false

        if (!hasIncrementalInfo) {
            return true
        }

        val resolved = inputPath.resolve(path).toFile()
        return resolved in dexConversionParameters.additionalPaths || resolved in dexConversionParameters.changedFiles
    }

    val bucketFilter = { name: String -> toProcess(name) }
    loggerWrapper.verbose("Dexing '" + inputPath + "' to '" + dexConversionParameters.output + "'")

    try {
        ClassFileInputs.fromPath(inputPath).use { input ->
            input.entries(bucketFilter).use { entries ->
                dexArchiveBuilder.convert(
                    entries,
                    Paths.get(URI(dexConversionParameters.output)),
                    dexConversionParameters.input.isDirectory
                )
            }
        }
    } catch (ex: DexArchiveBuilderException) {
        throw DexArchiveBuilderException("Failed to process $inputPath", ex)
    }
}

private val loggerWrapper = LoggerWrapper.getLogger(DexArchiveBuilderTaskDelegate::class.java)
