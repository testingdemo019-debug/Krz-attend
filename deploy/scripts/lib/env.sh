# Shared helpers for the bash scripts in deploy/scripts and database/ (source this file; it is not executable).
# deploy/.env is parsed line by line and NEVER sourced: values such as "Pvt. Ltd." or "&subtype=0"
# must stay data. Secret values are never written to stdout or stderr.

krz_project_root() {
  (cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
}

krz_env_path() {
  printf '%s/deploy/.env' "$(krz_project_root)"
}

# krz_env_get FILE KEY [DEFAULT] — prints the value (empty values fall back to DEFAULT).
krz_env_get() {
  local file="$1" key="$2" default="${3:-}" line k v found=""
  if [[ -f "$file" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
      [[ "$line" != *=* ]] && continue
      k="${line%%=*}"; k="${k//[[:space:]]/}"
      if [[ "$k" == "$key" ]]; then v="${line#*=}"; found=1; fi
    done < "$file"
  fi
  if [[ -n "$found" && -n "$v" ]]; then printf '%s' "$v"; else printf '%s' "$default"; fi
}

# krz_env_set FILE KEY VALUE — replaces the KEY=... line or appends it; atomic (temp file + mv).
krz_env_set() {
  local file="$1" key="$2" value="$3" line k replaced="" tmp
  if [[ "$value" == *$'\n'* ]]; then echo "The value for $key contains a line break." >&2; return 1; fi
  tmp="$(mktemp "${file}.tmp.XXXXXX")"
  chmod 600 "$tmp"
  if [[ -f "$file" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      k="${line%%=*}"; k="${k//[[:space:]]/}"
      if [[ -z "$replaced" && "$line" == *=* && ! "$line" =~ ^[[:space:]]*# && "$k" == "$key" ]]; then
        printf '%s=%s\n' "$key" "$value" >> "$tmp"; replaced=1
      else
        printf '%s\n' "$line" >> "$tmp"
      fi
    done < "$file"
  fi
  [[ -z "$replaced" ]] && printf '%s=%s\n' "$key" "$value" >> "$tmp"
  mv -f "$tmp" "$file"
}

# krz_render_template TEMPLATE OUTPUT ENV_FILE [MODE] — replaces every ${NAME} with its deploy/.env value (all must be
# set). The output is written with MODE (default 600); values are never printed.
krz_render_template() {
  local template="$1" output="$2" env_file="$3" mode="${4:-600}" content name value missing=()
  content="$(cat "$template")"
  for name in $(grep -o '\${[A-Z0-9_]*}' "$template" | sort -u | tr -d '${}'); do
    value="$(krz_env_get "$env_file" "$name")"
    if [[ -z "$value" ]]; then missing+=("$name"); continue; fi
    content="${content//\$\{$name\}/$value}"
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "deploy/.env has no value for: ${missing[*]} (run deploy/scripts/init-env.sh)." >&2
    return 1
  fi
  ( umask 077; printf '%s\n' "$content" > "$output.tmp" )
  chmod "$mode" "$output.tmp"
  mv -f "$output.tmp" "$output"
}

# krz_secret BYTES [urlsafe|base64] — cryptographically secure random secret.
krz_secret() {
  local bytes="${1:-32}" format="${2:-urlsafe}" raw
  if command -v openssl >/dev/null 2>&1; then
    raw="$(openssl rand -base64 "$bytes" | tr -d '\n')"
  else
    raw="$(head -c "$bytes" /dev/urandom | base64 | tr -d '\n')"
  fi
  if [[ "$format" == "base64" ]]; then printf '%s' "$raw"; else printf '%s' "$raw" | tr '+/' '-_' | tr -d '='; fi
}
