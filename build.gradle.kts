plugins {
    id("java")
}

group = "io.github.meiiraru"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map(::zipTree))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("LICENSE.md")

    manifest.attributes["Main-Class"] = "dxmconverter.DXMConverter"
}