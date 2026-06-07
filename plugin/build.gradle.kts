plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.3"
}

version = "1.0.1"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
    implementation("io.javalin:javalin:6.4.0")
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:6.1.3")
    annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:6.1.3")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("dev.samstevens.totp:totp:1.7.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    relocate("io.javalin", "dev.warasugi.warp.libs.javalin")
    relocate("io.jsonwebtoken", "dev.warasugi.warp.libs.jwt")
    relocate("com.fasterxml.jackson", "dev.warasugi.warp.libs.jackson")
    mergeServiceFiles()
}

tasks.test { useJUnitPlatform() }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

// ===== Frontend bundling =====
val npmBuild by tasks.registering(Exec::class) {
    workingDir = file("../frontend")
    commandLine("cmd", "/c", "npm", "run", "build")
    inputs.dir("../frontend/src")
    inputs.file("../frontend/package.json")
    outputs.dir("../frontend/dist")
}

val copyFrontend by tasks.registering(Copy::class) {
    dependsOn(npmBuild)
    from("../frontend/dist")
    into("src/main/resources/web")
}

tasks.named("processResources") {
    dependsOn(copyFrontend)
}
