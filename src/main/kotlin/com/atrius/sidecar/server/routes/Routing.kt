package com.atrius.sidecar.server.routes

import com.atrius.sidecar.api.ApplyActivityDefinitionRequest
import com.atrius.sidecar.api.ApplyPlanDefinitionRequest
import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.api.HealthResponse
import com.atrius.sidecar.config.SidecarEnv
import com.atrius.sidecar.cql.SidecarEvaluator
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.SidecarLibraryCacheAdmin
import com.atrius.sidecar.cr.SidecarActivityDefinitionApplier
import com.atrius.sidecar.cr.SidecarPlanDefinitionApplier
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.accept
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val PROMETHEUS_CONTENT_TYPE =
    ContentType.parse("text/plain; version=0.0.4; charset=utf-8")

fun Routing.sidecarRoutes(
    evaluator: SidecarEvaluator,
    planApplier: SidecarPlanDefinitionApplier,
    activityApplier: SidecarActivityDefinitionApplier,
) {
    get("/health") { call.respond(HealthResponse(status = "ok")) }

    // Default: Prometheus text for scrapers. JSON via Accept or /metrics.json.
    get("/metrics") {
        if (wantsJsonMetrics(call.request.accept(), call.request.queryParameters["format"])) {
            call.respond(SidecarMetrics.snapshot())
        } else {
            call.respondText(SidecarMetrics.prometheusText(), PROMETHEUS_CONTENT_TYPE)
        }
    }

    get("/metrics.json") { call.respond(SidecarMetrics.snapshot()) }

    route("/v1/admin/cache/libraries/clear") {
        post {
            if (!adminAuthorized(call.request.headers["Authorization"])) {
                call.respond(HttpStatusCode.Unauthorized, "missing or invalid admin token")
                return@post
            }
            call.respond(SidecarLibraryCacheAdmin.clearLibraryCaches())
        }
    }

    route("/v1/evaluate/expression") {
        post {
            val body = call.receive<EvaluateExpressionRequest>()
            call.respond(evaluator.evaluate(body))
        }
    }

    route("/v1/plandefinition/apply") {
        post {
            val body = call.receive<ApplyPlanDefinitionRequest>()
            call.respond(planApplier.apply(body))
        }
    }

    route("/v1/activitydefinition/apply") {
        post {
            val body = call.receive<ApplyActivityDefinitionRequest>()
            call.respond(activityApplier.apply(body))
        }
    }
}

private fun wantsJsonMetrics(accept: String?, format: String?): Boolean {
    if (format.equals("json", ignoreCase = true)) return true
    val a = accept ?: return false
    return a.contains("application/json", ignoreCase = true) &&
        !a.contains("text/plain", ignoreCase = true)
}

/**
 * When [SIDECAR_ADMIN_TOKEN] is set, require `Authorization: Bearer <token>` on admin routes.
 * When unset in development, admin routes are open. Non-dev refuses to start without a token
 * (see [SidecarEnv.requireAdminTokenInNonDev]).
 */
internal fun adminAuthorized(authorizationHeader: String?): Boolean {
    val required = SidecarEnv.adminToken()
    if (required == null) {
        // Non-dev should never reach here without a token (startup check), but deny if it does.
        return !SidecarEnv.isNonDev()
    }
    val header = authorizationHeader?.trim() ?: return false
    if (!header.startsWith("Bearer ", ignoreCase = true)) return false
    return header.substring(7).trim() == required
}
