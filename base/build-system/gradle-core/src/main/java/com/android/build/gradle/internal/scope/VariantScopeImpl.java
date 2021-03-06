/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.internal.scope;

import static com.android.SdkConstants.DOT_JAR;
import static com.android.SdkConstants.FD_COMPILED;
import static com.android.SdkConstants.FD_MERGED;
import static com.android.SdkConstants.FD_RES;
import static com.android.SdkConstants.FN_ANDROID_MANIFEST_XML;
import static com.android.build.gradle.internal.dsl.BuildType.PostProcessingConfiguration.POSTPROCESSING_BLOCK;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ARTIFACT_TYPE;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.ALL;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.PROJECT;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.CLASSES;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.COMPILE_ONLY_NAMESPACED_R_CLASS_JAR;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.SHARED_CLASSES;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH;
import static com.android.build.gradle.internal.scope.ArtifactPublishingUtil.publishArtifactToConfiguration;
import static com.android.build.gradle.internal.scope.ArtifactPublishingUtil.publishArtifactToDefaultVariant;
import static com.android.build.gradle.internal.scope.CodeShrinker.PROGUARD;
import static com.android.build.gradle.internal.scope.CodeShrinker.R8;
import static com.android.build.gradle.internal.scope.InternalArtifactType.COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR;
import static com.android.build.gradle.internal.scope.InternalArtifactType.COMPILE_ONLY_NOT_NAMESPACED_R_CLASS_JAR;
import static com.android.build.gradle.options.BooleanOption.ENABLE_D8;
import static com.android.build.gradle.options.BooleanOption.ENABLE_D8_DESUGARING;
import static com.android.build.gradle.options.BooleanOption.ENABLE_R8_DESUGARING;
import static com.android.build.gradle.options.BooleanOption.USE_NEW_APK_CREATOR;
import static com.android.build.gradle.options.BooleanOption.USE_NEW_JAR_CREATOR;
import static com.android.build.gradle.options.OptionalBooleanOption.ENABLE_R8;
import static com.android.builder.core.VariantTypeImpl.ANDROID_TEST;
import static com.android.builder.core.VariantTypeImpl.UNIT_TEST;
import static com.android.builder.model.AndroidProject.FD_GENERATED;
import static com.android.builder.model.AndroidProject.FD_OUTPUTS;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.FeaturePlugin;
import com.android.build.gradle.internal.BaseConfigAdapter;
import com.android.build.gradle.internal.PostprocessingFeatures;
import com.android.build.gradle.internal.ProguardFileType;
import com.android.build.gradle.internal.core.Abi;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.core.OldPostProcessingOptions;
import com.android.build.gradle.internal.core.PostProcessingBlockOptions;
import com.android.build.gradle.internal.core.PostProcessingOptions;
import com.android.build.gradle.internal.dependency.AndroidTestResourceArtifactCollection;
import com.android.build.gradle.internal.dependency.ArtifactCollectionWithExtraArtifact;
import com.android.build.gradle.internal.dependency.FilteredArtifactCollection;
import com.android.build.gradle.internal.dependency.FilteringSpec;
import com.android.build.gradle.internal.dependency.ProvidedClasspath;
import com.android.build.gradle.internal.dependency.SubtractingArtifactCollection;
import com.android.build.gradle.internal.dependency.VariantDependencies;
import com.android.build.gradle.internal.dsl.BuildType;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.CoreProductFlavor;
import com.android.build.gradle.internal.packaging.JarCreatorType;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.publishing.AndroidArtifacts;
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope;
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType;
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType;
import com.android.build.gradle.internal.publishing.AndroidArtifacts.PublishedConfigType;
import com.android.build.gradle.internal.publishing.PublishingSpecs;
import com.android.build.gradle.internal.publishing.PublishingSpecs.OutputSpec;
import com.android.build.gradle.internal.publishing.PublishingSpecs.VariantSpec;
import com.android.build.gradle.internal.variant.ApplicationVariantData;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.TestVariantData;
import com.android.build.gradle.internal.variant.TestedVariantData;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.IntegerOption;
import com.android.build.gradle.options.OptionalBooleanOption;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.options.StringOption;
import com.android.builder.core.BuilderConstants;
import com.android.builder.core.VariantType;
import com.android.builder.dexing.DexMergerTool;
import com.android.builder.dexing.DexerTool;
import com.android.builder.dexing.DexingType;
import com.android.builder.errors.EvalIssueReporter.Type;
import com.android.builder.internal.packaging.ApkCreatorType;
import com.android.builder.model.OptionalCompilationStep;
import com.android.sdklib.AndroidTargetHash;
import com.android.sdklib.AndroidVersion;
import com.android.utils.FileUtils;
import com.android.utils.StringHelper;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.SelfResolvingDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;

/** A scope containing data for a specific variant. */
public class VariantScopeImpl implements VariantScope {

    private static final String PUBLISH_ERROR_MSG =
            "Publishing to %1$s with no %1$s configuration object. VariantType: %2$s";

    @NonNull private final PublishingSpecs.VariantSpec variantPublishingSpec;

    @NonNull private final GlobalScope globalScope;
    @NonNull private final BaseVariantData variantData;
    @NonNull private final TransformManager transformManager;
    @NonNull private final Map<Abi, File> ndkDebuggableLibraryFolders = Maps.newHashMap();

    @NonNull private BuildArtifactsHolder artifacts;

    private final MutableTaskContainer taskContainer = new MutableTaskContainer();

    private final Supplier<ConfigurableFileCollection> desugarTryWithResourcesRuntimeJar;

    @NonNull private final PostProcessingOptions postProcessingOptions;

