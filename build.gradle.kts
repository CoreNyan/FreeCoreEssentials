plugins {
    java
}

group = "io.github.freecoreessentials"
version = "2.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("libs/VaultAPI-1.7.1.jar"))
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.11.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly(files("C:/MCSManager/daemon/data/InstanceData/FreeCore-Survival/plugins/DonutScoreboard-1.8.jar"))
    compileOnly(files("C:/MCSManager/daemon/data/InstanceData/FreeCore-Survival/plugins/HuskSync.jar"))

    implementation("com.zaxxer:HikariCP:6.3.2")
    implementation("com.mysql:mysql-connector-j:9.4.0")
    implementation("at.favre.lib:bcrypt:0.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("FreeCoreEssentials")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(configurations.runtimeClasspath.get().map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    exclude("org/slf4j/**")
}

tasks.assemble {
    dependsOn(tasks.jar)
}
