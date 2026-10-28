import io.papermc.paperweight.checkstyle.PaperCheckstyleExt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.24"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

// Cubiq is built as a patch set on top of Purpur, which in turn patches Paper.
// The upstream checkout is pinned by 'purpurCommit' in gradle.properties.
paperweight {
    upstreams.register("purpur") {
        repo = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurCommit")

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("cubiq-server/build.gradle.kts")
            patchFile = file("cubiq-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("cubiq-api/build.gradle.kts")
            patchFile = file("cubiq-api/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-checkstyle/build.gradle.kts"
            outputFile = file("cubiq-checkstyle/build.gradle.kts")
            patchFile = file("cubiq-checkstyle/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("cubiq-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = setOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("cubiq-api/purpur-patches")
            outputDir = file("purpur-api")
        }
        patchRepo("paperCheckstyle") {
            upstreamPath = "paper-checkstyle"
            patchesDir = file("cubiq-checkstyle/paper-patches")
            outputDir = file("paper-checkstyle")
        }
        patchRepo("paperCheckstyleConfig") {
            upstreamPath = ".checkstyle"
            patchesDir = file("cubiq-checkstyle/config-patches")
            outputDir = file(".checkstyle")
        }
    }
}

subprojects {
    apply {
        plugin("java-library")
        plugin("maven-publish")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // Checkstyle is enforced for the API (as upstream does); server sources are too large and mostly upstream code
    val checkstyleDisabled = setOf("cubiq-server", "paper-server", "test-plugin")

    if (name !in checkstyleDisabled) {
        apply { plugin("io.papermc.paperweight.paper-checkstyle") }
        extensions.configure<PaperCheckstyleExt> {
            typeUseAnnotationsFile.set(rootProject.layout.projectDirectory.file(".checkstyle/type_use_annotations.txt"))
        }

        dependencies {
            "checkstyle"(project(":cubiq-checkstyle"))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printCubiqVersion") {
    doLast {
        println(project.version)
    }
}
