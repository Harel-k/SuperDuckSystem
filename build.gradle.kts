plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.qducks"
version = "1.0.0-RC1"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "placeholderapi"
        url = uri("https://repo.helpch.at/releases/")
    }
    maven {
        name = "opencollab"
        url = uri("https://repo.opencollab.dev/main/")
    }
    maven {
        name = "codemc-vaultunlocked"
        url = uri("https://repo.codemc.io/repository/creatorfromhell/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.20")

    // Paper does not guarantee an SQLite JDBC driver for plugins. Bundle it so the
    // database works on a clean server instead of depending on another plugin's classloader.
    implementation("org.xerial:sqlite-jdbc:3.53.1.0") {
        exclude(group = "org.slf4j")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("SuperDuckSystem")
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveBaseName.set("SuperDuckSystem")
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }
}