    public VariantScopeImpl(
            @NonNull GlobalScope globalScope,
            @NonNull TransformManager transformManager,
            @NonNull BaseVariantData variantData) {
        this.globalScope = globalScope;
        this.transformManager = transformManager;
        this.variantData = variantData;
        this.variantPublishingSpec = PublishingSpecs.getVariantSpec(getType());

        if (globalScope.isActive(OptionalCompilationStep.INSTANT_DEV)) {
            throw new RuntimeException("InstantRun mode is not supported");
        }
        this.artifacts =
                new VariantBuildArtifactsHolder(
                        getProject(),
                        getFullVariantName(),
                        globalScope.getBuildDir(),
                        globalScope.getDslScope());
        this.desugarTryWithResourcesRuntimeJar =
                Suppliers.memoize(
                        () ->
                                getProject()
                                        .files(
                                                FileUtils.join(
                                                        globalScope.getIntermediatesDir(),
                                                        "processing-tools",
                                                        "runtime-deps",
                                                        getVariantConfiguration().getDirName(),
                                                        "desugar_try_with_resources.jar")));
        this.postProcessingOptions = createPostProcessingOptions();
    }

    private PostProcessingOptions createPostProcessingOptions() {
        // This may not be the case with the experimental plugin.
        CoreBuildType buildType = variantData.getVariantConfiguration().getBuildType();
        if (buildType instanceof BuildType) {
            BuildType dslBuildType = (BuildType) buildType;
            if (dslBuildType.getPostProcessingConfiguration() == POSTPROCESSING_BLOCK) {
                return new PostProcessingBlockOptions(
                        dslBuildType.getPostprocessing(), getType().isTestComponent());
            }
        }

        return new OldPostProcessingOptions(buildType, globalScope.getProject());
    }

    protected Project getProject() {
        return globalScope.getProject();
    }

    @Override
    @NonNull
    public PublishingSpecs.VariantSpec getPublishingSpec() {
        return variantPublishingSpec;
    }

    @NonNull
    @Override
    public MutableTaskContainer getTaskContainer() {
        return taskContainer;
    }

    /**
     * Publish an intermediate artifact.
     *
     * @param artifact FileCollection to be published.
     * @param artifactType the artifact type.
     * @param configTypes the PublishedConfigType. (e.g. api, runtime, etc)
     */
    @Override
    public void publishIntermediateArtifact(
            @NonNull Provider<FileCollection> artifact,
            @NonNull ArtifactType artifactType,
            @NonNull Collection<PublishedConfigType> configTypes) {
        // Create Provider so that the BuildableArtifact is not resolved until needed.
        Provider<File> file = artifact.map(fileCollection -> fileCollection.getSingleFile());

        Preconditions.checkState(!configTypes.isEmpty());

        // FIXME this needs to be parameterized based on the variant's publishing type.
        final VariantDependencies variantDependency = getVariantDependencies();

        for (PublishedConfigType configType : PublishedConfigType.values()) {
            if (configTypes.contains(configType)) {
                Configuration config = variantDependency.getElements(configType);
                Preconditions.checkNotNull(
                        config, String.format(PUBLISH_ERROR_MSG, configType, getType()));
                if (configType.isPublicationConfig()) {
                    String classifier = null;
                    if (configType.isClassifierRequired()) {
                        classifier = getFullVariantName();
                    }
                    publishArtifactToDefaultVariant(config, file, artifactType, classifier);
                } else {
                    publishArtifactToConfiguration(config, file, artifact, artifactType);
                }
            }
        }
    }

    @Override
    public void publishIntermediateArtifact(
            @NonNull Provider<? extends FileSystemLocation> artifact,
            @Nonnull Provider<String> lastProducerTaskName,
            @NonNull ArtifactType artifactType,
            @NonNull Collection<PublishedConfigType> configTypes) {

        Preconditions.checkState(!configTypes.isEmpty());

        // FIXME this needs to be parameterized based on the variant's publishing type.
        final VariantDependencies variantDependency = getVariantDependencies();

        for (PublishedConfigType configType : PublishedConfigType.values()) {
            if (configTypes.contains(configType)) {
                Configuration config = variantDependency.getElements(configType);
                Preconditions.checkNotNull(
                        config, String.format(PUBLISH_ERROR_MSG, configType, getType()));
                if (configType.isPublicationConfig()) {
                    String classifier = null;
                    if (configType.isClassifierRequired()) {
                        classifier = getFullVariantName();
                    }
                    publishArtifactToDefaultVariant(config, artifact, artifactType, classifier);
                } else {
                    publishArtifactToConfiguration(
                            config, artifact, lastProducerTaskName, artifactType);
                }
            }
        }
    }

    @Override
    @NonNull
    public GlobalScope getGlobalScope() {
        return globalScope;
    }

    @Override
    @NonNull
    public BaseVariantData getVariantData() {
        return variantData;
    }

    @Override
    @NonNull
    public GradleVariantConfiguration getVariantConfiguration() {
        return variantData.getVariantConfiguration();
    }

    @NonNull
    @Override
    public String getFullVariantName() {
        return getVariantConfiguration().getFullName();
    }

    @Override
    public boolean useResourceShrinker() {
        if (getType().isForTesting()
                || !postProcessingOptions.resourcesShrinkingEnabled()) {
            return false;
        }

        // TODO: support resource shrinking for multi-apk applications http://b/78119690
        if (getType().isFeatureSplit() || globalScope.hasDynamicFeatures()) {
            globalScope
                    .getErrorHandler()
                    .reportError(
                            Type.GENERIC,
                            "Resource shrinker cannot be used for multi-apk applications");
            return false;
        }

        if (getType().isAar()) {
            if (!getProject().getPlugins().hasPlugin("com.android.feature")) {
                globalScope
                        .getErrorHandler()
                        .reportError(
                                Type.GENERIC, "Resource shrinker cannot be used for libraries.");
            }
            return false;
        }

        if (getCodeShrinker() == null) {
            globalScope
                    .getErrorHandler()
                    .reportError(
                            Type.GENERIC,
                            "Removing unused resources requires unused code shrinking to be turned on. See "
                                    + "http://d.android.com/r/tools/shrink-resources.html "
                                    + "for more information.");

            return false;
        }

        return true;
    }

