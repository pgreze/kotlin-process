plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    jacoco
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin")
}

val myGroup = "com.github.pgreze"
    .also { group = it }
val myArtifactId = "kotlin-process"
val tagVersion = System.getenv("GITHUB_REF")?.split('/')?.last()
val myVersion = (tagVersion?.trimStart('v') ?: "WIP")
    .also { version = it }
val myDescription = "Kotlin friendly way to run an external process"
    .also { description = it }
val githubUrl = "https://github.com/pgreze/$myArtifactId"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    withSourcesJar()
    withJavadocJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    disabledRules.set(setOf("import-ordering"))
}

jacoco {
    toolVersion = "0.8.7"
}
tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(System.getenv("CI") != "true")
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(KotlinX.coroutines.core)

    testImplementation("org.amshove.kluent:kluent:_")
    testImplementation(platform(Testing.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

//
// Publishing
//

val propOrEnv: (String, String) -> String? = { key, envName ->
    project.properties.getOrElse(key, defaultValue = { System.getenv(envName) })?.toString()
}

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

nexusPublishing {
    packageGroup.set(myGroup)
    repositories {
        sonatype {
            username.set(ossrhUsername)
            password.set(ossrhPassword)
        }
    }
}
