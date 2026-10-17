# Local Hysteria2 test server

A reproducible Hysteria2 server on the Mac for the device checklist rows 3-6 in
`docs/agent/specs/2026-10-17-m9d1-hysteria2-design.md` §7: port hopping, Salamander, Brutal and a
self-signed certificate with and without its pin. The phone reaches it over the LAN.

Nothing here is a secret and nothing secret is committed: the certificate, private key, server config
and password are generated at run time into `.run/` (gitignored).

## Run it

```sh
./run.sh                                   # plain
./run.sh --salamander obfspw1              # row 4
./run.sh --hop 20000-20010                 # row 3
./run.sh --brutal                          # row 5
./run.sh --salamander obfspw1 --hop 20000-20010 --brutal --access-log
```

It needs a v26.10.10 Xray: `/private/tmp/claude-501/xray-26.10.10`, or set `XRAY_BIN`. Build one with
`go build ./main` in an Xray-core clone at tag `v26.10.10`; `xray version` must say 26.10.10 (older
Xray has no `finalmask.udp` `udphop`, and its `hysteria` inbound differs).

On start it prints the LAN address (`ipconfig getifaddr en0`, override with `--host`), a fresh random
password, the certificate's SHA-256 pin as hex, and three ready-to-paste links:

| Link | Use |
|---|---|
| `...&pinSHA256=<hex>` | Row 6 pass case: connects. |
| (no pin) | Row 6 fail case: latency and Home say "Certificate not trusted - add its pin". |
| `...&insecure=1` | The self-signed row where the user has chosen to skip checks. |

Flags: `--port N` (default 9443), `--password PW`, `--salamander PW`, `--hop A-B`, `--brutal`,
`--access-log`, `--fresh-cert`, `--sni NAME`, `--egress HOST:PORT`, `--host IP`, `--config-only`.
The certificate is reused between runs so the pin stays stable on the phone; `--fresh-cert` mints a new
one. The cert's names are `hy.test` and the LAN IP, and the links carry `sni=hy.test`.

## How it works, and why it is not simpler

- **Egress.** The Mac's own DNS is fake-IP (240.0.0.x) under its TUN VPN, so a `freedom` outbound would
  blackhole. TCP leaves through an `http` outbound to the Mac's HTTP proxy at `127.0.0.1:10808`
  (`--egress`). UDP 53 goes to a `dns` outbound that resolves with DoH `https://1.1.1.1/dns-query`.
  Other UDP (QUIC to a website) is blocked: an HTTP proxy cannot carry it, so a "QUIC site loads"
  check (row 2) needs the real subscription, not this server.
- **Port hopping needs `hop-relay.py`.** Xray's `hysteria` inbound given a port range
  (`"port": "20000-20010"`) opens one independent QUIC listener per port. A hopping client then
  lands on a listener that has never seen its connection and the session dies after the first hop
  (measured: first fetch OK, fetch after one hop interval times out). A real deployment puts every hop
  port in front of one socket with an iptables/pf redirect; this Mac has no passwordless sudo, so
  `hop-relay.py` does that redirect in user space: it listens on every port in the range and forwards
  to the server's single port, one upstream socket per client address, replying from the port the
  client last used. `--hop` starts it and the server listens on `--port` only. Each client/hop-port
  pair is logged once (`hop: client ... -> relay port 20004`), which is how hops are visible.
- **Brutal on the server.** Brutal is chosen by the client. The server needs no config for it to work
  (it falls back to BBR when the client sends no rate). `--brutal` additionally sets the server's
  `quicParams` (`brutalUp`/`brutalDown` 100 mbps) so it advertises a receive rate and sends with
  Brutal at min(its rate, the client's). The links carry `up=50 mbps&down=100 mbps`.
- **Salamander is mandatory when set.** A client without the mask is refused; that is row 4's second half.
- **`--access-log`.** Xray's hysteria inbound writes nothing to the access-log file, so this means
  `loglevel: debug` on stdout: `tunnelling request to tcp:host:port` per request, and the
  `from <client>:<port> accepted ...` line.

## Smoke test on the Mac

```sh
./smoke.sh                                   # plain
./smoke.sh --salamander obfspw1              # also checks a client without obfs is refused
./smoke.sh --hop 20000-20010                 # fetches, waits 12 s (> 2 hop intervals), fetches again
./smoke.sh --brutal
```

It starts the server with the given flags, connects a local Xray client (socks on `127.0.0.1:18080`)
and fetches `https://example.com` through it. A fetch that works with the server stopped would mean the
client is not using the tunnel; `smoke.sh` uses `--socks5-hostname` because `curl --noproxy '*'`
disables `-x` as well and silently fetches direct.

## Pointing the phone at it

The phone must be on the same Wi-Fi as the Mac and macOS must allow incoming UDP for `xray` (and
`python3` for `--hop`). Paste a link into the app's add-server flow, or serve a one-line subscription
through `adb reverse tcp:28080 tcp:28080` (the app allows cleartext only to 127.0.0.1).
