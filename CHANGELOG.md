# Changelog

All notable changes to Subspace are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
`version.properties`. The section for a version becomes its GitHub release notes.

## [Unreleased]

## [0.1.0-alpha03]

The first public build. This is an early alpha: expect rough edges and breaking changes
between versions, and keep another way to reach your server handy.

### What works
- **Protocols:** VLESS (REALITY, XTLS Vision, XHTTP), VMess, Trojan, Shadowsocks, SOCKS,
  HTTP, Hysteria2, WireGuard, plus mKCP and HTTPUpgrade transports. Raw Xray JSON configs
  run as written where possible.
- **Import:** share links, QR code, clipboard, file, and subscription URLs.
- **Subscriptions:** Remnawave and Happ-compatible subscriptions, including HWID, provider
  routing profiles and directives, auto-refresh, and fallback addresses for reaching a
  blocked subscription.
- **Routing:** rule sets with geoip/geosite, routing profiles from deep links or the
  provider, per-profile DNS, and per-app proxy (allow or deny list).
- **Censorship resistance:** TLS fragmentation, UDP noise, DoH pre-resolution of server
  addresses, and subscription fetching through fronting.
- **Tunnel tuning:** sniffing, mux, TUN MTU, excluded routes, and a password-protected
  local proxy.
- **Reliability:** always-on VPN, start on boot, kill switch, recovery after network
  changes, traffic counters and a redacted session log.
- **Interface:** Material 3 Expressive with dynamic colour, light and dark themes.

### Downloads
- Pick the APK for your phone's CPU (`arm64-v8a` for almost every phone from the last
  several years), or `universal` if you are not sure. `SHA256SUMS` lists the checksums.
- Builds are reproducible: see `RELEASING.md` to check an APK against your own build.

### Known limitations
- English only for now; Russian is coming.
- Only tested on arm64 hardware; the `armeabi-v7a` and `x86_64` builds are untested.
- This build is signed with the release key. It cannot be installed over a development
  build signed with another key: uninstall that first (its servers and settings are lost).

## [0.1.0-alpha02]

Tagged but never published: the release workflow stopped at its signing-certificate check,
which could not read the newer `apksigner` output. Fixed in 0.1.0-alpha03.

## [0.1.0-alpha01]

The first internal build, never published.
