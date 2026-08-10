# RelayForge Portfolio v1 Security Baseline

Status: Phase 0 baseline
Last updated: 2026-08-10

## 1. Purpose and threat boundary

This document chooses the minimum concrete security behavior required before implementation:

- owner authentication and browser session protection;
- publisher API-key format, persistence, and verification;
- project ownership authorization;
- endpoint signing-secret protection and outbound HMAC;
- SSRF-resistant destination connection;
- logging, payload, response-preview, and operational-secret handling.

Portfolio v1 is a temporary portfolio deployment, not a public multi-tenant SaaS. That reduces onboarding and abuse scope but does not relax ownership, credential, SSRF, or secret-handling requirements.

## 2. Owner authentication

### 2.1 Mechanism

The owner dashboard uses Spring Security with server-side sessions persisted through Spring Session JDBC in PostgreSQL.

This is chosen over JWT because:

- the browser needs one first-party authenticated dashboard, not delegated API access;
- logout and session invalidation remain server-controlled;
- no access/refresh-token rotation protocol is required;
- API instances can share sessions without Redis;
- the project already operates PostgreSQL.

Spring Session infrastructure tables are technical persistence owned by runtime/security configuration, not a fifth business module. Their migrations remain an implementation detail.

### 2.2 Password storage

- Bootstrap owner passwords use BCrypt with initial cost 12.
- The encoded hash is self-describing and stored in `owner_accounts.password_hash`.
- Plaintext password exists only during bootstrap input or login verification and is never logged or persisted.
- Bootstrap seed does not overwrite an existing hash silently.
- BCrypt cost is benchmarked on the target container before deployment; change it if login verification is trivially cheap or creates unacceptable CPU/latency.

Phase 1 credential verification now returns the same empty application outcome for an unknown login and a wrong password. Canonical unknown logins still perform the database lookup, and absent/malformed users still execute one BCrypt comparison against an in-memory dummy cost-12 hash. This mitigates the dominant missing-BCrypt timing difference but does not claim perfectly constant-time end-to-end authentication. Password verification occurs after the short credential-read transaction closes.

Phase 1 composes the `AuthenticationProvider` and browser-authentication adapter only in API mode. The provider delegates username/password verification to the identity public contract; on success it uses the hash-free owner ID and canonical login as the principal, and an empty identity result becomes one generic bad-credentials failure. The API filter chain is deny-by-default for `/api/v1/**`, permits only the four accepted authentication routes at this stage, explicitly saves the server-side security context after login, and disables framework form/basic login behavior.

The packaged launcher switches a worker to a non-web application context after Spring prepares configuration and before it creates the context. This matters because the shared artifact contains servlet, Spring Security, and Spring Session dependencies: worker mode must not fall back to Boot's default web security/session behavior merely because those libraries are present.


### 2.3 Session cookie and lifecycle

- Cookie name: `RF_SESSION`.
- `HttpOnly` always.
- `Secure` in production; development may disable it only for explicit local HTTP.
- `SameSite=Lax`, path `/`, no broad parent-domain scope.
- Idle timeout: 30 minutes.
- Authentication rotates the session identifier, preventing session fixation.
- Logout invalidates the server session and clears the cookie.
- Session IDs, cookies, and session attributes never appear in logs or API bodies.

### 2.4 CSRF and CORS

Every owner mutation, including login and logout, requires a CSRF token delivered by the dedicated CSRF endpoint and echoed in `X-CSRF-TOKEN`.

- The CSRF token is not an authentication credential but is still redacted from logs.
- Publisher Bearer-key requests do not use cookie authentication and are not subject to browser CSRF state.
- Production CORS allowlist contains only the configured dashboard origin and allows credentials.
- Wildcard origin with credentials is forbidden.
- Preflight does not expose authorization or secret headers beyond the explicit contract.

## 3. Publisher API keys

### 3.1 Token format and generation

Initial token format:

```text
rf_live_<public-uuid>.<base64url-secret>
```

