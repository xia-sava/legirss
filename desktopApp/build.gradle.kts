import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "to.sava.legirss.MainKt"
    }
}

// 開発起動（run）ではデータをビルドディレクトリの下に置き、利用者のデータ領域に触れない。
val devDataDir = layout.buildDirectory.dir("dev-data")
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        systemProperty("legirss.dataDir", devDataDir.get().asFile.absolutePath)
    }
}
