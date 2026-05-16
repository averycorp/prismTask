# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.9.x (current) | Yes |
| 1.8.x | Yes |
| < 1.8 | No |

## Overview

PrismTask is a cross-platform task manager consisting of a native Android app, a React web client, and a FastAPI backend. The app handles user credentials, task data, and optional cloud sync.

## Data Storage

- **Android:** Task data is stored on-device in a Room (SQLite) database. Optional cloud sync uses Firebase Firestore. Google Drive backup/restore is available for Pro subscribers.
- **Web:** All data is fetched from and persisted to the FastAPI backend. No sensitive data is stored in the browser beyond JWT tokens.
- **Backend:** User data is stored in a PostgreSQL database hosted on Railway. Passwords are hashed with bcrypt. Authentication uses JWT access tokens (short-lived) and refresh tokens.

## Network Communication

- All client-to-server communication uses HTTPS.
- The backend enforces CORS restrictions.
- JWT tokens are validated on every authenticated request.
- The NLP parsing feature sends user-provided text to the Anthropic API (Claude) for structured extraction. No personally identifiable information is sent beyond the task text itself.

## Authentication

- **Android:** Google Sign-In via Credential Manager, with Firebase Authentication.
- **Web:** Email/password registration and login with JWT-based sessions.
- **Backend:** JWT access tokens (15-minute expiry) with refresh token rotation (7-day expiry).

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public GitHub issue for security vulnerabilities.
2. Email the maintainer directly at the address listed in the repository's GitHub profile.
3. Include a description of the vulnerability, steps to reproduce, and potential impact.
4. Allow reasonable time for a fix before public disclosure.

We aim to acknowledge reports within 72 hours and provide a fix or mitigation plan within 30 days.
