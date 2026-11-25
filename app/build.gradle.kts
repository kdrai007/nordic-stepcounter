plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.nordic.stepcounter"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.nordic.stepcounter"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }

  sourceSets {
    getByName("test").assets.directories.add("$projectDir/schemas")
    getByName("androidTest").assets.directories.add("$projectDir/schemas")
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(21)
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  // Room Database
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // DataStore Preferences
  implementation(libs.androidx.datastore.preferences)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Unit Testing
  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.core.ktx)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.mockk)
  testImplementation(libs.robolectric)

  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