    @Override
    public boolean isPrecompileDependenciesResourcesEnabled() {
        // Resource shrinker expects MergeResources task to have all the resources merged and with
        // overlay rules applied, so we have to go through the MergeResources pipeline in case it's
        // enabled, see b/134766811.
        return globalScope.getProjectOptions().get(BooleanOption.PRECOMPILE_DEPENDENCIES_RESOURCES)
                && !useResourceShrinker();
    }

    @Override
    public boolean isCrunchPngs() {
        // If set for this build type, respect that.
        Boolean buildTypeOverride = getVariantConfiguration().getBuildType().isCrunchPngs();
        if (buildTypeOverride != null) {
            return buildTypeOverride;
        }
        // Otherwise, if set globally, respect that.
        Boolean globalOverride =
                globalScope.getExtension().getAaptOptions().getCruncherEnabledOverride();
        if (globalOverride != null) {
            return globalOverride;
        }
        // If not overridden, use the default from the build type.
        //noinspection deprecation TODO: Remove once the global cruncher enabled flag goes away.
        return getVariantConfiguration().getBuildType().isCrunchPngsDefault();
    }

    @Override
    public boolean consumesFeatureJars() {
        return getType().isBaseModule()
                && getVariantConfiguration().getBuildType().isMinifyEnabled()
                && globalScope.hasDynamicFeatures();
    }

    @Override
    public boolean getNeedsJavaResStreams() {
        // We need to create original java resource stream only if we're in a library module with
        // custom transforms.
        return getType().isAar() && !getGlobalScope().getExtension().getTransforms().isEmpty();
    }

    @Override
    public boolean getNeedsMergedJavaResStream() {
        // We need to create a stream from the merged java resources if we're in a library module,
        // or if we're in an app/feature module which uses the transform pipeline.
        return getType().isAar()
                || !getGlobalScope().getExtension().getTransforms().isEmpty()
                || getCodeShrinker() != null;
    }

    @Override
    public boolean getNeedsMainDexListForBundle() {
        return getType().isBaseModule()
                && globalScope.hasDynamicFeatures()
                && getVariantConfiguration().getDexingType().getNeedsMainDexList();
    }

    @Nullable
    @Override
    public CodeShrinker getCodeShrinker() {
        boolean isTestComponent = getType().isTestComponent();

        //noinspection ConstantConditions - getType() will not return null for a testing variant.
        if (isTestComponent && getTestedVariantData().getType().isAar()) {
            // For now we seem to include the production library code as both program and library
            // input to the test ProGuard run, which confuses it.
            return null;
        }

        CodeShrinker codeShrinker = postProcessingOptions.getCodeShrinker();
        if (codeShrinker != null) {
            Boolean enableR8 = globalScope.getProjectOptions().get(ENABLE_R8);
            if (enableR8 == null) {
                return codeShrinker;
            } else if (enableR8) {
                return R8;
            } else {
                return PROGUARD;
            }
        }

        return codeShrinker;
    }

    @NonNull
    @Override
    public List<File> getProguardFiles() {
        List<File> result = getExplicitProguardFiles();

        // For backwards compatibility, we keep the old behavior: if there are no files
        // specified, use a default one.
        if (result.isEmpty()) {
            return postProcessingOptions.getDefaultProguardFiles();
        }

        return result;
    }

    @NonNull
    @Override
    public List<File> getExplicitProguardFiles() {
        return gatherProguardFiles(ProguardFileType.EXPLICIT);
    }

    @NonNull
    @Override
    public List<File> getTestProguardFiles() {
        return gatherProguardFiles(ProguardFileType.TEST);
    }

    @NonNull
    @Override
    public List<File> getConsumerProguardFiles() {
        return gatherProguardFiles(ProguardFileType.CONSUMER);
    }

    @NonNull
    @Override
    public List<File> getConsumerProguardFilesForFeatures() {
        final boolean hasFeaturePlugin = getProject().getPlugins().hasPlugin(FeaturePlugin.class);
        // We include proguardFiles if we're in a dynamic-feature or feature module. For feature
        // modules, we check for the presence of the FeaturePlugin, because we want to include
        // proguardFiles even when we're in the library variant.
        final boolean includeProguardFiles = hasFeaturePlugin || getType().isDynamicFeature();
        final Collection<File> consumerProguardFiles = getConsumerProguardFiles();
        if (includeProguardFiles) {
            consumerProguardFiles.addAll(getExplicitProguardFiles());
        }

        return ImmutableList.copyOf(consumerProguardFiles);
    }

    @NonNull
    private List<File> gatherProguardFiles(ProguardFileType type) {
        GradleVariantConfiguration variantConfiguration = getVariantConfiguration();

        List<File> result =
                new ArrayList<>(
                        BaseConfigAdapter.getProguardFiles(
                                variantConfiguration.getDefaultConfig(), type));

        result.addAll(postProcessingOptions.getProguardFiles(type));

        for (CoreProductFlavor flavor : variantConfiguration.getProductFlavors()) {
            result.addAll(BaseConfigAdapter.getProguardFiles(flavor, type));
        }

        return result;
    }

    @Override
    @Nullable
    public PostprocessingFeatures getPostprocessingFeatures() {
        return postProcessingOptions.getPostprocessingFeatures();
    }

