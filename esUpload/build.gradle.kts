import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    `maven-publish`
}

android {
    namespace = "com.extremesolution.esupload"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.extremesolution"
                artifactId = "esupload"
                version = "1.0.0"

                pom {
                    name.set("esUpload")
                    description.set("Sequential file upload library for Android using WorkManager. Built by Extreme Solution.")
                    url.set("https://github.com/ReemMousaES/esUpload")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("extremesolution")
                            name.set("Extreme Solution")
                        }
                    }
                }
            }
        }
    }
}

dependencies {
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Hilt WorkManager integration
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // OkHttp for multipart uploads
    implementation(libs.okhttp.logging.interceptor)

    // Coroutines
    implementation(platform(libs.kotlinx.coroutines.bom))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Room (for upload tracking DB)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gson for serialization
    implementation(libs.retrofit.converter.gson)

    // LiveData (for observing upload state)
    implementation(libs.androidx.lifecycle.livedata.ktx)
}
