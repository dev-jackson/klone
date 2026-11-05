import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.publish)
    alias(libs.plugins.vanniktech.publish)
    `java-gradle-plugin`
    signing
}

group = "io.github.dev-jackson"
version = "2.0.0-beta1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Build scripts are compiled by the Kotlin embedded in the user's Gradle; older Gradle
        // versions cannot read newer Kotlin metadata, so target the oldest supported line.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    }
}

val functionalTest: SourceSet by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
// Scenarios also call Klone classes directly (GitResolver against real remotes)
functionalTest.compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
functionalTest.runtimeClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()

gradlePlugin {
    testSourceSets(functionalTest)
    website = "https://github.com/dev-jackson/klone"
    vcsUrl = "https://github.com/dev-jackson/klone"

    plugins {
        create("kloneSettings") {
            id = "io.github.dev-jackson.klone"
            implementationClass = "dev.klone.KloneSettingsPlugin"
            displayName = "Klone — Git Dependency Manager for Android (Settings)"
            description = "Apply in settings.gradle.kts. Clones git repos, auto-detects Gradle modules, and sets up Composite Builds so gitImplementation() resolves to local source."
            tags = listOf("android", "dependency-management", "git", "package-manager", "composite-build", "gradle-plugin", "kotlin")
        }
        create("kloneProject") {
            id = "io.github.dev-jackson.klone.project"
            implementationClass = "dev.klone.KloneProjectPlugin"
            displayName = "Klone — Git Dependency Manager for Android (Project)"
            description = "Apply in app/build.gradle.kts. Adds gitImplementation(url, from/branch/commit) to the dependencies block."
            tags = listOf("android", "dependency-management", "git", "package-manager", "composite-build", "gradle-plugin", "kotlin")
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "klone-gradle-plugin", version.toString())

    pom {
        name = "Klone — Git Dependency Manager for Android"
        description = "Klone brings Swift Package Manager-style git dependency management to Android and Gradle. " +
            "Declare library dependencies by GitHub/GitLab/Bitbucket URL directly in build.gradle.kts using gitImplementation(). " +
            "Klone clones the repo, auto-detects its Gradle modules, and wires everything up as a Composite Build — " +
            "no binary publishing required. Supports tags, branches, and exact commits. Works with private repos. " +
            "Apply io.github.dev-jackson.klone in settings.gradle.kts and io.github.dev-jackson.klone.project in your module."
        inceptionYear = "2026"
        url = "https://github.com/dev-jackson/klone"

        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "dev-jackson"
                name = "Klone Contributors"
                url = "https://github.com/dev-jackson/klone/graphs/contributors"
            }
        }

        scm {
            url = "https://github.com/dev-jackson/klone"
            connection = "scm:git:git://github.com/dev-jackson/klone.git"
            developerConnection = "scm:git:ssh://git@github.com/dev-jackson/klone.git"
        }
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(gradleApi())
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// End-to-end scenarios: real Gradle builds (TestKit) against local git repos and a fake private Maven repo
val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs end-to-end scenarios against emulated projects."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    // -PtestGradleVersion=8.4 runs the scenarios against another Gradle (compatibility matrix)
    providers.gradleProperty("testGradleVersion").orNull?.let { systemProperty("klone.test.gradleVersion", it) }
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.check { dependsOn(functionalTestTask) }

// Generates a hands-on playground (host app + local git repos) in build/playground
tasks.register<JavaExec>("generatePlayground") {
    description = "Generates a playground host project wired to local git repos."
    group = "verification"
    classpath = functionalTest.runtimeClasspath
    mainClass.set("dev.klone.fixtures.PlaygroundKt")
    args(layout.buildDirectory.dir("playground").get().asFile.absolutePath)
    dependsOn(tasks.named("pluginUnderTestMetadata"))
}
