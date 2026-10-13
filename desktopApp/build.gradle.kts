import org.gradle.api.tasks.JavaExec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // テストが作る一時ファイルを、利用者の一時領域ではなくビルドディレクトリの下に置く。
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
}

// 配布物の名前。インストール先のディレクトリ、起動用の実行ファイル、スタートメニューの表示名になる。
val appPackageName = "legirss"

// 版数は配布物を作るときに -Plegirss.version で渡す。渡さなければ既定値を使う。
val appVersion = providers.gradleProperty("legirss.version").getOrElse("0.1.0")

// アプリが自分の版を知るための値を、リソースとして埋め込む。
val versionResourceDir = layout.buildDirectory.dir("generated/version")
val generateVersionProperties = tasks.register<WriteProperties>("generateVersionProperties") {
    destinationFile = versionResourceDir.map { it.file("legirss-version.properties") }
    property("version", appVersion)
}

sourceSets["main"].resources.srcDir(versionResourceDir)

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVersionProperties)
}

compose.desktop {
    application {
        mainClass = "to.sava.legirss.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = appPackageName
            packageVersion = appVersion
            // 同梱する実行環境に含めるモジュール。suggestRuntimeModules タスクで求めたもの。
            modules("java.instrument", "java.management", "java.net.http", "java.sql", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/legirss.ico"))
                // データを利用者の領域に持つ 1 人用のアプリなので、インストールに管理者の権限を求めない。
                perUserInstall = true
                // 版を上げたインストーラが前の版を置き換えるよう、アップグレードコードを固定する。
                upgradeUuid = "70d7c713-b8e6-4c2c-bb95-bbfbff061988"
                menu = true
                menuGroup = appPackageName
            }
        }
    }
}

// アプリのアイコンのファイルを、タスクトレイのアイコンと同じ絵から作り直す。絵を変えたときに実行する。
tasks.register<JavaExec>("generateIcon") {
    group = "build setup"
    description = "Regenerates icons/legirss.ico from the tray icon drawing."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "to.sava.legirss.tools.IconFileKt"
    args(layout.projectDirectory.file("icons/legirss.ico").asFile.absolutePath)
}

// 開発起動（run）ではデータをビルドディレクトリの下に置き、利用者のデータ領域に触れない。
val devDataDir = layout.buildDirectory.dir("dev-data")
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        systemProperty("legirss.dataDir", devDataDir.get().asFile.absolutePath)
    }
}
