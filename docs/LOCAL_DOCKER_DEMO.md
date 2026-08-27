# RelayForge Local Docker Demo

This runbook starts the complete local Portfolio v1 workflow. Docker Compose builds one backend image and launches it twice: API mode serves browser/publisher requests, while worker mode polls PostgreSQL and dispatches outbound webhooks. The two processes never call each other.

## Prerequisites

- Docker Desktop with Linux containers.
- PowerShell 7 or Windows PowerShell 5.1 for the smoke script.
- Host ports `5432`, `8080`, `8081`, and `5173` available.

JDK, Maven, Node, and PostgreSQL do not need to be installed on the host for this Compose workflow.

## First-time configuration

From the repository root:

```powershell
Copy-Item .env.example .env
```

Replace every `replace-with-...` placeholder. The endpoint encryption key must be Base64URL for exactly 32 random bytes. One PowerShell way to generate it is:

```powershell
$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
```

`.env` is ignored by Git and excluded from every Docker build context. It is local development configuration, not a production secret-management mechanism.

## Start and inspect the stack

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f api worker receiver
```

Open:

- Dashboard: `http://localhost:5173`
- API: `http://localhost:8080`
- Receiver health: `http://localhost:8081/health`
- Receiver's bounded request history: `http://localhost:8081/requests`

Login with `RELAYFORGE_OWNER_LOGIN_NAME` and `RELAYFORGE_OWNER_PASSWORD` from the ignored `.env`.

The demo endpoint URL is intentionally `http://localhost:8081/webhooks/success`. Worker mode shares the receiver container's network namespace, so this address is actual loopback from the dispatching process. RelayForge's accepted development-only loopback rule remains intact; it is not expanded to arbitrary Docker-private addresses.

## Receiver modes

Create an endpoint using one of these URLs:

| URL | Receiver behavior | Expected delivery behavior |
| --- | --- | --- |
| `http://localhost:8081/webhooks/success` | Returns HTTP 200 | `SUCCEEDED` after one attempt |
| `http://localhost:8081/webhooks/fail` | Returns HTTP 500 | Persisted exponential retry; attempt five becomes `EXHAUSTED` |
| `http://localhost:8081/webhooks/slow` | Responds after 12 seconds | RelayForge's 10-second deadline produces a retryable timeout |

To make the receiver verify signatures, copy the endpoint's one-time `whsec_...` value and configure it without placing it in command history:

```powershell
$signingSecret = Read-Host 'Paste the one-time endpoint signing secret'
Invoke-WebRequest -UseBasicParsing -Method Put -ContentType 'text/plain' `
  -Uri 'http://localhost:8081/config/signing-secret' -Body $signingSecret
$signingSecret = $null
```

The receiver retains at most 100 observations in memory. It does not persist signing material or request history, and both disappear when the receiver container is recreated.

## Automated integrated smoke flow

With the stack running:

```powershell
./scripts/group12-smoke.ps1 -VerifyRestart
```

The script does not print raw API keys, endpoint signing secrets, passwords, CSRF tokens, or payload contents. It proves:

1. API and receiver become reachable;
2. owner login and CSRF-protected mutations work;
3. project, publisher API key, and loopback endpoint creation work;
4. the receiver verifies the exact v1 HMAC;
5. repeated publish with one idempotency key returns the original event;
6. worker processing reaches `SUCCEEDED` and owner history exposes the attempt;
7. the PostgreSQL-backed session and committed event survive API/worker restart.

## Stop, resume, and reset

Stop containers while retaining PostgreSQL data:

```powershell
docker compose down
```

The next `docker compose up -d` reuses the named volume.

To erase the local database deliberately:

```powershell
docker compose down --volumes
```

The `--volumes` command is destructive: projects, credentials, sessions, endpoints, events, deliveries, and attempts in the local Compose database cannot be recovered unless separately backed up.

## Boundaries

This stack is for local development and portfolio demonstration. It deliberately uses HTTP, non-secure cookies, an ignored `.env`, published database/receiver ports, and a local configuration endpoint on the receiver. Do not expose it to the public internet or reuse its settings for cloud deployment.
