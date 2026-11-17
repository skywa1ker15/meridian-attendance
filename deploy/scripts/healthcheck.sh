#!/usr/bin/env bash
# Meridian Attendance — external health check
#
#   deploy/scripts/healthcheck.sh https://attendance.example.co.za
#
# Exit 0 when the API is healthy AND ready (DB reachable, migrations at head) and the
# TLS certificate is valid for more than 14 days. Non-zero otherwise. Suitable for a
# cron job, an uptime monitor "script" check, or a quick post-deployment smoke test.
set -uo pipefail

BASE="${1:-${PUBLIC_BASE_URL:-}}"
[[ -n "${BASE}" ]] || { echo "usage: healthcheck.sh https://attendance.<domain>" >&2; exit 2; }
BASE="${BASE%/}"
rc=0

check() { # name url expected_substring
    local name="$1" url="$2" expect="$3" body code
    body="$(curl -sS --max-time 10 -w '\n%{http_code}' "${url}" 2>&1)" || { echo "FAIL ${name}: ${body}"; rc=1; return; }
    code="${body##*$'\n'}"; body="${body%$'\n'*}"
    if [[ "${code}" == "200" && "${body}" == *"${expect}"* ]]; then
        echo "OK   ${name} (${code}) ${body}"
    else
        echo "FAIL ${name} (${code}) ${body}"; rc=1
    fi
}

check healthz "${BASE}/healthz" '"status":"ok"'
check readyz  "${BASE}/readyz"  '"status":"ready"'

# Dashboard served
code="$(curl -sS -o /dev/null --max-time 10 -w '%{http_code}' "${BASE}/admin/")"
if [[ "${code}" == "200" ]]; then echo "OK   admin dashboard (${code})"; else echo "FAIL admin dashboard (${code})"; rc=1; fi

# Security headers
hdrs="$(curl -sSI --max-time 10 "${BASE}/healthz")"
for h in "strict-transport-security" "x-content-type-options" "x-frame-options"; do
    if grep -qi "^${h}:" <<<"${hdrs}"; then echo "OK   header ${h}"; else echo "WARN missing header ${h}"; fi
done

# TLS certificate expiry (only for https)
if [[ "${BASE}" == https://* ]]; then
    host="${BASE#https://}"; host="${host%%/*}"; host="${host%%:*}"
    notafter="$(echo | openssl s_client -servername "${host}" -connect "${host}:443" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
    if [[ -n "${notafter}" ]]; then
        exp=$(date -d "${notafter}" +%s 2>/dev/null || date -j -f '%b %d %T %Y %Z' "${notafter}" +%s)
        days=$(( (exp - $(date +%s)) / 86400 ))
        if (( days > 14 )); then echo "OK   certificate expires in ${days} days"; else echo "FAIL certificate expires in ${days} days"; rc=1; fi
    else
        echo "FAIL could not read certificate"; rc=1
    fi
fi

exit ${rc}
