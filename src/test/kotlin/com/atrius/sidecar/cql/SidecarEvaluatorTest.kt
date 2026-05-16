package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ElmFormat
import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.api.IncludedElmLibrary
import java.io.File
import javax.xml.namespace.QName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.cqframework.cql.elm.serializing.ElmXmlLibraryWriter
import org.hl7.cql_annotations.r1.CqlToElmInfo
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.VersionedIdentifier

class SidecarEvaluatorTest {

    /** Matches typical CQL Studio / CQ translator annotation bundle for compatibility checks. */
    private fun attachTranslatorAnnotations(library: Library) {
        library.annotation =
            mutableListOf(
                CqlToElmInfo().apply {
                    translatorVersion = "4.0.0-SNAPSHOT"
                    translatorOptions = "EnableAnnotations,EnableLocators,DisableListDemotion,DisableListPromotion"
                    signatureLevel = "Overloads"
                },
            )
    }

    private fun integerNamedType(): NamedTypeSpecifier =
        NamedTypeSpecifier().apply {
            name = QName("urn:hl7-org:cql-spec:r1", "Integer")
        }

    private fun literalAnswerLibrary(): Library {
        val literal =
            Literal().apply {
                valueType = QName("urn:hl7-org:cql-spec:r1", "Integer")
                value = "42"
            }
        val expr =
            ExpressionDef().apply {
                name = "Answer"
                context = "Unfiltered"
                resultTypeSpecifier = integerNamedType()
                resultTypeName = QName("urn:hl7-org:cql-spec:r1", "Integer")
                expression = literal
            }
        val statements =
            Library.Statements().apply {
                def = mutableListOf(expr)
            }
        return Library().apply {
            identifier =
                VersionedIdentifier().apply {
                    id = "TestLib"
                    version = "1.0.0"
                }
            this.statements = statements
            attachTranslatorAnnotations(this)
        }
    }

    private fun evaluateAnswer(elm: String, format: ElmFormat) =
        SidecarEvaluator().evaluate(
            EvaluateExpressionRequest(
                elm = elm,
                elmFormat = format,
                libraryId = "TestLib",
                libraryVersion = "1.0.0",
                expression = "Answer",
                hfsBaseUrl = "http://localhost:59999/fhir",
                htsBaseUrl = "http://localhost:59998/fhir",
                patientId = null,
                parameters = null,
                evaluationDateTime = null,
            ),
        )

    @Test
    fun evaluatesLiteralExpressionFromElmJson() {
        val elmPayload = ElmJsonLibraryWriter().writeAsString(literalAnswerLibrary())
        assertEquals(JsonPrimitive("42"), evaluateAnswer(elmPayload, ElmFormat.JSON).result)
    }

    @Test
    fun evaluatesLiteralExpressionFromElmXmlExplicitFormat() {
        val elmPayload = ElmXmlLibraryWriter().writeAsString(literalAnswerLibrary())
        assertEquals(JsonPrimitive("42"), evaluateAnswer(elmPayload, ElmFormat.XML).result)
    }

    @Test
    fun evaluatesLiteralExpressionFromElmXmlAutoFormat() {
        val elmPayload = ElmXmlLibraryWriter().writeAsString(literalAnswerLibrary())
        assertEquals(JsonPrimitive("42"), evaluateAnswer(elmPayload, ElmFormat.AUTO).result)
    }

    private fun depLibrary(): Library {
        val literal =
            Literal().apply {
                valueType = QName("urn:hl7-org:cql-spec:r1", "Integer")
                value = "99"
            }
        val expr =
            ExpressionDef().apply {
                name = "SharedConst"
                context = "Unfiltered"
                resultTypeSpecifier = integerNamedType()
                resultTypeName = QName("urn:hl7-org:cql-spec:r1", "Integer")
                expression = literal
            }
        return Library().apply {
            identifier =
                VersionedIdentifier().apply {
                    id = "DepLib"
                    version = "1.0.0"
                }
            statements = Library.Statements().apply { def = mutableListOf(expr) }
            attachTranslatorAnnotations(this)
        }
    }

    private fun mainLibraryWithInclude(): Library {
        val include =
            IncludeDef().apply {
                localIdentifier = "DepLib"
                path = "DepLib"
                version = "1.0.0"
            }
        val includes = Library.Includes().apply { def = mutableListOf(include) }
        val ref =
            ExpressionRef().apply {
                name = "SharedConst"
                libraryName = "DepLib"
                resultTypeSpecifier = integerNamedType()
                resultTypeName = QName("urn:hl7-org:cql-spec:r1", "Integer")
            }
        val expr =
            ExpressionDef().apply {
                name = "UseShared"
                context = "Unfiltered"
                resultTypeSpecifier = integerNamedType()
                resultTypeName = QName("urn:hl7-org:cql-spec:r1", "Integer")
                expression = ref
            }
        return Library().apply {
            identifier =
                VersionedIdentifier().apply {
                    id = "MainLib"
                    version = "1.0.0"
                }
            this.includes = includes
            statements = Library.Statements().apply { def = mutableListOf(expr) }
            attachTranslatorAnnotations(this)
        }
    }

    @Test
    fun resolvesIncludeViaIncludedLibraries() {
        val depElm = ElmJsonLibraryWriter().writeAsString(depLibrary())
        val mainElm = ElmJsonLibraryWriter().writeAsString(mainLibraryWithInclude())
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    elm = mainElm,
                    elmFormat = ElmFormat.JSON,
                    libraryId = "MainLib",
                    libraryVersion = "1.0.0",
                    expression = "UseShared",
                    hfsBaseUrl = "http://localhost:59999/fhir",
                    htsBaseUrl = "http://localhost:59998/fhir",
                    includedLibraries =
                        listOf(
                            IncludedElmLibrary(
                                elm = depElm,
                                elmFormat = ElmFormat.JSON,
                                libraryId = "DepLib",
                                libraryVersion = "1.0.0",
                            ),
                        ),
                    patientId = null,
                    parameters = null,
                    evaluationDateTime = null,
                ),
            )
        assertEquals(JsonPrimitive("99"), response.result)
    }

    /** Regression: CQL Studio ELM without statement-level resultTypeSpecifier + classpath FHIRHelpers. */
    @Test
    fun resolvesHelloWorldElmXmlWithClasspathFhirHelpers() {
        val elmFile = File("HelloWorld-elm.xml")
        assertTrue(elmFile.exists(), "Place HelloWorld-elm.xml at module root for this test")
        val elmPayload = elmFile.readText()
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    elm = elmPayload,
                    elmFormat = ElmFormat.XML,
                    libraryId = "HelloWorld",
                    libraryVersion = "1.0.0",
                    expression = "HelloWorld",
                    hfsBaseUrl = "http://localhost:59999/fhir",
                    htsBaseUrl = "http://localhost:59998/fhir",
                    patientId = null,
                    parameters = null,
                    evaluationDateTime = null,
                ),
            )
        assertTrue(
            response.result.toString().contains("working"),
            "expected HelloWorld string result, got ${response.result}",
        )
    }
}