- secret is 32 cryptographically random bytes encoded base64url without padding;
- public UUID selects the credential row without scanning secrets;
- `key_hint` displays a nonsecret shortened public identifier;
- raw token is returned once at creation and never recoverable afterward.

### 3.2 Persistence and verification

- Store `HMAC-SHA-256(key = server pepper, message = raw secret bytes)` as `secret_digest`.
- Store neither raw token nor raw secret.
- Pepper is supplied through environment/secret management and is not stored in PostgreSQL or Git.
- Verification parses the prefix/UUID, loads the one key record, computes the digest, and compares fixed-length bytes in constant time.
- Invalid format, missing key, digest mismatch, and revoked key return the same generic 401 contract externally.
- A valid key authorizes exactly its stored project; a different path project returns 403.
- Revocation is immediate for new publish authentication and irreversible in v1.

Pepper rotation is deferred because v1 stores no digest-version column. A later rotation design must support overlap or controlled key replacement rather than silently invalidating every publisher.

## 4. Authorization and IDOR prevention

- Owner identity comes only from the authenticated server session, never a request body/header owner ID.
- Every owner query/mutation scopes repository access by both owner and project identity.
- Child resource access verifies project scope in the same query or transaction; fetching by child UUID and checking in the controller afterward is insufficient.
- Cross-owner project, key, endpoint, event, delivery, and attempt access returns 404.
- API key records, endpoint signing material, claim tokens, leases, and password hashes are never mapped into general response DTOs.
- Worker scheduling adapters have no owner/publisher HTTP controllers.
- Authorization tests include valid UUIDs copied from another owner, not only random missing IDs.

Database foreign keys protect integrity but do not replace application authorization.

## 5. Endpoint signing-secret protection

### 5.1 Secret generation and visibility

- Generate 32 cryptographically random bytes per endpoint.
- Present `whsec_<base64url-raw-secret>` only in the successful endpoint-creation response; signing and verification decode the suffix back to the raw 32 bytes.
- The logical v1 signing secret is immutable.
- If the one-time response is lost, disable that endpoint and create a replacement; there is no recovery/rotation endpoint.

### 5.2 Encryption at rest

Endpoint signing secrets must be recoverable for outbound HMAC, so they use authenticated encryption rather than a digest.

- A module-owned `SecretCipher` port separates domain/application behavior from the key provider.
- Stored envelope contains format version, external key reference, random nonce, ciphertext, and authentication tag.
- Endpoint and project identifiers are authenticated additional data, preventing ciphertext from being copied to another endpoint context.
- Local/test implementation may use AES-256-GCM with a 32-byte key supplied outside Git.
- Cloud key custody is chosen in the deployment slice; the master/data-encryption key never shares the endpoint table.
- Decryption failure stops the attempt before network I/O, records a bounded internal failure classification, and never logs ciphertext/plaintext.
- Infrastructure key rotation may re-encrypt the same logical secret through a controlled path without changing receiver-visible secret material.

## 6. Outbound HMAC contract

### 6.1 Body and headers

RelayForge serializes the outbound JSON body once to UTF-8 bytes. The exact bytes sent are the bytes hashed.

Headers include event, delivery, attempt, attempt number, Unix timestamp seconds, and signature as defined by the API contract.

Canonical UTF-8 string uses one LF byte between fields and no trailing LF:

```text
v1
<timestamp-seconds>
<event-id>
<delivery-id>
<attempt-id>
<lowercase-hex-sha256-of-body>
```

Signature uses the raw 32-byte endpoint secret represented by the one-time `whsec_` value:

```text
base64url_without_padding(HMAC-SHA-256(secret, canonical-string-bytes))
```

Header value:

```text
X-RelayForge-Signature: v1=<signature>
```

### 6.2 Receiver verification guidance

The demo receiver must:

1. read raw body bytes before JSON reserialization;
2. reject unsupported signature version;
3. rebuild the canonical string from headers and raw-body digest;
4. compare signature bytes in constant time;
5. reject timestamps outside an initial five-minute tolerance;
6. deduplicate business effects using stable delivery ID or event ID according to receiver semantics.

