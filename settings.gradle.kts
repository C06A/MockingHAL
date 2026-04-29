pluginManagement {
    plugins {
        kotlin("jvm") version "2.0.21"
        id("com.gradleup.shadow") version "8.3.5"
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "mockinghal"

include("mockinghal")
