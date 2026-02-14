pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// Load credentials from local.properties (gitignored) for GitHub Packages auth
val localProps = java.util.Properties().apply {
    val localFile = file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = localProps.getProperty("gpr.user") ?: System.getenv("USERNAME") ?: ""
                password = localProps.getProperty("gpr.key") ?: System.getenv("TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "TymewearKaroo"
include(":app")
