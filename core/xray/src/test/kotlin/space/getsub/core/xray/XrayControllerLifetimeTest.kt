// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

class XrayControllerLifetimeTest {
    private object ForcedStartFailure : Throwable("TASK4_FORCED_RUN_XRAY_FAILURE")

    private object ForcedRegistrationFailure : Throwable("TASK4_FORCED_CONTROLLER_REGISTRATION_FAILURE")

    private val config = """{"outbounds":[{"tag":"proxy","protocol":"freedom"}]}"""

    private fun noopRegistration() = ControllerRegistration {}

    @Test
    fun `protector lifetime follows failed start successful start and stop`() =
        runTest {
            val startFailure = ForcedStartFailure
            var failureToThrow: Throwable? = startFailure
            val invocation =
                XrayControllerInvocation(
                    registration = noopRegistration(),
                    invokeRunXray = { failureToThrow?.let { throw it } },
                    invokeStopXray = {},
                )
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val controller = XrayController(invocation = invocation, io = testDispatcher)
            val protector = SocketProtector { true }

            try {
                val thrown =
                    shouldThrow<ForcedStartFailure> {
                        controller.start(config, protector)
                    }

                assertSame("start must rethrow the original exception", startFailure, thrown)
                assertFalse("failed start retained its protector", invocation.hasProtectorTarget())

                failureToThrow = null
                controller.start(config, protector)
                assertTrue("successful start dropped its live protector", invocation.hasProtectorTarget())

                controller.stopBlocking()
                assertFalse("stop retained its protector", invocation.hasProtectorTarget())
            } finally {
                invocation.clearProtector()
            }
        }

    @Test
    fun `runXrayPayloadIsTheJsonNotAPath`() =
        runTest {
            var payload: String? = null
            val invocation =
                XrayControllerInvocation(
                    registration = noopRegistration(),
                    invokeRunXray = { payload = it },
                    invokeStopXray = {},
                )
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val controller = XrayController(invocation = invocation, io = testDispatcher)

            try {
                controller.start(config, SocketProtector { true })
            } finally {
                invocation.clearProtector()
            }

            val sent = Json.parseToJsonElement(payload!!).jsonObject
            sent.keys shouldBe setOf("xrayJson")
            sent.getValue("xrayJson").jsonPrimitive.content shouldBe config
        }

    @Test
    fun `testXray payload is the json not a path`() =
        runTest {
            var payload: String? = null
            val invocation =
                XrayControllerInvocation(
                    registration = noopRegistration(),
                    invokeTestXray = { payload = it },
                )
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val controller = XrayController(invocation = invocation, io = testDispatcher)

            controller.validate(config)

            val sent = Json.parseToJsonElement(payload!!).jsonObject
            sent.keys shouldBe setOf("xrayJson")
            sent.getValue("xrayJson").jsonPrimitive.content shouldBe config
            assertFalse("validation must not retain a protector", invocation.hasProtectorTarget())
        }

    /**
     * libXray appends a controller on every register call and never removes one
     * (`docs/agent/research/libxray-api.md` §3), so a registration per start would
     * make every later socket call `protectFd` once per past session.
     */
    @Test
    fun `registers once across two starts`() =
        runTest {
            var registrations = 0
            val registration = ControllerRegistration { registrations++ }
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            // Two controllers, as two sessions get: the guard is the shared
            // registration, not the per-session controller.
            val first =
                XrayControllerInvocation(registration = registration, invokeRunXray = {}, invokeStopXray = {})
            val second =
                XrayControllerInvocation(registration = registration, invokeRunXray = {}, invokeStopXray = {})

            try {
                XrayController(invocation = first, io = testDispatcher).run {
                    start(config, SocketProtector { true })
                    stopBlocking()
                }
                XrayController(invocation = second, io = testDispatcher).start(config, SocketProtector { true })

                registrations shouldBe 1
                assertTrue("the second session must still retain its protector", second.hasProtectorTarget())
            } finally {
                second.clearProtector()
            }
        }

    @Test
    fun `a failed registration clears the protector and is retried on the next start`() =
        runTest {
            var attempts = 0
            var registrationFailure: Throwable? = ForcedRegistrationFailure
            val registration =
                ControllerRegistration {
                    attempts++
                    registrationFailure?.let { throw it }
                }
            val invocation =
                XrayControllerInvocation(registration = registration, invokeRunXray = {}, invokeStopXray = {})
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val controller = XrayController(invocation = invocation, io = testDispatcher)
            val protector = SocketProtector { true }

            try {
                val thrown =
                    shouldThrow<ForcedRegistrationFailure> {
                        controller.start(config, protector)
                    }
                assertSame("start must rethrow the registration exception", ForcedRegistrationFailure, thrown)
                assertFalse("registration failure retained the protector", invocation.hasProtectorTarget())

                registrationFailure = null
                controller.start(config, protector)

                attempts shouldBe 2
                assertTrue("a retried registration must retain the protector", invocation.hasProtectorTarget())
            } finally {
                invocation.clearProtector()
            }
        }
}
