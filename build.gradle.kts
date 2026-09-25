import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.papermc.hangarpublishplugin.model.Platforms
import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.5.1"
    id("io.papermc.hangar-publish-plugin") version "0.1.2"
    id("de.eldoria.plugin-yml.bukkit") version "0.7.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.modrinth.minotaur") version "2.8.7"
}

version = findProperty("project.version")!!
group = findProperty("project.group")!!

val targetMinecraftVersions = listOf(
    "1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1.2", "26.2"
)

repositories {
    mavenLocal() // BreweryShiroi, published by that repository with publishToMavenLocal
    mavenCentral()
    // Hosts TheBrewingProject, one of the two APIs this plugin hooks into, so it is looked at early
    maven("https://repo.breweryteam.dev/releases")
    maven("https://repo.jsinco.dev/releases")
    maven("https://storehouse.okaeri.eu/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.momirealms.net/releases/")
    maven("https://nexus.phoenixdevt.fr/repository/maven-public/")
    maven("https://mvn.lumine.io/repository/maven-public/")
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.oraxen.com/releases")
    // Kept last: this host answers with HTTP 522 while its origin is down, and Gradle aborts the whole
    // resolution on a server error instead of moving on, so nothing that needs a later repository may sit
    // behind it.
    maven("https://maven.devs.beer/")
    // Kept last on purpose: this host is the slowest of the list, and Gradle walks the repositories in
    // order, so anything resolvable elsewhere is found before it is ever contacted.
    maven("https://repo.breweryteam.dev/mirror")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.dre.brewery:BreweryShiroi:4.2.1")
    compileOnly("dev.jsinco.brewery:thebrewingproject-bukkit:3.3.1")
    compileOnly("org.spongepowered:configurate-yaml:4.2.0")

    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    implementation("com.zaxxer:HikariCP:6.2.1")

    implementation("dev.thorinwasher.schem:schem-reader:1.0.0")
    implementation("com.github.Thorinwasher.BlockUtil:blockutil:v2.1.5")

    compileOnly("org.xerial:sqlite-jdbc:3.47.2.0")

    // Cloud command framework, https://github.com/Incendo/cloud
    implementation(platform("org.incendo:cloud-minecraft-bom:2.0.0"))
    implementation("org.incendo:cloud-paper")
    implementation("org.incendo:cloud-minecraft-extras") {
        // Adventure is provided by the server
        exclude("net.kyori")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.50.0")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation("org.spongepowered:configurate-yaml:4.2.0")
    // The schematic reader parses NBT but declares the library as runtime scope, so compiling against its
    // signatures in a test needs it declared again. At runtime in the plugin it comes from the server.
    testImplementation("net.kyori:adventure-nbt:4.17.0")
}


tasks {

    jar {
        enabled = false // shadowJar
    }

    build {
        dependsOn(shadowJar)
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    register("publishRelease") {
        finalizedBy("publishPluginPublicationToHangar")
        finalizedBy("modrinth")

        doLast {
            val webhook = DiscordWebhook(System.getenv("DISCORD_WEBHOOK") ?: return@doLast, false)
            webhook.message = "@everyone"
            webhook.embedTitle = "GardenAngelicae - v${project.version}"
            webhook.embedDescription = readChangeLog()
            webhook.embedThumbnailUrl =
                "https://cdn.modrinth.com/data/3TaOMjJ9/5e44a541ba38ce5d8567207a4b75183658756d57_96.webp"
            webhook.send()
        }
    }

    shadowJar {
        archiveBaseName.set(rootProject.name)
        archiveClassifier.unset()
        addMultiReleaseAttribute = false

        dependencies {
            exclude {
                it.moduleGroup == "org.jetbrains.kotlin"
                        || it.moduleGroup == "org.jetbrains.kotlinx"
                        || it.moduleGroup == "org.joml"
                        || it.moduleGroup == "org.slf4j"
            }
        }

        exclude("org/jetbrains/annotations/**")
        exclude("org/intellij/lang/annotations/**")

        listOf(
            "com.zaxxer.hikari",
            "dev.thorinwasher.schem",
            "dev.thorinwasher.blockutil",
            "net.kyori.adventure.nbt",
            "net.kyori.examination"
        ).forEach { relocate(it, "${project.group}.lib.$it") }

        // Relocated so the command framework does not clash with another plugin shipping a different version
        relocate("org.incendo", "${project.group}.lib.cloud")
        // Only used by the annotation processor of cloud, not needed at runtime
        exclude("org/immutables/**")
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("1.21.11")
        downloadPlugins {
            modrinth("worldedit", "DlD8WKr9")
            // modrinth("thebrewingproject", "3.3.1")
            modrinth("worldguard", "7.0.17")
        }
    }

}

// BreweryShiroi is the plugin this build hooks into, and it is not published anywhere, so the dev server
// takes it from the local Maven repository instead (that project's `publishToMavenLocal` puts it there).
val breweryShiroi: Configuration = configurations.create("breweryShiroi") {
    isCanBeConsumed = false
}

dependencies {
    breweryShiroi("com.dre.brewery:BreweryShiroi:4.2.1")
}

tasks.withType<xyz.jpenilla.runtask.task.AbstractRun>().configureEach {
    dependsOn(breweryShiroi)
    doFirst {
        val pluginsDir = runDirectory.get().dir("plugins").asFile
        pluginsDir.mkdirs()
        pluginsDir.listFiles { file -> file.name.startsWith("BreweryShiroi") }?.forEach(File::delete)
        breweryShiroi.resolve().forEach { it.copyTo(pluginsDir.resolve(it.name), overwrite = true) }
    }
}

bukkit {
    main = "org.icarus.angelicae.garden.Garden"
    foliaSupported = true
    apiVersion = "1.21"
    authors = listOf("Jsinco", "Thorinwasher")
    contributors = listOf("fLip")
    name = rootProject.name
    permissions {
        register("garden.command") {
            children = listOf(
                "garden.command.reload",
                "garden.command.give",
                "garden.command.plant"
            )
        }
    }
    softDepend = listOf("BreweryShiroi", "TheBrewingProject")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

runPaper.folia.registerTask {
    runDirectory.set(File("run-folia"))
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString())
        channel.set("Release")
        // Pinned rather than taken from project.name: this is the slug of the existing Hangar project, and
        // renaming the Gradle project must not repoint the publication at one that does not exist.
        // Update it together with the Hangar listing if that is ever renamed too.
        id.set("Garden")
        apiKey.set(System.getenv("HANGAR_TOKEN") ?: return@register)
        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.shadowJar.flatMap { it.archiveFile })
                platformVersions.set(targetMinecraftVersions)
            }
        }
        changelog.set(readChangeLog())
    }
}

