pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        // 国内网络直连 Maven 中央仓库经常卡在数十 MB 的依赖下载上，镜像放在最前面
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "FnMusicTV"
include(":app")
include(":core:model")
include(":core:lyrics")
include(":core:data")
include(":core:playback")
include(":baselineprofile")
include(":third_party:gaze-capsule")
include(":third_party:accompanist-lyrics-ui")
