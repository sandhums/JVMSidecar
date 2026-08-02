package com.atrius.sidecar

import com.atrius.sidecar.config.SidecarEnv
import com.atrius.sidecar.server.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    SidecarEnv.requireAdminTokenInNonDev()
    val port = System.getenv("SIDECAR_PORT")?.toIntOrNull() ?: 8088
    embeddedServer(Netty, port = port) { module() }.start(wait = true)
}
