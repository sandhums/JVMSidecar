package com.atrius.sidecar.cql

import org.cqframework.cql.cql2elm.CompilerOptions
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.hl7.cql_annotations.r1.CqlToElmInfo
import org.hl7.elm.r1.Library

/**
 * Align [CqlCompilerOptions] with [CqlToElmInfo] embedded in translated ELM so
 * [org.cqframework.cql.cql2elm.LibraryManager] binary compatibility checks succeed for Studio/tooling output.
 */
internal fun cqlCompilerOptionsFromElmLibrary(library: Library): CqlCompilerOptions {
    val opts = cqlCompilerOptionsSharedFromElmLibrary(library)
    CompilerOptions.getCompilerVersion(library)?.let { opts.compatibilityLevel = it }
    return opts
}

/**
 * Compiler options for libraries loaded from FHIR KR. Keeps the default CQL language level (`1.5`) so
 * included libraries can be recompiled from `text/cql` when pre-translated ELM fails binary checks.
 */
internal fun cqlCompilerOptionsForFhirLibrary(library: Library): CqlCompilerOptions =
    cqlCompilerOptionsSharedFromElmLibrary(library)

private fun cqlCompilerOptionsSharedFromElmLibrary(library: Library): CqlCompilerOptions {
    val opts = CqlCompilerOptions()
    CompilerOptions.getCompilerOptions(library)?.let { parsed ->
        opts.options.clear()
        opts.options.addAll(parsed)
    }
    library.annotation.filterIsInstance<CqlToElmInfo>().firstOrNull()?.signatureLevel?.let { sl ->
        runCatching { opts.signatureLevel = SignatureLevel.valueOf(sl) }
    }
    return opts
}
