# Salesforce JWT + Selenium login (passkey-free)

Logs Selenium into Salesforce **without touching the login screen**, so the
passkey / MFA prompt never appears. It uses the OAuth 2.0 JWT Bearer flow
(server-to-server) to get an access token, then the Single Access UI Bridge API
(`/services/oauth2/singleaccess`) to bridge that token into an authenticated
browser session.

## Project layout

```
sf-jwt-selenium/
├── pom.xml
├── testng.xml
├── src/main/java/com/hyde/sfauth/
│   ├── SalesforceConfig.java     # login host, consumer key, username, key path
│   └── JWTAuthManager.java       # JWT auth + frontdoor URL (with fallback)
└── src/test/java/com/hyde/sfauth/
    └── SalesforceLoginTest.java  # opens the home screen, confirms, waits
```

## Prerequisites

1. **Java 17+** and **Maven** installed. **Google Chrome** installed.
2. A **Connected App** configured for JWT:
   - "Use digital signatures" enabled, with your **public cert** uploaded.
   - **Selected OAuth Scopes must include a UI-capable scope** — `web`
     (*Access the Salesforce API Platform*) or `full` (*Full access*) — in
     addition to `api`. Without this the frontdoor step bounces to the login
     screen.
   - OAuth policy: **Admin approved users are pre-authorized**, and the
     automation user assigned via a permission set.
3. The matching **private key** PEM file (`-----BEGIN PRIVATE KEY-----`) on disk.

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

On Windows PowerShell use `setx` or `$env:SF_CONSUMER_KEY="..."` for the session.

## Run

```bash
mvn test
```

Chrome opens, lands on the Salesforce home screen already logged in, prints the
URL and page title, holds for `SF_CONFIRM_WAIT` seconds so you can see it, then
closes.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `JWT auth failed [400] ... invalid_grant: user hasn't approved this consumer` | Pre-authorization or permission-set assignment missing on the Connected App |
| `JWT auth failed ... invalid assertion` / signature error | Wrong private key, or `SF_AUDIENCE` doesn't match the token host |
| Auth succeeds but browser sits on the **login screen** | Connected App missing the `web`/`full` scope — the token isn't UI-capable |
| `singleaccess` returns 404/400, code falls back to frontdoor.jsp | `singleaccess` only works on **My Domain / Experience Cloud** hosts — point `SF_AUDIENCE` at your My Domain URL |
| Chrome/driver mismatch | Update Chrome; Selenium 4 auto-provisions the driver via Selenium Manager |

## Reuse in your suite

`JWTAuthManager.buildLoginUrl(path)` returns a ready-to-open URL. Call it from a
`@BeforeMethod` (fresh session per test) or `@BeforeClass` (one session for the
class), and `driver.quit()` in the matching teardown.
