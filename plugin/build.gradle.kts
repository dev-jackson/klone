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
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

gradlePlugin {
    website = "https://github.com/dev-jackson/klone"
    vcsUrl = "https://github.com/dev-jackson/klone"

    plugins {
        create("kloneSettings") {
            id = "dev.klone"
            implementationClass = "dev.klone.KloneSettingsPlugin"
            displayName = "Klone - Android Package Manager (Settings)"
            description = "SPM-like git-based dependency management for Android"
            tags = listOf("android", "dependency-management", "git", "package-manager")
        }
        create("kloneProject") {
            id = "dev.klone.project"
            implementationClass = "dev.klone.KloneProjectPlugin"
            displayName = "Klone - Android Package Manager (Project)"
            description = "Provides gitImplementation() for use in the dependencies {} block"
            tags = listOf("android", "dependency-management", "git", "package-manager")
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "klone-gradle-plugin", version.toString())

    pom {
        name = "Klone"
        description = "SPM-like git-based dependency management for Android. Declare Android library dependencies by git URL."
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

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
