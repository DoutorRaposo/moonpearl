import com.android.build.api.variant.ApplicationVariant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val upstreamDir = rootProject.layout.projectDirectory.dir("external/zelda3")
val sdlDir = rootProject.layout.projectDirectory.dir("external/SDL")

// The 48-byte signature that LoadAssets() expects at the start of zelda3_assets.dat.
// Read from the pinned upstream so an imported .dat is checked against the code we ship.
val assetsSignature: String = providers.fileContents(upstreamDir.file("src/assets.h")).asText.map { text ->
    Regex("""#define kAssets_Sig ([0-9, ]+)""").find(text)?.groupValues?.get(1)?.replace(" ", "")
        ?: error("kAssets_Sig not found in external/zelda3/src/assets.h")
}.get()

android {
    namespace = "io.github.doutorraposo.z3"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.doutorraposo.z3"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "ASSETS_SIG", "\"$assetsSignature\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DZELDA3_DIR=${upstreamDir.asFile.absolutePath.replace('\\', '/')}",
                    "-DSDL_DIR=${sdlDir.asFile.absolutePath.replace('\\', '/')}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            // SDLActivity and friends must match the native SDL version exactly,
            // so they are compiled straight from the submodule.
            java.directories += "../external/SDL/android-project/app/src/main/java"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

/** Copies the stock config and the reference saves from the upstream checkout into the APK assets. */
abstract class SyncUpstreamFiles : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iniFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val refSaves: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        fs.sync {
            from(iniFile)
            from(refSaves) { into("saves/ref") }
            into(outputDir)
        }
    }
}

val syncUpstreamFiles = tasks.register<SyncUpstreamFiles>("syncUpstreamFiles") {
    iniFile.set(upstreamDir.file("zelda3.ini"))
    refSaves.set(upstreamDir.dir("saves/ref"))
}

androidComponents {
    onVariants { variant: ApplicationVariant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncUpstreamFiles, SyncUpstreamFiles::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
