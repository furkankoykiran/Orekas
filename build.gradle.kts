plugins {
    alias(libs.plugins.fabric.loom)
    id("com.gradleup.shadow") version "9.0.0"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

// Dependencies that should be shaded (relocated + merged) into the mod jar go here.
// Anything in `shade` is visible at compile time AND bundled into the final jar,
// but under a relocated package so there's no classloader conflict with other addons.
val shade: Configuration = configurations.create("shade")
configurations.named("implementation").configure { extendsFrom(shade) }

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)

    // Chicory — shaded (relocated) into our package tree to avoid any
    // classloader issues with Fabric's nested-jar mechanism.
    shade(libs.chicory.runtime)

    // Tests
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("dev-shadow")
        relocate("com.dylibso.chicory", "com.webrekas.addon.shaded.chicory")
        mergeServiceFiles()
    }

    remapJar {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
    }

    assemble {
        dependsOn(shadowJar)
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}
