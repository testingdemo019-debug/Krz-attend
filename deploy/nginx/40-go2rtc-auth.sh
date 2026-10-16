#!/bin/sh
# KRZ Attend web: renders go2rtc's basic-auth header for /media/api/ws from GO2RTC_API_USER and GO2RTC_API_PASSWORD
# into a tmpfs file (mode 600) that nginx.conf includes. The value is never baked into the image or logged.
set -eu
umask 077
mkdir -p /tmp/nginx
target=/tmp/nginx/go2rtc-auth.conf
if [ -z "${GO2RTC_API_USER:-}" ] || [ -z "${GO2RTC_API_PASSWORD:-}" ]; then
  echo "40-go2rtc-auth: GO2RTC_API_USER or GO2RTC_API_PASSWORD is not set; live video will be refused by go2rtc" >&2
  printf 'set $go2rtc_basic_auth "";\n' > "$target"
  exit 0
fi
token=$(printf '%s:%s' "$GO2RTC_API_USER" "$GO2RTC_API_PASSWORD" | base64 | tr -d '\n')
printf 'set $go2rtc_basic_auth "Basic %s";\n' "$token" > "$target"
echo "40-go2rtc-auth: header rendered" >&2
