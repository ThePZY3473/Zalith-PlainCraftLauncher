import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
    id("kotlin-parcelize")
    id("com.movtery.buildkeys")
}

val zalithPackageName = "com.zalith.pcl"
/**
 * 安装标识（applicationId）与源码包名解耦。
 *
 * 源码包/namespace 保持 `com.zalith.pcl`（878 个文件的 import、R 类、manifest 里的全限定
 * 类名都指向它，改这个等于重写工程）；真正决定「应用在系统里叫什么」的是 applicationId，
 * 它改成 `com.zpcl.launcher` 之后，包名里不再出现旧名字。
 *
 * 代价（改过一次就回不去）：新包是一个**全新应用**，旧版本的数据（MMKV、实例、登录态、
 * 外部 files 目录）不会跟过来，也不能覆盖升级 —— 老用户必须先卸载再装。
 */
val appIdName = "com.zpcl.launcher"
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
val launcherShortName = project.findProperty("launcher_short_name") as? String ?: error("The \"launcher_short_name\" property is not set in gradle.properties.")
val launcherUrl = project.findProperty("url_home") as? String ?: error("The \"url_home\" property is not set in gradle.properties.")

val launcherVersionCode = (project.findProperty("launcher_version_code") as? String)?.toIntOrNull() ?: error("The \"launcher_version_code\" property is not set as an integer in gradle.properties.")
val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")

val defaultCurseForgeApiKey = project.findProperty("curseforge_api_key") as? String

val projectArch: String = System.getProperty("arch", "all")

