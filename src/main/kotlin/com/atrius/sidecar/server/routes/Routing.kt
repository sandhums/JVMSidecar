package com.atrius.sidecar.server.routes

import com.atrius.sidecar.api.ApplyPlanDefinitionRequest
import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.api.HealthResponse
import com.atrius.sidecar.cql.SidecarEvaluator
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.SidecarLibraryCacheAdmin
import com.atrius.sidecar.cr.SidecarPlanDefinitionApplier
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Routing.sidecarRoutes(evaluator: SidecarEvaluator, planApplier: SidecarPlanDefinitionApplier) {
    get("/health") { call.respond(HealthResponse(status = "ok")) }

    get("/metrics") { call.respond(SidecarMetrics.snapshot()) }

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
}

/**
 * When [SIDECAR_ADMIN_TOKEN] is set, require `Authorization: Bearer <token>` on admin routes.
 * When unset, admin routes are open (local dev only).
 */
internal fun adminAuthorized(authorizationHeader: String?): Boolean {
    val required = System.getenv("SIDECAR_ADMIN_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    if (required == null) return true
    val header = authorizationHeader?.trim() ?: return false
    if (!header.startsWith("Bearer ", ignoreCase = true)) return false
    return header.substring(7).trim() == required
}
