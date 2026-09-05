plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.3"
}

version = "1.2.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
    implementation("io.javalin:javalin:6.4.0")
    // OpenAPI はビルド時にだけ使う。仕様 JSON を吐いてフロントの型を生成したら用済みなので
    // ランタイム側のプラグインは入れない（JAR に載せない / /openapi も公開しない）。
    compileOnly("io.javalin.community.openapi:openapi-specification:6.1.3")
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
    testImplementation("io.javalin:javalin-testtools:6.4.0")
    // テストは本番クラスを参照するので、そこに付いた @OpenApi を解決できないと
    // 「不明な列挙型定数です HttpMethod.GET」という警告が出る。実害は無いが埋もれると困る。
    testCompileOnly("io.javalin.community.openapi:openapi-specification:6.1.3")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

tasks.shadowJar {
    relocate("io.javalin", "dev.warasugi.warp.libs.javalin")
    relocate("io.jsonwebtoken", "dev.warasugi.warp.libs.jwt")
    relocate("com.fasterxml.jackson", "dev.warasugi.warp.libs.jackson")
    relocate("org.apache.commons.codec", "dev.warasugi.warp.libs.commonscodec")
    mergeServiceFiles()
}

tasks.test { useJUnitPlatform() }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

// -A... はアノテーションプロセッサへの引数（生成される仕様の info に入る）。
// encoding は JDK 18 未満やロケール差でソース中の日本語が壊れないための保険。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Aopenapi.info.title=WARP API",
            "-Aopenapi.info.version=$version",
        )
    )
}

// ===== Frontend bundling =====
val isWindows = System.getProperty("os.name").lowercase().contains("win")
fun npmCommand(vararg args: String): List<String> =
    if (isWindows) listOf("cmd", "/c", "npm") + args else listOf("npm") + args

fun npxCommand(vararg args: String): List<String> =
    if (isWindows) listOf("cmd", "/c", "npx") + args else listOf("npx") + args

// バックエンドを SoT にする要。compileJava が吐いた仕様からフロントの型を生成する。
// api-types.ts は生成物なので .gitignore 済み。手書きしないこと。
val openApiSpec = layout.buildDirectory.file("classes/java/main/openapi-plugin/openapi-default.json")

val generateApiTypes by tasks.registering(Exec::class) {
    dependsOn(tasks.compileJava)
    workingDir = file("../frontend")
    commandLine(
        npxCommand(
            "openapi-typescript",
            openApiSpec.get().asFile.absolutePath,
            "-o", file("../frontend/src/lib/api-types.ts").absolutePath
        )
    )
    inputs.file(openApiSpec)
    outputs.file("../frontend/src/lib/api-types.ts")
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(generateApiTypes)
    workingDir = file("../frontend")
    commandLine(npmCommand("run", "build"))
    inputs.dir("../frontend/src")
    inputs.file("../frontend/package.json")
    outputs.dir("../frontend/dist")
}

val copyFrontend by tasks.registering(Copy::class) {
    dependsOn(npmBuild)
    from("../frontend/dist")
    into("src/main/resources/web")
}

tasks.processResources {
    dependsOn(copyFrontend)
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
