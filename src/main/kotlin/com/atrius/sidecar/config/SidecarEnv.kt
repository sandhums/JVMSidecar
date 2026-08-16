package com.atrius.sidecar.config

/**
 * Sidecar runtime environment from `SIDECAR_ENV`.
 *
 * Unset / `development` / `dev` / `local` / `test` → local-friendly (admin token optional;
 * empty FHIR allowlist means allow all).
 * Any other value (e.g. `staging`, `production`) → non-dev: `SIDECAR_ADMIN_TOKEN` and
 * `SIDECAR_ALLOWED_FHIR_BASES` required.
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
     * Allowed FHIR HTTP bases (clinical / terminology / KR). Empty in development means allow all.
     */
    fun allowedFhirBases(): List<String> =
        getenv("SIDECAR_ALLOWED_FHIR_BASES")
            ?.split(',')
            ?.map { it.trim().trimEnd('/') }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun connectTimeoutMs(): Int =
        getenv("SIDECAR_FHIR_CONNECT_TIMEOUT_MS")?.toIntOrNull()?.takeIf { it > 0 } ?: 10_000

    fun socketTimeoutMs(): Int =
        getenv("SIDECAR_FHIR_SOCKET_TIMEOUT_MS")?.toIntOrNull()?.takeIf { it > 0 } ?: 30_000

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

    /**
     * Fail fast in non-dev when no FHIR base allowlist is configured (SSRF guard).
     */
    fun requireAllowlistInNonDev() {
        if (!isNonDev()) return
        if (allowedFhirBases().isEmpty()) {
            val env = raw() ?: "unknown"
            error(
                "SIDECAR_ENV=$env requires SIDECAR_ALLOWED_FHIR_BASES to be set " +
                    "(comma-separated hfs/hts/kr bases; evaluate/\$apply must not fetch arbitrary URLs)",
            )
        }
    }

    fun requireStartupConfig() {
        requireAdminTokenInNonDev()
        requireAllowlistInNonDev()
    }

    /**
     * Trim [url] and reject it when a non-empty allowlist is configured and the base is absent.
     * Development with an empty allowlist permits any base (local DX).
     */
    fun requireAllowedFhirBase(url: String?, fieldName: String): String {
        val trimmed =
            url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("$fieldName must not be blank")
        val allowed = allowedFhirBases()
        if (allowed.isEmpty()) return trimmed
        if (trimmed !in allowed) {
            throw IllegalArgumentException(
                "$fieldName '$trimmed' is not in SIDECAR_ALLOWED_FHIR_BASES",
            )
        }
        return trimmed
    }
}
