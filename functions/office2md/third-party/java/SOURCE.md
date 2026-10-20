# Java dependency redistribution materials

This directory is a self-contained copy of the license and attribution material for this function's resolved Java runtime dependencies. Keep it with the application JAR and the `lib/` directory when redistributing a deployment bundle. The dependencies retain their upstream licenses; the application's MIT license does not replace them.

`dependencies.tsv` contains one record per resolved runtime JAR: exact filename, SHA-256 of the published JAR, and comma-separated paths to its complete license/notice material, relative to this directory. `azure-functions-java-core-library-1.3.0.jar` is included because it is a resolved runtime dependency even when the Azure Functions Maven plugin omits the host-provided core library from the deployment `lib/` directory.

`sources.tsv` identifies the publisher's source of every vendored legal file and the SHA-256 of its stored bytes. A source written as `https://.../artifact.jar!/META-INF/LICENSE` means the named ZIP entry was extracted directly from that exact published JAR. The artifact's SHA-256 is in `dependencies.tsv`; the binary source URLs are listed below. Upstream source files use immutable commit URLs. License and notice files are copied without changing their bytes, except the explicitly identified SparseBitSet attribution excerpt.

`SHA256SUMS` covers every file in this directory except itself. Dependency upgrades require reviewing the replacement artifact's license/notice contents, refreshing all corresponding records, and regenerating those checksums. A matching license name or URL alone does not establish coverage.

The full Netty NOTICE and every file in its referenced license directory are retained, including bundled modifications and optional upstream components. The complete upstream Azure SDK NOTICE is also retained. These upstream notices describe broader upstream distributions; their inclusion does not assert that every optional component is present in this application. Native Netty tcnative/BoringSSL classifiers retain their embedded Apache, BoringSSL, Tomcat Native and Maven Wrapper texts. JNA's original dual-license statement and both supplied license texts are retained, together with the bundled libffi license; this application uses JNA under the Apache License 2.0 option.

## Pinned upstream supplements

These supplements cover binary artifacts whose publisher omitted the complete legal text, and the additional libffi notice. Release tags are resolved to commits; fallbacks are explicitly identified.

