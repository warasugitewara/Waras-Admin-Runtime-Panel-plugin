plugins {
    // Paper 26.x の Java 25 が手元に無くても、Gradle が自動で取得できるようにする。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "warp"
