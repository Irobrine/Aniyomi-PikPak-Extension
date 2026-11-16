import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PikPak"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://mypikpak.com"
    }
}

android {
    sourceSets {
        named("test") {
            java.directories.add("test")
            kotlin.directories.add("test")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly("androidx.preference:preference:1.2.1")

    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}

// The @Source processor only runs for extension sources; unit tests do not need generated code.
tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
