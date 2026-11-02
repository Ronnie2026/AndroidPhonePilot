plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "dev.phonepilot"; compileSdk = 34; buildToolsVersion = "36.0.0"
 defaultConfig { applicationId = "dev.phonepilot"; minSdk = 26; targetSdk = 32; versionCode = 2; versionName = "0.2.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 // Private sideload service; target upgrade tracked in README.
 lint { disable += "ExpiredTargetSdkVersion" }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.java-websocket:Java-WebSocket:1.5.7"); implementation("org.nanohttpd:nanohttpd:2.3.1") }
