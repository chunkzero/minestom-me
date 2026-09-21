# Minestom ME

A fork of [Minestom](https://github.com/Minestom/Minestom), published to the chunkzero Maven repository. Artifact coordinates remain `net.minestom:minestom` and `net.minestom:testing`.

## Publishing

Successful builds on `master` publish `master-SNAPSHOT` to `default/snapshots`. The manually triggered Release workflow publishes to `default/releases` using Minestom's date and Minecraft-version naming scheme. Release suffixes are selected from this fork's tags, so its releases can differ from upstream.

Both workflows install the Maven R2 CLI and publish the two modules together through its local proxy. Publishing here does not require Maven Central credentials or GPG signing keys.

Before enabling publishing, add a repository secret named `MAVEN_R2_TOKEN`. Use a publisher service-account token with the `net/minestom` path prefix and these scopes:

- `snapshots`: `publish:snapshot`
- `releases`: `publish:release`

The workspace is `default` and the server is `https://maven.chunkzero.com`. Repositories may be private. Consumers of private artifacts need a separate read token.

For local publishing, install the Maven R2 CLI and save your token with `maven-r2 --server https://maven.chunkzero.com login`. Then run:

```sh
MINESTOM_VERSION=master-SNAPSHOT maven-r2 publish --repository default/snapshots -- \
  ./gradlew :publishAllPublicationsToMavenR2Repository :testing:publishAllPublicationsToMavenR2Repository
```

The build uses Java 25. The proxy provides the temporary repository URL and credentials to Gradle and commits only after both publications succeed.
