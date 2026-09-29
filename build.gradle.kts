import org.gradle.jvm.tasks.Jar

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "1.9.24"
}

group = "io.github.balticamadeus"
// Overridable so CI can set the real released version from a pushed
// tag (-PpopeVersion=x.y.z) without touching this file - local dev
// keeps resolving to the snapshot by default.
version = (findProperty("popeVersion") as String?) ?: "0.1.0-SNAPSHOT"

// java-gradle-plugin + maven-publish together auto-register a
// "pluginMaven" publication (the plugin's own jar/pom) plus a marker
// publication per entry in gradlePlugin{} below (what lets a consumer
// resolve by plugin id instead of group:artifact coordinates) - no
// publications{} block needed here.
//
// popePublishRepoUrl defaults to a local, disposable folder so
// `./gradlew publish` works out of the box for testing. The real
// release path (see .github/workflows/publish.yml) passes it a local
// checkout of the `maven-repo` branch, which CI then commits and
// pushes - that branch, served via GitHub Pages, is the actual
// git-repo-hosted Maven repo decided in ADR-0008.
publishing {
    repositories {
        maven {
            name = "pope"
            url =
                uri(
                    (findProperty("popePublishRepoUrl") as String?)
                        ?: layout.buildDirectory.dir("local-maven-repo").get().asFile.toURI().toString(),
                )
        }
    }
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

// Lets popeVersion (PopePlugin.kt) read its own version back at runtime
// via Package.getImplementationVersion() - Gradle exposes no simpler
// "what version was I resolved at" API to a plugin's own code.
tasks.named<Jar>("jar") {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

val functionalTest: SourceSet by sourceSets.creating

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])

gradlePlugin {
    plugins {
        create("pope") {
            id = "io.github.balticamadeus.pope"
            implementationClass = "pope.PopePlugin"
            displayName = "pope"
            description = "Dependency management, versioning, and PROPATH generation for Progress OpenEdge ABL."
        }
    }
    testSourceSets.add(functionalTest)
}

dependencies {
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
    "functionalTestImplementation"(kotlin("test"))
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"("org.json:json:20250517")
}

tasks.test {
    useJUnitPlatform()
}

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
        description = "Runs the plugin against real Gradle builds via TestKit."
        group = "verification"
        testClassesDirs = functionalTest.output.classesDirs
        classpath = functionalTest.runtimeClasspath
        useJUnitPlatform()
        // Fixtures reference demo/ files relative to the repo root.
        workingDir = rootDir
        // PublishedPluginFunctionalTest applies the plugin via a real
        // Maven repository lookup (no withPluginClasspath()/includeBuild
        // shortcut) - it needs the current version actually published
        // somewhere Gradle's normal plugin resolution can find it first.
        // mavenLocal() is used rather than popePublishRepoUrl's target
        // here since it needs no configuration and is always available.
        dependsOn("publishToMavenLocal")
        systemProperty("popePluginVersion", version.toString())
    }

tasks.check {
    dependsOn(functionalTestTask)
}
