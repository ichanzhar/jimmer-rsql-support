import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    `maven-publish`
    signing
}

group = "com.github.ichanzhar"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()
}

dependencies {
    api("cz.jirutka.rsql:rsql-parser:2.1.0")
    compileOnly("org.babyfish.jimmer:jimmer-sql-kotlin:0.9.96")
    implementation("org.slf4j:slf4j-api:2.0.17")
}

java {
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(sourcesJar.get())
            pom {
                name.set("Jimmer RSQL Support")
                description.set("RSQL implementation for Jimmer ORM with association path and collection support")
                url.set("https://github.com/ichanzhar/jimmer-rsql-support")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("http://www.opensource.org/licenses/mit-license.php")
                    }
                }
                developers {
                    developer {
                        id.set("ichanzhar")
                        name.set("Ihor Chanzhar")
                        email.set("ihor.chanzhar@gmail.com")
                        organization.set("com.github.ichanzhar")
                        organizationUrl.set("https://github.com/ichanzhar")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/ichanzhar/jimmer-rsql-support.git")
                    developerConnection.set("scm:git:git@github.com:ichanzhar/jimmer-rsql-support.git")
                    url.set("https://github.com/ichanzhar/jimmer-rsql-support")
                }
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, providers.environmentVariable("SIGNING_PASSWORD").orNull)
    }
    sign(publishing.publications.getByName("mavenJava"))
}
