package com.atrius.sidecar.cql

import kotlinx.io.Source
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.model.Model
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.cqframework.cql.cql2elm.tracking.Trackable.withResultType
import org.cqframework.cql.elm.serializing.DefaultElmLibraryReaderProvider
import org.cqframework.cql.elm.serializing.ElmLibraryReader
import org.cqframework.cql.elm.serializing.ElmLibraryReaderProvider
import org.cqframework.cql.shared.QName
import org.hl7.cql.model.ChoiceType
import org.hl7.cql.model.DataType
import org.hl7.cql.model.IntervalType
import org.hl7.cql.model.ListType
import org.hl7.cql.model.TupleType
import org.hl7.cql.model.TupleTypeElement
import org.hl7.elm.r1.BinaryExpression
import org.hl7.elm.r1.CalculateAge
import org.hl7.elm.r1.ChoiceTypeSpecifier
import org.hl7.elm.r1.Concatenate
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.Expression
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.First
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.If
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.ListTypeSpecifier
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.NaryExpression
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.SingletonFrom
import org.hl7.elm.r1.TernaryExpression
import org.hl7.elm.r1.TupleTypeSpecifier
import org.hl7.elm.r1.TypeSpecifier
import org.hl7.elm.r1.UnaryExpression

/** Namespace often used on ELM literals / named types; maps to the System model for primitives. */
private const val CQL_SPEC_TYPES_NS = "urn:hl7-org:cql-spec:r1"

/**
 * When ELM omits an explicit FHIR version on `usings`, assume R4 — aligns with FHIRHelpers 4.0.x and
 * this sidecar's runtime (`hapi-fhir-structures-r4`).
 */
private const val DEFAULT_FHIR_MODEL_VERSION = "4.0.1"

private fun fhirModelVersionForLibrary(library: Library): String =
    library.usings?.def
        ?.firstOrNull { it.localIdentifier == "FHIR" }
        ?.version
        ?.takeIf { !it.isNullOrBlank() }
        ?: DEFAULT_FHIR_MODEL_VERSION

/**
 * CQ Framework stores semantic [Element.resultType] in [org.cqframework.cql.cql2elm.tracking.Trackable]
 * side storage while compiling CQL; deserialized ELM only has `resultTypeSpecifier` /
 * `resultTypeName`. [org.cqframework.cql.cql2elm.LibraryManager.generateCompiledLibrary] requires the
 * tracked type, so we hydrate it before evaluation.
 *
 * CQL Studio ELM often omits `resultTypeSpecifier` on statement-level [ExpressionDef]s but carries
 * enough structure (`Literal.valueType`, `Retrieve.dataType`, root expression shape) for us to infer
 * types when needed.
 */
internal fun hydratingElmLibraryReaderProvider(modelManager: ModelManager): ElmLibraryReaderProvider =
    HydratingElmLibraryReaderProvider(DefaultElmLibraryReaderProvider, modelManager)

private class HydratingElmLibraryReaderProvider(
    private val delegate: ElmLibraryReaderProvider,
    private val modelManager: ModelManager,
) : ElmLibraryReaderProvider {
    override fun create(contentType: String): ElmLibraryReader =
        HydratingElmLibraryReader(delegate.create(contentType), modelManager)
}

private class HydratingElmLibraryReader(
    private val delegate: ElmLibraryReader,
    private val modelManager: ModelManager,
) : ElmLibraryReader {
    override fun read(string: String): Library =
        delegate.read(string).also { hydrateElmTrackableResultTypes(it, modelManager) }

    override fun read(source: Source): Library =
        delegate.read(source).also { hydrateElmTrackableResultTypes(it, modelManager) }
}

