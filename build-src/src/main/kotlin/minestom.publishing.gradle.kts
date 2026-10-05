plugins {
    `maven-publish`
    signing
    alias(libs.plugins.nmcp)
}

val minestomDataVersion = libs.minestomData.get().version
        ?: throw IllegalStateException("unable to determine Minecraft version")
val mcVersion = minestomDataVersion.split("-")[0]

publishing.publications.create<MavenPublication>("maven") {
    groupId = project.group.toString()
    // Gradle project names stay upstream's; only the published coordinates differ.
    artifactId = if (project == rootProject) "multistom" else "multistom-${project.name}"
    version = project.version.toString()

    from(project.components["java"])

    pom {
        name.set(this@create.artifactId)
        description.set("$mcVersion Lightweight Minecraft server. A fork of Minestom (https://github.com/Minestom/Minestom) that runs multiple server processes in one JVM.")
        url.set("https://github.com/chunkzero/multistom")

        licenses {
            license {
                name.set("Apache 2.0")
                url.set("https://github.com/minestom/minestom/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("TheMode")
            }
            developer {
                id.set("mworzala")
                name.set("Matt Worzala")
                email.set("matt@hollowcube.dev")
            }
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/chunkzero/multistom/issues")
        }

        scm {
            connection.set("scm:git:git://github.com/chunkzero/multistom.git")
            developerConnection.set("scm:git:git@github.com:chunkzero/multistom.git")
            url.set("https://github.com/chunkzero/multistom")
            tag.set("HEAD")
        }

        ciManagement {
            system.set("Github Actions")
            url.set("https://github.com/chunkzero/multistom/actions")
        }
    }
}

val proxyUrl = providers.environmentVariable("MAVEN_R2_URL")
if (proxyUrl.isPresent) {
    publishing.repositories.maven {
        name = "mavenR2"
        url = uri(proxyUrl.get())
        isAllowInsecureProtocol = true // The publishing proxy listens on loopback only.
        credentials {
            username = providers.environmentVariable("MAVEN_R2_USERNAME").get()
            password = providers.environmentVariable("MAVEN_R2_PASSWORD").get()
        }
    }
} else {
    signing {
        isRequired = System.getenv("CI") != null

        val privateKey = System.getenv("GPG_PRIVATE_KEY")
        val keyPassphrase = System.getenv()["GPG_PASSPHRASE"]
        useInMemoryPgpKeys(privateKey, keyPassphrase)

        sign(publishing.publications)
    }
}

tasks.register<CheckAbiTask>("checkBinaryCompatibility") {
    group = "verification"
    description = "Checks binary compatibility against the baseline released version."

    val baselineJarDir = providers.gradleProperty("baselineJarDir")
    oldJar = layout.file(baselineJarDir.flatMap { dir ->
        val file = layout.settingsDirectory.file("$dir/${project.name}.jar")
        providers.provider { if (file.asFile.exists()) file.asFile else null }
    })

    newJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }

    rootProjectDir.set(layout.settingsDirectory.asFile)
    val javaExtension = project.extensions.findByType<JavaPluginExtension>() // No java plugin applied
    if (javaExtension != null) {
        sourceDirectories.from(javaExtension.sourceSets["main"].java.srcDirs)
        project.configurations.findByName("compileClasspath")?.let { classpath.from(it) }

        val javaToolchainService = project.extensions.findByType<JavaToolchainService>()
        if (javaToolchainService != null) {
            classpath.from(javaToolchainService.launcherFor(javaExtension.toolchain).map { launcher ->
                project.fileTree(launcher.metadata.installationPath.dir("jmods")) {
                    include("**/*.jmod")
                }
            })
        }
    }
}
