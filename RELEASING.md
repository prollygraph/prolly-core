# Releasing

How a version of this repo becomes consumable artifacts, and the constraints that make the
ritual what it is.

## The version is in LOCKSTEP with the upstream monorepo

The upstream private monorepo consumes these artifacts as
`io.github.prollygraph:{dolthub-java-port,prolly-storage,prolly-sync,prolly-dependencies}`
at **the same version on both sides** (`0.2.0-BETA` today, referenced there via
`${project.version}`). A version bump here is therefore a **coordinated change**: bump this
repo's parent pom, publish, then bump the monorepo's consumer poms in the same motion —
a one-sided bump breaks the other side's resolution.

## The ritual

Publishing runs `.github/workflows/maven-publish.yml`: one `mvn -B deploy` pass (verify's
tests + gates run inside it) to **GitHub Packages**, authenticated by the workflow's own
`GITHUB_TOKEN`. Two triggers:

1. **A GitHub release** (the normal path): create a release for a tag like `v0.2.0-BETA`
   — web UI, or `gh release create v0.2.0-BETA --generate-notes`.
2. **Manual dispatch** (re-publish / first-time smoke): Actions → *Maven Package* →
   *Run workflow*, or `gh workflow run maven-publish.yml`.

What ships (verified by a staged-deploy dry-run, 2026-07-13 —
`mvn -B -DskipTests -DaltDeploymentRepository=staging::file:///tmp/staging deploy`):

- `prolly-parent` (pom), `prolly-dependencies` (the BOM, pom)
- `dolthub-java-port` (jar + **tests-jar** — consumers use the shared test doubles), 
  `prolly-storage` (jar), `prolly-sync` (jar), each with its pom
- `prolly-playground-service` deliberately does **not** deploy
  (`maven.deploy.skip=true` — a runnable service, not a library)
- **Sources + javadoc jars attach unconditionally** for the three engine modules
  (added 2026-07-13, verified by a second staged dry-run: zero doclint findings after
  registering the `@apiNote`/`@implNote` tags). The remaining Central-only gap is
  **GPG signing** — the Central plan's workflow step; GitHub Packages needs none.

## What GitHub Packages gives — and does not

GitHub Packages Maven **requires authentication to read, even for public packages**. It
serves this repo's own CI and any consumer holding a token — never an anonymous `mvn`
user. Consuming from it needs a personal access token with `read:packages` and:

```xml
<!-- settings.xml -->
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_TOKEN</password>
  </server>
</servers>
```

```xml
<!-- consumer pom or profile -->
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/prollygraph/prolly-core</url>
  </repository>
</repositories>
```

**Anonymous consumption is Maven Central's job.** Central publishing is deliberately not
wired yet — it is gated on the per-file attribution-coverage audit (ported files carry
DoltHub's copyright; the audit proves every file's header matches its provenance ledger
entry before anything is signed and staged). Until then: build from source
(`mvn clean install`) or consume from Packages with a token.

## Pre-flight checklist

- [ ] `mvn -B verify` green locally (the workflow runs exactly this inside deploy)
- [ ] version lockstep: if the version changed, the monorepo bump is ready to land
- [ ] the module-name cleanliness grep is clean (no upstream monorepo names in sources —
      see the split-operability plan's wide grep)
- [ ] `CHANGELOG.md` updated (the release section header carries the version + date)

## Maven Central

Central is the **anonymous-consumption** tier (Packages needs a token even to read).
Publishing goes through the Central Publisher Portal (central.sonatype.com; OSSRH is
sunset) via the `central-release` profile + `.github/workflows/central-publish.yml`
(manual dispatch only — publishing is a deliberate act).

**One-time owner setup** (key material never enters a session or commit):

1. Portal account + verify the `io.github.prollygraph` namespace (GitHub-org proof).
2. Generate a user token in the Portal; store as repo secrets
   `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD`.
3. GPG key: generate, publish the public key to a keyserver
   (`keys.openpgp.org`), store the ASCII-armored private key + passphrase as
   `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE`.

**The ritual**: Actions → *central-publish* → Run workflow. A green run runs the full
build (tests + gates), signs every artifact, and **stages** the deployment
(`autoPublish=false`); review it in the Portal UI (Deployments) and press Publish.
Artifacts: parent + BOM poms, the three engine modules each as jar + sources + javadoc
(+ the engine's tests-jar), all `.asc`-signed. The playground service skips deploy.

**First-run cautions**: verify the plugin versions are current
(`central-publishing-maven-plugin`, `maven-gpg-plugin`); the local no-key rehearsal is
`mvn -Pcentral-release -Dgpg.skip=true verify`. Central validates
name/description/url/license/developers/scm on every pom — already present, but the
Portal's validator is the authority.

## Going public — the visibility-gated tail (owner actions)

Flipping the repo public is an owner decision (exposure cannot be un-published). The
items that only make sense at or after the flip:

- [ ] repo **About**: description ("A Java port of Dolt's prolly tree — content-addressed,
      history-independent versioned storage for the JVM") + topics
      (`prolly-tree`, `content-addressing`, `versioned-database`, `java`, `dolt`, `merkle`)
- [ ] enable **private vulnerability reporting** (Security tab) — SECURITY.md points there
- [ ] confirm the **build badge** renders anonymously
- [ ] re-probe visibility anonymously and update the "prepared for public" language
      (upstream records the same step)
- [ ] decide **GitHub Pages** for the playground and/or published javadoc (upstream
      split-operability plan owns the Pages mechanics)