private fun hydrateElmTrackableResultTypes(library: Library, modelManager: ModelManager) {
    val fhirVersion = fhirModelVersionForLibrary(library)
    runCatching { modelManager.resolveModel("System") }
    runCatching { modelManager.resolveModel("FHIR", fhirVersion) }

    library.statements?.def?.forEach { stmt ->
        if (stmt is ExpressionDef) {
            hydrateExpressionsDeep(stmt.expression, modelManager, fhirVersion)
            ensureElementHasTrackableResultType(stmt, modelManager, fhirVersion)
            if (stmt.resultType == null) {
                inferExpressionDefResultType(stmt, modelManager, fhirVersion)
            }
            if (stmt is FunctionDef) {
                stmt.operand.forEach { op ->
                    op.operandTypeSpecifier?.let { spec ->
                        resolveElmR1TypeSpecifier(spec, modelManager, fhirVersion)?.let { dt ->
                            if (op.resultType == null) op.withResultType(dt)
                        }
                    }
                }
                if (stmt.resultType == null) {
                    inferExpressionDefResultType(stmt, modelManager, fhirVersion)
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun hydrateExpressionsDeep(expr: Expression?, modelManager: ModelManager, fhirVersion: String) {
    if (expr == null) return
    when (expr) {
        is UnaryExpression -> hydrateExpressionsDeep(expr.operand, modelManager, fhirVersion)
        is BinaryExpression -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, fhirVersion) }
        is NaryExpression -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, fhirVersion) }
        is TernaryExpression -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, fhirVersion) }
        is First -> hydrateExpressionsDeep(expr.source, modelManager, fhirVersion)
        is Property -> hydrateExpressionsDeep(expr.source, modelManager, fhirVersion)
        is FunctionRef -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, fhirVersion) }
        is SingletonFrom -> hydrateExpressionsDeep(expr.operand, modelManager, fhirVersion)
        is Query -> {
            expr.source.forEach { hydrateExpressionsDeep(it.expression, modelManager, fhirVersion) }
            expr.where?.let { hydrateExpressionsDeep(it, modelManager, fhirVersion) }
            expr.`return`?.expression?.let { hydrateExpressionsDeep(it, modelManager, fhirVersion) }
        }
        is If -> {
            hydrateExpressionsDeep(expr.condition, modelManager, fhirVersion)
            hydrateExpressionsDeep(expr.then, modelManager, fhirVersion)
            hydrateExpressionsDeep(expr.`else`, modelManager, fhirVersion)
        }
        else -> {}
    }
    ensureElementHasTrackableResultType(expr, modelManager, fhirVersion)
}

private fun ensureElementHasTrackableResultType(element: Element, modelManager: ModelManager, fhirVersion: String) {
    if (element.resultType != null) return
    val dataType = resolveDataTypeForElmElement(element, modelManager, fhirVersion) ?: return
    element.withResultType(dataType)
}

@Suppress("ReturnCount")
private fun resolveDataTypeForElmElement(element: Element, modelManager: ModelManager, fhirVersion: String): DataType? {
    when (element) {
        is Literal ->
            element.valueType?.let {
                return resolveElmQName(it, modelManager, fhirVersion)
            }
        is Retrieve ->
            element.dataType?.let {
                return resolveElmQName(it, modelManager, fhirVersion)
            }
        else -> {}
    }
    element.resultTypeSpecifier?.let { spec ->
        resolveElmR1TypeSpecifier(spec, modelManager, fhirVersion)?.let { dt ->
            if (spec.resultType == null) {
                spec.withResultType(dt)
            }
            return dt
        }
    }
    element.resultTypeName?.let { return resolveElmQName(it, modelManager, fhirVersion) }
    return null
}

@Suppress("ReturnCount")
private fun inferExpressionDefResultType(def: ExpressionDef, modelManager: ModelManager, fhirVersion: String) {
    if (def.resultType != null) return
    val expr = def.expression ?: return
    inferExpressionResultType(expr, modelManager, fhirVersion)?.let { def.withResultType(it) }
}

@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun inferExpressionResultType(expr: Expression?, modelManager: ModelManager, fhirVersion: String): DataType? {
    if (expr == null) return null
    expr.resultType?.let { return it }
    when (expr) {
        is Literal ->
            expr.valueType?.let { return resolveElmQName(it, modelManager, fhirVersion) }
        is Retrieve ->
            expr.dataType?.let { return resolveElmQName(it, modelManager, fhirVersion) }
        is SingletonFrom -> {
            val op = expr.operand
            if (op is Retrieve) {
                op.dataType?.let { return resolveElmQName(it, modelManager, fhirVersion) }
            }
            return inferExpressionResultType(op, modelManager, fhirVersion)
        }
        is Concatenate ->
            return systemStringType(modelManager)
        is CalculateAge ->
            return modelManager.resolveModel("System").resolveTypeName("Integer")
        is Query -> {
            val srcExpr = expr.source.firstOrNull()?.expression
            if (srcExpr is Retrieve) {
                val elem =
                    srcExpr.dataType?.let { resolveElmQName(it, modelManager, fhirVersion) } ?: return null
                return ListType(elem)
            }
            return null
        }
        is If -> {
            val t = inferExpressionResultType(expr.then, modelManager, fhirVersion)
            val e = inferExpressionResultType(expr.`else`, modelManager, fhirVersion)
            return when {
                t != null && e != null && t == e -> t
                t != null -> t
                else -> e
            }
        }
        else -> {}
    }
    return null
}

