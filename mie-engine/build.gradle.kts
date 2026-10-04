import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM Kotlin binding for the native engine. Kept free of Android APIs so
// its unit tests run on the host against a host build of the same JNI code.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}

// ── Host build of the JNI bridge + test dictionary ───────────────────────

val cppDir = layout.projectDirectory.dir("../app/src/main/cpp")
val libmieDir = cppDir.dir("libmie")
val hostJniDir = layout.buildDirectory.dir("host-jni")
val testDictFile = layout.buildDirectory.file("test-dict/dict_mie_v4.bin")
val python = providers.gradleProperty("mokya.python").orElse("python3")
// The JDK running Gradle provides jni.h for the host build.
val gradleJavaHome = providers.systemProperty("java.home")

// Runs cmake directly (no shell), so paths with backslashes or spaces work on
// Windows too.
abstract class BuildHostJniTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val javaHome: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val jdk = javaHome.get()
        if (!File(jdk, "include/jni.h").isFile) {
            throw GradleException(
                "The host JNI build needs a full JDK (include/jni.h), but Gradle runs on $jdk. " +
                    "Point JAVA_HOME at a JDK 17+.",
            )
        }
        val out = outputDir.get().asFile.path
        exec.exec {
            commandLine(
                "cmake", "-S", sourceDir.get().asFile.path, "-B", out,
                "-DCMAKE_BUILD_TYPE=Debug", "-DMOKYA_JAVA_HOME=$jdk",
            )
        }
        // --config selects the configuration on multi-config generators
        // (Visual Studio); single-config ones ignore it.
        exec.exec { commandLine("cmake", "--build", out, "--config", "Debug") }
    }
}

val buildHostJni = tasks.register<BuildHostJniTask>("buildHostJni") {
    description = "Builds libmokyaime_jni for the host JVM (unit tests only)."
    sources.from(fileTree(cppDir) { exclude("**/.git", "libmie/data*/**") })
    sourceDir.set(cppDir)
    javaHome.set(gradleJavaHome)
    outputDir.set(hostJniDir)
}

val generateTestDict by tasks.registering(Exec::class) {
    description = "Builds a tiny MIE4 v4 dictionary from src/test/dict."
    val dictSrc = layout.projectDirectory.dir("src/test/dict")
    val genDict = libmieDir.file("tools/gen_dict.py")
    inputs.dir(dictSrc)
    inputs.file(genDict)
    outputs.file(testDictFile)
    val out = testDictFile.get().asFile
    commandLine(
        python.get(), genDict.asFile.path,
        "--libchewing", dictSrc.file("mini_tsi.csv").asFile.path,
        "--en-wordlist", dictSrc.file("mini_en.txt").asFile.path,
        "--v4-output", out.path,
        "--output-dir", File(out.parentFile, "v2").path,
    )
}

tasks.test {
    dependsOn(buildHostJni, generateTestDict)
    systemProperty("java.library.path", hostJniDir.get().asFile.path)
    systemProperty("mokya.testDict", testDictFile.get().asFile.path)
    systemProperty("mokya.keycodeHeader", libmieDir.file("include/mie/keycode.h").asFile.path)
    systemProperty("mokya.keyTableSource", libmieDir.file("src/ime_keys.cpp").asFile.path)
    // The field-test page's strings (debug builds), for FieldTestStringsSyncTest.
    val fieldTestRes = layout.projectDirectory.dir("../app/src/debug/res")
    inputs.dir(fieldTestRes).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("mokya.fieldTestRes", fieldTestRes.asFile.path)
    // Optional: full dictionary for RealDictionarySmokeTest.
    providers.gradleProperty("mokya.realDict").orNull?.let { systemProperty("mokya.realDict", it) }
}