    /**
     * Determine if the final output should be marked as testOnly to prevent uploading to Play
     * store.
     *
     * <p>Uploading to Play store is disallowed if:
     *
     * <ul>
     *   <li>An injected option is set (usually by the IDE for testing purposes).
     *   <li>compileSdkVersion, minSdkVersion or targetSdkVersion is a preview
     * </ul>
     *
     * <p>This value can be overridden by the OptionalBooleanOption.IDE_TEST_ONLY property.
     */
    @Override
    public boolean isTestOnly() {
        ProjectOptions projectOptions = globalScope.getProjectOptions();
        Boolean isTestOnlyOverride = projectOptions.get(OptionalBooleanOption.IDE_TEST_ONLY);

        if (isTestOnlyOverride != null) {
            return isTestOnlyOverride;
        }

        return !Strings.isNullOrEmpty(projectOptions.get(StringOption.IDE_BUILD_TARGET_ABI))
                || !Strings.isNullOrEmpty(projectOptions.get(StringOption.IDE_BUILD_TARGET_DENSITY))
                || projectOptions.get(IntegerOption.IDE_TARGET_DEVICE_API) != null
                || isPreviewTargetPlatform()
                || getMinSdkVersion().getCodename() != null
                || getVariantConfiguration().getTargetSdkVersion().getCodename() != null;
    }

    private boolean isPreviewTargetPlatform() {
        AndroidVersion version =
                AndroidTargetHash.getVersionFromHash(
                        globalScope.getExtension().getCompileSdkVersion());
        return version != null && version.isPreview();
    }

    @NonNull
    @Override
    public VariantType getType() {
        return variantData.getType();
    }

    @NonNull
    @Override
    public DexingType getDexingType() {
        return variantData.getVariantConfiguration().getDexingType();
    }

    @Override
    public boolean getNeedsMainDexList() {
        return getDexingType().getNeedsMainDexList();
    }

    @NonNull
    @Override
    public AndroidVersion getMinSdkVersion() {
        return getVariantConfiguration().getMinSdkVersion();
    }

    @NonNull
    @Override
    public String getDirName() {
        return getVariantConfiguration().getDirName();
    }

    @NonNull
    @Override
    public Collection<String> getDirectorySegments() {
        return getVariantConfiguration().getDirectorySegments();
    }

    @NonNull
    @Override
    public TransformManager getTransformManager() {
        return transformManager;
    }

    @Override
    @NonNull
    public String getTaskName(@NonNull String prefix) {
        return getTaskName(prefix, "");
    }

    @Override
    @NonNull
    public String getTaskName(@NonNull String prefix, @NonNull String suffix) {
        return variantData.getTaskName(prefix, suffix);
    }

    /**
     * Return the folder containing the shared object with debugging symbol for the specified ABI.
     */
    @Override
    @Nullable
    public File getNdkDebuggableLibraryFolders(@NonNull Abi abi) {
        return ndkDebuggableLibraryFolders.get(abi);
    }

    @Override
    public void addNdkDebuggableLibraryFolders(@NonNull Abi abi, @NonNull File searchPath) {
        this.ndkDebuggableLibraryFolders.put(abi, searchPath);
    }

    @Override
    @Nullable
    public BaseVariantData getTestedVariantData() {
        return variantData instanceof TestVariantData ?
                (BaseVariantData) ((TestVariantData) variantData).getTestedVariantData() :
                null;
    }

    @NonNull
    @Override
    public File getSplitApkOutputFolder() {
        return new File(globalScope.getIntermediatesDir(), "/split-apk/" + getDirName());
    }

    // Precomputed file paths.

    @Override
    @NonNull
    public FileCollection getJavaClasspath(
            @NonNull ConsumedConfigType configType, @NonNull ArtifactType classesType) {
        return getJavaClasspath(configType, classesType, null);
    }

    @Override
    @NonNull
    public FileCollection getJavaClasspath(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactType classesType,
            @Nullable Object generatedBytecodeKey) {
        FileCollection mainCollection = getArtifactFileCollection(configType, ALL, classesType);

        mainCollection =
                mainCollection.plus(variantData.getGeneratedBytecode(generatedBytecodeKey));

        // Add R class jars to the front of the classpath as libraries might also export
        // compile-only classes. This behavior is verified in CompileRClassFlowTest
        // While relying on this order seems brittle, it avoids doubling the number of
        // files on the compilation classpath by exporting the R class separately or
        // and is much simpler than having two different outputs from each library, with
        // and without the R class, as AGP publishing code assumes there is exactly one
        // artifact for each publication.
        mainCollection = getProject().files(getCompiledRClasses(configType), mainCollection);

        return mainCollection;
    }

    @Override
    @NonNull
    public FileCollection getCompiledRClasses(@NonNull ConsumedConfigType configType) {
        FileCollection mainCollection = getProject().files();
        BaseVariantData tested = getTestedVariantData();

        if (globalScope.getExtension().getAaptOptions().getNamespaced()) {
            Provider<RegularFile> namespacedRClassJar =
                    artifacts.getFinalProduct(
                            InternalArtifactType.COMPILE_ONLY_NAMESPACED_R_CLASS_JAR.INSTANCE);

            ConfigurableFileTree fileTree =
                    getProject().fileTree(namespacedRClassJar).builtBy(namespacedRClassJar);
            mainCollection = mainCollection.plus(fileTree);
            mainCollection =
                    mainCollection.plus(
                            getArtifactFileCollection(
                                    configType, ALL, COMPILE_ONLY_NAMESPACED_R_CLASS_JAR));
            mainCollection =
                    mainCollection.plus(getArtifactFileCollection(configType, ALL, SHARED_CLASSES));

            if (globalScope
                    .getProjectOptions()
                    .get(BooleanOption.CONVERT_NON_NAMESPACED_DEPENDENCIES)) {
                mainCollection =
                        mainCollection.plus(
                                artifacts
                                        .getFinalProductAsFileCollection(
                                                InternalArtifactType.NAMESPACED_CLASSES_JAR
                                                        .INSTANCE)
                                        .get());

                mainCollection =
                        mainCollection.plus(
                                getProject()
                                        .files(
                                                artifacts.getFinalProduct(
                                                        InternalArtifactType
                                                                .COMPILE_ONLY_NAMESPACED_DEPENDENCIES_R_JAR
                                                                .INSTANCE)));
            }

            if (tested != null) {
                mainCollection =
                        getProject()
                                .files(
                                        mainCollection,
                                        tested.getScope()
                                                .getArtifacts()
                                                .getFinalProduct(
                                                        InternalArtifactType
                                                                .COMPILE_ONLY_NAMESPACED_R_CLASS_JAR
                                                                .INSTANCE)
                                                .get());
            }
        } else {
            //noinspection VariableNotUsedInsideIf
            if (tested == null) {
                // TODO(b/138780301): Also use it in android tests.
                boolean useCompileRClassInApp =
                        globalScope
                                        .getProjectOptions()
                                        .get(BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS)
                                && !getType().isForTesting();

                if (getType().isAar() || useCompileRClassInApp) {
                    Provider<RegularFile> rJar =
                            artifacts.getFinalProduct(
                                    COMPILE_ONLY_NOT_NAMESPACED_R_CLASS_JAR.INSTANCE);
                    mainCollection = getProject().files(rJar);
                } else {
                    checkState(getType().isApk(), "Expected APK type but found: " + getType());
                    Provider<FileCollection> rJar =
                            artifacts.getFinalProductAsFileCollection(
                                    COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR.INSTANCE);
                    mainCollection = getProject().files(rJar);
                }
            } else { // Android test or unit test
                if (!globalScope.getProjectOptions().get(BooleanOption.GENERATE_R_JAVA)) {
                    Provider<RegularFile> rJar;
                    if (getType() == ANDROID_TEST) {
                        rJar =
                                artifacts.getFinalProduct(
                                        COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR.INSTANCE);
                    } else {
                        rJar = getRJarForUnitTests();
                    }
                    mainCollection = getProject().files(rJar);
                }
            }
        }

        return mainCollection;
    }

