# Vendored Concentus — pure-Java Opus codec

This directory contains the Java port of the Opus audio codec from the
Concentus project, vendored as source (unmodified) rather than pulled from a
third-party Maven republish, so the exact code that ships is auditable and
the build never depends on an external artifact host at *runtime*.

- Source: https://github.com/lostromb/concentus (branch `master`)
- Path in upstream: `Java/Concentus/src/main/java/org/concentus`
- License: see `LICENSE.txt` in this directory — permissive BSD-style terms,
  the same terms as the Opus reference library itself (redistribution in
  source and binary forms permitted with copyright notice retained).
- Used by: `com.hamseda.walkie.audio.OpusCodec` (16 kHz mono, 20 ms frames,
  ~20 kbps, VOIP application).

## Upstream verification (2026-10-09)

- Upstream HEAD (master): `3885c4e46513ef0fc81fca100189e54f1714c6ca`
  (2025-09-27, "Fix the Celt decoding bug. (#60)"). That commit touches
  **only the Go port** (`go/celt/*`, `go/opus/*`); the Java port is
  unaffected.
- Last upstream change to the Java port: `e42b40f757fe` (2024-05-09,
  "fix the java code").
- Integrity check 2026-10-09: all 124 vendored `.java` files were compared
  against upstream master's git blob SHAs (via the GitHub git-trees API);
  file lists are identical and **all 124 blob hashes match exactly** —
  the vendored tree is byte-identical to upstream and contains no local
  modifications.
- Conclusion: no codec upgrade is required; the vendored copy is current
  with the upstream Java port. Re-check upstream before any future change.

If Concentus publishes an official, verified Maven Central release, this
vendored copy may be replaced by that dependency.
