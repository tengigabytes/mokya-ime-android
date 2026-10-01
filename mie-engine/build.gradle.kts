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
val javaHome = providers.systemProperty("java.home")

val buildHostJni by tasks.registering(Exec::class) {
    description = "Builds libmokyaime_jni for the host JVM (unit tests only)."
    inputs.files(fileTree(cppDir) { exclude("**/.git", "libmie/data*/**") })
    outputs.dir(hostJniDir)
    val src = cppDir.asFile.path
    val out = hostJniDir.get().asFile.path
    val jdk = javaHome.get()
    commandLine(
        "sh", "-c",
        "cmake -S \"$src\" -B \"$out\" -DCMAKE_BUILD_TYPE=Debug -DMOKYA_JAVA_HOME=\"$jdk\" " +
            "&& cmake --build \"$out\"",
    )
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
    // Optional: full dictionary for RealDictionarySmokeTest.
    providers.gradleProperty("mokya.realDict").orNull?.let { systemProperty("mokya.realDict", it) }
}
