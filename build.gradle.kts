plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.diagramio"
version = "0.0.1-SNAPSHOT"
description = "Backend REST API cho dịch vụ vẽ sơ đồ UML/ERD trực tuyến"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// Ghi đè testcontainers.version trong Spring dependency management BOM để dùng 1.21.4 (tương thích Docker 29+)
extra["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)

    implementation(libs.bundles.flyway)
    runtimeOnly(libs.postgresql)

    implementation(libs.springdoc.openapi)
    implementation(libs.springboot3.dotenv)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.archunit)

    // Testcontainers với phiên bản 1.21.4
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Cho phép bỏ qua test E2E khi không có Docker: ./gradlew test -PskipE2e hoặc ./gradlew test -DskipE2e
    if (project.hasProperty("skipE2e") || System.getProperty("skipE2e") != null) {
        filter {
            excludeTestsMatching("com.diagramio.e2e.*")
        }
    }
}
