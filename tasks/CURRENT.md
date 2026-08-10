# Current Task

Status: Completed

## Goal

Implement the complete owner browser-authentication flow: API security configuration, JSON login/logout/me/CSRF endpoints, PostgreSQL-backed server sessions, CORS, and bounded local login-failure limiting.

## Learning outcome

Connect a safe identity verifier to browser requests without leaking credential decisions into HTTP code: CSRF protects browser mutations, a server-side session holds the security context, and session rotation prevents fixation after login.

## Scope

- Add API-mode-only Spring Security web/session configuration and PostgreSQL-backed Spring Session infrastructure.
- Implement the four accepted owner authentication endpoints: CSRF, session login, current owner, and logout.
- Enforce CSRF, security headers, explicit credential/session handling, CORS allowlisting, and a bounded in-process failed-login limiter.
- Add focused HTTP/session integration evidence using PostgreSQL Testcontainers.

## Decisions and trade-offs

- `identity` remains the sole owner of credential validity; HTTP adapters call Spring Security only and receive a hash-free `VerifiedOwner` principal.
- Server-side JDBC sessions are selected over JWT. A session is created only when needed, rotates on login, and is invalidated on logout.
- Login failure limiting is local to each API process and defense in depth; it uses direct source IP only and never confirms login existence.
- CORS accepts only configured dashboard origins with credentials; wildcard origins are forbidden.

## Out of scope

- Project/API-key/endpoint/delivery tables and workflows, publisher authentication, ownership authorization, endpoint signing/HMAC/SSRF, frontend work, cloud configuration, and global/distributed rate limiting.

## Test evidence required

- Login requires CSRF, rotates the session identifier, and returns only owner ID/canonical login.
- Invalid credentials are generic 401; repeated failed attempts receive generic 429 without creating an authenticated session; a successful login clears the local failure bucket.
- Session survives an API context restart through PostgreSQL; logout invalidates it; `/me` requires an authenticated session.
- Missing/invalid CSRF blocks login and logout before mutation; CORS rejects unapproved origins.
- API mode contains the web-security/session components; worker mode excludes them.

## Definition of done

- The documented owner browser flow works end to end and its critical browser/session invariants have focused automated evidence.
- No resource-dependent security behavior enters the slice.

## Actual verification

- `OwnerBrowserAuthenticationIntegrationTests` passed against real PostgreSQL Testcontainers: approved/rejected CORS, CSRF-gated login, session-ID rotation, authenticated `/me`, JDBC session survival across API restart, CSRF-gated logout, invalidation, generic credential failures, and local limiting.
- `WorkerRuntimeApplicationTests` passed against real PostgreSQL Testcontainers through the packaged launcher: worker selects `WebApplicationType.NONE` and has no web server, API controller, Spring Security filter chain, or Spring Session repository/filter.
- The narrow non-container suite passed 12/12: API composition (1), failure limiter (1), security provider (3), and module boundaries (7).
- Independent code review returned `READY` after one P1 correction to the worker launch path.

## Verified behavior

- API mode exposes only the four accepted owner-authentication routes under a deny-by-default `/api/v1/**` filter chain.
- `identity` remains the sole owner of credential verification; runtime security uses the existing hash-free `VerifiedOwner` principal.
- PostgreSQL Spring Session stores a 30-minute `RF_SESSION`; login rotates its ID and logout invalidates it.
- CSRF blocks login/logout mutations, configured credentialed CORS has no wildcard, and authentication responses are generic Problem Details errors.
- The local five-failures-per-minute bucket is bounded, keyed by canonical login plus direct source IP, and cleared only after successful authentication.

## Remaining and limitations

- Publisher API-key authentication, project ownership checks, endpoint signing/SSRF, and all resource-specific authorization remain tied to their future modules.
- The local login limiter is not cluster-wide and is not a substitute for a public-edge WAF.
- Hash corruption or unsupported stored encoding remains an operational error policy for a later slice.

## Next recommended slice

Start Group 2 only after review: establish the `project` capability and owner-scoped project access, then use the existing owner session for the first protected resource workflow.
