import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Подпись release-сборки: keystore.properties в корне проекта (не коммитится).
// storeFile=.toolchain/keystore/exmail.jks, storePassword=..., keyAlias=exmail, keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Сервер организации по умолчанию: company.properties в корне проекта (не коммитится, образец — company.properties.example).
// Без файла приложение ни о какой организации не знает: сервер находится по рабочему e-mail при входе.
val companyProps = Properties().apply {
    val f = rootProject.file("company.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun company(key: String): String = companyProps.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "io.github.denhaiz.exmail"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.denhaiz.exmail"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        buildConfigField("String", "DEFAULT_EWS_URL", "\"${company("ewsUrl")}\"")
        buildConfigField("String", "DEFAULT_MAIL_DOMAIN", "\"${company("mailDomain")}\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                // Тем же ключом подписаны все выпущенные версии: новая ставится поверх без удаления.
                // Ключ лежит только в рабочей папке (.toolchain), стандартные пароль и псевдоним debug-ключа Android.
                storeFile = rootProject.file(".toolchain/android-user-home/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Интеграционный тест против реального сервера включается переменными EXMAIL_USER / EXMAIL_PASSWORD.
            it.environment("EXMAIL_USER", System.getenv("EXMAIL_USER") ?: "")
            it.environment("EXMAIL_PASSWORD", System.getenv("EXMAIL_PASSWORD") ?: "")
            it.environment("EXMAIL_URL", System.getenv("EXMAIL_URL") ?: "")
            it.environment("EXMAIL_SEND_TEST", System.getenv("EXMAIL_SEND_TEST") ?: "")
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // Установка профиля компиляции (baseline-prof.txt) при установке APK не из Google Play — быстрый запуск и прокрутка
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // Проверка вёрстки: рендер экранов в PNG без эмулятора
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
