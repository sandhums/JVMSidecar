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
import org.hl7.elm.r1.Aggregate
import org.hl7.elm.r1.And
import org.hl7.elm.r1.As
import org.hl7.elm.r1.BinaryExpression
import org.hl7.elm.r1.CalculateAge
import org.hl7.elm.r1.ChoiceTypeSpecifier
import org.hl7.elm.r1.Concatenate
import org.hl7.elm.r1.Count
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.Equivalent
import org.hl7.elm.r1.Exists
import org.hl7.elm.r1.Expression
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.First
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.Greater
import org.hl7.elm.r1.If
import org.hl7.elm.r1.Implies
import org.hl7.elm.r1.In
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.IsNull
import org.hl7.elm.r1.Less
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.ListTypeSpecifier
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.NaryExpression
import org.hl7.elm.r1.Not
import org.hl7.elm.r1.Or
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
private const val ELM_TYPES_NS = "urn:hl7-org:elm-types:r1"
private const val FHIR_NS = "http://hl7.org/fhir"

/**
 * When ELM omits an explicit FHIR version on `usings`, assume R4 — aligns with FHIRHelpers 4.0.x and
 * this sidecar's runtime (`hapi-fhir-structures-r4`).
 */
private const val DEFAULT_FHIR_MODEL_VERSION = "4.0.1"

/** eCQM 2025 / QI-Core STU6 libraries often omit QICore version on ELM `usings`. */
private const val DEFAULT_QICORE_MODEL_VERSION = "6.0.0"

private const val ATRIUS_IN_NS = "AtriusIn"

internal fun resolveLibraryUsings(library: Library, modelManager: ModelManager) {
    library.usings?.def?.forEach { using ->
        val id = using.localIdentifier?.takeIf { it.isNotBlank() } ?: return@forEach
        var version = using.version?.takeIf { !it.isNullOrBlank() }
        if (id == "QICore" && version.isNullOrBlank()) {
            version = DEFAULT_QICORE_MODEL_VERSION
        }
        if (id == ATRIUS_IN_MODEL_NAME && version.isNullOrBlank()) {
            version = ATRIUS_IN_MODEL_VERSION
        }
        runCatching { modelManager.resolveModel(id, version) }
        using.uri?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { modelManager.resolveModelByUri(uri) }
        }
    }
}

private fun fhirModelVersionForLibrary(library: Library): String =
    library.usings?.def
        ?.firstOrNull { it.localIdentifier == "FHIR" }
        ?.version
        ?.takeIf { !it.isNullOrBlank() }
        ?: DEFAULT_FHIR_MODEL_VERSION

private fun qicoreModelVersionForLibrary(library: Library): String =
    library.usings?.def
        ?.firstOrNull { it.localIdentifier == "QICore" }
        ?.version
        ?.takeIf { !it.isNullOrBlank() }
        ?: DEFAULT_QICORE_MODEL_VERSION

/** ELM JSON often encodes types as `{namespaceURI}LocalName` in a single string. */
private fun parseBracedTypeName(text: String): Pair<String, String>? {
    if (!text.startsWith("{") || !text.contains("}")) return null
    val end = text.indexOf('}')
    if (end <= 1) return null
    val namespace = text.substring(1, end)
    val localName = text.substring(end + 1)
    if (localName.isBlank()) return null
    return namespace to localName
}

private fun resolveTypeByNamespace(
    namespace: String,
    localName: String,
    modelManager: ModelManager,
    fhirVersion: String,
    qicoreVersion: String,
): DataType? {
    when (namespace) {
        CQL_SPEC_TYPES_NS, ELM_TYPES_NS ->
            return runCatching { modelManager.resolveModel("System").resolveTypeName(localName) }.getOrNull()
        FHIR_NS -> {
            runCatching { modelManager.resolveModel("QICore", qicoreVersion) }.getOrNull()?.let { qicore ->
                qicore.resolveTypeName(localName)?.let { return it }
                qicore.resolveTypeName("FHIR.$localName")?.let { return it }
            }
            runCatching { modelManager.resolveModel("FHIR", fhirVersion) }.getOrNull()?.let { fhir ->
                fhir.resolveTypeName(localName)?.let { return it }
                fhir.resolveTypeName("FHIR.$localName")?.let { return it }
            }
            return null
        }
        ATRIUS_IN_NS, ATRIUS_IN_MODEL_URI -> {
            runCatching { modelManager.resolveModel(ATRIUS_IN_MODEL_NAME, ATRIUS_IN_MODEL_VERSION) }.getOrNull()?.let { atrius ->
                atrius.resolveTypeName(localName)?.let { return it }
                atrius.resolveTypeName("$ATRIUS_IN_NS.$localName")?.let { return it }
            }
            return null
        }
    }
    runCatching { modelManager.resolveModelByUri(namespace) }.getOrNull()?.let { model ->
        model.resolveTypeName(localName)?.let { return it }
    }
    return null
}

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

