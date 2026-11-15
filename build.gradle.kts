plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.diagramio"
version = "0.0.1-SNAPSHOT"
description = "Backend REST API cho dịch vụ vẽ sơ đồ UML/ERD trực tuyến"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.springdoc.openapi)
    implementation(libs.springboot3.dotenv)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.flyway.test)
    testImplementation(libs.archunit)

    // Testcontainers (phiên bản 2.0.5 do Spring Boot 4.1.2 BOM quản lý)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "-Dnet.bytebuddy.experimental=true",
        "--enable-native-access=ALL-UNNAMED"
    )

    // Tự động kết nối Podman socket nếu có trên hệ thống để chạy Testcontainers
    val podmanSock = file("/run/user/1000/podman/podman.sock")
    if (podmanSock.exists() && System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix://${podmanSock.absolutePath}")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    }

    // Cho phép bỏ qua test E2E: ./gradlew test -PskipE2e hoặc ./gradlew test -DskipE2e
    if (project.hasProperty("skipE2e") || System.getProperty("skipE2e") != null) {
        filter {
            excludeTestsMatching("com.diagramio.e2e.*")
        }
    }
}