    @NonNull
    @Override
    public ArtifactCollection getJavaClasspathArtifacts(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactType classesType,
            @Nullable Object generatedBytecodeKey) {
        ArtifactCollection mainCollection = getArtifactCollection(configType, ALL, classesType);

        return ArtifactCollectionWithExtraArtifact.makeExtraCollection(
                mainCollection,
                variantData.getGeneratedBytecode(generatedBytecodeKey),
                getProject().getPath());
    }

    @NonNull
    @Override
    public BuildArtifactsHolder getArtifacts() {
        return artifacts;
    }

    @Override
    @NonNull
    public FileCollection getArtifactFileCollection(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactScope scope,
            @NonNull ArtifactType artifactType) {
        return getArtifactFileCollection(configType, scope, artifactType, null);
    }

    @Override
    @NonNull
    public FileCollection getArtifactFileCollection(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactScope scope,
            @NonNull ArtifactType artifactType,
            @Nullable Map<Attribute<String>, String> attributeMap) {
        if (configType.needsTestedComponents()) {
            return getArtifactCollection(configType, scope, artifactType, attributeMap)
                    .getArtifactFiles();
        }
        ArtifactCollection artifacts =
                computeArtifactCollection(configType, scope, artifactType, attributeMap);

        FileCollection fileCollection;

        if (configType == RUNTIME_CLASSPATH
                && getType().isFeatureSplit()
                && artifactType != ArtifactType.PACKAGED_DEPENDENCIES) {

            FileCollection excludedDirectories =
                    computeArtifactCollection(
                                    RUNTIME_CLASSPATH,
                                    PROJECT,
                                    ArtifactType.PACKAGED_DEPENDENCIES,
                                    attributeMap)
                            .getArtifactFiles();

            fileCollection =
                    new FilteringSpec(artifacts, excludedDirectories)
                            .getFilteredFileCollection(getProject());

        } else {
            fileCollection = artifacts.getArtifactFiles();
        }

        return fileCollection;
    }

    @Override
    @NonNull
    public ArtifactCollection getArtifactCollection(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactScope scope,
            @NonNull ArtifactType artifactType) {
        return getArtifactCollection(configType, scope, artifactType, null);
    }

    @NonNull
    @Override
    public ArtifactCollection getArtifactCollection(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactScope scope,
            @NonNull ArtifactType artifactType,
            @Nullable Map<Attribute<String>, String> attributeMap) {
        ArtifactCollection artifacts =
                computeArtifactCollection(configType, scope, artifactType, attributeMap);

        if (configType == RUNTIME_CLASSPATH
                && getType().isFeatureSplit()
                && artifactType != ArtifactType.PACKAGED_DEPENDENCIES) {

            FileCollection excludedDirectories =
                    computeArtifactCollection(
                                    RUNTIME_CLASSPATH,
                                    PROJECT,
                                    ArtifactType.PACKAGED_DEPENDENCIES,
                                    null)
                            .getArtifactFiles();
            artifacts =
                    new FilteredArtifactCollection(
                            getProject(), new FilteringSpec(artifacts, excludedDirectories));
        }

        if (!configType.needsTestedComponents() || !getType().isTestComponent()) {
            return artifacts;
        }

        // get the matching file collection for the tested variant, if any.
        if (!(variantData instanceof TestVariantData)) {
            return artifacts;
        }

        TestedVariantData tested = ((TestVariantData) variantData).getTestedVariantData();
        final VariantScope testedScope = tested.getScope();

        // we only add the tested component to the PROJECT | ALL scopes.
        if (scope == ArtifactScope.PROJECT || scope == ALL) {
            VariantSpec testedSpec = testedScope.getPublishingSpec().getTestingSpec(getType());

            // get the OutputPublishingSpec from the ArtifactType for this particular variant
            // spec
            OutputSpec taskOutputSpec =
                    testedSpec.getSpec(artifactType, configType.getPublishedTo());

            if (taskOutputSpec != null) {
                Collection<PublishedConfigType> publishedConfigs =
                        taskOutputSpec.getPublishedConfigTypes();

                // check that we are querying for a config type that the tested artifact
                // was published to.
                if (publishedConfigs.contains(configType.getPublishedTo())) {
                    // if it's the case then we add the tested artifact.
                    final com.android.build.api.artifact.ArtifactType<? extends FileSystemLocation>
                            taskOutputType = taskOutputSpec.getOutputType();
                    BuildArtifactsHolder testedArtifacts = testedScope.getArtifacts();
                    artifacts =
                            ArtifactCollectionWithExtraArtifact.makeExtraCollectionForTest(
                                    artifacts,
                                    testedArtifacts
                                            .getFinalProductAsFileCollection(taskOutputType)
                                            .get(),
                                    getProject().getPath(),
                                    testedScope.getFullVariantName());
                }
            }
        }

        // We remove the transitive dependencies coming from the
        // tested app to avoid having the same artifact on each app and tested app.
        // This applies only to the package scope since we do want these in the compile
        // scope in order to compile.
        // We only do this for the AndroidTest.
        // We do have to however keep the Android resources.
        if (tested instanceof ApplicationVariantData
                && configType == RUNTIME_CLASSPATH
                && getType().isApk()) {
            if (artifactType == ArtifactType.ANDROID_RES
                    || artifactType == ArtifactType.COMPILED_DEPENDENCIES_RESOURCES) {
                artifacts =
                        new AndroidTestResourceArtifactCollection(
                                artifacts,
                                getVariantDependencies().getIncomingRuntimeDependencies(),
                                getVariantDependencies().getRuntimeClasspath().getIncoming());
            } else {
                ArtifactCollection testedArtifactCollection =
                        testedScope.getArtifactCollection(
                                configType, scope, artifactType, attributeMap);
                artifacts = new SubtractingArtifactCollection(artifacts, testedArtifactCollection);
            }
        }

        return artifacts;

    }

