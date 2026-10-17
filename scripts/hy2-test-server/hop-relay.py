#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
"""UDP port-hopping front for the local Hysteria2 test server.

Xray's `hysteria` inbound given a port range opens one independent QUIC listener per port. A
client hopping from port 20003 to port 20007 then talks to a listener that has never heard of its
connection, and the session dies. A real Hysteria2 deployment puts every hop port in front of one
socket with an iptables/pf redirect; this Mac has no passwordless sudo, so this script is that
redirect in user space: it listens on every port in the range and forwards each datagram to the
server's single port, one upstream socket per client address, replying from the port the client
last used.

Usage: hop-relay.py LISTEN_IP FIRST-LAST TARGET_IP TARGET_PORT
Logs the first datagram from each (client address, hop port) pair, which is how hops show up.
"""
import asyncio
import sys
import time


def stamp() -> str:
    return time.strftime("%H:%M:%S")


class Upstream(asyncio.DatagramProtocol):
    """One socket to the real server per client address."""

    def __init__(self, relay, client):
        self.relay, self.client = relay, client

    def datagram_received(self, data, addr):
        self.relay.reply(self.client, data)


class Front(asyncio.DatagramProtocol):
    """One listening hop port."""

    def __init__(self, relay, port):
        self.relay, self.port = relay, port

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        self.relay.forward(self, addr, data)


class Relay:
    def __init__(self, target):
        self.target = target
        self.upstreams = {}
        self.last_front = {}
        self.seen = set()
        self.loop = asyncio.get_running_loop()

    def forward(self, front, client, data):
        if (client, front.port) not in self.seen:
            self.seen.add((client, front.port))
            print(f"{stamp()} hop: client {client[0]}:{client[1]} -> relay port {front.port}", flush=True)
        self.last_front[client] = front
        up = self.upstreams.get(client)
        if up is None:
            task = self.loop.create_task(self.open_upstream(client, data))
            self.upstreams[client] = task
            return
        if isinstance(up, asyncio.Task):
            up.add_done_callback(lambda t, d=data: t.result().sendto(d))
        else:
            up.sendto(data)

    async def open_upstream(self, client, first):
        transport, _ = await self.loop.create_datagram_endpoint(
            lambda: Upstream(self, client), remote_addr=self.target
        )
        self.upstreams[client] = transport
        transport.sendto(first)
        return transport

    def reply(self, client, data):
        front = self.last_front.get(client)
        if front is not None:
            front.transport.sendto(data, client)


async def main():
    listen_ip, span, target_ip, target_port = sys.argv[1:5]
    first, last = (int(p) for p in span.split("-"))
    relay = Relay((target_ip, int(target_port)))
    loop = asyncio.get_running_loop()
    for port in range(first, last + 1):
        await loop.create_datagram_endpoint(lambda p=port: Front(relay, p), local_addr=(listen_ip, port))
    print(f"{stamp()} hop-relay: udp {listen_ip}:{first}-{last} -> {target_ip}:{target_port}", flush=True)
    await asyncio.Event().wait()


asyncio.run(main())
