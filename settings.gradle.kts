import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties().apply {
    val f = rootDir.resolve("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Asks the GitHub CLI for its token, so nothing has to be stored in plaintext.
 *
 * Returns null when gh is missing, logged out, or otherwise unhappy - the
 * caller falls through to the other sources. providers.exec keeps this
 * compatible with the configuration cache; a raw ProcessBuilder would not be.
 */
fun ghCliToken(): String? = runCatching {
    providers.exec {
        commandLine("gh", "auth", "token")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifBlank { null }
}.getOrNull()

/**
 * GitHub Packages demands authentication even though the SDK repo is public,
 * and the DAT licence is non-transferrable - so it cannot be vendored into this
 * repo and everyone who builds Cicero brings their own credential. That is not
 * only a chore: fetching the SDK yourself is how you accept Meta's Developer
 * Terms.
 *
 * The keyring is tried last but costs nothing to prefer, because the elvis
 * chain short-circuits - gh only runs when the other two are absent.
 */
val githubToken: String =
    System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("github_token")?.takeIf { it.isNotBlank() }
        ?: ghCliToken()
        ?: ""

if (githubToken.isBlank()) {
    // Otherwise this surfaces as an unexplained 401 on mwdat-core, which tells
    // a newcomer nothing about what to do next.
    logger.warn(
        """
        |
        |  No GitHub Packages credential found, so com.meta.wearable:mwdat-* will fail with 401.
        |  The Meta SDK cannot be redistributed, so you need your own read:packages token.
        |
        |  Easiest (keeps the token in your OS keyring, nothing written to disk):
        |      gh auth refresh -h github.com -s read:packages
        |
        |  Or export GITHUB_TOKEN, or add github_token=... to local.properties.
        |
        """.trimMargin(),
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // mwdat-* artifacts are published to GitHub Packages, not Maven Central
        // or Google's Maven - both return 404. See README.
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = ""
                password = githubToken
            }
        }
    }
}

rootProject.name = "Cicero"
include(":app")
