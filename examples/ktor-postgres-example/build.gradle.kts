import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.10"
    application
}

group = "com.github.ichanzhar.rsql.example"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.0"
val jimmerVersion = "0.9.96"
val testcontainersVersion = "2.0.5"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
    implementation("com.github.ichanzhar:jimmer-rsql-support:0.1.0-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainersVersion")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

application {
    mainClass.set("com.github.ichanzhar.rsql.example.MainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
