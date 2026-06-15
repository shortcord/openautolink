# Third-Party Licenses

OpenAutoLink is licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)).

It incorporates and/or links against the following third-party components.
Each is used under its own license.

## Statically linked / incorporated

| Component | Origin | License |
|-----------|--------|---------|
| aasdk | https://github.com/f1xpl/aasdk (fork: https://github.com/mossyhub/aasdk, branch `openautolink`) | GPL-3.0-or-later |
| Boost (headers) | https://www.boost.org/ | Boost Software License 1.0 |
| OpenSSL | https://www.openssl.org/ | Apache-2.0 (OpenSSL 3.x) |
| Protocol Buffers (protobuf) | https://github.com/protocolbuffers/protobuf | BSD-3-Clause |
| Abseil | https://github.com/abseil/abseil-cpp | Apache-2.0 |

## Android / Kotlin runtime dependencies

Standard AndroidX, Jetpack Compose, Kotlin stdlib, and Google Play services
libraries are used under their respective licenses (Apache-2.0 unless noted in
each artifact's POM). See `app/build.gradle.kts` and `companion/build.gradle.kts`
for the resolved dependency set.

## Notes

- Because aasdk is GPL-3.0 and is statically linked into the app via JNI
  (`app/src/main/cpp/`), the combined work is distributed under
  GPL-3.0-or-later. This is the reason the project as a whole uses GPLv3.
- Boost, OpenSSL, protobuf, and Abseil are all GPL-3.0 compatible.
- Source for all GPL-licensed components is available at the URLs above and
  (for our aasdk fork) as a git submodule under `external/opencardev-aasdk/`.
