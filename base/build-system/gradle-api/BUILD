load("//tools/base/bazel:maven.bzl", "maven_java_library", "maven_pom")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library", "kotlin_test")
load("//tools/base/bazel:utils.bzl", "fileset")

fileset(
    name = "resources",
    srcs = glob(["src/main/resources/**"]),
    mappings = {
        "src/main/resources/": "",
    },
)

kotlin_library(
    name = "gradle-api",
    srcs = [
        "src/main/java",
    ],
    pom = ":pom",
    resource_strip_prefix = "tools/base/build-system/gradle-api",
    resources = [":resources"],
    visibility = ["//visibility:public"],
    deps = [
        ":builder-model_neverlink",  # Only present for legacy reasons.
        "//tools/base/annotations",
        "//tools/base/build-system:gradle-api_neverlink",
        "//tools/base/build-system/builder-test-api:tools.builder-test-api",
        "//tools/base/third_party:com.google.guava_guava",
        "//tools/base/third_party:org.jetbrains.kotlin_kotlin-stdlib",
    ],
)

maven_pom(
    name = "pom",
    artifact = "gradle-api",
    group = "com.android.tools.build",
    source = "//tools/buildSrc/base:build_version",
)

kotlin_test(
    name = "tests",
    size = "medium",
    srcs = ["src/test/java"],
    coverage = True,
    jvm_flags = ["-Dtest.suite.jar=tests.jar"],
    resources = glob(["src/test/resources/**"]),
    test_class = "com.android.testutils.JarTestSuite",
    runtime_deps = [
        "//tools/base/third_party:org.codehaus.groovy_groovy-all",
    ],
    deps = [
        ":gradle-api",
        "//tools/base/annotations",
        "//tools/base/build-system:gradle-api",
        "//tools/base/build-system/builder-model",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/third_party:com.google.guava_guava",
        "//tools/base/third_party:com.google.truth_truth",
        "//tools/base/third_party:junit_junit",
        "//tools/base/third_party:org.jetbrains.kotlin_kotlin-stdlib",
    ],
)

java_library(
    name = "builder-model_neverlink",
    neverlink = 1,
    exports = ["//tools/base/build-system/builder-model"],
)
