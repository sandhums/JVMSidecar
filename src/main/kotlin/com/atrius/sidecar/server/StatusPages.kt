package com.atrius.sidecar.server

import com.atrius.sidecar.api.ErrorResponse
import com.atrius.sidecar.cql.EvaluationFailedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.uri
import io.ktor.server.response.respond

fun StatusPagesConfig.configureStatusPages() {
    exception<IllegalArgumentException> { call, cause ->
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(error = "bad_request", message = cause.message ?: "invalid request", path = call.request.uri),
        )
    }
    exception<EvaluationFailedException> { call, cause ->
        call.respond(
            HttpStatusCode.UnprocessableEntity,
            ErrorResponse(error = "evaluation_failed", message = cause.message ?: "evaluation failed", path = call.request.uri),
        )
    }
    exception<Throwable> { call, cause ->
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse(error = "internal_error", message = cause.message ?: cause.javaClass.simpleName, path = call.request.uri),
        )
    }
}
