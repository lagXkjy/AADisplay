plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
    id("com.google.protobuf") version "0.9.5"
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.5")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
