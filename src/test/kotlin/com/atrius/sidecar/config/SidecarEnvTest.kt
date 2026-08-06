package com.atrius.sidecar.config

import com.atrius.sidecar.server.routes.adminAuthorized
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock

@ResourceLock(value = "sidecar-env", mode = ResourceAccessMode.READ_WRITE)
class SidecarEnvTest {
    private val values = mutableMapOf<String, String?>()

    @AfterEach
    fun reset() {
        values.clear()
        SidecarEnv.getenv = { System.getenv(it) }
    }

    private fun install() {
        SidecarEnv.getenv = { key -> values[key] }
    }

    @Test
    fun unsetEnv_isDev_adminOpenWithoutToken() {
        install()
        assertFalse(SidecarEnv.isNonDev())
        assertTrue(adminAuthorized(null))
    }

    @Test
    fun stagingWithoutToken_failsRequire() {
        values["SIDECAR_ENV"] = "staging"
        install()
        assertTrue(SidecarEnv.isNonDev())
        assertThrows(IllegalStateException::class.java) {
            SidecarEnv.requireAdminTokenInNonDev()
        }
        assertFalse(adminAuthorized(null))
    }

    @Test
    fun stagingWithToken_requiresBearer() {
        values["SIDECAR_ENV"] = "staging"
        values["SIDECAR_ADMIN_TOKEN"] = "secret"
        install()
        SidecarEnv.requireAdminTokenInNonDev()
        assertFalse(adminAuthorized(null))
        assertFalse(adminAuthorized("Bearer wrong"))
        assertTrue(adminAuthorized("Bearer secret"))
    }

    @Test
    fun developmentAllowsOpenAdminWhenTokenUnset() {
        values["SIDECAR_ENV"] = "development"
        install()
        assertFalse(SidecarEnv.isNonDev())
        SidecarEnv.requireAdminTokenInNonDev()
        assertTrue(adminAuthorized(null))
    }
}
