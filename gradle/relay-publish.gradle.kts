apply(plugin = "maven-publish")

group = providers.gradleProperty("relay.group").get()
version = providers.gradleProperty("relay.version").get()

extensions.configure<PublishingExtension>("publishing") {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/tonexue1/relay")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orElse("")
                    .get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orElse("")
                    .get()
            }
        }
    }
}

fun MavenPublication.configureRelayPom() {
    pom {
        name.set(artifactId)
        url.set("https://github.com/tonexue1/relay")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/tonexue1/relay.git")
            developerConnection.set("scm:git:ssh://git@github.com/tonexue1/relay.git")
            url.set("https://github.com/tonexue1/relay")
        }
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    extensions.configure<JavaPluginExtension>("java") {
        withSourcesJar()
    }
    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = "relay-${project.name}"
                configureRelayPom()
            }
        }
    }
}

pluginManager.withPlugin("com.android.library") {
    afterEvaluate {
        extensions.configure<PublishingExtension>("publishing") {
            publications {
                create<MavenPublication>("maven") {
                    from(components["release"])
                    artifactId = "relay-${project.name}"
                    configureRelayPom()
                }
            }
        }
    }
}
