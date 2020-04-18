plugins {
    java
}

group = "edu.purdue.dsnl"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java {
            exclude("com/android/tools/perflib/vmtrace/viz/**")
        }
    }
}

dependencies {
    implementation("com.google.guava:guava:27.0.1-jre")
    testCompile("junit", "junit", "4.12")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}