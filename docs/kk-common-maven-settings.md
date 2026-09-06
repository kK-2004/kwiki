# Private GitHub Packages repositories (kwiki consumer)

kwiki resolves two private GitHub Packages repositories. Maven matches repository
credentials by **server id**, so each repository id in `pom.xml` needs a matching
`<server>` entry in your `~/.m2/settings.xml` (or the CI runner's settings file).
Tokens stay out of the repository: use a placeholder or an environment variable.

| Repository id (`pom.xml`) | Package repo | Used for |
| --- | --- | --- |
| `github` | `kK-2004/kk-common` | `com.kK-2004:kk-common` shared toolkit |
| `github-kfile` | `kK-2004/kFile` | `com.kk:content-center-sdk` attachment storage |

## Token requirements

The GitHub token must have at least **`read:packages`** permission. The same token
can serve both server entries (both repos belong to the `kK-2004` account); classic
tokens with `read:packages` or fine-grained tokens with package read access both work.

## `settings.xml` template

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <servers>
    <!-- kk-common SDK: https://maven.pkg.github.com/kK-2004/kk-common -->
    <server>
      <id>github</id>
      <username>kK-2004</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
    <!-- content-center SDK: https://maven.pkg.github.com/kK-2004/kFile -->
    <server>
      <id>github-kfile</id>
      <username>kK-2004</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

`${env.GITHUB_TOKEN}` is resolved by Maven from your environment; replace it with a
literal token only in a file that is never committed. If resolution fails, check:

1. the server `<id>` matches the repository `<id>` exactly (`github` vs `github-kfile`),
2. the token has `read:packages`,
3. the token owner can access both `kK-2004/kk-common` and `kK-2004/kFile`.

## Verifying resolution

```bash
export GITHUB_TOKEN=github_pat_...   # read:packages
./mvnw -U dependency:resolve         # must fetch com.kK-2004:kk-common:0.1.2
```

The default `./mvnw test` build works without credentials only while both artifacts
are present in the local `~/.m2/repository`; a clean CI cache always needs the
settings above.
