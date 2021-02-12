import java.util.Properties

plugins {
    kotlin("jvm") version "1.4.30"
    id("org.jetbrains.dokka") version "0.10.1"
    jacoco
    id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
    id("io.gitlab.arturbosch.detekt") version "1.15.0"
    `maven-publish`
    signing
    id("io.codearte.nexus-staging") version "0.22.0"
}

val myGroup = "com.github.pgreze".also { group = it }
val myArtifactId = "kotlin-process"
val tagVersion = System.getenv("GITHUB_REF")?.split('/')?.last()
val myVersion = (tagVersion?.trimStart('v') ?: "WIP").also { version = it }
val myDescription = "Counting easily with Kotlin".also { description = it }
val githubUrl = "https://github.com/pgreze/$myArtifactId"

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    disabledRules.set(setOf("import-ordering"))
}
configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    baseline = file("detekt-baseline.xml")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}
tasks.jacocoTestReport {
    reports {
        xml.isEnabled = true
        html.isEnabled = System.getenv("CI") != "true"
    }
}

val moveCss by tasks.registering {
    description = "Move style.css in the base folder, easier for distribution."
    fun File.rewriteStyleLocations() {
        readText().replace("../style.css", "style.css")
            .also { writeText(it) }
    }
    fun File.recursivelyRewriteStyleLocations() {
        list()?.map(this::resolve)?.forEach {
            if (it.isDirectory) it.recursivelyRewriteStyleLocations() else it.rewriteStyleLocations()
        }
    }
    doLast {
        val dokkaOutputDirectory = file(tasks.dokka.get().outputDirectory)
        val baseFolder = dokkaOutputDirectory.resolve(myArtifactId)
        baseFolder.recursivelyRewriteStyleLocations()
        dokkaOutputDirectory.resolve("style.css").also {
            it.renameTo(baseFolder.resolve(it.name))
        }
    }
}
tasks.dokka {
    outputFormat = "html"
    outputDirectory = "$buildDir/dokka"
    configuration {
        sourceLink {
            // URL showing where the source code can be accessed through the web browser
            url = "$githubUrl/tree/${tagVersion ?: "master"}/"
            // Suffix which is used to append the line number to the URL. Use #L for GitHub
            lineSuffix = "#L"
        }
    }
    finalizedBy(moveCss)
}

repositories {
    jcenter()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")

    testImplementation("org.amshove.kluent:kluent:1.61")
    val junit5 = "5.7.1"
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junit5")
}

//
// Publishing
//

val local = rootProject.file("local.properties")
    .takeIf(File::exists)
    ?.let { f -> f.reader().use { Properties().also { p -> p.load(it) } } }
val propOrEnv: (String, String) -> String? = { key, envName -> local?.get(key)?.toString() ?: System.getenv(envName) }

val ossrhUsername = propOrEnv("ossrh.username", "OSSRH_USERNAME")
val ossrhPassword = propOrEnv("ossrh.password", "OSSRH_PASSWORD")

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = myGroup
            artifactId = myArtifactId
            version = myVersion

            from(components["java"])

            pom {
                name.set(myArtifactId)
                description.set(myDescription)
                url.set(githubUrl)
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("pgreze")
                        name.set("Pierrick Greze")
                    }
                }
                scm {
                    connection.set("$githubUrl.git")
                    developerConnection.set("scm:git:ssh://github.com:pgreze/$myArtifactId.git")
                    url.set(githubUrl)
                }
            }
        }
    }
    repositories {
        maven {
            name = "sonatype"
            setUrl("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = ossrhUsername
                password = ossrhPassword
            }
        }
    }
}
mapOf(
    "signing.keyId" to "SIGNING_KEY_ID",
    "signing.password" to "SIGNING_PASSWORD",
    "signing.secretKeyRingFile" to "SIGNING_SECRET_KEY_RING_FILE"
).forEach { (key, envName) ->
    val value = propOrEnv(key, envName)
        ?.let {
            if (key.contains("File")) {
                rootProject.file(it).absolutePath
            } else it
        }
    ext.set(key, value)
}
signing {
    sign(publishing.publications)
}

// https://github.com/Codearte/gradle-nexus-staging-plugin
nexusStaging {
    packageGroup = myGroup
    stagingProfileId = propOrEnv("sonatype.staging.profile.id", "SONATYPE_STAGING_PROFILE_ID")
    username = ossrhUsername
    password = ossrhPassword
}
