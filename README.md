# Multistom

A fork of [Minestom](https://github.com/Minestom/Minestom), published to the chunkzero Maven repository as `com.chunkzero.multistom:multistom` and `com.chunkzero.multistom:multistom-testing`. Java packages remain `net.minestom.*`, so it is a drop-in replacement for Minestom.

## Versions

`releaseVersion` in `gradle.properties` holds the upcoming release, e.g. `0.1.0` or `0.1.0-beta.1`. Versions follow the [chunkzero/release-tools](https://github.com/chunkzero/release-tools) scheme and are immutable:

- Nightlies: every successful build on `master` publishes `<X.Y.Z>-nightly.<UTC commit time>.g<12-character commit>`, e.g. `0.1.0-nightly.20261004062300.ge282f11816cd`, to `https://maven.chunkzero.com/nightlies`.
- Releases: the manually triggered Release workflow publishes `releaseVersion` as is to `https://maven.chunkzero.com`, tags it `v<version>` and creates a GitHub release. Versions containing `-` are prereleases.

After a release, bump `releaseVersion` to the next upcoming version.

```kotlin
repositories {
    maven("https://maven.chunkzero.com")
    maven("https://maven.chunkzero.com/nightlies") // Only for nightlies
}

dependencies {
    implementation("com.chunkzero.multistom:multistom:0.1.0")
}
```

## Publishing

Both workflows install the Maven R2 CLI and publish the two modules together through its local proxy. Publishing here does not require Maven Central credentials or GPG signing keys. A version that is already published is skipped, so reruns succeed.

Publishing uses the `MAVEN_R2_TOKEN` secret: a publisher service-account token with the `com/chunkzero/multistom` path prefix that can publish to the `nightlies` and `releases` repositories in the `default` workspace on `https://maven.chunkzero.com`.

For local publishing, install the Maven R2 CLI and save your token with `maven-r2 --server https://maven.chunkzero.com login`. Then run:

```sh
MINESTOM_VERSION=<version> maven-r2 publish --repository default/nightlies -- \
  ./gradlew :publishAllPublicationsToMavenR2Repository :testing:publishAllPublicationsToMavenR2Repository
```

The build uses Java 25. The proxy provides the temporary repository URL and credentials to Gradle and commits only after both publications succeed.