    @NonNull
    private Configuration getConfiguration(@NonNull ConsumedConfigType configType) {
        switch (configType) {
            case COMPILE_CLASSPATH:
                return getVariantDependencies().getCompileClasspath();
            case RUNTIME_CLASSPATH:
                return getVariantDependencies().getRuntimeClasspath();
            case ANNOTATION_PROCESSOR:
                return getVariantDependencies().getAnnotationProcessorConfiguration();
            case REVERSE_METADATA_VALUES:
                return Preconditions.checkNotNull(
                        getVariantDependencies().getReverseMetadataValuesConfiguration());
            default:
                throw new RuntimeException("unknown ConfigType value " + configType);
        }
    }

    @NonNull
    private ArtifactCollection computeArtifactCollection(
            @NonNull ConsumedConfigType configType,
            @NonNull ArtifactScope scope,
            @NonNull ArtifactType artifactType,
            @Nullable Map<Attribute<String>, String> attributeMap) {

        Configuration configuration = getConfiguration(configType);

        Action<AttributeContainer> attributes =
                container -> {
                    container.attribute(ARTIFACT_TYPE, artifactType.getType());
                    if (attributeMap != null) {
                        for (Attribute<String> attribute : attributeMap.keySet()) {
                            container.attribute(attribute, attributeMap.get(attribute));
                        }
                    }
                };

        Spec<ComponentIdentifier> filter = getComponentFilter(scope);

        boolean lenientMode =
                Boolean.TRUE.equals(
                        globalScope.getProjectOptions().get(BooleanOption.IDE_BUILD_MODEL_ONLY));

        return configuration
                .getIncoming()
                .artifactView(
                        config -> {
                            config.attributes(attributes);
                            if (filter != null) {
                                config.componentFilter(filter);
                            }
                            // TODO somehow read the unresolved dependencies?
                            config.lenient(lenientMode);
                        })
                .getArtifacts();
    }

    @Nullable
    private static Spec<ComponentIdentifier> getComponentFilter(
            @NonNull AndroidArtifacts.ArtifactScope scope) {
        switch (scope) {
            case ALL:
                return null;
            case EXTERNAL:
                // since we want both Module dependencies and file based dependencies in this case
                // the best thing to do is search for non ProjectComponentIdentifier.
                return id -> !(id instanceof ProjectComponentIdentifier);
            case PROJECT:
                return id -> id instanceof ProjectComponentIdentifier;
            case REPOSITORY_MODULE:
                return id -> id instanceof ModuleComponentIdentifier;
            case FILE:
                return id ->
                        !(id instanceof ProjectComponentIdentifier
                                || id instanceof ModuleComponentIdentifier);
        }
        throw new RuntimeException("unknown ArtifactScope value");
    }

    /**
     * Returns the packaged local Jars
     *
     * @return a non null, but possibly empty set.
     */
    @NonNull
    @Override
    public FileCollection getLocalPackagedJars() {
        return getLocalFileDependencies(
                (file) -> file.getName().toLowerCase(Locale.US).endsWith(DOT_JAR));
    }

    /**
     * Returns the direct (i.e., non-transitive) local file dependencies matching the given
     * predicate
     *
     * @return a non null, but possibly empty FileCollection
     * @param filePredicate the file predicate used to filter the local file dependencies
     */
    @NonNull
    @Override
    public FileCollection getLocalFileDependencies(Predicate<File> filePredicate) {
        Configuration configuration = getVariantDependencies().getRuntimeClasspath();

        // Get a list of local file dependencies. There is currently no API to filter the
        // files here, so we need to filter it in the return statement below. That means that if,
        // for example, filePredicate filters out all files but jars in the return statement, but an
        // AarProducerTask produces an aar, then the returned FileCollection contains only jars but
        // still has AarProducerTask as a dependency.
        Callable<Collection<SelfResolvingDependency>> dependencies =
                () ->
                        configuration
                                .getAllDependencies()
                                .stream()
                                .filter((it) -> it instanceof SelfResolvingDependency)
                                .filter((it) -> !(it instanceof ProjectDependency))
                                .map((it) -> (SelfResolvingDependency) it)
                                .collect(ImmutableList.toImmutableList());

        // Create a file collection builtBy the dependencies.  The files are resolved later.
        return getProject()
                .files(
                        (Callable<Collection<File>>)
                                () ->
                                        dependencies
                                                .call()
                                                .stream()
                                                .flatMap((it) -> it.resolve().stream())
                                                .filter(filePredicate)
                                                .collect(Collectors.toList()))
                .builtBy(dependencies);
    }

