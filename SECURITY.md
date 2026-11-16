# Security Policy

> English · [中文](SECURITY.zh-CN.md)

PocketSim takes security seriously. This document describes how to report a security vulnerability and how we handle it.

## Reporting Scope

We accept security reports of the following types:

- Arbitrary code execution, privilege escalation, or data leakage within the app
- Solver crashes / denial of service (e.g., a crafted model causing OOM or an infinite loop)
- Injection, authorization bypass, or credential leakage in the AI interaction chain
- Risks of tampering with signatures / update packages
- Known vulnerabilities in third-party dependencies (Chaquopy, scikit-fem, SciPy, NumPy, etc.)

## How to Report

**Please do not** disclose security vulnerability details in public issues, to avoid exploitation. Use one of the following private channels:

1. **GitHub Private Vulnerability Reporting** (recommended):
   Repository → **Security** → **Report a vulnerability**, and fill in the template.
2. **Email**: send to the security contact address published in the repository's About section, with the subject prefix `[SECURITY]`.

Please include as much of the following as possible:

- Affected version number and build channel
- Steps to reproduce (a minimized model JSON or action sequence)
- Expected behavior vs. actual behavior
- Potential impact assessment (optional)

## Handling Process

| Stage | Timeframe | Description |
|---|---|---|
| Acknowledgement | 2 business days | Reply to the reporter, assign an internal tracking ID |
| Assessment & reproduction | 7 business days | Determine severity, confirm validity |
| Fix | Depends on severity | High severity ≤ 30 days, medium ≤ 90 days |
| Public disclosure | After the fix is released | Agree on disclosure timing with the reporter, credit the reporter |

## Disclaimer

This project is provided "as is" without any express or implied warranties, per AGPL-3.0 Sections 15–17.
Security fixes only cover the latest supported version; older versions are not guaranteed to receive patches.

## Known Non-Issues (Out of Scope)

- Engineering accuracy errors in simulation results (a functional boundary; see the README disclaimer)
- Leakage of user-provided API keys (BYOK) — keys are held by the user and never stored by the app
- A compromised security model after the physical device is rooted / jailbroken
- Non-security bugs in third-party dependencies (please report them upstream directly)
