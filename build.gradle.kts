plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.netflix.dgs.codegen") version "8.3.0"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "IngredientCatalogue"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Publishes build/resources/main/META-INF/build-info.properties so /actuator/info
// reports the running version, build time, and coordinates.
springBoot {
    buildInfo()
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.0"

dependencies {
    implementation("com.example:catalogue-common")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-rest")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-mysql")
    // Distributed tracing: propagate a trace id across services, report spans to Zipkin.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("org.springframework.boot:spring-boot-zipkin")
    // Span sender: spring-boot-zipkin 4.1.x's JDK-HttpClient sender fails with
    // ClosedChannelException in-container; TracingSenderConfig swaps in this one.
    implementation("io.zipkin.reporter2:zipkin-sender-urlconnection")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-rest-test")
    // Contract test: validate live responses against src/main/resources/static/openapi.yaml
    testImplementation("com.atlassian.oai:swagger-request-validator-mockmvc:2.46.1")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

tasks.generateJava {
    schemaPaths.add("${projectDir}/src/main/resources/graphql-client")
    packageName = "com.example.ingredientcatalogue.codegen"
    generateClient = true
}

tasks.withType<Test> {
    useJUnitPlatform()
}
