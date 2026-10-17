#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# A local Hysteria2 server (Xray v26.10.10 `hysteria` inbound) for the device checklist rows 3-6 in
# docs/agent/specs/2026-10-17-m9d1-hysteria2-design.md. See README.md.
#
# Nothing secret is committed: the certificate, key, config and password are generated at run time
# into .run/ (gitignored). Reuse of a previous certificate keeps the pin stable across restarts; pass
# --fresh-cert to mint a new one.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
run_dir="${HY2_RUN_DIR:-$here/.run}"

xray_bin="${XRAY_BIN:-/private/tmp/claude-501/xray-26.10.10}"
host=""
port=9443
password=""
salamander=""
hop=""
brutal=0
access_log=0
fresh_cert=0
sni="hy.test"
egress="127.0.0.1:10808"
config_only=0

usage() {
  cat <<USAGE
Usage: run.sh [options]
  --host IP              address to listen on and put in links (default: \`ipconfig getifaddr en0\`)
  --port N               UDP port (default 9443)
  --password PW          auth password (default: a fresh random one every start)
  --salamander PW        require Salamander obfuscation with this password (>= 4 chars)
  --hop A-B              also answer on every UDP port in A-B (e.g. 20000-20010) via hop-relay.py,
                         which forwards them all to --port (Xray's own port range cannot hop, see README)
  --brutal               server side Brutal (advertises/uses a 100 mbps rate)
  --access-log           debug-level log on stdout: each tunnelled request and its source port
  --fresh-cert           generate a new self-signed certificate (new pin)
  --sni NAME             certificate DNS name and the sni= in the printed links (default hy.test)
  --egress HOST:PORT     HTTP proxy that carries the server's outgoing TCP (default 127.0.0.1:10808)
  --config-only          write the config and print the links, but do not start Xray
Environment: XRAY_BIN (default $xray_bin), HY2_RUN_DIR (default $run_dir)
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --host) host="$2"; shift 2 ;;
    --port) port="$2"; shift 2 ;;
    --password) password="$2"; shift 2 ;;
    --salamander) salamander="$2"; shift 2 ;;
    --hop) hop="$2"; shift 2 ;;
    --brutal) brutal=1; shift ;;
    --access-log) access_log=1; shift ;;
    --fresh-cert) fresh_cert=1; shift ;;
    --sni) sni="$2"; shift 2 ;;
    --egress) egress="$2"; shift 2 ;;
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
if [ -n "$salamander" ] && [ "${#salamander}" -lt 4 ]; then
  echo "--salamander password must be at least 4 characters" >&2; exit 2
fi

# Port range: the inbound listens on every UDP port in it; the link carries the whole range.
if [ -n "$hop" ]; then
  case "$hop" in
    *[!0-9-]*|-*|*-|"") echo "--hop must look like 20000-20010" >&2; exit 2 ;;
  esac
  listen_ports="$port"
  link_ports="$hop"
else
  listen_ports="$port"
  link_ports="$port"
fi

[ -n "$password" ] || password="$(openssl rand -hex 12)"

mkdir -p "$run_dir"
chmod 700 "$run_dir"
cert="$run_dir/cert.pem"
key="$run_dir/key.pem"
if [ "$fresh_cert" = 1 ] || [ ! -f "$cert" ] || [ ! -f "$key" ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 \
    -keyout "$key" -out "$cert" -subj "/CN=$sni" \
    -addext "subjectAltName=DNS:$sni,IP:$host" >/dev/null 2>&1
  chmod 600 "$key"
fi
# Xray's pinnedPeerCertSha256 is the SHA-256 of the DER leaf certificate.
pin="$(openssl x509 -in "$cert" -outform der | openssl dgst -sha256 -hex | awk '{print $NF}')"

egress_host="${egress%:*}"
egress_port="${egress##*:}"

stream_extra=""
mask=""
if [ -n "$salamander" ]; then
  mask="{\"type\":\"salamander\",\"settings\":{\"password\":\"$salamander\"}}"
fi
quic=""
if [ "$brutal" = 1 ]; then
  # brutalDown is what the server advertises to the client; brutalUp caps what the server sends.
  quic="\"quicParams\":{\"congestion\":\"brutal\",\"brutalUp\":\"100 mbps\",\"brutalDown\":\"100 mbps\"}"
fi
finalmask=""
if [ -n "$mask" ] || [ -n "$quic" ]; then
  parts=()
  [ -n "$mask" ] && parts+=("\"udp\":[$mask]")
  [ -n "$quic" ] && parts+=("$quic")
  finalmask=",\"finalmask\":{$(IFS=,; echo "${parts[*]}")}"
fi

# Xray's hysteria inbound writes nothing to the access log; its own log lines (tunnelling request,
# new QUIC connections and the UDP port they arrive on) are at debug level, so --access-log means
# loglevel debug on stdout.
loglevel="info"
[ "$access_log" = 1 ] && loglevel="debug"

config="$run_dir/server.json"
umask 077
cat > "$config" <<JSON
{
  "log": { "loglevel": "$loglevel" },
  "dns": { "servers": ["https://1.1.1.1/dns-query"] },
  "inbounds": [
    {
      "tag": "hy2-in",
      "listen": "$host",
      "port": "$listen_ports",
      "protocol": "hysteria",
      "settings": { "version": 2, "clients": [ { "auth": "$password", "email": "test@hy2.local" } ] },
      "streamSettings": {
        "network": "hysteria",
        "hysteriaSettings": { "version": 2 },
        "security": "tls",
        "tlsSettings": {
          "alpn": ["h3"],
          "certificates": [ { "certificateFile": "$cert", "keyFile": "$key" } ]
        }$finalmask
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

# --- links -----------------------------------------------------------------
# The userinfo is the auth password; hex needs no escaping.
q="sni=$sni"
[ -n "$salamander" ] && q="$q&obfs=salamander&obfs-password=$salamander"
[ "$brutal" = 1 ] && q="$q&up=50%20mbps&down=100%20mbps"
[ -n "$hop" ] && q="$q&hop-interval=10"
name="hy2-local"
[ -n "$salamander" ] && name="$name-salamander"
[ -n "$hop" ] && name="$name-hop"
[ "$brutal" = 1 ] && name="$name-brutal"

echo "Xray:      $("$xray_bin" version | head -1)"
echo "Listening: udp://$host:$listen_ports"
[ -n "$hop" ] && echo "Hop range: udp://$host:$hop (hop-relay.py -> $listen_ports)"
echo "Password:  $password"
[ -n "$salamander" ] && echo "Salamander: $salamander"
echo "Cert pin (SHA-256 hex): $pin"
echo "Egress:    HTTP proxy $egress_host:$egress_port (+ DoH https://1.1.1.1/dns-query for UDP 53)"
[ "$access_log" = 1 ] && echo "Log level: debug (hops show as new connections / source ports below)"
echo
echo "Link, pinned (row 6 pass case):"
echo "  hysteria2://$password@$host:$link_ports?$q&pinSHA256=$pin#$name-pinned"
echo "Link, unpinned (row 6 'Certificate not trusted' case):"
echo "  hysteria2://$password@$host:$link_ports?$q#$name-unpinned"
echo "Link, insecure=1 (self-signed accepted without a pin):"
echo "  hysteria2://$password@$host:$link_ports?$q&insecure=1#$name-insecure"
echo

"$xray_bin" run -test -c "$config" >/dev/null || { echo "xray rejected the generated config ($config)" >&2; exit 1; }
[ "$config_only" = 1 ] && { echo "config written to $config"; exit 0; }
pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 143' INT TERM
if [ -n "$hop" ]; then
  python3 -I "$here/hop-relay.py" "$host" "$hop" "$host" "$port" &
  pids+=($!)
fi
echo "Starting Xray (Ctrl-C to stop)..."
"$xray_bin" run -c "$config" &
pids+=($!)
wait "${pids[${#pids[@]}-1]}"
