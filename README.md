# Salesforce JWT + Selenium login (passkey-free)

This project lets an automated Selenium test log into Salesforce **without a
human typing a password or touching a passkey/MFA prompt**. Instead of
driving the login screen, it authenticates "behind the scenes" over a plain
API call, then hands the browser an already-logged-in session.

If you're new to some of the terms used here (JWT, Connected App, passkey,
etc.), read the [Glossary](#glossary) first — everything below assumes you
know what those words mean.

## Why this exists (in plain language)

Salesforce now requires every interactive login to pass **MFA** (Multi-Factor
Authentication) — usually a **passkey**. That's great for real users, but a
Selenium test is a robot: it can't tap a phone or touch a security key. If
the automation tried to log in through the normal screen, it would get stuck
forever on the passkey prompt.

The fix used here: **never show the login screen at all.** The test proves
who it is using a signed digital "note" (the JWT) instead of a password, gets
back a token, and uses that token to open the browser directly onto an
already-authenticated page. Because the login screen is never rendered,
there's no passkey prompt to get stuck on — with one exception, explained
below.

## How it works

There are two moving parts: the **login** itself, and (for a brand-new user)
a one-time **passkey enrolment nudge** that Salesforce shows even when you
bridge in a session. The project handles both.

### 1. Logging in without a password (JWT Bearer flow)

```mermaid
sequenceDiagram
    participant T as Test script (JWTAuthManager)
    participant K as Private key (local file, never sent anywhere)
    participant SF as Salesforce token endpoint
    participant CA as Connected App (holds the matching public certificate)
    participant B as Browser (ChromeDriver)

    Note over T,CA: Steps 1-4: plain HTTP, no browser, no login screen at all
    T->>T: 1. Build a JWT: "I am this app, acting as this user, valid for 3 min"
    T->>K: 2. Sign the JWT with the private key
    T->>SF: 3. POST the signed JWT (grant_type = jwt-bearer)
    SF->>CA: Check the signature against the public certificate on file
    CA-->>SF: Signature matches, user is pre-authorized
    SF-->>T: 4. access_token + instance_url

    Note over T,B: Step 5: Selenium only shows up now, with a valid pass already in hand
    T->>B: 5. driver.get(frontdoor URL carrying the access_token)
    B->>SF: Open frontdoor.jsp / singleaccess with the token
    SF-->>B: Session cookie set — browser is now logged in
```

**Why this skips the passkey:** a passkey/MFA challenge only exists on the
interactive login *form*. This flow never opens that form — Salesforce
verifies the JWT's signature instead of a password, which is a completely
different, non-interactive check.

### 2. The one-time "Create a Passkey" nudge

The first time a brand-new automation user lands in an authenticated browser
session, Salesforce notices they have **no verification method on file at
all** and shows a "Create a Passkey" enrolment page — even though the login
itself never touched the login form. Selenium can't tap a security key
either, so this would normally get stuck too. The project works around it
using Selenium's **virtual authenticator**: a fake, software-only passkey
device that Chrome treats as real.

```mermaid
sequenceDiagram
    participant B as Browser (ChromeDriver)
    participant VA as Virtual authenticator (fake, software-only passkey)
    participant SF as Salesforce

    B->>SF: Open the frontdoor URL from step 5 above
    SF-->>B: Redirect to "Create a Passkey" (user has no verification method yet)
    B->>B: Selenium clicks the "Create Passkey" / "Continue" button
    B->>VA: Chrome asks the registered authenticator to create a credential
    VA-->>B: Credential created instantly — no human, no device, no prompt
    B->>SF: Browser sends the new credential back to Salesforce
    SF-->>B: Enrolment accepted, redirect on to the real home screen
```

Once a user has enrolled once, Salesforce doesn't ask again on later runs
(confirmed by re-running the suite with a brand-new, empty virtual
authenticator — it went straight to the home screen). So this enrolment step
only ever fires once per user, the first time they're used.

## Glossary

Plain-language explanations of the terms used throughout this README and the
code:

| Term | What it actually means |
|---|---|
| **JWT** (JSON Web Token) | A small, signed piece of text that says "I am X, acting as Y, valid until Z." Anyone can read it, but only the holder of the private key could have signed it — so a valid signature proves who sent it, without a password. |
| **Connected App** / **External Client App** | A Salesforce-side registration that says "this application is allowed to talk to us." It holds the public certificate used to check the JWT's signature, and the list of OAuth scopes (permissions) it's allowed to request. |
| **Consumer Key** | The Connected App's public ID number. Goes in the JWT as `iss` (issuer) — "which app is asking." |
| **Private key / Public certificate (cert)** | A matched pair. The **private key** stays only on the machine running the tests and is used to *sign* the JWT — treat it like a password, never commit it. The **public certificate** is uploaded to the Connected App and is used to *check* that signature — safe to share. |
| **OAuth scope** (`api`, `web`, `full`) | What the resulting access token is allowed to do. `api` alone only allows REST/API calls — it **cannot** open a logged-in browser session. `web`/`full` are needed for that. |
| **frontdoor.jsp / Single Access UI Bridge (`singleaccess`)** | The Salesforce endpoint(s) that turn an API access token into a real, cookie-based browser session — the step that lets a UI test "walk in" already logged in. |
| **Passkey / MFA / WebAuthn** | Salesforce's mandatory extra proof-of-identity step for interactive logins, usually a fingerprint, device PIN, or security key. WebAuthn is the underlying web standard passkeys use. |
| **Virtual authenticator** | A Selenium/Chrome feature (via the Chrome DevTools Protocol) that acts as a fake, software passkey device inside the test browser. It answers WebAuthn requests automatically, so a robot can pass a passkey ceremony that would otherwise need a human. |
| **`must change password`** flag | A per-user Salesforce setting that forces a password reset screen on the next session — fires regardless of how the session started, including this JWT-bridged one, if the flag is set. |

## Project layout

```
sf-jwt-selenium/
├── pom.xml
├── testng.xml
├── src/main/java/com/hyde/sfauth/
│   ├── SalesforceConfig.java     # login host, consumer key, username, key path
│   └── JWTAuthManager.java       # JWT auth + frontdoor URL (with fallback)
└── src/test/java/com/hyde/sfauth/
    └── SalesforceLoginTest.java  # registers the virtual authenticator, opens
                                   # the home screen, clicks through passkey
                                   # enrolment if it appears, confirms, waits
```

## Prerequisites

1. **Java 17+** and **Maven** installed. **Google Chrome** installed
   (the virtual authenticator only works in Chrome/Edge, not Firefox/Safari).
2. A **Connected App** configured for JWT:
   - "Use digital signatures" enabled, with your **public certificate**
     uploaded.
   - **Selected OAuth Scopes must include a UI-capable scope** — `web`
     (*Access the Salesforce API Platform*) or `full` (*Full access*) — in
     addition to `api`. Without this the frontdoor step bounces to the login
     screen.
   - OAuth policy: **Admin approved users are pre-authorized**, with every
     automation/test user assigned via a permission set.
3. The matching **private key** PEM file (`-----BEGIN PRIVATE KEY-----`) on
   disk — never commit this file (see `.gitignore`).
4. Each test user must have a **permanent, non-expiring password** set once
   by an admin (clears the "must change password" flag — see
   [Troubleshooting](#troubleshooting)). They do **not** need to enroll a
   passkey manually; the test does that for them the first time it runs.

## Testing as a different user (e.g. an "agent" persona)

You do **not** need a new Connected App or a new key pair per user. One
Connected App + key pair can authenticate any number of users — the identity
being tested is just the `sub` (subject) claim inside the JWT, i.e.
`SF_USERNAME`.

To add a new test user:

1. **Pre-authorize them** against the same Connected App — assign them the
   same permission set used for "Admin approved users are pre-authorized."
   Skipping this gives `invalid_grant: user hasn't approved this consumer`.
2. Point `SF_USERNAME` at the new user and re-run. Everything else
   (`SF_CONSUMER_KEY`, `SF_PRIVATE_KEY`, `SF_AUDIENCE`) stays the same.
3. Expect the one-time passkey enrolment screen to appear again for *this*
   user specifically, the first time — it's per-user, not per-app. The
   existing code already handles it.

Only create a separate Connected App/key pair if you deliberately want
isolation between personas (different scopes, or so revoking one doesn't
affect the other) — not required just to test a second user.

## Configure

Either set environment variables (recommended) or edit the fallbacks in
`SalesforceConfig.java`.

```bash
export SF_AUDIENCE="https://test.salesforce.com"      # or your My Domain URL
export SF_CONSUMER_KEY="3MVG9..."
export SF_USERNAME="svc-testauto@yourco.com.uat"
export SF_PRIVATE_KEY="/secure/path/jwt_testauto.key"
# optional:
export SF_START_PATH="/lightning/page/home"
export SF_HEADLESS="false"        # false = watch the browser
export SF_CONFIRM_WAIT="20"       # seconds to hold the home screen open
```

On Windows PowerShell use `setx` or `$env:SF_CONSUMER_KEY="..."` for the
session.

## Run

```bash
mvn test
```

Chrome opens, registers the virtual authenticator, lands on the frontdoor
URL, clicks through the passkey enrolment screen if it appears, confirms it
reached the real Salesforce home screen, prints the URL and page title,
holds for `SF_CONFIRM_WAIT` seconds so you can see it, then closes.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `JWT auth failed [400] ... invalid_grant: user hasn't approved this consumer` | Pre-authorization or permission-set assignment missing on the Connected App for this user |
| `JWT auth failed ... invalid assertion` / signature error | Wrong private key, or `SF_AUDIENCE` doesn't match the token host |
| Auth succeeds but browser sits on the **login screen** | Connected App missing the `web`/`full` scope — the token isn't UI-capable |
| `singleaccess` returns 404/400, code falls back to frontdoor.jsp | `singleaccess` only works on **My Domain / Experience Cloud** hosts — point `SF_AUDIENCE` at your My Domain URL |
| Browser lands on a **password reset / Change Password** screen | The user's `must change password` flag is set (fresh/admin-reset account) or their password expired under org policy. As an admin, set a real password once and mark the user's profile "User passwords expire in: Never" so it doesn't recur |
| Browser lands on **"Create a Passkey"** and `mvn test` times out after 30s | `clickPasskeyCreateButton()` couldn't find a matching button label. Run with `SF_HEADLESS=false` (default), watch what the button actually says, and add that exact label to the list in `SalesforceLoginTest.clickPasskeyCreateButton()` |
| Passkey screen reappears on every run instead of just the first | The enrolled credential was revoked/reset on the Salesforce side (e.g. in Setup), not a code issue — re-enrolment via the virtual authenticator will just happen again automatically |
| Chrome/driver mismatch | Update Chrome; Selenium 4 auto-provisions the driver via Selenium Manager |

## Reuse in your suite

`JWTAuthManager.buildLoginUrl(path)` returns a ready-to-open URL. Call it from
a `@BeforeMethod` (fresh session per test) or `@BeforeClass` (one session for
the class), register a virtual authenticator on the driver before navigating
(see `SalesforceLoginTest.setUp()`), and `driver.quit()` in the matching
teardown.
