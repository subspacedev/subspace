// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject

/**
 * Owns the libXray lifecycle for one tunnel session.
 *
 * Every call is slow and runs on IO (ARCHITECTURE.md §5.3) — the connect button must stay
 * responsive through the whole start sequence.
 *
 * Instances are single-use: create one per connection, so a stale protector
 * reference cannot survive a service recreation (ARCHITECTURE.md §5.1).
 *
 * Configs are handed to libXray as JSON text (`xrayJson`); nothing is written to
 * disk for the core. The geo asset location is not a property of the controller:
 * it travels inside each config's own root `env` block
 * ([TunnelSettings.geoAssetDir], `RawConfigComposer`), the only channel
 * libXray v26.10.10 honours (`docs/agent/research/libxray-api.md` §4).
 */
public class XrayController private constructor(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val invocation: XrayControllerInvocation,
) {
    public constructor(
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(io, XrayControllerInvocation())

    internal constructor(
        invocation: XrayControllerInvocation,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(io, invocation)

    /**
     * §10.6: never a hardcoded port. A fixed port collides with other proxy apps.
     *
     * libXray allocates by binding `localhost:0` and closing the listener, so
     * there is a small race between allocation and Xray binding it. Still far
     * better than a literal.
     */
    public suspend fun allocatePort(): Int =
        withContext(io) {
            fetchFreePorts(1).first()
        }

    /**
     * Requests [count] **distinct** free ports.
     *
     * See [allocateDistinctPorts] for why a single `getFreePorts` call is not
     * enough on its own to guarantee that — `docs/agent/research/libxray-api.md`
     * §5 has the upstream source. This is the seam `TunnelService` uses to
     * allocate the SOCKS and loopback HTTP ports together (§10.6: neither is
     * ever a literal).
     *
     * @throws XrayException when [count] distinct ports could not be obtained.
     */
    public suspend fun allocatePorts(count: Int): List<Int> =
        withContext(io) {
            allocateDistinctPorts(count) { n -> fetchFreePorts(n) }
        }

    private fun fetchFreePorts(count: Int): List<Int> {
        val data = LibXrayInvoke.call("getFreePorts", JSONObject().put("count", count))
        val ports = data?.optJSONArray("ports")
        if (ports == null || ports.length() == 0) {
            throw XrayException("libXray returned no free ports")
        }
        return List(ports.length()) { i -> ports.getInt(i) }
    }

    /**
     * ARCHITECTURE.md §6: validate before starting. A malformed config makes libXray fail in a way
     * that is hard to attribute; catching it here produces a real error instead.
     *
     * Takes the JSON text itself, as `testXray` does since v26.10.10 — the caller hands
     * the same string to [start], so the bytes checked are the bytes run, without a
     * file holding the UUID and keys on disk (ARCHITECTURE.md §5.6).
     *
     * `testXray` constructs a real core (`core.New`, research §2), so a
     * `geoip:`/`geosite:`/`ext:` rule resolves geo data here exactly as it would on a
     * live start — the config's own `env` block supplies the asset location to both.
     *
     * **It refuses to run in a process where [start] has a live instance**
     * (`testXray requires an isolated process without a managed Xray instance`,
     * `xray/validation.go:13-14`). Callers in `:bg` must stop the previous core
     * first; `TunnelService.restartCoreRetainingTun` does.
     */
    public suspend fun validate(xrayJson: String) {
        withContext(io) { invocation.testXray(xrayJson) }
    }

    /**
     * Starts the core.
     *
     * [protector] is wired here, on every start, rather than in a constructor or
     * `init` block. ARCHITECTURE.md §5.1 requires re-wiring whenever the service is recreated, and
     * a code path that *cannot* skip the wiring beats one that must remember to.
     * "Wiring" is the [protector] swap only: the Go-side registration happens once
     * per process — see [XrayControllerInvocation.retainProtector].
     *
     * There is deliberately no DNS call. libXray v26.10.10 does have `setDNS`
     * (research §3), which replaces Go's process-wide resolver; Subspace does not
     * use it, so the generated config remains the only place DNS is configured.
     * ARCHITECTURE.md §5.2 needs three levers, and this one carries two of them:
     * the config's `dns` block and the **port-53 hijack** — a routing rule sending
     * all port-53 traffic to the `dns-out` outbound, which is the lever that
     * reaches an app ignoring the advertised resolver.
     * `VpnService.Builder.addDnsServer()` is the third and lives in the service.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun start(
        xrayJson: String,
        protector: SocketProtector,
    ) {
        withContext(io) {
            // gomobile maps Go's int to a Java long; VpnService.protect() takes an
            // int, so the bridge narrows here. libXray v26.10.10 honours the
            // returned boolean: a false aborts that dial or listen with
            // "protect Android VPN socket failed" (controller/controller.go). The
            // protector must be retained before runXray, or the core's first
            // dials fail. SocketProtector's implementation must log a false.
            try {
                invocation.retainProtector(protector)
                invocation.runXray(xrayJson)
            } catch (error: Throwable) {
                invocation.clearProtector()
                throw error
            }
        }
    }

    /** True when the core reports itself running. ARCHITECTURE.md §5.5 — never infer this locally. */
    public suspend fun isRunning(): Boolean =
        withContext(io) {
            LibXrayInvoke.call("getXrayState")?.optBoolean("running", false) ?: false
        }

    /**
     * Idempotent — teardown runs from disconnect, onRevoke, and onDestroy (ARCHITECTURE.md §5.4).
     *
     * The broad catch here is the one permitted in this milestone: ARCHITECTURE.md §5.4 requires
     * teardown to complete even when a step fails, because a leaked fd wedges the
     * VPN subsystem until reboot. Stopping an already-stopped core is not an error
     * worth propagating.
     */
    @Suppress("SwallowedException")
    public suspend fun stop() {
        withContext(io) { stopBlocking() }
    }

    /**
     * Same as [stop], without a coroutine.
     *
     * `VpnService.onDestroy` and `onRevoke` have no scope that outlives them, and
     * ARCHITECTURE.md §5.4 requires teardown to finish before the process goes away — a leaked fd
     * wedges the VPN subsystem until reboot. Suspending there would mean either
     * abandoning the teardown or wrapping it in `runBlocking`, which ARCHITECTURE.md §12 bans.
     *
     * This does block its caller. That is a deliberate, bounded exception to
     * ARCHITECTURE.md §5.3: stopping the core is a single JSON call into an already-running Go
     * runtime, and ARCHITECTURE.md §5.4's "must complete" outranks ARCHITECTURE.md §5.3's "must not block" on the
     * teardown path specifically. Do not use this on the start path.
     */
    @Suppress("SwallowedException")
    public fun stopBlocking() {
        // Drop the protector first. Go keeps its reference to ProtectorHolder
        // forever — there is no unregister — so this is the only way to stop a
        // finished session's VpnService from being reachable from native code.
        invocation.clearProtector()
        try {
            invocation.stopXray()
        } catch (e: XrayException) {
            // Deliberately swallowed — see the KDoc above. Not logged, because
            // the message can quote the config (ARCHITECTURE.md §5.6) and the caller already
            // publishes a state transition.
        }
    }
}

/** The `testXray`/`runXray` payload: the config itself, never a path (v26.10.10, research §2). */
internal fun xrayJsonPayload(xrayJson: String): String = buildJsonObject { put("xrayJson", xrayJson) }.toString()

/**
 * Registers the dialer and listener controllers with libXray **once**.
 *
 * libXray appends on every registration and never replaces or removes one
 * (`transport/internet/system_dialer.go:195`, `system_listener.go:193`; research §3).
 * Registering on every start would leave one hook per past session, each making its
 * own `protectFd` call on every socket — and since v26.10.10 any one `false` aborts
 * the dial. So the hook is installed once per process and
 * [XrayControllerInvocation]'s holder target is the only switch.
 *
 * Marked done only after [register] returns, so a failed registration is retried by
 * the next start rather than remembered as done.
 */
internal class ControllerRegistration(
    private val register: () -> Unit,
) {
    private var registered = false

    @Synchronized
    fun ensureRegistered() {
        if (!registered) {
            register()
            registered = true
        }
    }
}

/**
 * The process-global protector bridge and the native calls that define its lifetime.
 *
 * [ProtectorHolder] stays private: tests may observe only whether it retains a target,
 * never retrieve the service-backed [SocketProtector] itself. Replacing the native
 * operations lets JVM tests exercise [XrayController.start], [XrayController.validate]
 * and [XrayController.stopBlocking] without loading libXray. The `invoke*` seams take
 * the rendered payload, so a test sees exactly what libXray would.
 */
internal class XrayControllerInvocation(
    private val registration: ControllerRegistration = processRegistration,
    private val invokeRunXray: (String) -> Unit = { payload ->
        LibXrayInvoke.callJson("runXray", payload)
    },
    private val invokeTestXray: (String) -> Unit = { payload ->
        LibXrayInvoke.callJson("testXray", payload)
    },
    private val invokeStopXray: () -> Unit = {
        LibXrayInvoke.call("stopXray")
    },
) {
    fun retainProtector(protector: SocketProtector) {
        registration.ensureRegistered()
        ProtectorHolder.target = protector
    }

    fun runXray(xrayJson: String) {
        invokeRunXray(xrayJsonPayload(xrayJson))
    }

    fun testXray(xrayJson: String) {
        invokeTestXray(xrayJsonPayload(xrayJson))
    }

    fun clearProtector() {
        ProtectorHolder.target = null
    }

    fun stopXray() {
        invokeStopXray()
    }

    fun hasProtectorTarget(): Boolean = ProtectorHolder.target != null

    /**
     * The one object the Go runtime ever holds.
     *
     * libXray's dialer controller is process-global state with no unregister
     * call, so whatever is passed to it lives as long as the process. Passing a
     * lambda that captured the `VpnService` would strand a destroyed service —
     * and its `Context` — in native memory until the process dies.
     *
     * Instead Go holds this singleton forever and we swap [target], so a stopped
     * session leaves nothing but a null field behind. With no target it answers
     * `false`, which since v26.10.10 **aborts** the socket: a Go dial in this
     * process between [clearProtector] and the next [retainProtector] fails
     * rather than leaking into the TUN.
     */
    private object ProtectorHolder : DialerController {
        @Volatile
        var target: SocketProtector? = null

        override fun protectFd(fd: Long): Boolean = target?.protect(fd.toInt()) ?: false
    }

    private companion object {
        /** The process's one registration of [ProtectorHolder] — see [ControllerRegistration]. */
        val processRegistration =
            ControllerRegistration {
                LibXray.registerDialerController(ProtectorHolder)
                LibXray.registerListenerController(ProtectorHolder)
            }
    }
}