private data class HydrationContext(
    val fhirVersion: String,
    val qicoreVersion: String,
)

private fun hydrationContextFor(library: Library): HydrationContext =
    HydrationContext(
        fhirVersion = fhirModelVersionForLibrary(library),
        qicoreVersion = qicoreModelVersionForLibrary(library),
    )

private fun hydrateElmTrackableResultTypes(library: Library, modelManager: ModelManager) {
    val ctx = hydrationContextFor(library)
    resolveLibraryUsings(library, modelManager)

    library.statements?.def?.forEach { stmt ->
        if (stmt is ExpressionDef) {
            hydrateExpressionsDeep(stmt.expression, modelManager, ctx)
            ensureElementHasTrackableResultType(stmt, modelManager, ctx)
            if (stmt.resultType == null) {
                inferExpressionDefResultType(stmt, modelManager, ctx)
            }
            if (stmt is FunctionDef) {
                stmt.operand.forEach { op ->
                    op.operandTypeSpecifier?.let { spec ->
                        resolveElmR1TypeSpecifier(spec, modelManager, ctx)?.let { dt ->
                            if (op.resultType == null) op.withResultType(dt)
                        }
                    }
                }
                if (stmt.resultType == null) {
                    inferExpressionDefResultType(stmt, modelManager, ctx)
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun hydrateExpressionsDeep(expr: Expression?, modelManager: ModelManager, ctx: HydrationContext) {
    if (expr == null) return
    when (expr) {
        is UnaryExpression -> hydrateExpressionsDeep(expr.operand, modelManager, ctx)
        is BinaryExpression -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, ctx) }
        is NaryExpression -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, ctx) }
        is TernaryExpression -> expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, ctx) }
        is First -> hydrateExpressionsDeep(expr.source, modelManager, ctx)
        is Property -> hydrateExpressionsDeep(expr.source, modelManager, ctx)
        is FunctionRef -> {
            expr.operand.forEach { hydrateExpressionsDeep(it, modelManager, ctx) }
        }
        is ExpressionRef -> {}
        is SingletonFrom -> hydrateExpressionsDeep(expr.operand, modelManager, ctx)
        is As -> hydrateExpressionsDeep(expr.operand, modelManager, ctx)
        is Aggregate -> {
            hydrateExpressionsDeep(expr.source, modelManager, ctx)
            hydrateExpressionsDeep(expr.iteration, modelManager, ctx)
            hydrateExpressionsDeep(expr.initialValue, modelManager, ctx)
        }
        is Count -> hydrateExpressionsDeep(expr.source, modelManager, ctx)
        is Query -> {
            expr.source.forEach { hydrateExpressionsDeep(it.expression, modelManager, ctx) }
            expr.where?.let { hydrateExpressionsDeep(it, modelManager, ctx) }
            expr.`return`?.expression?.let { hydrateExpressionsDeep(it, modelManager, ctx) }
        }
        is If -> {
            hydrateExpressionsDeep(expr.condition, modelManager, ctx)
            hydrateExpressionsDeep(expr.then, modelManager, ctx)
            hydrateExpressionsDeep(expr.`else`, modelManager, ctx)
        }
        else -> {}
    }
    ensureElementHasTrackableResultType(expr, modelManager, ctx)
}

private fun ensureElementHasTrackableResultType(element: Element, modelManager: ModelManager, ctx: HydrationContext) {
    if (element.resultType != null) return
    val dataType = resolveDataTypeForElmElement(element, modelManager, ctx) ?: return
    element.withResultType(dataType)
}

@Suppress("ReturnCount")
private fun resolveDataTypeForElmElement(element: Element, modelManager: ModelManager, ctx: HydrationContext): DataType? {
    when (element) {
        is Literal ->
            element.valueType?.let {
                return resolveElmQName(it, modelManager, ctx)
            }
        is Retrieve ->
            element.dataType?.let {
                return resolveElmQName(it, modelManager, ctx)
            }
        else -> {}
    }
    element.resultTypeSpecifier?.let { spec ->
        resolveElmR1TypeSpecifier(spec, modelManager, ctx)?.let { dt ->
            if (spec.resultType == null) {
                spec.withResultType(dt)
            }
            return dt
        }
    }
    element.resultTypeName?.let { return resolveElmQName(it, modelManager, ctx) }
    return null
}

@Suppress("ReturnCount")
private fun inferExpressionDefResultType(def: ExpressionDef, modelManager: ModelManager, ctx: HydrationContext) {
    if (def.resultType != null) return
    val expr = def.expression ?: return
    inferExpressionResultType(expr, modelManager, ctx)?.let { def.withResultType(it) }
}

private fun booleanType(modelManager: ModelManager): DataType? =
    runCatching { modelManager.resolveModel("System").resolveTypeName("Boolean") }.getOrNull()

