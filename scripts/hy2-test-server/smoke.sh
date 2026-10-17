#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Smoke test for run.sh on this Mac: starts the server with the given run.sh flags, connects a local
# Xray client (socks inbound on 127.0.0.1) to it and fetches one URL through the tunnel.
#
#   ./smoke.sh                                  # plain
#   ./smoke.sh --salamander obfspw1             # Salamander
#   ./smoke.sh --hop 20000-20010 --access-log   # port hopping, with the hops visible
#   ./smoke.sh --brutal
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
xray_bin="${XRAY_BIN:-/private/tmp/claude-501/xray-26.10.10}"
work="$(mktemp -d)"
socks_port=18080
url="${SMOKE_URL:-https://example.com}"
trap 'kill "${server_pid:-}" "${client_pid:-}" 2>/dev/null || true; rm -rf "$work"' EXIT

salamander=""; hop=""; brutal=0; port=9443; prev=""
for arg in "$@"; do
  case "$prev" in
    --salamander) salamander="$arg" ;;
    --hop) hop="$arg" ;;
    --port) port="$arg" ;;
  esac
  [ "$arg" = "--brutal" ] && brutal=1
  prev="$arg"
done

HY2_RUN_DIR="$work/run" "$here/run.sh" --access-log "$@" >"$work/server.out" 2>&1 &
server_pid=$!
for _ in $(seq 1 50); do
  grep -q "Starting Xray" "$work/server.out" 2>/dev/null && break
  sleep 0.2
done
sleep 1.5
password="$(awk '/^Password:/{print $2}' "$work/server.out")"
pin="$(awk '/^Cert pin/{print $NF}' "$work/server.out")"
host="$(awk -F'[/:]' '/^Listening:/{print $5}' "$work/server.out")"
[ -n "$password" ] && [ -n "$pin" ] || { echo "server did not start:"; sed 's/^Password:.*/Password: <redacted>/' "$work/server.out"; exit 1; }

masks=()
[ -n "$salamander" ] && masks+=("{\"type\":\"salamander\",\"settings\":{\"password\":\"$salamander\"}}")
if [ -n "$hop" ]; then
  masks+=("{\"type\":\"udphop\",\"settings\":{\"mode\":\"intervalLocal,intervalRemote\",\"interval\":5,\"remotePorts\":\"$hop\"}}")
  port="${hop%-*}"
fi
fm=""
parts=()
[ "${#masks[@]}" -gt 0 ] && parts+=("\"udp\":[$(IFS=,; echo "${masks[*]}")]")
[ "$brutal" = 1 ] && parts+=("\"quicParams\":{\"congestion\":\"brutal\",\"brutalUp\":\"50 mbps\",\"brutalDown\":\"100 mbps\"}")
[ "${#parts[@]}" -gt 0 ] && fm=",\"finalmask\":{$(IFS=,; echo "${parts[*]}")}"

cat > "$work/client.json" <<JSON
{
  "log": { "loglevel": "${SMOKE_CLIENT_LOG:-warning}" },
  "inbounds": [ { "tag": "socks-in", "listen": "127.0.0.1", "port": $socks_port, "protocol": "socks", "settings": { "udp": true } } ],
  "outbounds": [ {
    "tag": "proxy", "protocol": "hysteria",
    "settings": { "version": 2, "address": "$host", "port": $port },
    "streamSettings": {
      "network": "hysteria",
      "hysteriaSettings": { "version": 2, "auth": "$password" },
      "security": "tls",
      "tlsSettings": { "serverName": "hy.test", "alpn": ["h3"], "pinnedPeerCertSha256": "$pin" }$fm
    }
  } ]
}
JSON
"$xray_bin" run -c "$work/client.json" >"$work/client.out" 2>&1 &
client_pid=$!
sleep 1

echo "== fetching $url through the tunnel"
curl -sS -I --max-time 20 --socks5-hostname "127.0.0.1:$socks_port" "$url" | head -3 || echo "FETCH FAILED"
if [ -n "$hop" ]; then
  echo "== waiting 12 s for the client to hop, then fetching again"
  sleep 12
    curl -sS -I --max-time 20 --socks5-hostname "127.0.0.1:$socks_port" "$url" | head -1 || echo "FETCH FAILED"
fi
if [ -n "$salamander" ]; then
  # Row 4's second half: a client that does not speak Salamander must be refused.
  sed -E 's/"udp":\[\{"type":"salamander"[^]]*\}\}\]//; s/"port": '"$socks_port"'/"port": '"$((socks_port + 1))"'/' "$work/client.json" \
    | sed -E 's/"finalmask":\{,?\}//' > "$work/client-plain.json"
  "$xray_bin" run -c "$work/client-plain.json" >"$work/client-plain.out" 2>&1 &
  plain_pid=$!
  sleep 1
  echo "== client WITHOUT Salamander (must fail)"
  if curl -sS -I --max-time 8 --socks5-hostname "127.0.0.1:$((socks_port + 1))" "$url" >/dev/null 2>&1; then
    echo "UNEXPECTED: fetch succeeded without obfs"
  else
    echo "refused, as expected"
  fi
  kill "$plain_pid" 2>/dev/null || true
fi
echo "== server log (tunnelled requests and connections)"
grep -E "tunnelling|hysteria|accepted|hop" "$work/server.out" | sed -E 's/^[0-9/]+ //' | cut -c1-200 | head -30
echo "== client log"
sed -n '3,12p' "$work/client.out" | cut -c1-200
