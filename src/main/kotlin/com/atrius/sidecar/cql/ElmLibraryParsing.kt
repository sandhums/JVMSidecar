package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ElmFormat
import org.cqframework.cql.elm.serializing.ElmJsonLibraryReader
import org.cqframework.cql.elm.serializing.ElmXmlLibraryReader
import org.hl7.elm.r1.Library

internal fun readElmLibrary(payload: String, format: ElmFormat): Library {
    require(payload.isNotBlank()) { "elm must not be blank" }
    val trimmed = payload.trimStart()
    val effective =
        when (format) {
            ElmFormat.JSON -> ElmFormat.JSON
            ElmFormat.XML -> ElmFormat.XML
            ElmFormat.AUTO ->
                if (trimmed.startsWith("<")) {
                    ElmFormat.XML
                } else {
                    ElmFormat.JSON
                }
        }
    return try {
        when (effective) {
            ElmFormat.JSON -> ElmJsonLibraryReader().read(payload)
            ElmFormat.XML -> ElmXmlLibraryReader().read(payload)
            ElmFormat.AUTO -> error("internal: AUTO must resolve to json or xml")
        }
    } catch (e: Exception) {
        val hint =
            when (effective) {
                ElmFormat.JSON -> "ELM JSON"
                ElmFormat.XML -> "ELM XML"
                ElmFormat.AUTO -> "ELM"
            }
        throw IllegalArgumentException("$hint parse failed: ${e.message}", e)
    }
}

/** Resolved concrete wire format for provider lookup (never [ElmFormat.AUTO]). */
internal fun resolveElmStorageFormat(payload: String, format: ElmFormat): ElmFormat =
    when (format) {
        ElmFormat.JSON -> ElmFormat.JSON
        ElmFormat.XML -> ElmFormat.XML
        ElmFormat.AUTO ->
            if (payload.trimStart().startsWith("<")) {
                ElmFormat.XML
            } else {
                ElmFormat.JSON
            }
    }
