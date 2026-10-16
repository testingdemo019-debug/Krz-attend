#!/usr/bin/env bash
# Backs up everything needed to rebuild KRZ Attend: the database dump, the crops and uploads (still encrypted)
# and a source zip. With --source-only, only the source zip is written (end of every phase; replaces Git history).
set -uo pipefail
# shellcheck source=lib/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/env.sh"

SOURCE_ONLY=""
for arg in "$@"; do
  case "$arg" in
    --help|-h)
      cat <<'EOF'
backup-all - write into BACKUP_DIR (deploy/.env, default data/backups):
  db-<ts>.dump[.gpg]           database (database/scripts/backup.sh)
  files-<ts>.tar.gz            data/crops and data/uploads, exactly as stored (already encrypted; owners and modes kept)
  source-<version>-<ts>.zip    the source tree without .env, data/, node_modules, target, .venv, dist and caches
  --source-only                only the source zip
Archives older than BACKUP_KEEP_DAYS are deleted (source zips are kept). VERSION must have a CHANGELOG entry.
EOF
      exit 0 ;;
    --source-only) SOURCE_ONLY=1 ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done

ROOT="$(krz_project_root)"
ENV_FILE="$(krz_env_path)"
DEPLOY_DIR="$(dirname "$ENV_FILE")"
resolve() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$DEPLOY_DIR" "$1" ;; esac; }
BACKUP_DIR="$(resolve "$(krz_env_get "$ENV_FILE" BACKUP_DIR ../data/backups)")"
mkdir -p "$BACKUP_DIR"; BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
STAMP="$(date +%Y%m%d-%H%M)"

VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "VERSION '$VERSION' is not a semantic version." >&2; exit 1; }
grep -Eq "^## ${VERSION//./\\.}([^0-9A-Za-z.-]|$)" "$ROOT/docs/CHANGELOG.md" \
  || { echo "docs/CHANGELOG.md has no '## $VERSION' entry. Write it before making the source zip." >&2; exit 1; }

failed=0
if [[ -z "$SOURCE_ONLY" ]]; then
  bash "$ROOT/database/scripts/backup.sh" || failed=1
  DATA_DIR="$(resolve "$(krz_env_get "$ENV_FILE" DATA_DIR ../data)")"
  mkdir -p "$DATA_DIR/crops" "$DATA_DIR/uploads"
  FILES="$BACKUP_DIR/files-$STAMP.tar.gz"
  tar -czf "$FILES.partial" -C "$DATA_DIR" crops uploads && mv -f "$FILES.partial" "$FILES" \
    && echo "Files written: $(basename "$FILES")" || failed=1
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'files-*.tar.gz' -mtime +"$(krz_env_get "$ENV_FILE" BACKUP_KEEP_DAYS 14)" -delete
fi

SOURCE="$BACKUP_DIR/source-$VERSION-$STAMP.zip"
# The same exclusion rules as backup-all.ps1; python3's zipfile avoids depending on the zip package.
count="$(python3 -I - "$ROOT" "$SOURCE.partial" <<'PY'
import os, sys, zipfile
root, target = sys.argv[1], sys.argv[2]
excluded = {"node_modules", "target", ".venv", "dist", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".idea", ".vscode"}
count = 0
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        dirnames[:] = sorted(d for d in dirnames
                             if d not in excluded and not d.startswith("target-")
                             and os.path.normpath(os.path.join(rel_dir, d)) not in ("data", os.path.join("frontend", "test", "parity", "diff")))
        for name in sorted(filenames):
            rel = os.path.normpath(os.path.join(rel_dir, name))
            if (name.startswith(".env") and name != ".env.example") or rel in (
                    os.path.join("database", "bootstrap", "check-report.txt"), os.path.join("deploy", "redis", "users.acl")):
                continue
            z.write(os.path.join(dirpath, name), rel.replace(os.sep, "/"))
            count += 1
print(count)
PY
)" || exit 1
mv -f "$SOURCE.partial" "$SOURCE"
echo "Source written: $(basename "$SOURCE") ($count files, $(( $(stat -c %s "$SOURCE") / 1024 )) KB)"
exit "$failed"
