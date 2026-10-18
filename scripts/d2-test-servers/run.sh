#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Local test servers (Xray v26.10.10) for the M9-D2 device rows in
# docs/agent/specs/2026-10-18-m9d2-wireguard-http-kcp-httpupgrade-design.md section 6. See README.md.
#
# One Xray process, four inbounds on the Mac's LAN address:
#   (a) VLESS over mKCP, seed + wechat header masks, plaintext   udp  KCP_PORT
#   (b) VLESS over HTTPUpgrade, plaintext                        tcp  UPGRADE_PORT
#   (c) HTTP proxy with user/pass                                tcp  HTTP_PORT
#   (d) WireGuard                                                udp  WG_PORT
#
# Nothing secret is committed: the UUID, seed, keys and password are generated at run time into .run/
# (gitignored) and reused on later starts, so links stay valid across restarts; --fresh mints new ones.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
run_dir="${D2_RUN_DIR:-$here/.run}"
xray_bin="${XRAY_BIN:-/private/tmp/claude-501/xray-26.10.10}"

host=""
kcp_port=9601
upgrade_port=9602
http_port=9603
wg_port=9604
egress="127.0.0.1:10808"
fresh=0
config_only=0

usage() {
  cat <<USAGE
Usage: run.sh [options]
  --host IP              address to listen on and put in links (default: \`ipconfig getifaddr en0\`)
  --kcp-port N           mKCP UDP port (default $kcp_port)
  --upgrade-port N       HTTPUpgrade TCP port (default $upgrade_port)
  --http-port N          HTTP proxy TCP port (default $http_port)
  --wg-port N            WireGuard UDP port (default $wg_port)
  --egress HOST:PORT     HTTP proxy that carries the server's outgoing TCP (default $egress)
  --fresh                generate new secrets (new UUID, seed, keys, password)
  --config-only          write the config, links and Clash snippet, but do not start Xray
Environment: XRAY_BIN (default $xray_bin), D2_RUN_DIR (default $run_dir)
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --host) host="$2"; shift 2 ;;
    --kcp-port) kcp_port="$2"; shift 2 ;;
    --upgrade-port) upgrade_port="$2"; shift 2 ;;
    --http-port) http_port="$2"; shift 2 ;;
    --wg-port) wg_port="$2"; shift 2 ;;
    --egress) egress="$2"; shift 2 ;;
    --fresh) fresh=1; shift ;;
    --config-only) config_only=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[ -x "$xray_bin" ] || { echo "xray not found at $xray_bin (set XRAY_BIN to a v26.10.10 build)" >&2; exit 1; }
if [ -z "$host" ]; then
  host="$(ipconfig getifaddr en0 2>/dev/null || true)"
  [ -n "$host" ] || { echo "no LAN address on en0; pass --host IP" >&2; exit 1; }
fi

# Xray v26.10.10 refuses VLESS/Trojan without TLS to a public address (a1bf968, ARCHITECTURE.md 14.10); the
# mKCP and HTTPUpgrade links here are plaintext VLESS. "Private" is RFC 1918, loopback and link-local.
private_host=0
case "$host" in
  10.*|127.*|192.168.*|169.254.*) private_host=1 ;;
  172.1[6-9].*|172.2[0-9].*|172.3[01].*) private_host=1 ;;
esac

mkdir -p "$run_dir"
chmod 700 "$run_dir"
umask 077
secrets="$run_dir/secrets.env"

# Xray's own `wg` prints "PrivateKey: ..." and "Password (PublicKey): ...".
wg_keys() { "$xray_bin" wg | awk '/^PrivateKey:/{k=$2} /^Password \(PublicKey\):/{p=$NF} END{print k, p}'; }

if [ "$fresh" = 1 ] || [ ! -f "$secrets" ]; then
  read -r server_priv server_pub < <(wg_keys)
  read -r client_priv client_pub < <(wg_keys)
  {
    echo "UUID=$("$xray_bin" uuid)"
    echo "SEED=$(openssl rand -hex 8)"
    echo "HTTP_USER=d2user"
    echo "HTTP_PASS=$(openssl rand -hex 8)"
    echo "WG_SERVER_PRIV=$server_priv"
    echo "WG_SERVER_PUB=$server_pub"
    echo "WG_CLIENT_PRIV=$client_priv"
    echo "WG_CLIENT_PUB=$client_pub"
  } > "$secrets"
fi
# shellcheck disable=SC1090
. "$secrets"

egress_host="${egress%:*}"
egress_port="${egress##*:}"
upgrade_path="/d2up"
wg_address="10.9.0.2/32"

config="$run_dir/server.json"
cat > "$config" <<JSON
{
  "log": { "loglevel": "info" },
  "dns": { "servers": ["https://1.1.1.1/dns-query"] },
  "inbounds": [
    {
      "tag": "kcp-in",
      "listen": "$host",
      "port": $kcp_port,
      "protocol": "vless",
      "settings": { "clients": [ { "id": "$UUID", "email": "kcp@d2.local" } ], "decryption": "none" },
      "streamSettings": {
        "network": "kcp",
        "kcpSettings": {},
        "security": "none",
        "finalmask": {
          "udp": [
            { "type": "mkcp-legacy", "settings": { "value": "$SEED" } },
            { "type": "mkcp-legacy", "settings": { "header": "wechat" } }
          ]
        }
      }
    },
    {
      "tag": "upgrade-in",
      "listen": "$host",
      "port": $upgrade_port,
      "protocol": "vless",
      "settings": { "clients": [ { "id": "$UUID", "email": "upgrade@d2.local" } ], "decryption": "none" },
      "streamSettings": {
        "network": "httpupgrade",
        "httpupgradeSettings": { "path": "$upgrade_path" },
        "security": "none"
      }
    },
    {
      "tag": "http-in",
      "listen": "$host",
      "port": $http_port,
      "protocol": "http",
      "settings": { "accounts": [ { "user": "$HTTP_USER", "pass": "$HTTP_PASS" } ] }
    },
    {
      "tag": "wg-in",
      "listen": "$host",
      "port": $wg_port,
      "protocol": "wireguard",
      "settings": {
        "secretKey": "$WG_SERVER_PRIV",
        "mtu": 1420,
        "peers": [ { "publicKey": "$WG_CLIENT_PUB", "allowedIPs": ["$wg_address"] } ]
      }
    }
  ],
  "outbounds": [
    {
      "tag": "egress",
      "protocol": "http",
      "settings": { "address": "$egress_host", "port": $egress_port }
    },
    { "tag": "dns-out", "protocol": "dns" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      { "type": "field", "network": "udp", "port": "53", "outboundTag": "dns-out" },
      { "type": "field", "network": "udp", "outboundTag": "block" },
      { "type": "field", "network": "tcp", "outboundTag": "egress" }
    ]
  }
}
JSON

# --- links and the Clash snippet ---------------------------------------------
# `xray wg` prints standard base64 (+ / =), so the keys are percent-encoded; the seed and password are hex.
urlenc() { python3 -I -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }
link_kcp="vless://$UUID@$host:$kcp_port?type=kcp&security=none&encryption=none&seed=$SEED&headerType=wechat-video#d2-vless-kcp"
link_upgrade="vless://$UUID@$host:$upgrade_port?type=httpupgrade&security=none&encryption=none&path=%2Fd2up#d2-vless-httpupgrade"
link_wg="wireguard://$(urlenc "$WG_CLIENT_PRIV")@$host:$wg_port?publickey=$(urlenc "$WG_SERVER_PUB")&address=10.9.0.2%2F32&mtu=1420#d2-wireguard"

clash="$run_dir/d2-clash.yaml"
cat > "$clash" <<YAML
proxies:
  - {name: d2-http, type: http, server: $host, port: $http_port, username: $HTTP_USER, password: $HTTP_PASS}
  - {name: d2-wireguard, type: wireguard, server: $host, port: $wg_port, ip: 10.9.0.2, private-key: '$WG_CLIENT_PRIV', public-key: '$WG_SERVER_PUB', mtu: 1420, udp: true}
YAML

echo "Xray:      $("$xray_bin" version | head -1)"
echo "Listening: $host  kcp udp/$kcp_port  httpupgrade tcp/$upgrade_port  http tcp/$http_port  wireguard udp/$wg_port"
echo "Egress:    HTTP proxy $egress_host:$egress_port (+ DoH https://1.1.1.1/dns-query for UDP 53)"
echo "Secrets:   $secrets (gitignored)"
if [ "$private_host" = 0 ]; then
  echo
  echo "WARNING:   $host is not a private address. The core refuses the plaintext mKCP and HTTPUpgrade links below"
  echo "           ('vless without TLS or other encryption is prohibited'); Subspace shows that refusal. The HTTP"
  echo "           proxy (Clash) and WireGuard rows are not affected. For the plaintext rows use a private --host"
  echo "           (see README: adb reverse for HTTPUpgrade, a private LAN for mKCP)."
fi
echo
echo "mKCP (seed + wechat header masks):"
echo "  $link_kcp"
echo "HTTPUpgrade:"
echo "  $link_upgrade"
echo "WireGuard:"
echo "  $link_wg"
echo "HTTP proxy (user $HTTP_USER) and WireGuard as Clash YAML (serve it as a subscription): $clash"
echo "  http://$HTTP_USER:$HTTP_PASS@$host:$http_port"
echo

"$xray_bin" run -test -c "$config" >/dev/null || { echo "xray rejected the generated config ($config)" >&2; exit 1; }
[ "$config_only" = 1 ] && { echo "config written to $config"; exit 0; }
echo "Starting Xray (Ctrl-C to stop)..."
exec "$xray_bin" run -c "$config"
