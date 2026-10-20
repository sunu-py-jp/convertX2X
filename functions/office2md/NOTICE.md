# Third-party notices

The convertX2X application code is distributed under the MIT License in `LICENSE`.
Third-party libraries, fonts and tools retain their original licenses and copyright notices.

## Java dependencies

`third-party/java/dependencies.tsv` identifies the exact reviewed runtime JARs and
maps each to its original license and notice files. `third-party/java/SOURCE.md`
records upstream sources; `SHA256SUMS` verifies the retained texts. This includes
transitive dependencies and notices for bundled native components, not only direct dependencies.

Upstream JARs are redistributed unchanged. When an upstream JAR omits license text
or copyright notices, this module supplies the original upstream files separately.
JNA offers alternative licenses; this distribution uses its Apache License 2.0 option.
All original JNA license alternatives and native-component notices are retained.

## Fonts and build scripts

Bundled Noto Sans CJK JP / Noto Serif CJK JP (Regular and Bold) fonts are unchanged upstream files under
SIL Open Font License 1.1. Full copyright/license text, provenance and checksums
are retained with the fonts in `src/main/resources/fonts/` and `fonts/` inside the JAR.

The Maven Wrapper scripts use Apache License 2.0. Their original LICENSE and NOTICE
are included in `third-party/maven-wrapper/`.

## Independent distribution

Build this directory with `./mvnw clean package`. The build validates runtime JAR
versions and hashes against the reviewed inventory, embeds the full notices in
`META-INF/` of the application JAR, and includes `LICENSE`, `NOTICE.md`,
`THIRD-PARTY-NOTICES.txt` and `licenses/` at the root of the Functions deployment directory.
The readable catalog contains upstream license/copyright/NOTICE text, including
font and native-library notices. Keep these files with any binary distribution.

For source distribution, retain this entire module including `third-party/`, the
font licenses, `LICENSE`, and this file; no repository-level notice file is required.
