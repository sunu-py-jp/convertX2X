# Chromium and bundled native components

`provenance.json` records exact npm/runtime versions, binary archive hashes, upstream documents and source locations. The packaging command verifies every document and all four Brotli archives against that manifest. It does not download a browser at startup.

## Chromium and generated credits

The runtime comes from `@sparticuz/chromium@153.0.0`, using Chromium revision **1681091**, version **153.0.8010.0**. `LICENSE.headless_shell` is the unchanged full license file extracted from Google's official Linux headless-shell archive for that exact version/revision. Its source URL and archive member are recorded in `provenance.json`. It includes Chromium's third-party credits, including SwiftShader, Vulkan, FFmpeg and their associated license texts. `LICENSE.chromium` is the corresponding Chromium BSD license; `Sparticuz-LICENSE` covers the serverless wrapper.

The upstream build has its own build flags, so the official headless license bundle is accompanied by the upstream build configuration (`args-x64.gn`), binary packaging script (`build-x64.sh`), revision and separately bundled components below. These are provenance records, not scripts executed by this application. The full build sources are available at the pinned upstream tree recorded in the manifest; Chromium's pinned source tree and `DEPS` identify its dependency sources.

## Separate runtime libraries and fonts

The AL2023 archive includes NSS, NSPR and Expat libraries that are not covered merely by the wrapper's MIT declaration:

| Component | Observed runtime version | License/source record |
| --- | --- | --- |
| NSS | 3.112.0, Amazon package `3.112.0-8.amzn2023.0.2` | `NSS-COPYING`; exact Amazon source RPM includes downstream patches |
| NSPR | 4.36, bundled by that NSS source package | `NSPR-LICENSE`; source RPM includes `nspr-4.36.tar.gz` and patches |
| Expat | 2.6.3, Amazon package `2.6.3-1.amzn2023.0.6` | `Expat-COPYING`; exact Amazon source RPM and SHA-256 |
| Open Sans | 3.003, Regular/Bold/Italic | `OpenSans-OFL.txt`; font name tables confirm the version, copyright and OFL |

Both Amazon source RPMs were downloaded and their SHA-256 values checked. The package identities were read from the bundled ELF metadata. NSS and NSPR source code, including the matching downstream changes, can be obtained using the manifest URLs. The libraries and font binaries are unmodified in this project.

The app's separate Japanese fonts are documented in `assets/fonts/OFL.txt`. Keep this directory, all legal documents and the matching manifest with each standalone distribution. Updating only the binary or npm version intentionally fails the packaging verification until the matching provenance is updated.
