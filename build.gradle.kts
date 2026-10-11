import settings.getVersionMod

plugins {
    alias(libs.plugins.setup.minecraft)
    alias(libs.plugins.setup.publish)
    id(libs.plugins.buildconfig.get().pluginId)
}

val modId: String by extra
val modName: String by extra
val modGroup: String by extra
val modAdapter: String by extra

extra.set("modVersion", getVersionMod())

buildConfig {
    packageName(modGroup)
    buildConfigField("String", "MODID", "\"${modId}\"")
    buildConfigField("String", "MODNAME", "\"${modName}\"")
    buildConfigField("String", "VERSION", "\"${getVersionMod()}\"")
    buildConfigField("String", "GROUPNAME", "\"${modGroup}\"")
    buildConfigField("String", "MODADAPTER", "\"${modAdapter}\"")
}

repositories {
    maven("https://maven.accident.space/repository/maven-public/") {
        mavenContent {
            includeGroup("space.impact")
            includeGroup("com.github.GTNewHorizons")
            includeGroupByRegex("space\\.impact\\..+")
        }
    }
}

// Standalone stdio process, bundled as a resource rather than loaded by Minecraft.
val mcpBridge = sourceSets.create("mcpBridge")
val mcpBridgeBundle by configurations.creating
configurations[mcpBridge.implementationConfigurationName].extendsFrom(mcpBridgeBundle)
val mcpBridgeJar by tasks.registering(Jar::class) {
    archiveFileName.set("forgemcp-stdio.jar")
    destinationDirectory.set(layout.buildDirectory.dir("mcp"))
    manifest { attributes["Main-Class"] = "space.impact.forgeaimcp.bridge.StdioBridge" }
    from(mcpBridge.output)
    from({ mcpBridgeBundle.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<JavaCompile>(mcpBridge.compileJavaTaskName) {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}

tasks.named<ProcessResources>("processResources") {
    // Shadow expands nested .jar files; .bin keeps this executable archive intact.
    from(mcpBridgeJar) {
        into("assets/forgeaimcp/mcp")
        rename { "forgemcp-stdio.bin" }
    }
}

tasks.named<Test>("test") {
    dependsOn(mcpBridgeJar)
    doFirst { systemProperty("forgemcp.bridgeJar", mcpBridgeJar.get().archiveFile.get().asFile.absolutePath) }
}

dependencies {
    api("space.impact:Forgelin:2.1.1")
    implementation("com.github.GTNewHorizons:NotEnoughItems:2.8.158-GTNH:dev")
    testImplementation("junit:junit:4.13.2")
    add(mcpBridgeBundle.name, "com.google.code.gson:gson:2.2.4")
}
