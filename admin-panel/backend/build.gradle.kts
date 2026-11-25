import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

// Align BOM-managed core with the test modules that support current Docker APIs.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

group = "kz.epharm"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(22))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.sentry.spring.boot)

    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    runtimeOnly(libs.postgresql)
    // Standard-N pharmacist directory connects to Firebird at runtime.
    runtimeOnly(libs.jaybird)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    implementation(libs.aws.s3)
    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)
    implementation(libs.zxing.core)
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")

    implementation(libs.springdoc.openapi.starter)

    developmentOnly(libs.spring.boot.devtools)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "org.mockito", module = "mockito-core")
    }
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
}

// CI deals the suite out over machines: -PtestShards=8 -PtestShard=0..7, and -PtestForks=2 test JVMs on each.
// Every integration test class starts its own PostgreSQL container, so classes are independent. Integration and
// plain classes are dealt out separately, in name order, so each machine gets an even share of the slow ones.
val testShards = providers.gradleProperty("testShards").map(String::toInt).getOrElse(1)
val testShard = providers.gradleProperty("testShard").map(String::toInt).getOrElse(0)
val testForks = providers.gradleProperty("testForks").map(String::toInt).getOrElse(1)

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
    forkEvery = 12
    maxParallelForks = testForks
    systemProperty("spring.test.context.cache.maxSize", "6")
    if (testShards > 1) {
        require(testShard in 0 until testShards) { "testShard must be 0..${testShards - 1}" }
        val classesDirs = testClassesDirs
        val shardOf: Map<String, Int> by lazy {
            val names = sortedSetOf<String>()
            classesDirs.asFileTree.matching { include("**/*.class") }.visit {
                if (!isDirectory) names += relativePath.pathString.removeSuffix(".class").substringBefore('$')
            }
            val (slow, plain) = names.partition { it.endsWith("IntegrationTest") }
            (slow + plain).withIndex().associate { (index, name) -> name to index % testShards }
        }
        exclude { element ->
            !element.isDirectory && element.name.endsWith(".class") &&
                shardOf[element.relativePath.pathString.removeSuffix(".class").substringBefore('$')] != testShard
        }
    }
}

// Стабильное имя исполняемого jar — чтобы Dockerfile копировал build/libs/app.jar
// без зависимости от версии. Plain-jar не нужен в runtime-образе.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
tasks.named<Jar>("jar") {
    enabled = false
}

val verifyMerchandisingArtifact by tasks.registering {
    dependsOn("bootJar")
    doLast {
        val contract = Properties().apply {
            file("src/main/resources/merch-artifact-contract.properties").inputStream().use { load(it) }
        }
        ZipFile(layout.buildDirectory.file("libs/app.jar").get().asFile).use { jar ->
            contract.forEach { path, markers ->
                val entry = requireNotNull(jar.getEntry(path.toString())) { "Missing merchandising artifact: $path" }
                val bytes = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                markers.toString().split(',').forEach { marker ->
                    check(bytes.contains(marker)) { "Obsolete merchandising artifact $path: missing $marker" }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyMerchandisingArtifact) }
