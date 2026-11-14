import java.util.Base64
import java.net.URI

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "kr.ac.demo.ai_study_planner_mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "kr.ac.demo.ai_study_planner_mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// Debug-only exact-host cleartext policy; no LAN address is stored in source.
val apiDefines = (project.findProperty("dart-defines") as? String).orEmpty()
    .split(',').filter { it.isNotEmpty() }.associate {
        val value = String(Base64.getDecoder().decode(it), Charsets.UTF_8)
        value.substringBefore('=') to value.substringAfter('=', "")
    }
val debugNetworkRes = layout.buildDirectory.dir("generated/debugNetwork/res")
val generateDebugNetworkPolicy = tasks.register("generateDebugNetworkPolicy") {
    inputs.property("apiAddress", apiDefines["API_BASE_URL"].orEmpty())
    inputs.property("lanOptIn", apiDefines["ALLOW_DEBUG_LAN_HTTP"].orEmpty())
    outputs.dir(debugNetworkRes)
    doLast {
        val uri = runCatching { URI(apiDefines["API_BASE_URL"].orEmpty()) }.getOrNull()
        val host = uri?.host.orEmpty()
        val parts = host.split('.')
        val numbers = parts.mapNotNull { it.toIntOrNull() }
        val privateIp = parts.size == 4 && numbers.size == 4 &&
            parts.all { it.matches(Regex("^(0|[1-9][0-9]{0,2})$")) } && numbers.all { it in 0..255 } &&
            (numbers[0] == 10 || (numbers[0] == 172 && numbers[1] in 16..31) || (numbers[0] == 192 && numbers[1] == 168))
        val lan = apiDefines["ALLOW_DEBUG_LAN_HTTP"] == "true" && privateIp &&
            uri?.scheme == "http" && uri.port in 1024..65535 &&
            uri.port !in setOf(8083, 8084, 8085, 8086, 8099, 8101, 8102) && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")
        val domain = if (lan) host else "localhost"
        val target = debugNetworkRes.get().file("xml/network_security_config.xml").asFile
        target.parentFile.mkdirs()
        target.writeText("""<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">$domain</domain>
    </domain-config>
</network-security-config>
""")
    }
}
android.sourceSets.getByName("debug").res.srcDir(debugNetworkRes.get().asFile)
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(generateDebugNetworkPolicy) }

