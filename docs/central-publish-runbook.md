# Maven Central publish runbook — the prolly rings

Status as of 2026-08-20 (verified, not assumed — see citations). This is a runbook for the
repo owner: publication requires Sonatype Central credentials and a GPG signing key that
this session does not have and cannot obtain. Everything below is either read from a file
(cited `path:line`), a probe result (command + output shown), or an explicit
`TODO(verify)` with the exact command that would resolve it.

## 1. What the workflows actually do (read, not assumed)

### `prolly-core` — `.github/workflows/central-publish.yml`

- **Target**: Maven Central, via the **Central Publisher Portal** (`central.sonatype.com`),
  using the `org.sonatype.central:central-publishing-maven-plugin` (not OSSRH — OSSRH is
  sunset per `RELEASING.md:83`).
- **Trigger**: `workflow_dispatch` only (`.github/workflows/central-publish.yml:15`) — no
  push/tag/release trigger. The file's own header comment states this is deliberate:
  "publishing to Central is a deliberate act, never a side effect of a push/release"
  (`central-publish.yml:1-2`).
- **Secrets expected** (`central-publish.yml:4-8,29-38`):
  - `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` — a Portal user token, wired as the
    Maven `server-username`/`server-password` for server id `central`
    (`central-publish.yml:28-30`, matching `pom.xml:619` `publishingServerId=central`).
  - `GPG_PRIVATE_KEY` — ASCII-armored private key, imported by `actions/setup-java`
    (`central-publish.yml:31`).
  - `GPG_PASSPHRASE` — passed to the GPG plugin via env, using `--pinentry-mode loopback`
    so CI never hits a pinentry prompt (`pom.xml:604-608`).
- **Build command**: `mvn -B -Pcentral-release deploy` (`central-publish.yml:34`), which
  activates the `central-release` profile (`pom.xml:590-625`): signs every artifact
  (`maven-gpg-plugin:sign`, `pom.xml:594-612`) then stages to the Portal
  (`central-publishing-maven-plugin`, `pom.xml:613-622`).
- **`autoPublish=false`** (`pom.xml:620`): a green run **stages** the deployment; a human
  must go into the Portal UI (Deployments) and press Publish before anything becomes
  resolvable on `repo1.maven.org`. This is a second manual gate beyond the workflow
  dispatch itself.
- **Version / coordinates**: `pom.xml:12-14` — `io.github.prollygraph:prolly-parent:0.2.0-BETA`.
  What actually deploys (per `RELEASING.md:29-34`, verified there by a staged-deploy
  dry-run): `prolly-parent` + `prolly-dependencies` (poms), `dolthub-java-port` (jar +
  tests-jar), `prolly-storage` (jar), `prolly-sync` (jar) — each with sources + javadoc
  jars attached. `prolly-playground-service` explicitly does **not** deploy
  (`maven.deploy.skip=true`, `RELEASING.md:32-33`).

### `prolly-core` — `.github/workflows/maven-publish.yml`

- **Target**: **GitHub Packages**, not Central — `distributionManagement/repository/id=github`
  → `https://maven.pkg.github.com/prollygraph/prolly-core` (`pom.xml:39-45`), matching the
  workflow's `server-id: github` (`maven-publish.yml:26`).
- **Trigger**: `release: types: [created]` **or** `workflow_dispatch`
  (`maven-publish.yml:7-9`) — this one *does* fire automatically on a GitHub release.
- **Auth**: the ambient `github.token` (`maven-publish.yml:33`) — no repo secrets to
  provision; `permissions: packages: write` (`maven-publish.yml:16-17`).
- **This path is a distraction for this task**: it publishes to GitHub Packages, which
  `RELEASING.md:41-42` states plainly "requires authentication to read, even for public
  packages" — it can never give the consumer repo an anonymous, credential-free
  `mvn install`. Only the Central path (Section above) satisfies Task 7's goal.

### Does `prolly-rdf` have equivalents?