@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun inferExpressionResultType(expr: Expression?, modelManager: ModelManager, ctx: HydrationContext): DataType? {
    if (expr == null) return null
    expr.resultType?.let { return it }
    when (expr) {
        is Literal ->
            expr.valueType?.let { return resolveElmQName(it, modelManager, ctx) }
        is Retrieve ->
            expr.dataType?.let { return resolveElmQName(it, modelManager, ctx) }
        is SingletonFrom -> {
            val op = expr.operand
            if (op is Retrieve) {
                op.dataType?.let { return resolveElmQName(it, modelManager, ctx) }
            }
            return inferExpressionResultType(op, modelManager, ctx)
        }
        is Concatenate ->
            return systemStringType(modelManager)
        is CalculateAge ->
            return modelManager.resolveModel("System").resolveTypeName("Integer")
        is And, is Or, is Not, is Exists, is Equivalent, is In, is Implies, is IsNull, is Greater, is Less ->
            return booleanType(modelManager)
        is Query -> {
            val srcExpr = expr.source.firstOrNull()?.expression
            if (srcExpr is Retrieve) {
                val elem =
                    srcExpr.dataType?.let { resolveElmQName(it, modelManager, ctx) } ?: return null
                return ListType(elem)
            }
            return null
        }
        is If -> {
            val t = inferExpressionResultType(expr.then, modelManager, ctx)
            val e = inferExpressionResultType(expr.`else`, modelManager, ctx)
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
private fun resolveElmR1TypeSpecifier(spec: TypeSpecifier, modelManager: ModelManager, ctx: HydrationContext): DataType? {
    return when (spec) {
        is NamedTypeSpecifier ->
            spec.name?.let { resolveElmQName(it, modelManager, ctx) }
        is ListTypeSpecifier -> {
            val inner = spec.elementType ?: return null
            val elemType = resolveElmR1TypeSpecifier(inner, modelManager, ctx) ?: return null
            ListType(elemType)
        }
        is IntervalTypeSpecifier -> {
            val inner = spec.pointType ?: return null
            val pointType = resolveElmR1TypeSpecifier(inner, modelManager, ctx) ?: return null
            IntervalType(pointType)
        }
        is TupleTypeSpecifier -> {
            val elements = mutableListOf<TupleTypeElement>()
            for (tel in spec.element) {
                val name = tel.name ?: return null
                val tspec = tel.elementType ?: return null
                val dt = resolveElmR1TypeSpecifier(tspec, modelManager, ctx) ?: return null
                elements.add(TupleTypeElement(name, dt))
            }
            TupleType(elements)
        }
        is ChoiceTypeSpecifier -> {
            val choices = mutableListOf<DataType>()
            for (inner in spec.choice) {
                val dt = resolveElmR1TypeSpecifier(inner, modelManager, ctx) ?: return null
                choices.add(dt)
            }
            ChoiceType(choices)
        }
        else -> null
    }
}

@Suppress("NestedBlockDepth", "ReturnCount")
private fun resolveElmQName(qname: QName, modelManager: ModelManager, ctx: HydrationContext): DataType? {
    var local = qname.localPart ?: return null
    val ns = qname.namespaceURI?.takeIf { it.isNotBlank() }

    parseBracedTypeName(local)?.let { (namespace, localName) ->
        resolveTypeByNamespace(namespace, localName, modelManager, ctx.fhirVersion, ctx.qicoreVersion)?.let { return it }
    }
    if (!ns.isNullOrBlank()) {
        resolveTypeByNamespace(ns, local, modelManager, ctx.fhirVersion, ctx.qicoreVersion)?.let { return it }
    }

    // Some ELM serializers put "fhir:Patient" / "t:String" in localPart without namespace URI.
    if (local.contains(':') && !local.startsWith("{") && (ns.isNullOrBlank() || ns == "urn:hl7-org:elm:r1")) {
        val idx = local.indexOf(':')
        val pfx = local.substring(0, idx)
        val name = local.substring(idx + 1)
        when (pfx) {
            "fhir" ->
                resolveTypeByNamespace(FHIR_NS, name, modelManager, ctx.fhirVersion, ctx.qicoreVersion)?.let { return it }
            "t" ->
                runCatching { modelManager.resolveModel("System") }
                    .getOrNull()
                    ?.resolveTypeName(name)
                    ?.let { return it }
        }
    }

    val models = LinkedHashSet<Model>()
    runCatching { models.add(modelManager.resolveModel("System")) }
    runCatching { models.add(modelManager.resolveModel("FHIR", ctx.fhirVersion)) }
    runCatching { models.add(modelManager.resolveModel("QICore", ctx.qicoreVersion)) }
    runCatching { models.add(modelManager.resolveModel(ATRIUS_IN_MODEL_NAME, ATRIUS_IN_MODEL_VERSION)) }

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