modrinth {
    token.set(System.getenv("MODRINTH_TOKEN") ?: run {
        return@modrinth
    })
    projectId.set("ePND7F16")
    versionNumber.set(project.version.toString())
    versionType.set("release")
    uploadFile.set(tasks.shadowJar)
    loaders.addAll("paper", "purpur", "folia")
    gameVersions.addAll(targetMinecraftVersions)
    changelog.set(readChangeLog())
}

fun readChangeLog(): String {
    val text: String = System.getenv("CHANGELOG") ?: file("CHANGELOG.md").run {
        if (exists()) readText() else "No Changelog found."
    }
    return text.replace("\${version}", project.version.toString())
}

class DiscordWebhook(
    val webhookUrl: String,
    var defaultThumbnail: Boolean = true
) {

    companion object {
        private const val MAX_EMBED_DESCRIPTION_LENGTH = 4096
    }

    var message: String = "content"
    var username: String = "GardenAngelicae Updates"
    var avatarUrl: String = "https://github.com/breweryteam.png"
    var embedTitle: String = "Embed Title"
    var embedDescription: String = "Embed Description"
    var embedColor: String = "F5E083"
    var embedThumbnailUrl: String? = if (defaultThumbnail) avatarUrl else null
    var embedImageUrl: String? = null

    private fun hexStringToInt(hex: String): Int {
        val hexWithoutPrefix = hex.removePrefix("#")
        return hexWithoutPrefix.toInt(16)
    }

    private fun buildToJson(): String {
        val json = JsonObject()
        json.addProperty("username", username)
        json.addProperty("avatar_url", avatarUrl)
        json.addProperty("content", message)

        val embed = JsonObject()
        embed.addProperty("title", embedTitle)
        embed.addProperty("description", embedDescription)
        embed.addProperty("color", hexStringToInt(embedColor))

        embedThumbnailUrl?.let {
            val thumbnail = JsonObject()
            thumbnail.addProperty("url", it)
            embed.add("thumbnail", thumbnail)
        }

        embedImageUrl?.let {
            val image = JsonObject()
            image.addProperty("url", it)
            embed.add("image", image)
        }

        val embeds = JsonArray()
        createEmbeds().forEach(embeds::add)

        json.add("embeds", embeds)
        return json.toString()
    }

    private fun createEmbeds(): List<JsonObject> {
        if (embedDescription.length <= MAX_EMBED_DESCRIPTION_LENGTH) {
            return listOf(JsonObject().apply {
                addProperty("title", embedTitle)
                addProperty("description", embedDescription)
                addProperty("color", embedColor.toInt(16))
                embedThumbnailUrl?.let {
                    val thumbnail = JsonObject()
                    thumbnail.addProperty("url", it)
                    add("thumbnail", thumbnail)
                }
                embedImageUrl?.let {
                    val image = JsonObject()
                    image.addProperty("url", it)
                    add("image", image)
                }
            })
        }
        val embeds = mutableListOf<JsonObject>()
        var description = embedDescription
        while (description.isNotEmpty()) {
            val chunkLength = minOf(MAX_EMBED_DESCRIPTION_LENGTH, description.length)
            val chunk = description.substring(0, chunkLength)
            description = description.substring(chunkLength)
            embeds.add(JsonObject().apply {
                addProperty("title", embedTitle)
                addProperty("description", chunk)
                addProperty("color", embedColor.toInt(16))
                embedThumbnailUrl?.let {
                    val thumbnail = JsonObject()
                    thumbnail.addProperty("url", it)
                    add("thumbnail", thumbnail)
                }
                embedImageUrl?.let {
                    val image = JsonObject()
                    image.addProperty("url", it)
                    add("image", image)
                }
            })
        }
        return embeds
    }

    fun send() {
        val url = URI(webhookUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { outputStream ->
            outputStream.write(buildToJson().toByteArray())

            val responseCode = connection.responseCode
            println("POST Response Code :: $responseCode")
            if (responseCode == HttpURLConnection.HTTP_OK) {
                println("Message sent successfully.")
            } else {
                println("Failed to send message.")
            }
        }
    }
}