private fun systemStringType(modelManager: ModelManager): DataType? =
    modelManager.resolveModel("System").resolveTypeName("String")

@Suppress("ReturnCount")
private fun resolveElmR1TypeSpecifier(spec: TypeSpecifier, modelManager: ModelManager, fhirVersion: String): DataType? {
    return when (spec) {
        is NamedTypeSpecifier ->
            spec.name?.let { resolveElmQName(it, modelManager, fhirVersion) }
        is ListTypeSpecifier -> {
            val inner = spec.elementType ?: return null
            val elemType = resolveElmR1TypeSpecifier(inner, modelManager, fhirVersion) ?: return null
            ListType(elemType)
        }
        is IntervalTypeSpecifier -> {
            val inner = spec.pointType ?: return null
            val pointType = resolveElmR1TypeSpecifier(inner, modelManager, fhirVersion) ?: return null
            IntervalType(pointType)
        }
        is TupleTypeSpecifier -> {
            val elements = mutableListOf<TupleTypeElement>()
            for (tel in spec.element) {
                val name = tel.name ?: return null
                val tspec = tel.elementType ?: return null
                val dt = resolveElmR1TypeSpecifier(tspec, modelManager, fhirVersion) ?: return null
                elements.add(TupleTypeElement(name, dt))
            }
            TupleType(elements)
        }
        is ChoiceTypeSpecifier -> {
            val choices = mutableListOf<DataType>()
            for (inner in spec.choice) {
                val dt = resolveElmR1TypeSpecifier(inner, modelManager, fhirVersion) ?: return null
                choices.add(dt)
            }
            ChoiceType(choices)
        }
        else -> null
    }
}

@Suppress("NestedBlockDepth", "ReturnCount")
private fun resolveElmQName(qname: QName, modelManager: ModelManager, fhirVersion: String): DataType? {
    var local = qname.localPart ?: return null
    val ns = qname.namespaceURI?.takeIf { it.isNotBlank() }

    // Some ELM serializers put "fhir:Patient" / "t:String" in localPart without namespace URI.
    if (local.contains(':') && (ns.isNullOrBlank() || ns == "urn:hl7-org:elm:r1")) {
        val idx = local.indexOf(':')
        val pfx = local.substring(0, idx)
        val name = local.substring(idx + 1)
        when (pfx) {
            "fhir" ->
                runCatching { modelManager.resolveModel("FHIR", fhirVersion) }
                    .getOrNull()
                    ?.let { model ->
                        model.resolveTypeName("FHIR.$name") ?: model.resolveTypeName(name)
                    }?.let { return it }
            "t" ->
                runCatching { modelManager.resolveModel("System") }
                    .getOrNull()
                    ?.resolveTypeName(name)
                    ?.let { return it }
        }
    }

    val models = LinkedHashSet<Model>()
    runCatching { models.add(modelManager.resolveModel("System")) }
    runCatching { models.add(modelManager.resolveModel("FHIR", fhirVersion)) }

    if (!ns.isNullOrBlank() && ns != CQL_SPEC_TYPES_NS) {
        runCatching { models.add(modelManager.resolveModelByUri(ns)) }
    }

    val candidates = mutableListOf(local, "System.$local")

    for (model in models) {
        val modelName = model.modelInfo.name ?: continue
        val perModel =
            buildList {
                addAll(candidates)
                add("$modelName.$local")
            }
        for (c in perModel.distinct()) {
            model.resolveTypeName(c)?.let {
                return it
            }
        }
    }
    return null
}
