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

package com.android.build.gradle.integration.nativebuild

import com.android.build.gradle.integration.common.fixture.GradleTestProject.DEFAULT_NDK_SIDE_BY_SIDE_VERSION
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldJniApp
import com.android.testutils.truth.PathSubject.assertThat
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.cxx.configure.CmakeProperty.*
import com.android.build.gradle.internal.cxx.json.AndroidBuildGradleJsons.getNativeBuildMiniConfig
import com.android.build.gradle.internal.cxx.json.NativeBuildConfigValueMini
import com.android.build.gradle.internal.cxx.model.createCxxAbiModelFromJson
import com.android.build.gradle.internal.cxx.model.jsonFile
import com.android.build.gradle.internal.cxx.settings.Macro.*
import com.android.builder.model.NativeAndroidProject
import com.android.utils.FileUtils.join
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CmakeSettingsTest(cmakeVersionInDsl: String) {

    @Rule
    @JvmField
    var project = GradleTestProject.builder()
        .fromTestApp(
            HelloWorldJniApp.builder().withNativeDir("cxx").withCmake().build()
        )
        .setCmakeVersion(cmakeVersionInDsl)
        .setSideBySideNdkVersion(DEFAULT_NDK_SIDE_BY_SIDE_VERSION)
        .create()


    companion object {
        @Parameterized.Parameters(name = "model = {0}")
        @JvmStatic
        fun data() = arrayOf(
            arrayOf("3.6.0-x"),
            arrayOf("3.10.2"))
    }

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            join(project.buildFile.parentFile, "CMakeSettings.json"),
            """
            {
                "configurations": [{
                    "name": "android-gradle-plugin-predetermined-name",
                    "description": "Configuration generated by Android Gradle Plugin",
                    "inheritEnvironments": ["ndk"],
                    "buildCommandArgs": "",
                    "buildRoot": "${NDK_PROJECT_DIR.ref}/cmake/android/${NDK_VARIANT_NAME.ref}/${NDK_ABI.ref}",
                    "variables": [
                        {"name": "$CMAKE_C_FLAGS", "value": "-DTEST_C_FLAG -DTEST_C_FLAG_2"},
                        {"name": "$CMAKE_CXX_FLAGS", "value": "-DTEST_CPP_FLAG"},
                    ]
                }]
            }""".trimIndent())

        TestFileUtils.appendToFile(
            project.buildFile,
            """
                apply plugin: 'com.android.application'

                android {
                    compileSdkVersion ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}
                    buildToolsVersion "${GradleTestProject.DEFAULT_BUILD_TOOL_VERSION}"
                    ndkVersion "$DEFAULT_NDK_SIDE_BY_SIDE_VERSION"
                    defaultConfig {
                      externalNativeBuild {
                          cmake {
                            abiFilters.addAll("armeabi-v7a", "x86_64");
                            targets.addAll("hello-jni")
                            // TODO enable this once configuration has been added to DSL
                            // configuration "my-test-configuration"
                          }
                      }
                    }
                    externalNativeBuild {
                      cmake {
                        path "CMakeLists.txt"
                      }
                    }
                    buildTypes {
                        debug {}
                        release {}
                        minSizeRel {}
                        relWithDebInfo {}
                    }
                }

            """.trimIndent()
        )
    }

    @Test
    fun checkBuildFoldersRedirected() {
        project.execute("clean", "assemble")
        val model = project.model().fetch(NativeAndroidProject::class.java)
        val abis = 2
        val buildTypes = 4
        assertThat(model).hasBuildOutputCountEqualTo(abis * buildTypes)
        assertThat(model).allBuildOutputsExist()
        val projectRoot = project.buildFile.parentFile
        assertThat(join(projectRoot, "cmake/android/debug")).isDirectory()
        assertThat(join(projectRoot, "cmake/android/release")).isDirectory()
        assertThat(join(projectRoot, "cmake/android/minSizeRel")).isDirectory()
        assertThat(join(projectRoot, "cmake/android/relWithDebInfo")).isDirectory()
    }

    @Test
    fun checkJsonRegeneratedForDifferentBuildCommands() {
        project.execute("clean", "assemble")
        val miniConfigs = getMiniConfigs()

        assertThat(miniConfigs.size).isEqualTo(2)
        for(miniConfig in miniConfigs){
            val buildCommand = miniConfig.buildTargetsCommand
            assertThat(buildCommand).doesNotContain("-j 100")
        }

        TestFileUtils.searchAndReplace(
            join(project.buildFile.parentFile, "CMakeSettings.json"),
            "\"buildCommandArgs\": \"\",",
            "\"buildCommandArgs\": \"-j 100\",")

        project.execute("clean", "assemble")
        val miniConfigsWithBuildCommandArgs = getMiniConfigs()

        assertThat(miniConfigs.size).isEqualTo(2)
        for(miniConfig in miniConfigsWithBuildCommandArgs){
            val buildCommand = miniConfig.buildTargetsCommand
            assertThat(buildCommand).contains("-j 100")
        }
    }

    private fun getMiniConfigs() : List<NativeBuildConfigValueMini> {
        val x86JsonFile =
            join(project.testDir, ".cxx", "cmake", "debug", "x86_64", "build_model.json")
        val armJsonFile =
            join(project.testDir, ".cxx", "cmake", "debug", "armeabi-v7a", "build_model.json")

        return listOf(x86JsonFile, armJsonFile)
            .filter { it.exists() }
            .map { modelFile -> createCxxAbiModelFromJson(modelFile.readText()).jsonFile }
            .map { jsonFile -> getNativeBuildMiniConfig(jsonFile, null) }
    }
}
