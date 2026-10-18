#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Smoke test for run.sh on this Mac: starts the servers in a scratch run dir, connects a local Xray client
# to each of the four and fetches one URL through it. Then a client with the WRONG mKCP seed must fail.
#
#   ./smoke.sh
#   SMOKE_URL=https://example.org ./smoke.sh
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
xray_bin="${XRAY_BIN:-/private/tmp/claude-501/xray-26.10.10}"
url="${SMOKE_URL:-https://example.com}"
work="$(mktemp -d)"
pids=()
trap 'kill "${pids[@]:-}" 2>/dev/null || true; [ -n "${KEEP:-}" ] || rm -rf "$work"' EXIT

# The system proxy must not carry the check: it would pass whether or not the tunnel works.
unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY

# Loopback is private for the core's plaintext rule; the Mac LAN address (172.100.x) is not. Override with --host.
D2_RUN_DIR="$work/run" "$here/run.sh" --host 127.0.0.1 "$@" >"$work/server.out" 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do
  grep -q "Starting Xray" "$work/server.out" 2>/dev/null && break
  sleep 0.2
done
sleep 1.5
[ -f "$work/run/secrets.env" ] && grep -q "Starting Xray" "$work/server.out" || {
  echo "server did not start:"; grep -v -E "vless://|wireguard://|http://.*@" "$work/server.out"; exit 1; }
# shellcheck disable=SC1091
. "$work/run/secrets.env"
host="$(awk '/^Listening:/{print $2}' "$work/server.out")"
port() { awk -v k="$1" '/^Listening:/{for(i=1;i<=NF;i++) if($i==k) {split($(i+1),a,"/"); print a[2]}}' "$work/server.out"; }
kcp_port="$(port kcp)"; upgrade_port="$(port httpupgrade)"; http_port="$(port http)"; wg_port="$(port wireguard)"

socks=18080
fail=0

# client NAME OUTBOUND_JSON: starts a client with a SOCKS inbound on the next port; sets $client_port.
client() {
  client_port=$((socks++))
  cat > "$work/$1.json" <<JSON
{
  "log": { "loglevel": "warning" },
  "inbounds": [ { "tag": "socks-in", "listen": "127.0.0.1", "port": $client_port, "protocol": "socks", "settings": { "udp": false } } ],
  "outbounds": [ $2 ]
}
JSON
  "$xray_bin" run -c "$work/$1.json" >"$work/$1.out" 2>&1 &
  pids+=($!)
  sleep 1
}

fetch() { curl -sS -o /dev/null -w '%{http_code}' --max-time "${2:-20}" --socks5-hostname "127.0.0.1:$1" "$url" 2>/dev/null; }

expect_ok() {
  local code
  code="$(fetch "$2")"
  if [ "$code" = "200" ]; then echo "PASS  $1 (HTTP $code)"; else echo "FAIL  $1 (got '${code:-none}')"; fail=1; fi
}

kcp_out() {
  cat <<JSON
{ "tag": "proxy", "protocol": "vless",
  "settings": { "vnext": [ { "address": "$host", "port": $kcp_port, "users": [ { "id": "$UUID", "encryption": "none" } ] } ] },
  "streamSettings": { "network": "kcp", "security": "none", "kcpSettings": {},
    "finalmask": { "udp": [
      { "type": "mkcp-legacy", "settings": { "value": "$1" } },
      { "type": "mkcp-legacy", "settings": { "header": "wechat" } } ] } } }
JSON
}

client kcp "$(kcp_out "$SEED")"
expect_ok "mKCP seed + wechat" "$client_port"

client upgrade "{ \"tag\": \"proxy\", \"protocol\": \"vless\",
  \"settings\": { \"vnext\": [ { \"address\": \"$host\", \"port\": $upgrade_port, \"users\": [ { \"id\": \"$UUID\", \"encryption\": \"none\" } ] } ] },
  \"streamSettings\": { \"network\": \"httpupgrade\", \"security\": \"none\", \"httpupgradeSettings\": { \"host\": \"\", \"path\": \"/d2up\" } } }"
expect_ok "HTTPUpgrade" "$client_port"

client http "{ \"tag\": \"proxy\", \"protocol\": \"http\",
  \"settings\": { \"address\": \"$host\", \"port\": $http_port, \"user\": \"$HTTP_USER\", \"pass\": \"$HTTP_PASS\" } }"
expect_ok "HTTP proxy with auth" "$client_port"

client wg "{ \"tag\": \"proxy\", \"protocol\": \"wireguard\",
  \"settings\": { \"secretKey\": \"$WG_CLIENT_PRIV\", \"address\": [\"10.9.0.2/32\"], \"mtu\": 1420, \"noKernelTun\": true,
    \"peers\": [ { \"publicKey\": \"$WG_SERVER_PUB\", \"endpoint\": \"$host:$wg_port\", \"keepAlive\": 25 } ] } }"
expect_ok "WireGuard" "$client_port"

client kcpwrong "$(kcp_out "wrong-$SEED")"
code="$(fetch "$client_port" 8)"
if [ "$code" = "200" ]; then echo "FAIL  mKCP with the WRONG seed fetched the page"; fail=1
else echo "PASS  mKCP with the WRONG seed is refused (got '${code:-none}')"; fi

[ "$fail" = 0 ] && echo "smoke: all passed" || { echo "smoke: FAILED"; echo "== server log tail"; tail -15 "$work/server.out" | cut -c1-200 | sed -E 's/[0-9a-f]{8}-[0-9a-f-]{27}/<uuid>/g'; }
exit "$fail"
