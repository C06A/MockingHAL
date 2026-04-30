val haldish: Configuration by configurations.creating

plugins {
    kotlin("jvm")
    application
    war
    id("com.gradleup.shadow")
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

// ─── dependencies ──────────────────────────────────────────────────────────────

val ktorVersion     = "3.0.3"
val jacksonVersion  = "2.17.2"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-servlet-jakarta:$ktorVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    haldish("com.helpchoice:haldish:2.2.4@run")
}

// ─── JVM toolchain ────────────────────────────────────────────────────────────

kotlin {
    jvmToolchain(21)
}

// ─── application ──────────────────────────────────────────────────────────────

application {
    mainClass.set("com.helpchoice.hal.mockinghal.ApplicationKt")
}

// ─── publish ──────────────────────────────────────────────────────────────────

/**
 * Resolve the published groupId the same way the :scripts publication does —
 * mavenCentralNamespace property takes precedence over project.group so that
 * local and CI builds find the artifact regardless of which namespace was used.
 */
val publishedGroup: String = providers.gradleProperty("mavenCentralNamespace")
    .orElse(providers.environmentVariable("MAVEN_CENTRAL_NAMESPACE"))
    .orElse(project.group.toString()).get()

tasks.register("checkPort") {
    group = "application"
    description = "Show which process is holding port 8080"
    doLast {
        val port = 8080
        val os = System.getProperty("os.name").lowercase()
        val (cmd, args) = if ("win" in os)
            "netstat" to listOf("-ano", "-p", "TCP")
        else
            "lsof"   to listOf("-i", ":$port", "-P", "-n")
        val result = ProcessBuilder(cmd, *args.toTypedArray())
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()
        val output = if ("win" in os)
            result.lines().filter { ":$port " in it }.joinToString("\n")
        else
            result
        println(if (output.isBlank()) "Nothing is holding port $port." else output)
    }
}

tasks.register("setup") {
    group = "build"
    dependsOn(haldish)
    inputs.files(haldish)
    outputs.dir(layout.buildDirectory.dir("haldish"))
    doLast {
        val runFile = haldish.singleFile
        runFile.setExecutable(true)
        exec {
            commandLine(runFile.absolutePath, "--prefix", layout.buildDirectory.dir("haldish").get().asFile.absolutePath)
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("app") {
            groupId    = publishedGroup
            artifactId = "mockinghal"

            artifact(tasks.named("shadowJar"))

            pom {
                name.set("mockinghal")
                description.set("Mock HAL API server for testing HALDiSh clients")
                url.set("https://github.com/C06A/HALDiSh")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("C06A")
                        name.set("C06A")
                        url.set("https://github.com/C06A")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/C06A/HALDiSh.git")
                    developerConnection.set("scm:git:ssh://github.com/C06A/HALDiSh.git")
                    url.set("https://github.com/C06A/HALDiSh")
                }
            }
        }
    }
}

signing {
    val keyFile = providers.gradleProperty("signingKeyFile")
        .orElse(providers.environmentVariable("SIGNING_KEY_FILE"))
        .orNull
    val password = providers.gradleProperty("signingPassword")
        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
        .orElse("").get()

    if (keyFile != null) {
        useInMemoryPgpKeys(file(keyFile).readText(), password)
        sign(publishing.publications["app"])
    }
}

// ─── Maven Central publishing (new Central Portal) ───────────────────────────
// Uses central.sonatype.com/api/v1/publisher/upload — works for new portal accounts.
// Credentials: mavenCentralUsername / mavenCentralPassword in gradle.properties
//              or env vars MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD.
//              Generate tokens at central.sonatype.com → Account → Generate User Token.
// publicationType = "USER_MANAGED"  → appears in portal for manual review before release
// publicationType = "AUTOMATIC"     → released to Central immediately after validation
nmcp {
    publish("app") {
        username.set(
            providers.gradleProperty("mavenCentralUsername")
                .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        )
        password.set(
            providers.gradleProperty("mavenCentralPassword")
                .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        )
        publicationType = "USER_MANAGED"
    }
}

// nmcp tasks have no group by default — assign them so they appear in the standard task list
afterEvaluate {
    tasks.named("publishAllPublicationsToCentralPortal") { group = "publishing" }
    tasks.named("publishAppPublicationToCentralPortal")  { group = "publishing" }
}
