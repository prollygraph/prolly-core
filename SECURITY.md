# Security policy

## Reporting

Please report suspected vulnerabilities **privately** via GitHub's security advisories
("Security" tab → "Report a vulnerability") rather than a public issue. If that path is
unavailable, open an issue that says only "security — requesting a private channel"
without details, and a maintainer will arrange one.

## The trust model, honestly stated

This library deserializes **content-addressed bytes**. Content addressing verifies that
bytes match their *name* — it does **not** make incoming bytes trustworthy before that
check happens. The surfaces that parse potentially untrusted input are treated as trust
boundaries and are fuzz-hardened (Jazzer; regression seeds run in every build):

- `Node.fromBytes` — the node envelope + flatbuffer parse (checks a magic prefix and
  version first; unknown versions fail closed)
- `Commit` deserialization — length-field hardening against denial-of-service via
  crafted inputs (a real finding, fixed by fuzzing before this repo existed publicly)
- `SyncPackCodec` — the replication pack format (magic + version header, fail-closed)

`prolly-playground-service` is a **demo backend**: unauthenticated by design, wide-open
CORS, in-memory or local-disk state. Do not expose it beyond localhost/trusted networks.

## Scope

The engine modules (`dolthub-java-port`, `prolly-storage`, `prolly-sync`) are in scope.
Pre-1.0 caveat: the on-disk format is not stable; "old files can't be read after a
format change" is expected behavior, not a vulnerability.