fun getKeyFromLocal(envKey: String, fileName: String? = null, default: String? = null): String {
    // 必须 trim：用记事本 / echo 保存的密钥文件常带结尾换行，
    // 带着 "\n" 的 OAUTH_CLIENT_ID 发给微软会被判为无效 client，
    // 表现为「登录凭据被服务器拒绝」，且极难从现象反推。
    System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    fileName?.let { name ->
        val file = File(rootDir, name)
        if (file.canRead() && file.isFile) {
            file.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
    }
    return default ?: run {
        logger.warn("BUILD: $envKey not set; related features may throw exceptions.")
        ""
    }
}

android {
    namespace = zalithPackageName
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    // 本 fork 沿用 `zalith_launcher.jks`（别名 cutepzy）签名，保证已安装的 fork 包
    // 能覆盖升级；上游 ZalithLauncher 2.8.1 改用的 `zpcl_release.jks` 与本 fork 无关。
    // 密码来自环境变量 STORE_PASSWORD / KEY_PASSWORD，或根目录
    // `.store_password.txt` / `.key_password.txt`（已 gitignore）。
    //
    // 公开源码或 CI 上拿不到密钥文件时必须还能完成编译：Debug 退回 Android 默认
    // 调试签名，Release 输出未签名包，否则 `validateSigning` 会在打包阶段直接失败。
    val localKeystore = file("zalith_launcher.jks")

    signingConfigs {
        if (localKeystore.exists()) {
            create("releaseBuild") {
                storeFile = localKeystore
                storePassword = getKeyFromLocal("STORE_PASSWORD", ".store_password.txt")
                keyAlias = "cutepzy"
                keyPassword = getKeyFromLocal("KEY_PASSWORD", ".key_password.txt")
                //同时启用 v1(JAR) / v2 / v3 签名，提升对旧版系统安装器的兼容性
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
            create("debugBuild") {
                // debug 使用与 release 相同的密钥，方便直接覆盖安装已发布的包。
                storeFile = localKeystore
                storePassword = getKeyFromLocal("STORE_PASSWORD", ".store_password.txt")
                keyAlias = "cutepzy"
                keyPassword = getKeyFromLocal("KEY_PASSWORD", ".key_password.txt")
                //同时启用 v1(JAR) / v2 / v3 签名，提升对旧版系统安装器的兼容性
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        } else {
            logger.lifecycle("BUILD: zalith_launcher.jks not found; falling back to default debug signing / unsigned release.")
        }
    }

    defaultConfig {
        applicationId = appIdName
        minSdk = 24
        targetSdk = 34
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        // 桌面图标与最近任务里显示的名字，跟 `launcher_app_name` 保持一致。
        // 现在 clientToken 用的是写死的 CLIENT_TOKEN_SEED（见 AuthServerApi /
        // MicrosoftAuthenticator），所以改名不会影响已保存的登录态。
        manifestPlaceholders["launcher_name"] = launcherAPPName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("releaseBuild")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // 没有项目签名文件时不指定签名配置，AGP 会自动使用默认调试签名。
            signingConfigs.findByName("debugBuild")?.let { signingConfig = it }
        }
    }

    splits {
        val arch = projectArch.takeIf { it != "all" } ?: return@splits
        abi {
            isEnable = true
            reset()
            when (arch) {
                "arm" -> include("armeabi-v7a")
                "arm64" -> include("arm64-v8a")
                "x86" -> include("x86")
                "x86_64" -> include("x86_64")
            }
        }
    }

    ndkVersion = "25.2.9519653"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    androidResources {
        // 内置 Java 运行环境的 `.tar.xz` 一律**不压缩**存储。
        //
        // 它们本来就是 xz 压缩包，aapt 再 deflate 一遍的收益是负的：
        // 实测 jre-21/universal.tar.xz 18,974,588 → 18,980,378 字节，反而大了 5.8KB。
        // 而代价很实在：压缩后的 asset 用 `AssetManager.open()` 读时，必须先 inflate
        // 整条流才能拿到内容，大文件在部分设备上会直接失败 —— 崩溃页里那条
        // `java.io.FileNotFoundException: runtimes/jre-21/universal.tar.xz` 就是它
        // （APK 里明明有这个条目，`assetManager.list()` 也列得出来，但 open 拿不到）。
        //
        // 标成不压缩后，asset 以 STORED 存放，open() 直接按 ZIP 里的偏移读，
        // 零解压、零内存峰值，也能被 zipalign 正常对齐。
        noCompress += listOf("xz")
    }

    compileOptions {
        // sora-editor language-textmate
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            //让 android.util.Log 等框架方法在本地单测中返回默认值而非抛出异常
            isReturnDefaultValues = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                afterEvaluate {
                    val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                    task.inputs.property("lwjglArch", projectArch)
                    task.doLast {
                        val assetsDir = task.outputDir.get().asFile
                        val tag = "JREAssetsCleanup"
                        logger.lifecycle("[$tag] arch: $projectArch")
                        val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")
                        jreList.forEach { jreVersion ->
                            val runtimeDir = File("$assetsDir/runtimes/$jreVersion")
                            logger.lifecycle("[$tag] runtimeDir: ${runtimeDir.absolutePath}")
                            runtimeDir.listFiles()?.forEach {
                                if (projectArch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-$projectArch.tar.xz") {
                                    logger.lifecycle("[$tag] delete: $it : ${it.delete()}")
                                }
                            }
                        }

                        if (projectArch == "all") return@doLast
                        val abi = when (projectArch) {
                            "arm" -> "armeabi-v7a"
                            "arm64" -> "arm64-v8a"
                            "x86" -> "x86"
                            "x86_64" -> "x86_64"
                            else -> return@doLast
                        }
                        val lwjglVersions = file("libs").listFiles { f ->
                            f.name.matches(Regex("lwjgl-\\d+\\.\\d+\\.\\d+-natives-release\\.aar"))
                        }
                            ?.map { Regex("lwjgl-(\\d+\\.\\d+\\.\\d+)-natives-release\\.aar").find(it.name)!!.groupValues[1] }
                            ?: emptyList()
                        lwjglVersions.forEach { version ->
                            val nativesDir = File(assetsDir, "app_runtime/lwjgl/$version/natives")
                            if (nativesDir.isDirectory) {
                                nativesDir.listFiles()?.forEach { dir ->
                                    if (dir.isDirectory && dir.name != abi) {
                                        logger.lifecycle("Removing non-target-arch natives: $dir")
                                        dir.deleteRecursively()
                                    }
                                }
                            }
                        }
                    }
                }

                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    // 产物名跟着对外显示名走（`ZPCL Launcher` → `ZPCL-Launcher-1.6.0-arm64-v8a.apk`），
                    // 不再用内部模块名 / launcher_name，避免发行包名里出现旧名字。
                    val baseName = launcherAPPName.replace(" ", "-") + "-" +
                            (if (variant.buildType == "release") launcherVersionName else "Debug-$launcherVersionName")
                    output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                }
            }
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

buildKeys {
    // 正版登录的微软应用 client id 不随源码分发：本仓库不内置任何 client id。
    // 要启用正版登录，请自己在 Azure 注册应用，然后把 ID 写进工程根目录的
    // `.oauth_client_id.txt`（已加入 .gitignore），或设环境变量 OAUTH_CLIENT_ID。
    // 没配置时构建照常成功，只是调用正版登录会直接报「未配置 Client ID」。
    string("OAUTH_CLIENT_ID", getKeyFromLocal("OAUTH_CLIENT_ID", ".oauth_client_id.txt"), true)
    string("LAUNCHER_NAME", launcherAPPName, true)
    string("LAUNCHER_IDENTIFIER", launcherName, true)
    string("LAUNCHER_SHORT_NAME", launcherShortName, true)
    string("URL_HOME", launcherUrl, true)
    string("CURSEFORGE_API", getKeyFromLocal("CURSEFORGE_API_KEY", ".curseforge_api.txt", defaultCurseForgeApiKey), true)
    string("BUILD_ARCH", projectArch)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.nav3)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.material.color.utilities)
    implementation(libs.materialKolor)
    implementation(libs.reorderable)
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui)
    implementation(libs.richtext.ui.material3)
    implementation(platform(libs.editor.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)
    coreLibraryDesugaring(libs.desugar.nio)
    //Project
    implementation(project(":LayerController"))
    implementation(project(":ColorPicker"))
    implementation(project(":CardGrid"))
    implementation(project(":Terracotta"))
    implementation(project(":InputMap"))
    implementation(project(":Guide"))
    //Utils
    implementation(libs.bytehook)
    implementation(libs.jetbrains.annotations)
    implementation(libs.gson)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zip4j)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.ktor.http)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.minidns.hla)
    implementation(libs.toml4j)
    implementation(libs.maven.artifact)
    implementation(libs.mmkv)
    implementation(libs.fishnet)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.process.phoenix)
    implementation(libs.lunarcalendar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    //Safe
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    //Support
    implementation(libs.proxy.client.android)
    //Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    //Test
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
