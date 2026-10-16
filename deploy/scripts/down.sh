#!/usr/bin/env bash
# Stops KRZ Attend's containers. Data in data/ is kept (nothing is deleted).
set -euo pipefail
# shellcheck source=lib/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/env.sh"

for arg in "$@"; do
  case "$arg" in
    --help|-h) echo "down - stop and remove the containers (docker compose down). data/ is kept."; exit 0 ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done
DEPLOY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docker compose --project-directory "$DEPLOY" --env-file "$(krz_env_path)" -f "$DEPLOY/docker-compose.yml" down
echo "Containers stopped. data/ is unchanged."