Timestamp checking reduces replay exposure but cannot prohibit RelayForge's valid at-least-once retries. Automatic retries keep delivery identity and create new attempt identity/timestamp/signature. Manual replay creates a new delivery identity.

## 7. SSRF-resistant destination handling

### 7.1 Configuration-time validation

Before endpoint persistence:

- parse as an absolute URI;
- reject user-info and fragments;
- production accepts only `https`;
- development local HTTP requires an explicit development-only flag;
- require a hostname or IP literal and a valid port;
- reject syntactically invalid, oversized, or unsupported representations.

Configuration-time validation improves feedback but is never treated as sufficient because DNS and network state can change.

### 7.2 Attempt-time validation and connection pinning

Immediately before every production connection:

1. use the URL snapshot committed at attempt start;
2. resolve every A and AAAA result within the outer dispatch deadline;
3. normalize IPv4, IPv6, and IPv4-mapped IPv6 forms;
4. reject the entire attempt if any result is loopback, private, link-local, multicast, unspecified, reserved, documentation/test space, carrier-grade NAT, or cloud-metadata space;
5. select only from the validated public addresses;
6. connect to the selected validated address without a second unconstrained DNS resolution;
7. preserve the original hostname for HTTP `Host`, TLS SNI, and certificate hostname verification;
8. disable redirects completely.

This resolve-validate-pin behavior closes the DNS-rebinding gap between validation and connect. The concrete Java HTTP client must demonstrate that the actual socket target is the validated address; a preflight DNS check followed by a normal hostname connection is insufficient.

Any prohibited destination ends the already-started dispatch attempt as permanent failure without network I/O. Resolution timeout/failure is retryable. Security-validation detail exposed to owners is bounded and never includes internal interface or resolver data.

## 8. Input, payload, and response safety

- Publish body is limited to 64 KiB before JSON parsing.
- Event type, names, URL, idempotency key, and cursor lengths are bounded before persistence/query work.
- JSON nesting depth and parser constraints use conservative bounded configuration to resist pathological input.
- Endpoint response body read stops after the 8 KiB preview plus one-byte truncation detection; the rest is not buffered.
- Response preview is stored as bytes, escaped/safely rendered in the dashboard, owner-scoped, and omitted from list APIs.
- Response headers are not persisted in v1; `Set-Cookie`, authorization, and other receiver secrets therefore cannot leak through history.
- Error responses do not reflect event payload, credentials, signing material, internal SQL, or stack traces.

## 9. Logging and observability redaction

Safe correlation fields:

- trace ID;
- project, endpoint, event, delivery, and attempt UUID;
- attempt number and bounded outcome code;
- HTTP status, duration, retry number, and destination fingerprint;
- runtime mode and worker instance identifier.

Never log:

- password or password hash;
- session ID, cookie, CSRF token, Authorization header, raw API key, digest, or pepper;
- signing-secret plaintext, ciphertext, key reference, or HMAC header;
- full event payload or full receiver response;
- full destination URL, query string, or user-info;
- database connection string or cloud credentials.

Structured logging uses explicit safe fields rather than serializing request/response/entity objects. Representative automated tests capture logs and search for seeded secret markers.

## 10. Browser and transport headers

Production HTTPS termination must provide:

- HSTS after HTTPS is confirmed end to end;
- `X-Content-Type-Options: nosniff`;
- `Referrer-Policy: no-referrer` for the dashboard;
- clickjacking protection through CSP `frame-ancestors 'none'`;
- a restrictive dashboard Content Security Policy refined when the React asset pipeline exists;
- no caching of login, key-creation, endpoint-secret-creation, or attempt-preview responses containing sensitive material.

TLS policy and certificate automation belong to cloud infrastructure, but production application URLs and secure-cookie behavior assume HTTPS.

## 11. Login abuse and rate-limiting scope

The bootstrap dashboard has no public registration, but login brute force still requires a bounded defense:

- initial per-API-process limiter allows five failed attempts per canonical login plus source IP per minute;
- source IP comes from the direct connection or an explicitly trusted proxy chain, never an arbitrary client-supplied forwarding header;
- limiter storage is bounded and expires inactive entries;
- exceeded attempts return generic 429 without confirming account existence;
- successful authentication clears that local failure bucket;
- metrics record rejected login attempts without logging login/password values.

