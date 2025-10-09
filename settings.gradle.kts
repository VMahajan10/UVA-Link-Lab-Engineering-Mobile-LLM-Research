/**
 * Settings configuration for LLM Battery Benchmark project.
 * 
 * This file configures the project structure, plugin management,
 * and dependency resolution for the Android application.
 */

// Plugin management configuration
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Dependency resolution management
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Root project name
rootProject.name = "LLMBatteryBenchmark"

// Include modules
include(":app")
