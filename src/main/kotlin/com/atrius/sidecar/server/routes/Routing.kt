package com.atrius.sidecar.server.routes

import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.api.ApplyPlanDefinitionRequest
import com.atrius.sidecar.api.HealthResponse
import com.atrius.sidecar.cql.SidecarEvaluator
import com.atrius.sidecar.cr.SidecarPlanDefinitionApplier
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Routing.sidecarRoutes(evaluator: SidecarEvaluator, planApplier: SidecarPlanDefinitionApplier) {
    get("/health") { call.respond(HealthResponse(status = "ok")) }

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
