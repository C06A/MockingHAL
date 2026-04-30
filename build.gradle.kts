plugins {
    kotlin("jvm")
}

allprojects {
    group   = "com.helpchoice.kotlin.hal"
    version = "1.0.1"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

