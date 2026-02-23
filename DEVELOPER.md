## Release Process

Update versions in `README.md`

```
export ORG_GRADLE_PROJECT_mavenCentralUsername=username
export ORG_GRADLE_PROJECT_mavenCentralPassword=the_password

export ORG_GRADLE_PROJECT_signingInMemoryKey=exported_ascii_armored_key
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=some_password
```

```
git commit -am "Release vX.Y.Z"
git tag vX.Y.Z -m "Release vX.Y.Z"
git push --atomic origin main v0X.Y.Z

./gradlew publishAndReleaseToMavenCentral
```
