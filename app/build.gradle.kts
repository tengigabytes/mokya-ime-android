import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tengigabytes.mokyaime"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tengigabytes.mokyaime"
        // Provisional value: the minimum supported Android version is still
        // to be decided.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The dictionary is memory-mapped straight out of the APK, which needs
    // the asset stored uncompressed (AssetManager.openFd fails otherwise).
    androidResources {
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":mie-engine"))
}

// ── Dictionary asset ─────────────────────────────────────────────────────
//
// dict_mie_v4.bin is generated at build time with libmie's own tools and is
// never committed. Sources (libchewing tsi.csv, FrequencyWords en_50k.txt)
// are downloaded once into <repo>/.mie-data/ (gitignored). To build offline,
// pass an existing dictionary with -Pmokya.dict=/path/to/dict_mie_v4.bin.

abstract class GenerateMieDictTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {

    @get:Input
    abstract val python: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fetchScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val genDictScript: RegularFileProperty

    /** Download cache for the third-party sources; lives outside build/. */
    @get:Internal
    abstract val sourcesDir: DirectoryProperty

    /** Optional prebuilt dictionary; skips download and generation. */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val prebuilt: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val dict = File(out, "dict_mie_v4.bin")
        if (prebuilt.isPresent) {
            prebuilt.get().asFile.copyTo(dict, overwrite = true)
            return
        }
        val sources = sourcesDir.get().asFile
        exec.exec {
            commandLine(
                python.get(), fetchScript.get().asFile.path,
                "--data-dir", sources.path, "--skip-if-exists",
                "--only", "tsi.csv", "en_50k.txt",
            )
        }
        exec.exec {
            commandLine(
                python.get(), genDictScript.get().asFile.path,
                "--libchewing", File(sources, "tsi.csv").path,
                "--zh-max-abbr-syls", "4",
                "--en-wordlist", File(sources, "en_50k.txt").path,
                "--v4-output", dict.path,
                "--output-dir", temporaryDir.path,   // legacy v2 by-products, discarded
            )
        }
    }
}

val generateMieDict = tasks.register<GenerateMieDictTask>("generateMieDict") {
    description = "Builds the MIE4 v4 dictionary asset (dict_mie_v4.bin)."
    val tools = layout.projectDirectory.dir("src/main/cpp/libmie/tools")
    python.set(providers.gradleProperty("mokya.python").orElse("python3"))
    fetchScript.set(tools.file("fetch_data.py"))
    genDictScript.set(tools.file("gen_dict.py"))
    sourcesDir.set(layout.projectDirectory.dir("../.mie-data"))
    providers.gradleProperty("mokya.dict").orNull?.let { prebuilt.set(file(it)) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateMieDict, GenerateMieDictTask::outputDir)
    }
}