    @NonNull
    @Override
    public FileCollection getProvidedOnlyClasspath() {
        ArtifactCollection compile = getArtifactCollection(COMPILE_CLASSPATH, ALL, CLASSES);
        ArtifactCollection runtime = getArtifactCollection(RUNTIME_CLASSPATH, ALL, CLASSES);

        return ProvidedClasspath.getProvidedClasspath(compile, runtime);
    }

    @NonNull
    @Override
    public Provider<RegularFile> getRJarForUnitTests() {
        VariantScope testedScope =
                checkNotNull(
                                getTestedVariantData(),
                                "Variant type does not have a tested variant: " + getType())
                        .getScope();
        checkState(getType() == UNIT_TEST, "Expected unit test type but found: " + getType());

        if (testedScope.getType().isAar()) {
            return this.getArtifacts()
                    .getFinalProduct(COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR.INSTANCE);
        } else {
            checkState(
                    testedScope.getType().isApk(),
                    "Expected APK type but found: " + testedScope.getType());
            return testedScope
                    .getArtifacts()
                    .getFinalProduct(COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR.INSTANCE);
        }
    }

    /**
     * An intermediate directory for this variant.
     *
     * <p>Of the form build/intermediates/dirName/variant/
     */
    @NonNull
    private File intermediate(@NonNull String directoryName) {
        return FileUtils.join(globalScope.getIntermediatesDir(), directoryName, getDirName());
    }

    /**
     * An intermediate file for this variant.
     *
     * <p>Of the form build/intermediates/directoryName/variant/filename
     */
    @NonNull
    private File intermediate(@NonNull String directoryName, @NonNull String fileName) {
        return FileUtils.join(
                globalScope.getIntermediatesDir(), directoryName, getDirName(), fileName);
    }

    @Override
    @NonNull
    public File getDefaultMergeResourcesOutputDir() {
        return FileUtils.join(globalScope.getIntermediatesDir(), FD_RES, FD_MERGED, getDirName());
    }

    @Override
    @NonNull
    public File getCompiledResourcesOutputDir() {
        return FileUtils.join(globalScope.getIntermediatesDir(), FD_RES, FD_COMPILED, getDirName());
    }

    @NonNull
    @Override
    public File getResourceBlameLogDir() {
        return FileUtils.join(
                globalScope.getIntermediatesDir(),
                StringHelper.toStrings(
                        "blame", "res", getDirectorySegments()));
    }

    @Override
    @NonNull
    public File getBuildConfigSourceOutputDir() {
        return new File(
                globalScope.getBuildDir()
                        + "/"
                        + FD_GENERATED
                        + "/source/buildConfig/"
                        + getDirName());
    }

    @NonNull
    private File getGeneratedResourcesDir(String name) {
        return FileUtils.join(
                globalScope.getGeneratedDir(),
                StringHelper.toStrings(
                        "res",
                        name,
                        getDirectorySegments()));
    }

    @Override
    @NonNull
    public File getGeneratedResOutputDir() {
        return getGeneratedResourcesDir("resValues");
    }

    @Override
    @NonNull
    public File getGeneratedPngsOutputDir() {
        return getGeneratedResourcesDir("pngs");
    }

    @Override
    @NonNull
    public File getRenderscriptResOutputDir() {
        return getGeneratedResourcesDir("rs");
    }

    @NonNull
    @Override
    public File getRenderscriptObjOutputDir() {
        return FileUtils.join(
                globalScope.getIntermediatesDir(),
                StringHelper.toStrings(
                        "rs",
                        getDirectorySegments(),
                        "obj"));
    }

    @Override
    @NonNull
    public File getIncrementalDir(String name) {
        return FileUtils.join(
                globalScope.getIntermediatesDir(),
                "incremental",
                name);
    }

    @NonNull
    @Override
    public File getCoverageReportDir() {
        return new File(globalScope.getReportsDir(), "coverage/" + getDirName());
    }

    @Override
    @NonNull
    public File getClassOutputForDataBinding() {
        return new File(
                globalScope.getGeneratedDir(), "source/dataBinding/trigger/" + getDirName());
    }

    @Override
    @NonNull
    public File getGeneratedClassListOutputFileForDataBinding() {
        return new File(dataBindingIntermediate("class-list"), "_generated.txt");
    }

    private File dataBindingIntermediate(String name) {
        return intermediate("data-binding", name);
    }

    @NonNull
    @Override
    public File getFullApkPackagesOutputDirectory() {
        return new File(
                globalScope.getBuildDir(),
                FileUtils.join(FD_OUTPUTS, "splits", "full", getDirName()));
    }

    @NonNull
    @Override
    public File getIntermediateDir(
            @NonNull com.android.build.api.artifact.ArtifactType<Directory> taskOutputType) {
        return intermediate(taskOutputType.name().toLowerCase(Locale.US));
    }

    @NonNull
    @Override
    public File getMicroApkManifestFile() {
        return FileUtils.join(
                globalScope.getGeneratedDir(),
                "manifests",
                "microapk",
                getDirName(),
                FN_ANDROID_MANIFEST_XML);
    }

    @NonNull
    @Override
    public File getMicroApkResDirectory() {
        return FileUtils.join(globalScope.getGeneratedDir(), "res", "microapk", getDirName());
    }

    @NonNull
    @Override
    public File getManifestOutputDirectory() {
        final VariantType variantType = getType();

        if (variantType.isTestComponent()) {
            if (variantType.isApk()) { // ANDROID_TEST
                return FileUtils.join(globalScope.getIntermediatesDir(), "manifest", getDirName());
            }
        } else {
            return FileUtils.join(
                    globalScope.getIntermediatesDir(), "manifests", "full", getDirName());
        }

        throw new RuntimeException("getManifestOutputDirectory called for an unexpected variant.");
    }

