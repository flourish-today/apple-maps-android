import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

val testBuild = providers.gradleProperty("testBuild").orNull == "true"

android {
    namespace = "org.getflourish.maps"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "org.getflourish.maps"
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "1.0"
        manifestPlaceholders["appName"] = if (testBuild) "Apple Maps Test" else "Apple Maps"
        buildConfigField("boolean", "TEST", testBuild.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        if (testBuild) {
            getByName("release") {
                manifest.srcFile("testing/AndroidManifest.xml")
                res.directories.add("testing/res")
            }
        }
    }

    buildTypes {
        release {
            if (testBuild) applicationIdSuffix = ".test"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