This local limiter is defense in depth, not a cluster-wide guarantee. Redis is not added for it. A cloud edge/WAF or shared limiter is considered only if the demo becomes publicly exposed or measurements show distributed abuse.

Publisher quotas/rate limits are deferred because billing/public SaaS is out of scope. Bounded payloads, API-key revocation, worker permits, and backlog metrics remain mandatory; evidence of publisher abuse or database saturation can trigger a focused policy.

## 12. Secret and configuration custody

- Local secrets come from ignored environment/configuration, never committed files.
- Cloud secrets use the chosen provider's secret manager and least-privilege runtime identity.
- API and worker receive only secrets required by their runtime mode where practical.
- CI logs and build artifacts never print secrets.
- Frontend bundles contain no backend credential, API-key pepper, encryption key, endpoint secret, or privileged management URL.
- Sample `.env` documentation contains placeholders only.

## 13. Failure behavior

| Security failure | Behavior |
| --- | --- |
| Invalid owner credentials | Generic 401, session remains unauthenticated, failure limiter updated. |
| Invalid/revoked API key | Generic 401; no event transaction begins. |
| Valid API key for another path project | 403; no project/event details exposed. |
| CSRF failure | 403; no mutation transaction begins. |
| Signing-key provider temporarily unavailable | No network request; retryable attempt outcome and operator signal. |
| Signing-secret authenticated-decryption failure | No network request; permanent internal attempt failure and high-priority operator alert because retry cannot repair corrupted ciphertext. |
| Destination resolves to any prohibited address | Permanent attempt failure; no network request. |
| DNS changes after configuration | Every attempt re-resolves and validates; actual socket remains pinned to a validated address. |
| Receiver signature mismatch | Receiver rejects; RelayForge classifies returned status by normal HTTP rules. |
| Log serialization encounters an entity/request | Logging policy rejects or sanitizes rather than relying on ad-hoc string redaction. |

## 14. Required future security evidence

Automated tests must eventually prove:

1. login rotates session, logout invalidates it, and session survives API restart through JDBC storage;
2. every owner mutation rejects missing/invalid CSRF;
3. CORS rejects unapproved origins and never combines wildcard origin with credentials;
4. password, raw API key, endpoint secret, session, CSRF, and seeded payload markers are absent from representative logs;
5. raw API key is not recoverable from persistence and revoked key cannot publish;
6. API-key verification uses one selected record and constant-time digest comparison;
7. cross-owner access returns 404 for every scoped resource type;
8. endpoint ciphertext fails authentication if copied to another endpoint/project context;
9. outbound HMAC verifies against raw bytes and fails for changed body, timestamp, or identity header;
10. response loss after one-time secret creation does not expose a recovery endpoint;
11. production rejects non-HTTPS, user-info, redirects, and every prohibited IPv4/IPv6 class;
12. DNS rebinding fixture cannot cause the actual connection to use a newly prohibited address;
13. response preview stops at 8 KiB, records truncation, and renders without script execution;
14. generic authentication errors do not reveal whether owner/API-key identifiers exist;
15. login limiter is bounded, expires entries, and does not require Redis;
16. secure-cookie and browser headers are active in production configuration.

## 15. Deferred security decisions

- Concrete Java HTTP client and IP-pinning adapter.
- Cloud key provider, secret manager, IAM policy, and TLS termination.
- API-key pepper rotation and digest-version migration.
- Endpoint signing-secret rotation; explicitly absent in v1.
- Cluster-wide rate limiting, quotas, billing, and abuse automation.
- CSP final directives after frontend assets and external dependencies are known.
- Dependency/SBOM/container vulnerability scanning in the CI/cloud phase.

Implementation may replace a mechanism only with documented evidence and equivalent or stronger behavior. It must preserve ownership scoping, one-time secret visibility, signed outbound bytes, at-attempt destination validation, and log redaction.