    /**
     * Obtains the location where APKs should be placed.
     *
     * @return the location for APKs
     */
    @NonNull
    @Override
    public File getApkLocation() {
        String override = globalScope.getProjectOptions().get(StringOption.IDE_APK_LOCATION);
        File baseDirectory =
                override != null && !getType().isHybrid()
                        ? getProject().file(override)
                        : getDefaultApkLocation();

        return new File(baseDirectory, getDirName());
    }

    /**
     * Obtains the default location for APKs.
     *
     * @return the default location for APKs
     */
    @NonNull
    private File getDefaultApkLocation() {
        return FileUtils.join(globalScope.getBuildDir(), FD_OUTPUTS, "apk");
    }

    @NonNull
    @Override
    public File getAarLocation() {
        return FileUtils.join(globalScope.getOutputsDir(), BuilderConstants.EXT_LIB_ARCHIVE);
    }

    @Override
    @NonNull
    public OutputScope getOutputScope() {
        return variantData.getOutputScope();
    }

    @NonNull
    @Override
    public VariantDependencies getVariantDependencies() {
        return variantData.getVariantDependency();
    }

    @NonNull
    @Override
    public Java8LangSupport getJava8LangSupportType() {
        // in order of precedence
        if (!globalScope
                .getExtension()
                .getCompileOptions()
                .getTargetCompatibility()
                .isJava8Compatible()) {
            return Java8LangSupport.UNUSED;
        }

        if (getProject().getPlugins().hasPlugin("me.tatarka.retrolambda")) {
            return Java8LangSupport.RETROLAMBDA;
        }

        CodeShrinker shrinker = getCodeShrinker();
        if (shrinker == R8) {
            if (globalScope.getProjectOptions().get(ENABLE_R8_DESUGARING)) {
                return Java8LangSupport.R8;
            }
        } else {
            // D8 cannot be used if R8 is used
            if (globalScope.getProjectOptions().get(ENABLE_D8_DESUGARING)
                    && isValidJava8Flag(ENABLE_D8_DESUGARING, ENABLE_D8)) {
                return Java8LangSupport.D8;
            }
        }

        if (globalScope.getProjectOptions().get(BooleanOption.ENABLE_DESUGAR)) {
            return Java8LangSupport.DESUGAR;
        }

        BooleanOption missingFlag = shrinker == R8 ? ENABLE_R8_DESUGARING : ENABLE_D8_DESUGARING;
        globalScope
                .getErrorHandler()
                .reportError(
                        Type.GENERIC,
                        String.format(
                                "Please add '%s=true' to your "
                                        + "gradle.properties file to enable Java 8 "
                                        + "language support.",
                                missingFlag.name()),
                        getVariantConfiguration().getFullName());
        return Java8LangSupport.INVALID;
    }

    private boolean isValidJava8Flag(
            @NonNull BooleanOption flag, @NonNull BooleanOption... dependsOn) {
        List<String> invalid = null;
        for (BooleanOption requiredFlag : dependsOn) {
            if (!globalScope.getProjectOptions().get(requiredFlag)) {
                if (invalid == null) {
                    invalid = Lists.newArrayList();
                }
                invalid.add("'" + requiredFlag.getPropertyName() + "= false'");
            }
        }

        if (invalid == null) {
            return true;
        } else {
            String template =
                    "Java 8 language support, as requested by '%s= true' in your "
                            + "gradle.properties file, is not supported when %s.";
            String msg = String.format(template, flag.getPropertyName(), String.join(",", invalid));
            globalScope
                    .getErrorHandler()
                    .reportError(Type.GENERIC, msg, getVariantConfiguration().getFullName());
            return false;
        }
    }

    @NonNull
    @Override
    public ConfigurableFileCollection getTryWithResourceRuntimeSupportJar() {
        return desugarTryWithResourcesRuntimeJar.get();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(getFullVariantName()).toString();
    }

    @NonNull
    @Override
    public DexerTool getDexer() {
        if (globalScope.getProjectOptions().get(BooleanOption.ENABLE_D8)) {
            return DexerTool.D8;
        } else {
            return DexerTool.DX;
        }
    }

    @NonNull
    @Override
    public DexMergerTool getDexMerger() {
        if (globalScope.getProjectOptions().get(BooleanOption.ENABLE_D8)) {
            return DexMergerTool.D8;
        } else {
            return DexMergerTool.DX;
        }
    }

    @NonNull
    @Override
    public FileCollection getBootClasspath() {
        return globalScope.getBootClasspath();
    }

    @NonNull
    @Override
    public InternalArtifactType<Directory> getManifestArtifactType() {
        return globalScope.getProjectOptions().get(BooleanOption.IDE_DEPLOY_AS_INSTANT_APP)
                ? InternalArtifactType.INSTANT_APP_MANIFEST.INSTANCE
                : InternalArtifactType.MERGED_MANIFESTS.INSTANCE;
    }

    @NonNull
    @Override
    public File getSymbolTableFile() {
        return new File(
                globalScope.getIntermediatesDir(),
                "symbols/" + variantData.getVariantConfiguration().getDirName());
    }

    @NonNull
    @Override
    public JarCreatorType getJarCreatorType() {
        if (globalScope.getProjectOptions().get(USE_NEW_JAR_CREATOR)) {
            return JarCreatorType.JAR_FLINGER;
        } else {
            return JarCreatorType.JAR_MERGER;
        }
    }

    @NonNull
    @Override
    public ApkCreatorType getApkCreatorType() {
        if (globalScope.getProjectOptions().get(USE_NEW_APK_CREATOR)) {
            return ApkCreatorType.APK_FLINGER;
        } else {
            return ApkCreatorType.APK_Z_FILE_CREATOR;
        }
    }
}
