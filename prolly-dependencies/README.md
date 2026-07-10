# prolly-dependencies — the BOM

A Maven Bill of Materials: the single source of truth for dependency versions across
this repo's modules, importable by consumers so their versions stay coherent with ours.

Deliberately **parentless and dependency-free** — it builds instantly and can be imported
without dragging any build configuration along.

## Usage

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.prollygraph</groupId>
      <artifactId>prolly-dependencies</artifactId>
      <version>0.2.0-BETA</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- version-less: the imported BOM pins them -->
  <dependency>
    <groupId>io.github.prollygraph</groupId>
    <artifactId>dolthub-java-port</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.prollygraph</groupId>
    <artifactId>prolly-storage</artifactId>
  </dependency>
</dependencies>
```

That exact shape is exercised by the integration fixture under
`src/it/external-consumer/` — the BOM's usage is tested, not just documented.

## What it pins

The repo's own artifacts (`dolthub-java-port`, `prolly-storage`) plus the curated
third-party set (logging, jackson, junit/jqwik/mockito, flatbuffers, rocksdbjni, …).
Declaration order is load-bearing for a few entries — see the in-pom comments.
