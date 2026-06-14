package com.atrius.sidecar.server

import com.atrius.sidecar.cql.SidecarEvaluator
import com.atrius.sidecar.cr.SidecarActivityDefinitionApplier
import com.atrius.sidecar.cr.SidecarPlanDefinitionApplier
import com.atrius.sidecar.server.routes.sidecarRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun Application.module() {
    val evaluator = SidecarEvaluator()
    val planApplier = SidecarPlanDefinitionApplier()
    val activityApplier = SidecarActivityDefinitionApplier()

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }

    install(StatusPages) { configureStatusPages() }

    routing { sidecarRoutes(evaluator, planApplier, activityApplier) }
}
