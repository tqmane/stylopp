plugins { id("com.android.application") }
android { namespace = "jp.stylopp.demo"; compileSdk = 37
    defaultConfig { applicationId = "jp.stylopp.demo"; minSdk = 31; targetSdk = 36; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
