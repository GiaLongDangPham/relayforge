# RelayForge Production Release Runbook

## Scope

This runbook operates the existing single EC2 Compose host. It documents a
checked PostgreSQL backup, a deliberate image rollback, and guarded GitHub
Actions release automation. It does not make database restore automatic,
publish PostgreSQL/API/worker ports, or update DuckDNS automatically.

## 1. One-time EC2 installation

Upload `deploy/backup-production-postgres.sh` and
`deploy/apply-production-release.sh` to `/home/ubuntu/` with Termius SFTP.
Then run on EC2:

```bash
sudo install -o root -g root -m 700 \
  /home/ubuntu/backup-production-postgres.sh \
  /opt/relayforge/backup-production-postgres.sh

sudo install -o root -g root -m 700 \
  /home/ubuntu/apply-production-release.sh \
  /opt/relayforge/apply-production-release.sh
```

Create a dedicated CI account rather than reusing the `ubuntu` owner account:

```bash
sudo adduser --disabled-password --gecos '' relayforge-deploy
sudo install -d -o relayforge-deploy -g relayforge-deploy -m 700 \
  /home/relayforge-deploy/.ssh
```

On your local machine, create an SSH key used **only** by GitHub Actions:

```powershell
ssh-keygen -t ed25519 -f $HOME/.ssh/relayforge_github_deploy -C "relayforge-github-actions"
```

Copy only the `.pub` file's content into
`/home/relayforge-deploy/.ssh/authorized_keys` on EC2, then set ownership:

```bash
sudo chown relayforge-deploy:relayforge-deploy \
  /home/relayforge-deploy/.ssh/authorized_keys
sudo chmod 600 /home/relayforge-deploy/.ssh/authorized_keys
```

Grant that account permission for only the release script. Use `visudo` and add
this one line:

```text
relayforge-deploy ALL=(root) NOPASSWD: /opt/relayforge/apply-production-release.sh
```

Verify it before GitHub uses the key:

```bash
sudo -l -U relayforge-deploy
```

## 2. GitHub production environment

In the repository, open **Settings → Environments → New environment** and name
it `production`. Enable required reviewers if available, so a passing `main`
build waits for your approval before it can publish/deploy.

Add these **environment** variables:

| Variable | Value |
| --- | --- |
| `DOCKERHUB_USERNAME` | `gialong1416` |
| `PRODUCTION_PUBLIC_ORIGIN` | `https://gialong.duckdns.org` |
| `EC2_HOST` | `gialong.duckdns.org` |
| `DEPLOY_SSH_USER` | `relayforge-deploy` |

Add these environment secrets:

| Secret | Value |
| --- | --- |
| `DOCKERHUB_TOKEN` | Docker Hub personal access token with push access to `gialong1416` repositories |
| `EC2_SSH_PRIVATE_KEY` | the private `relayforge_github_deploy` key; never commit or paste it in chat |
| `EC2_KNOWN_HOSTS` | one line beginning `gialong.duckdns.org` followed by the EC2 SSH host public key |

For the known-host line, obtain the public host key on the already trusted EC2
session, then prepend the DuckDNS name before saving it as the environment
secret:

```bash
sudo cat /etc/ssh/ssh_host_ed25519_key.pub
```

Example shape only: `gialong.duckdns.org ssh-ed25519 AAAA...`. The host public
key is not a login secret, but storing the exact trusted line prevents the CI
runner from accepting an unexpected SSH host.

Under **Settings → Secrets and variables → Actions → Variables**, create the
repository-level variable `PRODUCTION_DEPLOY_ENABLED=false` initially. Set it
to `true` only after all environment values and secrets are present. GitHub
evaluates the release job's condition before environment-level variables are
available, so this one enable flag must be repository-scoped. The workflow does
nothing for production while it remains `false`.

## 3. Backup drill

Run this on EC2 before a release with a migration, an intentional rollback, a
Docker volume cleanup, or instance termination:

```bash
sudo /opt/relayforge/backup-production-postgres.sh
sudo find /opt/relayforge/backups -maxdepth 1 -type f -printf '%f %s bytes\n'
```

The script creates a root-readable-only PostgreSQL custom archive, checksum,
and metadata file. Its `pg_restore --list` validation proves the archive is
readable. It does **not** prove a restore until an isolated restore target is
created; never restore directly into the running production database merely to
test a backup.

Copy a validated archive off the single EC2 disk before relying on it for
instance-termination recovery.

## 4. Release and rollback

When `main` is pushed and the quality jobs pass, GitHub Actions derives the
first 12 characters of that commit SHA, publishes both images, and SSHs to:

```text
sudo /opt/relayforge/apply-production-release.sh <tag>
```

The host script validates Compose, pulls the exact images, waits for API
readiness (including Flyway), then starts worker and gateway. It retains the
previous tag in output but intentionally stops on a failure rather than making
a blind rollback decision.

For the first release after enabling automation, use **Actions → Continuous
integration → Run workflow → main**. This reruns the quality gate and then the
guarded production job without requiring a throwaway source commit.

To roll an image back after checking database compatibility and taking a
backup, run from Termius with a known-good prior tag:

```bash
sudo /opt/relayforge/apply-production-release.sh <known-good-tag>
```

Do not roll back from a release that applied an incompatible migration. Use an
isolated restore procedure or diagnose/fix forward instead.

## 5. DuckDNS after stop/start

With the selected dynamic-IP approach, update DuckDNS to the new EC2 public IP
before a GitHub deployment. The `EC2_HOST` variable remains
`gialong.duckdns.org`; only its DNS record changes. Verify the current address
from EC2 and from DNS, then confirm the dashboard HTTPS route before approving
the production job:

```bash
curl -4fsS https://checkip.amazonaws.com
getent ahostsv4 gialong.duckdns.org | awk 'NR==1 {print $1}'
```

If EC2 is terminated/recreated rather than merely stopped, its SSH host key
changes. Replace the `EC2_KNOWN_HOSTS` environment secret with the new trusted
host-key line before enabling deployment.