- **pom.xml: yes, in substance.** `prolly-rdf/pom.xml:12-14` —
  `io.github.prollygraph:prolly-rdf-parent:0.2.0-BETA`. It carries the same
  `distributionManagement` (id=`github`, `pom.xml:39-45`) and the same `central-release`
  profile (gpg-plugin + `central-publishing-maven-plugin:0.7.0`, `pom.xml:511-537`),
  parameterized identically to `prolly-core`'s.
- **Workflow files: no.** `/home/eriver6/git/prolly-rdf/.github/workflows/` contains only
  `build.yml` and `pages.yml` — **no `central-publish.yml`, no `maven-publish.yml`**.
  `prolly-rdf/RELEASING.md:8-9` asserts "Publication paths mirror the engine repo's
  RELEASING.md" but this is aspirational: the pom is wired for it, the CI trigger to
  invoke it does not exist in this repo. **Finding, not an edit** (this task documents
  only): the maintainer needs to either add `prolly-rdf/.github/workflows/central-publish.yml`
  (copy of `prolly-core`'s, same secret names — they're repo-scoped so `prolly-rdf` needs
  its own copies of all four secrets) before Step 3 below, or publish `prolly-rdf` some
  other way (e.g. local `mvn -Pcentral-release deploy` from a machine holding the
  credentials). The runbook's execution sequence below assumes the workflow gets added;
  if the maintainer instead publishes locally, substitute the equivalent `mvn` invocation.

## 2. Current publication state (probed 2026-08-20)

### Maven Central — confirmed absent

```
$ curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    https://repo1.maven.org/maven2/io/github/prollygraph/dolthub-java-port/0.2.0-BETA/dolthub-java-port-0.2.0-BETA.pom
HTTP 404

$ curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    https://repo1.maven.org/maven2/io/github/prollygraph/prolly-rdf4j/0.2.0-BETA/prolly-rdf4j-0.2.0-BETA.pom
HTTP 404

$ curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    https://repo1.maven.org/maven2/io/github/prollygraph/
HTTP 404
```

The **whole `io/github/prollygraph` path** 404s on `repo1.maven.org`, not just the specific
version — i.e. nothing under this groupId has ever landed on Central, for either ring. This
is consistent with (but does not by itself prove) the namespace never having been staged at
all, which matters for the immutability question in Section 3.

### GitHub Packages — unverifiable without a token

```
$ curl -sI https://maven.pkg.github.com/prollygraph/prolly-core/io/github/prollygraph/dolthub-java-port/0.2.0-BETA/dolthub-java-port-0.2.0-BETA.pom
HTTP/2 401
```

GitHub Packages Maven returns `401` for **every** unauthenticated request regardless of
whether the artifact exists — this matches `RELEASING.md:41-42`'s own statement that it
"requires authentication to read, even for public packages." The 401 is therefore not
evidence either way.

**TODO(verify): GitHub Packages publication state — needs an authenticated call.** Any of:
- `gh auth login` (as a user with at least read access to the org) then
  `gh api /orgs/prollygraph/packages?package_type=maven`
- Or, with a PAT that has `read:packages`:
  `curl -u <user>:<PAT> -sI https://maven.pkg.github.com/prollygraph/prolly-core/io/github/prollygraph/dolthub-java-port/0.2.0-BETA/dolthub-java-port-0.2.0-BETA.pom`

