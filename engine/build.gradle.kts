plugins {
    kotlin("multiplatform") version "2.2.20"
}

repositories {
    mavenCentral()
}

kotlin {
    // JVM target exists purely to run the math test-suite fast (./gradlew jvmTest).
    jvm()

    // JS IR library is what the web app consumes; emits .js + .d.ts (ESM).
    js(IR) {
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        useEsModules()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
