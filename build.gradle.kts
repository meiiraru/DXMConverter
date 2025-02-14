plugins {
    java
}

group = "com.github.meiiraru"
version = "1.0.1"
val mainClass = "dxmconverter.Main"

//dependencies
val cinnamonVersion = "0.0.2"

val lwjglModules = arrayOf(
    "lwjgl",
    "lwjgl-assimp",
    "lwjgl-glfw",
    "lwjgl-openal",
    "lwjgl-opengl",
    "lwjgl-openxr",
    "lwjgl-stb"
)

val os = Pair(
    System.getProperty("os.name")!!,
    System.getProperty("os.arch")!!
).let { (name, arch) ->
    when {
        "FreeBSD" == name ->
            "freebsd"

        arrayOf("Linux", "SunOS", "Unit").any { name.startsWith(it) } ->
            if (arrayOf("arm", "aarch64").any { arch.startsWith(it) })
                "linux${if (arch.contains("64") || arch.startsWith("armv8")) "-arm64" else "-arm32"}"
            else if (arch.startsWith("ppc"))
                "linux-ppc64le"
            else if (arch.startsWith("riscv"))
                "linux-riscv64"
            else
                "linux"

        arrayOf("Mac OS X", "Darwin").any { name.startsWith(it) } ->
            "macos${if (arch.startsWith("aarch64")) "-arm64" else ""}"

        arrayOf("Windows").any { name.startsWith(it) } ->
            if (arch.contains("64"))
                "windows${if (arch.startsWith("aarch64")) "-arm64" else ""}"
            else
                "windows-x86"

        else ->
            throw Error("Unrecognized or unsupported platform. Please set \"lwjglNatives\" manually")
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.meiiraru", "Cinnamon", cinnamonVersion)
    val lwjglNatives = "natives-$os"
    lwjglModules.forEach {
        runtimeOnly("org.lwjgl", it, classifier = lwjglNatives)
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set(os)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    manifest.attributes["Main-Class"] = mainClass

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.jar {
    archiveClassifier.set("")
    manifest.attributes["Main-Class"] = mainClass
    from("LICENSE.md")
}