| Local family | Official repository and revision | Version relationship |
| --- | --- | --- |
| `azure-sdk-for-java` | [Azure/azure-sdk-for-java at 183ffbee0bbe753baeb521878cf27ab09c61c648](https://github.com/Azure/azure-sdk-for-java/tree/183ffbee0bbe753baeb521878cf27ab09c61c648) | Pinned official repository fallback; release tag azure-core_1.59.1 is not published |
| `azure-functions-java-library-3.3.0` | [Azure/azure-functions-java-library at 5d006a58d8a358ddf59d276502e49d8184d2e41f](https://github.com/Azure/azure-functions-java-library/tree/5d006a58d8a358ddf59d276502e49d8184d2e41f) | Pinned official source commit whose root POM declares 3.3.0 |
| `azure-functions-java-core-library-1.3.0` | [Azure/azure-functions-java-core-library at 8a320514ec019e6d6be0448f3197228b88de3233](https://github.com/Azure/azure-functions-java-core-library/tree/8a320514ec019e6d6be0448f3197228b88de3233) | Pinned official repository fallback; no 1.3.0 release tag is published |
| `msal4j-1.23.1` | [AzureAD/microsoft-authentication-library-for-java at b8c025bacb28a6ad8e997f3d168d6d77bfed4325](https://github.com/AzureAD/microsoft-authentication-library-for-java/tree/b8c025bacb28a6ad8e997f3d168d6d77bfed4325) | Tag v1.23.1; its msal4j-persistence-extension/pom.xml declares 1.3.0 |
| `SparseBitSet-1.3` | [brettwooldridge/SparseBitSet at 46c1f7412b992aa8c72f5ce4601013b9160110a4](https://github.com/brettwooldridge/SparseBitSet/tree/46c1f7412b992aa8c72f5ce4601013b9160110a4) | Tag SparseBitSet-1.3 |
| `curvesapi-1.08` | [virtuald/curvesapi at a39c6d234bd1b70a324a8cf4f5a2b0e1f2780c7f](https://github.com/virtuald/curvesapi/tree/a39c6d234bd1b70a324a8cf4f5a2b0e1f2780c7f) | Tag 1.08 |
| `slf4j-api-1.7.36` | [qos-ch/slf4j at e9ee55cca93c2bf26f14482a9bdf961c750d2a56](https://github.com/qos-ch/slf4j/tree/e9ee55cca93c2bf26f14482a9bdf961c750d2a56) | Tag v_1.7.36; module-specific MIT license |
| `reactive-streams-1.0.4` | [reactive-streams/reactive-streams-jvm at 944163a4b2477a2bebaaada86b0ba910b6302f2f](https://github.com/reactive-streams/reactive-streams-jvm/tree/944163a4b2477a2bebaaada86b0ba910b6302f2f) | Tag v1.0.4; MIT No Attribution |
| `jna-5.13.0` | [java-native-access/jna at 4962fd7758493b7395e86578705d8a32f6238872](https://github.com/java-native-access/jna/tree/4962fd7758493b7395e86578705d8a32f6238872) | Tag 5.13.0; license for libffi incorporated into the native library |
| `netty-4.1.137.Final` | [netty/netty at 5471f0a6ffa4ab39db22c468c6c851af4ecae709](https://github.com/netty/netty/tree/5471f0a6ffa4ab39db22c468c6c851af4ecae709) | Tag netty-4.1.137.Final; full upstream license directory including every NOTICE reference |
| `netty-4.1.135.Final` | [netty/netty at f05f765d81460799c53123a207f665bf3b465171](https://github.com/netty/netty/tree/f05f765d81460799c53123a207f665bf3b465171) | Tag netty-4.1.135.Final; full upstream license directory including every NOTICE reference |

## Exact binary sources

| Runtime JAR | Original Maven Central artifact |
| --- | --- |
| `SparseBitSet-1.3.jar` | [SparseBitSet-1.3.jar](https://repo.maven.apache.org/maven2/com/zaxxer/SparseBitSet/1.3/SparseBitSet-1.3.jar) |
| `azure-core-1.59.1.jar` | [azure-core-1.59.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-core/1.59.1/azure-core-1.59.1.jar) |
| `azure-core-http-netty-1.16.7.jar` | [azure-core-http-netty-1.16.7.jar](https://repo.maven.apache.org/maven2/com/azure/azure-core-http-netty/1.16.7/azure-core-http-netty-1.16.7.jar) |
| `azure-functions-java-core-library-1.3.0.jar` | [azure-functions-java-core-library-1.3.0.jar](https://repo.maven.apache.org/maven2/com/microsoft/azure/functions/azure-functions-java-core-library/1.3.0/azure-functions-java-core-library-1.3.0.jar) |
| `azure-functions-java-library-3.3.0.jar` | [azure-functions-java-library-3.3.0.jar](https://repo.maven.apache.org/maven2/com/microsoft/azure/functions/azure-functions-java-library/3.3.0/azure-functions-java-library-3.3.0.jar) |
| `azure-identity-1.18.6.jar` | [azure-identity-1.18.6.jar](https://repo.maven.apache.org/maven2/com/azure/azure-identity/1.18.6/azure-identity-1.18.6.jar) |
| `azure-json-1.5.1.jar` | [azure-json-1.5.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-json/1.5.1/azure-json-1.5.1.jar) |
| `azure-storage-blob-12.35.1.jar` | [azure-storage-blob-12.35.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-storage-blob/12.35.1/azure-storage-blob-12.35.1.jar) |
| `azure-storage-common-12.34.1.jar` | [azure-storage-common-12.34.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-storage-common/12.34.1/azure-storage-common-12.34.1.jar) |
| `azure-storage-internal-avro-12.20.1.jar` | [azure-storage-internal-avro-12.20.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-storage-internal-avro/12.20.1/azure-storage-internal-avro-12.20.1.jar) |
| `azure-storage-queue-12.30.1.jar` | [azure-storage-queue-12.30.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-storage-queue/12.30.1/azure-storage-queue-12.30.1.jar) |
| `azure-xml-1.2.1.jar` | [azure-xml-1.2.1.jar](https://repo.maven.apache.org/maven2/com/azure/azure-xml/1.2.1/azure-xml-1.2.1.jar) |
| `commons-codec-1.20.0.jar` | [commons-codec-1.20.0.jar](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.20.0/commons-codec-1.20.0.jar) |
| `commons-collections4-4.5.0.jar` | [commons-collections4-4.5.0.jar](https://repo.maven.apache.org/maven2/org/apache/commons/commons-collections4/4.5.0/commons-collections4-4.5.0.jar) |
| `commons-compress-1.28.0.jar` | [commons-compress-1.28.0.jar](https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar) |
| `commons-io-2.21.0.jar` | [commons-io-2.21.0.jar](https://repo.maven.apache.org/maven2/commons-io/commons-io/2.21.0/commons-io-2.21.0.jar) |
| `commons-lang3-3.18.0.jar` | [commons-lang3-3.18.0.jar](https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.jar) |
| `commons-math3-3.6.1.jar` | [commons-math3-3.6.1.jar](https://repo.maven.apache.org/maven2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar) |
| `curvesapi-1.08.jar` | [curvesapi-1.08.jar](https://repo.maven.apache.org/maven2/com/github/virtuald/curvesapi/1.08/curvesapi-1.08.jar) |
| `jackson-annotations-2.22.jar` | [jackson-annotations-2.22.jar](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar) |
| `jackson-core-2.22.2.jar` | [jackson-core-2.22.2.jar](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.2/jackson-core-2.22.2.jar) |
| `jackson-databind-2.22.2.jar` | [jackson-databind-2.22.2.jar](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.22.2/jackson-databind-2.22.2.jar) |
| `jackson-datatype-jsr310-2.22.2.jar` | [jackson-datatype-jsr310-2.22.2.jar](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.22.2/jackson-datatype-jsr310-2.22.2.jar) |
| `jna-5.13.0.jar` | [jna-5.13.0.jar](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar) |
| `jna-platform-5.17.0.jar` | [jna-platform-5.17.0.jar](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar) |
| `log4j-api-2.24.3.jar` | [log4j-api-2.24.3.jar](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-api/2.24.3/log4j-api-2.24.3.jar) |
| `msal4j-1.23.1.jar` | [msal4j-1.23.1.jar](https://repo.maven.apache.org/maven2/com/microsoft/azure/msal4j/1.23.1/msal4j-1.23.1.jar) |
| `msal4j-persistence-extension-1.3.0.jar` | [msal4j-persistence-extension-1.3.0.jar](https://repo.maven.apache.org/maven2/com/microsoft/azure/msal4j-persistence-extension/1.3.0/msal4j-persistence-extension-1.3.0.jar) |
| `netty-buffer-4.1.137.Final.jar` | [netty-buffer-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-buffer/4.1.137.Final/netty-buffer-4.1.137.Final.jar) |
| `netty-codec-4.1.137.Final.jar` | [netty-codec-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-codec/4.1.137.Final/netty-codec-4.1.137.Final.jar) |
| `netty-codec-dns-4.1.135.Final.jar` | [netty-codec-dns-4.1.135.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-codec-dns/4.1.135.Final/netty-codec-dns-4.1.135.Final.jar) |
| `netty-codec-http-4.1.137.Final.jar` | [netty-codec-http-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-codec-http/4.1.137.Final/netty-codec-http-4.1.137.Final.jar) |
| `netty-codec-http2-4.1.137.Final.jar` | [netty-codec-http2-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-codec-http2/4.1.137.Final/netty-codec-http2-4.1.137.Final.jar) |
| `netty-codec-socks-4.1.137.Final.jar` | [netty-codec-socks-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.1.137.Final/netty-codec-socks-4.1.137.Final.jar) |
| `netty-common-4.1.137.Final.jar` | [netty-common-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-common/4.1.137.Final/netty-common-4.1.137.Final.jar) |
| `netty-handler-4.1.137.Final.jar` | [netty-handler-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-handler/4.1.137.Final/netty-handler-4.1.137.Final.jar) |
| `netty-handler-proxy-4.1.137.Final.jar` | [netty-handler-proxy-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-handler-proxy/4.1.137.Final/netty-handler-proxy-4.1.137.Final.jar) |
| `netty-resolver-4.1.137.Final.jar` | [netty-resolver-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-resolver/4.1.137.Final/netty-resolver-4.1.137.Final.jar) |
| `netty-resolver-dns-4.1.135.Final.jar` | [netty-resolver-dns-4.1.135.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns/4.1.135.Final/netty-resolver-dns-4.1.135.Final.jar) |
| `netty-resolver-dns-classes-macos-4.1.135.Final.jar` | [netty-resolver-dns-classes-macos-4.1.135.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-classes-macos/4.1.135.Final/netty-resolver-dns-classes-macos-4.1.135.Final.jar) |
| `netty-resolver-dns-native-macos-4.1.135.Final-osx-x86_64.jar` | [netty-resolver-dns-native-macos-4.1.135.Final-osx-x86_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-native-macos/4.1.135.Final/netty-resolver-dns-native-macos-4.1.135.Final-osx-x86_64.jar) |
| `netty-tcnative-boringssl-static-2.0.81.Final-linux-aarch_64.jar` | [netty-tcnative-boringssl-static-2.0.81.Final-linux-aarch_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.81.Final/netty-tcnative-boringssl-static-2.0.81.Final-linux-aarch_64.jar) |
| `netty-tcnative-boringssl-static-2.0.81.Final-linux-x86_64.jar` | [netty-tcnative-boringssl-static-2.0.81.Final-linux-x86_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.81.Final/netty-tcnative-boringssl-static-2.0.81.Final-linux-x86_64.jar) |
| `netty-tcnative-boringssl-static-2.0.81.Final-osx-aarch_64.jar` | [netty-tcnative-boringssl-static-2.0.81.Final-osx-aarch_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.81.Final/netty-tcnative-boringssl-static-2.0.81.Final-osx-aarch_64.jar) |
| `netty-tcnative-boringssl-static-2.0.81.Final-osx-x86_64.jar` | [netty-tcnative-boringssl-static-2.0.81.Final-osx-x86_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.81.Final/netty-tcnative-boringssl-static-2.0.81.Final-osx-x86_64.jar) |
| `netty-tcnative-boringssl-static-2.0.81.Final-windows-x86_64.jar` | [netty-tcnative-boringssl-static-2.0.81.Final-windows-x86_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.81.Final/netty-tcnative-boringssl-static-2.0.81.Final-windows-x86_64.jar) |
| `netty-tcnative-boringssl-static-2.0.81.Final.jar` | [netty-tcnative-boringssl-static-2.0.81.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.81.Final/netty-tcnative-boringssl-static-2.0.81.Final.jar) |
| `netty-tcnative-classes-2.0.81.Final.jar` | [netty-tcnative-classes-2.0.81.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-classes/2.0.81.Final/netty-tcnative-classes-2.0.81.Final.jar) |
| `netty-transport-4.1.137.Final.jar` | [netty-transport-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-transport/4.1.137.Final/netty-transport-4.1.137.Final.jar) |
| `netty-transport-classes-epoll-4.1.137.Final.jar` | [netty-transport-classes-epoll-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-transport-classes-epoll/4.1.137.Final/netty-transport-classes-epoll-4.1.137.Final.jar) |
| `netty-transport-classes-kqueue-4.1.137.Final.jar` | [netty-transport-classes-kqueue-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-transport-classes-kqueue/4.1.137.Final/netty-transport-classes-kqueue-4.1.137.Final.jar) |
| `netty-transport-native-epoll-4.1.137.Final-linux-x86_64.jar` | [netty-transport-native-epoll-4.1.137.Final-linux-x86_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.137.Final/netty-transport-native-epoll-4.1.137.Final-linux-x86_64.jar) |
| `netty-transport-native-kqueue-4.1.137.Final-osx-x86_64.jar` | [netty-transport-native-kqueue-4.1.137.Final-osx-x86_64.jar](https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-kqueue/4.1.137.Final/netty-transport-native-kqueue-4.1.137.Final-osx-x86_64.jar) |
| `netty-transport-native-unix-common-4.1.137.Final.jar` | [netty-transport-native-unix-common-4.1.137.Final.jar](https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.137.Final/netty-transport-native-unix-common-4.1.137.Final.jar) |
| `poi-5.5.1.jar` | [poi-5.5.1.jar](https://repo.maven.apache.org/maven2/org/apache/poi/poi/5.5.1/poi-5.5.1.jar) |
| `poi-ooxml-5.5.1.jar` | [poi-ooxml-5.5.1.jar](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/5.5.1/poi-ooxml-5.5.1.jar) |
| `poi-ooxml-lite-5.5.1.jar` | [poi-ooxml-lite-5.5.1.jar](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml-lite/5.5.1/poi-ooxml-lite-5.5.1.jar) |
| `reactive-streams-1.0.4.jar` | [reactive-streams-1.0.4.jar](https://repo.maven.apache.org/maven2/org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4.jar) |
| `reactor-core-3.7.19.jar` | [reactor-core-3.7.19.jar](https://repo.maven.apache.org/maven2/io/projectreactor/reactor-core/3.7.19/reactor-core-3.7.19.jar) |
| `reactor-netty-core-1.2.18.jar` | [reactor-netty-core-1.2.18.jar](https://repo.maven.apache.org/maven2/io/projectreactor/netty/reactor-netty-core/1.2.18/reactor-netty-core-1.2.18.jar) |
| `reactor-netty-http-1.2.18.jar` | [reactor-netty-http-1.2.18.jar](https://repo.maven.apache.org/maven2/io/projectreactor/netty/reactor-netty-http/1.2.18/reactor-netty-http-1.2.18.jar) |
| `slf4j-api-1.7.36.jar` | [slf4j-api-1.7.36.jar](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar) |
| `xmlbeans-5.3.0.jar` | [xmlbeans-5.3.0.jar](https://repo.maven.apache.org/maven2/org/apache/xmlbeans/xmlbeans/5.3.0/xmlbeans-5.3.0.jar) |

## Static vector image support

The Office2MD image preprocessor additionally uses unmodified Apache Batik 1.19 for static SVG and Apache POI scratchpad 5.5.1 for EMF/WMF. Full upstream LICENSE and NOTICE files are retained per artifact; the XML APIs artifacts include their complete `license/` directories, including W3C and SAX notices. SVG scripting and external resource loading are disabled by the application.

| Runtime JAR | Original Maven Central artifact |
| --- | --- |
| `batik-anim-1.19.jar` | [batik-anim-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-anim/1.19/batik-anim-1.19.jar) |
| `batik-awt-util-1.19.jar` | [batik-awt-util-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-awt-util/1.19/batik-awt-util-1.19.jar) |
| `batik-bridge-1.19.jar` | [batik-bridge-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-bridge/1.19/batik-bridge-1.19.jar) |
| `batik-constants-1.19.jar` | [batik-constants-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-constants/1.19/batik-constants-1.19.jar) |
| `batik-css-1.19.jar` | [batik-css-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-css/1.19/batik-css-1.19.jar) |
| `batik-dom-1.19.jar` | [batik-dom-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-dom/1.19/batik-dom-1.19.jar) |
| `batik-ext-1.19.jar` | [batik-ext-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-ext/1.19/batik-ext-1.19.jar) |
| `batik-gvt-1.19.jar` | [batik-gvt-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-gvt/1.19/batik-gvt-1.19.jar) |
| `batik-i18n-1.19.jar` | [batik-i18n-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-i18n/1.19/batik-i18n-1.19.jar) |
| `batik-parser-1.19.jar` | [batik-parser-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-parser/1.19/batik-parser-1.19.jar) |
| `batik-script-1.19.jar` | [batik-script-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-script/1.19/batik-script-1.19.jar) |
| `batik-shared-resources-1.19.jar` | [batik-shared-resources-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-shared-resources/1.19/batik-shared-resources-1.19.jar) |
| `batik-svg-dom-1.19.jar` | [batik-svg-dom-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-svg-dom/1.19/batik-svg-dom-1.19.jar) |
| `batik-svggen-1.19.jar` | [batik-svggen-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-svggen/1.19/batik-svggen-1.19.jar) |
| `batik-transcoder-1.19.jar` | [batik-transcoder-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-transcoder/1.19/batik-transcoder-1.19.jar) |
| `batik-util-1.19.jar` | [batik-util-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-util/1.19/batik-util-1.19.jar) |
| `batik-xml-1.19.jar` | [batik-xml-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-xml/1.19/batik-xml-1.19.jar) |
| `commons-logging-1.3.0.jar` | [commons-logging-1.3.0.jar](https://repo.maven.apache.org/maven2/commons-logging/commons-logging/1.3.0/commons-logging-1.3.0.jar) |
| `poi-scratchpad-5.5.1.jar` | [poi-scratchpad-5.5.1.jar](https://repo.maven.apache.org/maven2/org/apache/poi/poi-scratchpad/5.5.1/poi-scratchpad-5.5.1.jar) |
| `xml-apis-1.4.01.jar` | [xml-apis-1.4.01.jar](https://repo.maven.apache.org/maven2/xml-apis/xml-apis/1.4.01/xml-apis-1.4.01.jar) |
| `xml-apis-ext-1.3.04.jar` | [xml-apis-ext-1.3.04.jar](https://repo.maven.apache.org/maven2/xml-apis/xml-apis-ext/1.3.04/xml-apis-ext-1.3.04.jar) |
| `xmlgraphics-commons-2.11.jar` | [xmlgraphics-commons-2.11.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/xmlgraphics-commons/2.11/xmlgraphics-commons-2.11.jar) |
| `batik-codec-1.19.jar` | [batik-codec-1.19.jar](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-codec/1.19/batik-codec-1.19.jar) |
