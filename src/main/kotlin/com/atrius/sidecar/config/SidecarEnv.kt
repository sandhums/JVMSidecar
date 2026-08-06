package com.atrius.sidecar.config

/**
 * Sidecar runtime environment from `SIDECAR_ENV`.
 *
 * Unset / `development` / `dev` / `local` / `test` → local-friendly (admin token optional).
 * Any other value (e.g. `staging`, `production`) → non-dev: `SIDECAR_ADMIN_TOKEN` required.
 */
object SidecarEnv {
    /** Overridable in unit tests; production uses [System.getenv]. */
    @Volatile
    internal var getenv: (String) -> String? = { System.getenv(it) }

    fun raw(): String? = getenv("SIDECAR_ENV")?.trim()?.takeIf { it.isNotEmpty() }

    fun isNonDev(): Boolean {
        val env = raw()?.lowercase() ?: return false
        return env !in setOf("development", "dev", "local", "test")
    }

    fun adminToken(): String? =
        getenv("SIDECAR_ADMIN_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Fail fast in non-dev when the admin bearer token is missing so cache-clear
     * cannot stay open by accident.
     */
    fun requireAdminTokenInNonDev() {
        if (!isNonDev()) return
        if (adminToken() == null) {
            val env = raw() ?: "unknown"
            error(
                "SIDECAR_ENV=$env requires SIDECAR_ADMIN_TOKEN to be set " +
                    "(admin cache clear must not be open outside development)",
            )
        }
    }
}
