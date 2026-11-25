plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // コンパイルとテストには、アプリを動かすものと同じ JetBrains Runtime を使う。
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.networkKtor)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ksoup)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(libs.sqldelight.sqliteDriver)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

// テストが作る一時ファイルを、利用者の一時領域ではなくビルドディレクトリの下に置く。
tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
}

sqldelight {
    databases {
        create("LegirssDatabase") {
            packageName.set("to.sava.legirss.data.db")
            // 版ごとのスキーマの控え（N.db）を置く場所。移行のファイル（N.sqm）が正しいかを、この控えで確かめる。
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
