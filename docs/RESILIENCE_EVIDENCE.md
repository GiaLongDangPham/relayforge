# RelayForge Failure and Recovery Evidence

Status: controlled local/owner-readiness evidence, not a production recovery claim  
Date: 2026-08-29

## Group 18 — Delivery failure and recovery

The local Compose API, worker, PostgreSQL, receiver, Prometheus, and Grafana
were used. Every scenario created a separate local project/key/endpoint; raw
key and signing-secret material was not printed or committed.

| Scenario | Durable attempt sequence | Final delivery result |
| --- | --- | --- |
| Receiver HTTP 500, then endpoint correction | `RETRYABLE_FAILURE (500)` → `SUCCEEDED` | `SUCCEEDED` after 2 attempts |
| Receiver slower than the 10-second deadline, then correction | `RETRYABLE_FAILURE (DISPATCH_TIMEOUT)` → `SUCCEEDED` | `SUCCEEDED` after 2 attempts |
| Local worker killed after slow request started, then restarted | `UNKNOWN` → `SUCCEEDED` | `SUCCEEDED` after lease recovery and 2 attempts |
| Receiver remains HTTP 500 | five `RETRYABLE_FAILURE` records | `EXHAUSTED`; no sixth automatic attempt |

After the exercises, Prometheus reported zero ready backlog, eight available
worker permits, and zero pending Hikari connections. The `UNKNOWN` record is
especially important: it was preserved as history rather than rewritten when a
later attempt succeeded. That is RelayForge's at-least-once trade-off: an HTTP
side effect may be ambiguous after worker failure, so the system retries rather
than silently claiming success.

The non-secret artifacts are ignored at:

- `performance/results/group18-failure-recovery.json`
- `performance/results/group18-exhaustion.json`

## Group 19 — Restore, compatibility, and DNS readiness

The local Compose PostgreSQL database was dumped as a PostgreSQL custom archive
and validated with `pg_restore --list`. It was restored into an isolated
PostgreSQL 17.10 container with no host port. The restore contained 12 Flyway
history rows, one owner, 3,004 events, and 3,004 deliveries. These counts are
local demonstration data, not production data.

Immutable image `gialong1416/relayforge-backend:a75ef093bc8d` then became
`UP` on its readiness endpoint against that restored database. The temporary
database, backend container, Docker network, and dump were removed after the
check.

The owner-readiness DuckDNS command also verified that
`gialong.duckdns.org` resolved to `35.72.33.67` and returned HTTPS 200 at the
time of the check. It made no DNS, EC2, GitHub, or deployment change.

## Limits that remain true

- No production PostgreSQL archive was restored, and no production database was
  overwritten.
- No EC2 instance was stopped, no live image rollback was performed, and no
  SSH host key was changed.
- An image compatibility start does not prove every future Flyway migration is
  reversible. A later migration may still require fix-forward rather than
  rollback.
- The one-host EC2 topology remains a single failure domain until a separate,
  explicitly approved architecture change addresses that.

## References

- [Failure and Recovery Runbook](FAILURE_RECOVERY_RUNBOOK.md)
- [Recovery Drill Runbook](RECOVERY_DRILL_RUNBOOK.md)
- [Production Release Runbook](PRODUCTION_RELEASE_RUNBOOK.md)
