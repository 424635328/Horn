# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 3.x | Yes |
| 2.x | Security fixes only, best effort |
| < 2.0 | No |

## Reporting a vulnerability

Please **do not** open a public issue for security vulnerabilities. Instead:

1. Use GitHub's [Private vulnerability reporting](https://github.com/424635328/Horn/security/advisories/new)
   for this repository, or contact the maintainer through the profile linked on the repository page.
2. Include a clear description, affected version/commit, reproduction steps and, if possible, a
   proof of concept.
3. You will receive an acknowledgement within 72 hours. We aim to publish a fix or mitigation
   within 14 days for high-severity issues.

## Scope notes

Horn renders all audio procedurally at runtime: it ships no sampled audio assets, performs no
network requests of its own, and stores only local preferences (DataStore). The Wear OS
communication path uses the on-device Wearable Data Layer between the paired phone and watch.

Typical in-scope findings include crashes or privilege issues reachable from untrusted input,
unsafe exported components, and insecure handling of the release signing pipeline. Reports about
the volume of the horn itself are, regrettably, out of scope.
