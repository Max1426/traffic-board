import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingFile = File(System.getProperty("user.home"), ".android/traffic-board-signing/signing.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.liuliang.board"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.liuliang.board"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingFile.exists()) {
                signingConfig = signingConfigs.create("distribution") {
                    storeFile = file(signingValues.getProperty("storeFile"))
                    storePassword = signingValues.getProperty("storePassword")
                    keyAlias = signingValues.getProperty("keyAlias")
                    keyPassword = signingValues.getProperty("keyPassword")
                }
            }
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Windows Gradle workers cannot reliably load class directories whose paths contain Chinese.
// Stage compiled classes only; source files and test reports remain in this checkout.
val stagedTestClasses = tasks.register<Sync>("stageLocalUnitTestClasses") {
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin", "compileDebugJavaWithJavac")
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"))
    from(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
    into(File(System.getProperty("java.io.tmpdir"), "TrafficBoard-test-classes/${rootDir.absolutePath.hashCode().toUInt().toString(16)}"))
}
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(stagedTestClasses)
        doFirst { classpath += files(stagedTestClasses.get().destinationDir) }
    }
    jvmArgs("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
