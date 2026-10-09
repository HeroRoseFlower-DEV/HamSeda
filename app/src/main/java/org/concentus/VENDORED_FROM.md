# Vendored Concentus — pure-Java Opus codec

This directory contains the Java port of the Opus audio codec from the
Concentus project, vendored as source (unmodified) rather than pulled from a
third-party Maven republish, so the exact code that ships is auditable and
the build never depends on an external artifact host at *runtime*.

- Source: https://github.com/lostromb/concentus
- Path in upstream: `Java/Concentus/src/main/java/org/concentus`
- License: see `LICENSE.txt` in this directory — permissive BSD-style terms,
  the same terms as the Opus reference library itself (redistribution in
  source and binary forms permitted with copyright notice retained).
- Used by: `com.hamseda.walkie.audio.OpusCodec` (16 kHz mono, 20 ms frames,
  ~20 kbps, VOIP application).

If Concentus publishes an official, verified Maven Central release, this
vendored copy may be replaced by that dependency.
