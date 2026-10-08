# Public pre-release APK signing key

This directory intentionally contains the **private** APK signing keystore used for public GitHub pre-release builds.

It is **not secret**. Anyone who clones the repository can sign an APK with the same Android package signature.

Purpose: provide a stable signature so automated pre-release APKs can update one another without requiring GitHub Secrets.

Security consequence: this signature must **not** be treated as proof that an APK came from the project owner. Anyone can produce a same-package, same-signature APK. If a trusted production distribution is ever desired, change the application ID and use a private signing key kept outside the public repository.

Bundled identity:

- Keystore: `cliproxyapi-public-prerelease.p12`
- Type: PKCS#12
- Alias: `cliproxyapi-public`
- Store password: `cliproxyapi-public-prerelease`
- Key password: `cliproxyapi-public-prerelease`
- Key algorithm: RSA 4096
- Signature algorithm: SHA256withRSA

The public certificate and human-readable certificate details are committed alongside the keystore.
