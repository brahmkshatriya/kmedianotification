allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories {
                maven {
                    name = "releaseShard"
                    url = rootProject.layout.buildDirectory.dir("maven-release-shard").get().asFile.toURI()
                }
            }
        }
    }
}
