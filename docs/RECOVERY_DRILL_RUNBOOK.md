# RelayForge Recovery Drill Runbook

## Scope and non-negotiable safety boundary

RelayForge runs one EC2 host and one PostgreSQL volume, so backup and rollback
are operational procedures—not automatic actions. This runbook supplies an
isolated local proof plus an owner-operated EC2 checklist.

Never restore a dump over the running EC2 PostgreSQL container merely to test
it. Never select an older application image until database migration
compatibility has been checked and a fresh backup exists.

## 1. Local isolated backup and restore proof

With local Compose PostgreSQL running, execute:

```powershell
./scripts/group19-local-restore-drill.ps1
```

The script:

1. creates a PostgreSQL custom-format dump from the **local** Compose database;
2. validates it with `pg_restore --list`;
3. restores it into a new PostgreSQL 17.10 container on a temporary private
   Docker network with no host port;
4. verifies Flyway history and durable owner/event/delivery data; and
5. starts immutable `gialong1416/relayforge-backend:a75ef093bc8d` against the
   restored database and waits for readiness.

It removes the temporary API, database, network, and dump at the end. The
known-good tag is an input, not an automatic rollback target:

```powershell
./scripts/group19-local-restore-drill.ps1 -KnownGoodTag <known-good-git-sha-tag>
```

The check establishes that a backup can be restored and that the chosen image
can start with that schema. It does **not** establish that rolling production
backward is safe after every future Flyway migration.

## 2. EC2 backup before a disruptive decision

From Termius, before a production migration, host cleanup, manual rollback, or
instance termination:

```bash
sudo /opt/relayforge/backup-production-postgres.sh
sudo find /opt/relayforge/backups -maxdepth 1 -type f -printf '%f %s bytes\n'
```

The host script writes a custom archive, metadata, and checksum, then validates
archive readability. Copy a validated archive off the single EC2 disk before
relying on it for instance-termination recovery.

## 3. Owner-operated production rollback decision

Only after checking migration compatibility and creating a new backup may the
owner select a known good image tag:

```bash
sudo /opt/relayforge/apply-production-release.sh <known-good-tag>
```

The script validates Compose, pulls the exact images, waits for API readiness,
then starts worker and gateway. It deliberately fails closed; it does not
restore PostgreSQL or decide on a database rollback for you.

## 4. DuckDNS readiness after EC2 stop/start

After obtaining the new EC2 public IPv4 from the trusted Termius session:

```bash
curl -4fsS https://checkip.amazonaws.com
```

Update DuckDNS manually, then on the local machine verify DNS and HTTPS before
approving another GitHub deployment:

```powershell
./scripts/verify-duckdns-recovery.ps1 -ExpectedIpv4 <new-ec2-ipv4>
```

The script only reads DNS and performs an HTTPS request. It never updates
DuckDNS, stops EC2, changes GitHub variables, or accepts a new SSH host key.
If EC2 was recreated, obtain and replace the trusted `EC2_KNOWN_HOSTS` secret
before enabling CD.

## 5. Operational limits to state honestly

- The EC2 host, Caddy, API, worker, and PostgreSQL volume are a single failure
  domain.
- A host-local backup is not off-host disaster recovery until copied elsewhere.
- Image rollback does not roll back a Flyway migration.
- DuckDNS can recover a changed dynamic IP but does not preserve a recreated
  instance's SSH host identity.

## References

- [Measured resilience evidence](RESILIENCE_EVIDENCE.md)
- [Production Release Runbook](PRODUCTION_RELEASE_RUNBOOK.md)
- [ADR-005](adr/0005-single-ec2-compose-deployment.md)
- [ADR-006](adr/0006-guarded-github-actions-ec2-release.md)
