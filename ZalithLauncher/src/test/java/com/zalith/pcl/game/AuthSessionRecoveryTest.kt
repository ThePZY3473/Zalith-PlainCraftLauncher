package com.zalith.pcl.game.account.auth_server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AuthSessionRecoveryTest {
    @Test
    fun `successful request does not attempt recovery`() {
        runBlocking {
            val result = recoverAuthSessionOnce(request = { "original" }, recover = { error("unexpected recovery") })
            assertEquals("original", result)
        }
    }

    @Test
    fun `403 attempts exactly one recovery and returns its result`() {
        runBlocking {
            var attempts = 0
            val result = recoverAuthSessionOnce(
                request = { throw ResponseException("Forbidden", 403) },
                recover = { attempts++; "recovered" },
            )
            assertEquals("recovered", result)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `403 during recovery is returned without another retry`() {
        runBlocking {
            val denied = ResponseException("Recovery rejected", 403)
            var attempts = 0
            val result = runCatching {
                recoverAuthSessionOnce<Unit>(
                    request = { throw ResponseException("Forbidden", 403) },
                    recover = { attempts++; throw denied },
                )
            }
            assertSame(denied, result.exceptionOrNull())
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `other errors and cancellation do not attempt recovery`() {
        runBlocking {
            for (failure in listOf(ResponseException("Unavailable", 503), CancellationException())) {
                val result = runCatching {
                    recoverAuthSessionOnce(request = { throw failure }, recover = { error("unexpected recovery") })
                }
                assertSame(failure, result.exceptionOrNull())
            }
        }
    }
}