This session has no such credential (`gh auth status` → "You are not logged into any
GitHub hosts"), so the question is left open rather than guessed at.

## 3. Prerequisites (owner-held; none of this session's business to execute)

1. **Sonatype Central Portal account** at `central.sonatype.com`.
2. **`io.github.prollygraph` namespace verification** — automatic via Central's
   `io.github.<owner>` GitHub-org proof (the same mechanism the `groupId` comment in
   `pom.xml:8-9` documents as the reason this groupId was chosen at the monorepo export).
   No separate DNS/domain verification needed.
3. **Repo secrets**, exact names from Section 1, provisioned on **each repo that will run
   `central-publish.yml`** (secrets are repo-scoped, not org-scoped, unless the maintainer
   explicitly sets an org-level secret visible to both repos):
   - `CENTRAL_TOKEN_USERNAME`
   - `CENTRAL_TOKEN_PASSWORD`
   - `GPG_PRIVATE_KEY`
   - `GPG_PASSPHRASE`
   Per `RELEASING.md:87-94`: generate the Portal user token first (gives the TOKEN_USERNAME/
   PASSWORD pair), then generate a GPG key, publish its **public** key to a keyserver
   (`keys.openpgp.org`), and store the **private** key + passphrase as the two GPG secrets.
4. **`prolly-rdf` workflow gap** (Section 1 finding): before `prolly-rdf` can be published
   via Actions, `central-publish.yml` needs to exist there. Until it does, the maintainer's
   options are (a) add the workflow file (out of scope for this task — it's a doc-only
   task) or (b) run `mvn -Pcentral-release deploy` locally in `prolly-rdf` with the same
   four credentials exported as env vars.

## 4. The Central-immutability decision point — DO NOT GUESS

Maven Central (via the Portal) **rejects re-publishing a version that was ever staged**,
even if the earlier staging was dropped/never released. Section 2's probe shows the
`io/github/prollygraph` namespace has never appeared on `repo1.maven.org` — but a **staged,
never-released** deployment would *also* produce that same "absent from repo1" result,
because staged-but-unpublished artifacts never reach the public repo. The repo1 404 alone
does not distinguish "never staged" from "staged once and abandoned."

**At execution time, before running `central-publish.yml`, the maintainer must check the
Portal UI (central.sonatype.com → Deployments) for prior deployments of
`io.github.prollygraph:prolly-parent` / `prolly-rdf-parent` under this account:**

- **If no prior deployment of `0.2.0-BETA` exists** (the expected case, given nothing on
  this account has published before): proceed with `0.2.0-BETA` as-is.
- **If a prior `0.2.0-BETA` staging exists** (dropped or otherwise): Central will reject
  re-publishing that exact version. In that case bump to `0.2.1-BETA` in **both**
  `prolly-core/pom.xml:14` and `prolly-rdf/pom.xml:14` (and every module pom that inherits
  the version — verify with `mvn versions:set` or a manual bump across both reactors), per
  the version-lockstep rule in `RELEASING.md:6-13` and `prolly-rdf/RELEASING.md:3-6`. A
  lockstep bump also means re-pointing the upstream monorepo's consumer poms
  (`RELEASING.md:8-13`) — outside this runbook's scope but noted so it isn't forgotten.

This is a judgment call against live Portal state that this session cannot see. Write down
which branch was taken, and why, when this step is actually executed.

## 5. Execution sequence

Order matters: `prolly-rdf` depends on `prolly-core`'s artifacts at `${project.version}`
(`prolly-rdf/RELEASING.md:11-13` — "The engine ring must be publicly resolvable BEFORE any
public artifact of this ring can be consumed"). Publish `prolly-core` first, confirm it
resolves (Section 6), *then* publish `prolly-rdf`.

1. **Resolve Section 4's decision point** against the Portal UI. Pick the version
   (`0.2.0-BETA` or `0.2.1-BETA`) for both rings; if bumping, land the version-bump commits
   in both repos before proceeding.
2. **Provision secrets** (Section 3) on `prolly-core`, and on `prolly-rdf` once its
   workflow file exists.
3. **`prolly-core`**: Actions → *central-publish* → Run workflow (or
   `gh workflow run central-publish.yml` from the `prolly-core` repo). This is
   `workflow_dispatch`-only — there is no tag or release to create for this step.
4. **Review and release in the Portal**: central.sonatype.com → Deployments → find the new
   staged deployment → verify contents (parent + BOM poms, `dolthub-java-port` jar +
   tests-jar, `prolly-storage`, `prolly-sync`, each with sources/javadoc, all `.asc`-signed
   — the checklist from `RELEASING.md:98-100`) → press **Publish**.
5. **Wait for Central sync**, then verify per Section 6 before moving on.
6. **`prolly-rdf`**: same dispatch (workflow file permitting — see Section 3.4), same
   Portal review-and-release step, for the `prolly-rdf-parent` reactor (`prolly-codec`,
   `prolly-rdf`, `prolly-flatsail`, `prolly-rdf4j`, `prolly-urdna2015`, and whichever
   others `prolly-rdf/RELEASING.md` designates as shipping — confirm against that repo's
   deploy list the same way `RELEASING.md:29-34` documents it for the engine ring, since
   this runbook did not re-derive `prolly-rdf`'s deploy set from a dry run).
7. **Verify per Section 6** again for the `prolly-rdf` ring.

## 6. Post-publication verification

Central sync from the Portal to `repo1.maven.org` is not instant — allow it a few minutes,
then re-run:

```bash
# engine ring — one artifact is a representative sample, not exhaustive
curl -sf https://repo1.maven.org/maven2/io/github/prollygraph/dolthub-java-port/<v>/dolthub-java-port-<v>.pom

# rdf ring — the artifact named in the task's own green-signal
curl -sf https://repo1.maven.org/maven2/io/github/prollygraph/prolly-rdf4j/<v>/prolly-rdf4j-<v>.pom
```

`-f` makes `curl` exit non-zero on 404, so a clean exit (`echo $?` → `0`) plus visible POM
XML on stdout is the green signal. Repeat for any other module the maintainer wants
independently confirmed (`prolly-storage`, `prolly-sync`, `prolly-codec`, `prolly-rdf`,
`prolly-flatsail`, `prolly-urdna2015`, per the deploy lists in Sections 1 and 5).

## 7. Consumer-side follow-ups (quarkus-ontology-editor) — only after Section 6 is green

1. **Remove the CI bridge.** Both workflows carry the same marker comment pointing at this
   task:
   - `quarkus-ontology-editor/.github/workflows/build.yml:23-30` — "Remove this bridge when
     io.github.prollygraph 0.2.0-BETA resolves from Central — see plan Task 7." The bridge
     checks out `prollygraph/prolly-core` at a pinned SHA
     (`build.yml:31-38`, currently `79f0f504fd8b0034bea10b23a67a7d3bc3803787`) and (per the
     comment at `build.yml:24-27`) builds both rings from source into the runner's `~/.m2`
     before building this repo.
   - `quarkus-ontology-editor/.github/workflows/release.yml:22-29` — same bridge, same
     removal condition, worded "Same from-source bridge as build.yml."
   Delete the bridge steps in both files once Section 6's `curl -sf` checks are green for
   the exact version these workflows resolve (`0.2.0-BETA`, or the Section 4 bump if that
   branch was taken — update the version reference in both workflows if so).
2. **Update `CONTRIBUTING.md`.** `quarkus-ontology-editor/CONTRIBUTING.md:5-11` currently
   reads: "The server depends on the prolly rings at `io.github.prollygraph:0.2.0-BETA`; if
   those artifacts are not available to your resolver, install them locally first" followed
   by the two `git clone && mvn install` commands. Once Central resolution is confirmed,
   reframe this block as: *only needed for unreleased ring changes* — i.e. rewrite the lead
   sentence so a contributor working against released ring versions never needs the local
   install, and the clone/install commands are explicitly scoped to "building against an
   unreleased engine/rdf-ring commit." (This edit belongs to the consumer repo, not this
   one — flagged here per the task's own instruction, not applied by this runbook.)

## 8. Summary of what changes and what doesn't

- This commit adds **only** this runbook to `prolly-core` (this repo). No workflow files
  were modified — Section 1's findings (the `prolly-rdf` workflow gap, the GitHub-Packages
  401-detection line) are documentation, not fixes.
- Nothing in `quarkus-ontology-editor` is touched by this commit; Section 7 describes work
  for a *later* task once Section 6 is actually green.
