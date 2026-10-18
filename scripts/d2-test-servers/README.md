# Local D2 test servers

One Xray v26.10.10 process on the Mac with four inbounds, for the device rows in
`docs/agent/specs/2026-10-18-m9d2-wireguard-http-kcp-httpupgrade-design.md` §6 (results in
`docs/agent/research/2026-10-18-m9d2-device-verification.md`).

| Inbound | Port | Link / import |
|---|---|---|
| VLESS over mKCP, `mkcp-legacy` seed mask + `wechat` header mask, plaintext | udp 9601 | `vless://…?type=kcp&seed=…&headerType=wechat-video` |
| VLESS over HTTPUpgrade (`/d2up`), plaintext | tcp 9602 | `vless://…?type=httpupgrade&path=%2Fd2up` |
| HTTP proxy, user `d2user` + generated password | tcp 9603 | Clash YAML `type: http` |
| WireGuard (one peer, `10.9.0.2/32`) | udp 9604 | `wireguard://…` and Clash YAML `type: wireguard` |

Nothing secret is committed. The UUID, mKCP seed, WireGuard keys and HTTP password are generated at run
time into `.run/secrets.env` (gitignored, mode 600) and reused on later starts so the links stay valid;
`--fresh` mints new ones.

## Run it

```sh
./run.sh                 # listens on `ipconfig getifaddr en0`
./run.sh --host IP       # another address
./run.sh --config-only   # write config, links and .run/d2-clash.yaml, do not start
```

Needs a v26.10.10 Xray: `/private/tmp/claude-501/xray-26.10.10`, or set `XRAY_BIN`. It prints one link per
row and writes `.run/d2-clash.yaml` (the HTTP proxy and WireGuard entries) for serving as a subscription;
the app allows cleartext only to 127.0.0.1, so serve it through `adb reverse tcp:28080 tcp:28080`.
Ports: `--kcp-port`, `--upgrade-port`, `--http-port`, `--wg-port`.

## The plaintext rule and the Mac's LAN address

The mKCP and HTTPUpgrade links are VLESS with no TLS. Xray v26.10.10 refuses that at build time unless the
server address is private (ARCHITECTURE.md §14.10). "Private" means RFC 1918, loopback or link-local, so
**a LAN such as `172.100.14.x` (outside 172.16/12) is public to the core** and the phone gets
`CoreRefusesPlaintextTransport` for both links; `run.sh` prints a warning when `--host` is not private.
The HTTP proxy and WireGuard rows are not affected. For the plaintext rows:

- HTTPUpgrade (TCP): `adb reverse tcp:9602 tcp:9602`, run with `--host 127.0.0.1`, and edit the link's
  host to `127.0.0.1`.
- mKCP (UDP): `adb reverse` is TCP-only, so the phone needs a genuinely private route to the Mac
  (a 10.x / 192.168.x / 172.16-31 network).

## How it works

- **Egress.** The Mac's DNS is fake-IP under its TUN VPN, so a `freedom` outbound would blackhole. TCP leaves
  through an `http` outbound to the Mac's HTTP proxy at `127.0.0.1:10808` (`--egress`); UDP 53 goes to a
  `dns` outbound with DoH `https://1.1.1.1/dns-query`; other UDP is blocked. (Only `freedom` to a private
  target would need `finalRules` allow; nothing here uses `freedom`.)
- **mKCP.** Xray v26.10.10 ignores `kcpSettings.header`/`seed`; obfuscation is `finalmask.udp` `mkcp-legacy`
  masks in the order `[seed, header]`.
- **WireGuard.** `xray wg` makes the key pairs. The tunnel carries IP packets, so the client resolves names
  itself (through the tunnel, to UDP 53, which the server answers via DoH).

## Smoke test on the Mac

```sh
./smoke.sh
```

Starts the servers in a scratch run dir on `127.0.0.1` (private for the core), connects a local Xray client
to each of the four and fetches `https://example.com` with `curl --socks5-hostname` and the proxy
environment variables unset, then checks that a client with the wrong mKCP seed is refused. Passing
`--host` forwards to `run.sh`.
