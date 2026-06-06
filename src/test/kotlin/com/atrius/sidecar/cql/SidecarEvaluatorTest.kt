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
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.cqframework.cql.elm.serializing.ElmXmlLibraryWriter
import org.hl7.cql_annotations.r1.CqlToElmInfo
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
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

    @Test
    fun classpathContainsFhirHelpers440() {
        val stream =
            ClasspathElmLibraryProvider::class.java.getResourceAsStream(
                "/elm-libraries/FHIRHelpers-4.4.000.xml",
            )
        assertTrue(stream != null, "FHIRHelpers-4.4.000.xml must be on the classpath for eCQM includes")
        stream.close()
    }

    @Test
    fun classpathProviderServesFhirHelpers440Xml() {
        val provider = ClasspathElmLibraryProvider()
        val vid =
            VersionedIdentifier().apply {
                id = "FHIRHelpers"
                version = "4.4.000"
            }
        val source =
            provider.getLibraryContent(vid, org.cqframework.cql.cql2elm.LibraryContentType.XML)
        assertTrue(source != null, "classpath must serve FHIRHelpers-4.4.000.xml")
        source?.close()
    }

    @Test
    fun libraryManagerResolvesFhirHelpers440FromClasspath() {
        val vid =
            VersionedIdentifier().apply {
                id = "FHIRHelpers"
                version = "4.4.000"
            }
        val modelManager = ModelManager()
        val libraryManager =
            LibraryManager(
                modelManager,
                CqlCompilerOptions(),
            )
        libraryManager.resolveLibrary(vid)
    }

    @Test
    fun resolvesStatusElmXmlWithClasspathFhirHelpers440() {
        val bridge = resolveHfsBridgeUrlForTests()
        assumeFhirMetadataReachable(bridge, "HFS bridge")
        val elmFile = File("Status-elm.xml")
        assertTrue(elmFile.exists(), "Place Status-elm.xml at module root for this test")
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    elm = elmFile.readText(),
                    elmFormat = ElmFormat.XML,
                    libraryId = "Status",
                    libraryVersion = "1.15.000",
                    expression = "Patient",
                    hfsBaseUrl = bridge,
                    htsBaseUrl = "http://127.0.0.1:9091",
                    patientId = "03d2e5e6-a126-d0de-0a38-3f0bad766b30",
                ),
            )
        assertTrue(
            response.result.toString().contains("03d2e5e6-a126-d0de-0a38-3f0bad766b30"),
            "expected Patient context result, got ${response.result}",
        )
    }

    @Test
    fun evaluatesStatusFromKrWhenStackRunning() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        val bridge = resolveHfsBridgeUrlForTests()
        assumeFhirMetadataReachable(bridge, "HFS bridge")
        val hts = System.getenv("HTS_BASE_URL") ?: "http://127.0.0.1:9091"
        val patient = System.getenv("TEST_PATIENT_ID") ?: "03d2e5e6-a126-d0de-0a38-3f0bad766b30"
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    libraryId = "Status",
                    expression = "Patient",
                    hfsBaseUrl = bridge,
                    htsBaseUrl = hts,
                    libraryBaseUrl = kr,
                    resolveLibraryArtifactsFromFhir = true,
                    patientId = patient,
                ),
            )
        assertTrue(
            response.result.toString().contains(patient),
            "expected Patient context result for $patient, got ${response.result}",
        )
    }

    @Test
    fun hydratesCms165ElmFromKr() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        assumeFhirMetadataReachable(kr, "KR")
        val client =
            ca.uhn.fhir.context.FhirContext.forR4().newRestfulGenericClient(kr).apply {
                encoding = ca.uhn.fhir.rest.api.EncodingEnum.JSON
            }
        val loader = FhirLibraryElmLoader(client, fetched = mutableMapOf())
        val vid =
            VersionedIdentifier().apply {
                id = "CMS165FHIRControllingHighBloodPressure"
                version = "0.3.000"
            }
        val resource =
            loader.loadLibrary(vid)
                ?: error("CMS165 Library not found on KR at $kr")
        val (elmPayload, format) = decodePreferredElmString(resource)
        val modelManager = ModelManager()
        val reader = hydratingElmLibraryReaderProvider(modelManager)
        val library = reader.create(elmMimeType(format)).read(elmPayload)
        val statements = library.statements?.def.orEmpty()
        assertTrue(statements.isNotEmpty(), "CMS165 must declare statement definitions")
        val missing =
            statements.filterIsInstance<ExpressionDef>().filter { it.resultType == null }.map { it.name }
        assertTrue(
            missing.isEmpty(),
            "CMS165 statements missing hydrated resultType: $missing",
        )
        buildCompiledLibrary(library)
    }

    @Test
    fun seedsAtriusCms165CompiledLibraryBeforeResolve() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        assumeFhirMetadataReachable(kr, "KR")
        val client =
            ca.uhn.fhir.context.FhirContext.forR4().newRestfulGenericClient(kr).apply {
                encoding = ca.uhn.fhir.rest.api.EncodingEnum.JSON
            }
        val loader = FhirLibraryElmLoader(client, fetched = mutableMapOf())
        val libraryIdentifier =
            VersionedIdentifier().apply {
                id = "AtriusCMS165ControllingHighBP"
                version = "0.1.0"
            }
        val resource =
            loader.loadLibrary(libraryIdentifier)
                ?: error("AtriusCMS165 Library not found on KR at $kr")
        val (elmPayload, format) = decodePreferredElmString(resource)
        val modelManager = ModelManager()
        ensureAtriusInModelInfo(modelManager, loader)
        resolveLibraryUsings(readElmLibrary(elmPayload, format), modelManager)
        val libraryManager =
            LibraryManager(
                modelManager,
                cqlCompilerOptionsForFhirLibrary(readElmLibrary(elmPayload, format)),
                elmLibraryReaderProvider = hydratingElmLibraryReaderProvider(modelManager),
            )
        libraryManager.librarySourceLoader.registerProvider(FhirElmLibrarySourceProvider(loader))
        val payloads =
            listOf(
                ElmLibraryPayload(
                    libraryIdentifier,
                    resolveElmStorageFormat(elmPayload, format),
                    elmPayload,
                ),
            )
        seedCompiledLibrariesFromPayloads(libraryManager, modelManager, payloads)
        seedIncludedLibrariesFromFhir(
            libraryManager,
            modelManager,
            loader,
            readElmLibrary(elmPayload, format),
        )
        val compiled = libraryManager.resolveLibrary(libraryIdentifier)
        val patientDef =
            compiled.resolveExpressionRef("Patient")
                ?: error(
                    "compiled library missing Patient expression; " +
                        "cached=${libraryManager.compiledLibraries.containsKey(libraryIdentifier)}",
                )
        assertTrue(patientDef.resultType != null, "Patient expression must have resultType")
    }

    @Test
    fun seedsCms165CompiledLibraryBeforeResolve() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        assumeFhirMetadataReachable(kr, "KR")
        val client =
            ca.uhn.fhir.context.FhirContext.forR4().newRestfulGenericClient(kr).apply {
                encoding = ca.uhn.fhir.rest.api.EncodingEnum.JSON
            }
        val loader = FhirLibraryElmLoader(client, fetched = mutableMapOf())
        val libraryIdentifier =
            VersionedIdentifier().apply {
                id = "CMS165FHIRControllingHighBloodPressure"
                version = "0.3.000"
            }
        val resource =
            loader.loadLibrary(libraryIdentifier)
                ?: error("CMS165 Library not found on KR at $kr")
        val (elmPayload, format) = decodePreferredElmString(resource)
        val modelManager = ModelManager()
        resolveLibraryUsings(readElmLibrary(elmPayload, format), modelManager)
        val libraryManager =
            LibraryManager(
                modelManager,
                cqlCompilerOptionsForFhirLibrary(readElmLibrary(elmPayload, format)),
                elmLibraryReaderProvider = hydratingElmLibraryReaderProvider(modelManager),
            )
        libraryManager.librarySourceLoader.registerProvider(FhirElmLibrarySourceProvider(loader))
        val payloads =
            listOf(
                ElmLibraryPayload(
                    libraryIdentifier,
                    resolveElmStorageFormat(elmPayload, format),
                    elmPayload,
                ),
            )
        seedCompiledLibrariesFromPayloads(libraryManager, modelManager, payloads)
        seedIncludedLibrariesFromFhir(
            libraryManager,
            modelManager,
            loader,
            readElmLibrary(elmPayload, format),
        )
        val compiled = libraryManager.resolveLibrary(libraryIdentifier)
        val patientDef =
            compiled.resolveExpressionRef("Patient")
                ?: error(
                    "compiled library missing Patient expression; " +
                        "cached=${libraryManager.compiledLibraries.containsKey(libraryIdentifier)}",
                )
        assertTrue(patientDef.resultType != null, "Patient expression must have resultType")
    }

    @Test
    fun evaluatesAtriusCms165PatientFromKrWhenStackRunning() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        val bridge = resolveHfsBridgeUrlForTests()
        assumeFhirMetadataReachable(bridge, "HFS bridge")
        val hts = System.getenv("HTS_BASE_URL") ?: "http://127.0.0.1:9091"
        val patient = System.getenv("TEST_PATIENT_ID") ?: "cms165-demo"
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    libraryId = "AtriusCMS165ControllingHighBP",
                    libraryVersion = "0.1.0",
                    expression = "Patient",
                    hfsBaseUrl = bridge,
                    htsBaseUrl = hts,
                    libraryBaseUrl = kr,
                    resolveLibraryArtifactsFromFhir = true,
                    patientId = patient,
                ),
            )
        assertTrue(
            response.result.toString().contains(patient),
            "expected Patient context result for $patient, got ${response.result}",
        )
    }

    @Test
    fun evaluatesCms165PatientFromKrWhenStackRunning() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        val bridge = resolveHfsBridgeUrlForTests()
        assumeFhirMetadataReachable(bridge, "HFS bridge")
        val hts = System.getenv("HTS_BASE_URL") ?: "http://127.0.0.1:9091"
        val patient = System.getenv("TEST_PATIENT_ID") ?: "03d2e5e6-a126-d0de-0a38-3f0bad766b30"
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    libraryId = "CMS165FHIRControllingHighBloodPressure",
                    libraryVersion = "0.3.000",
                    expression = "Patient",
                    hfsBaseUrl = bridge,
                    htsBaseUrl = hts,
                    libraryBaseUrl = kr,
                    resolveLibraryArtifactsFromFhir = true,
                    patientId = patient,
                ),
            )
        assertTrue(
            response.result.toString().contains(patient),
            "expected Patient context result for $patient, got ${response.result}",
        )
    }

    @Test
    fun evaluatesCms165InitialPopulationFromKrWhenStackRunning() {
        val kr = System.getenv("KR_BASE_URL") ?: "http://127.0.0.1:8079"
        val bridge = resolveHfsBridgeUrlForTests()
        assumeFhirMetadataReachable(bridge, "HFS bridge")
        val hts = System.getenv("HTS_BASE_URL") ?: "http://127.0.0.1:9091"
        val patient = System.getenv("TEST_PATIENT_ID") ?: "03d2e5e6-a126-d0de-0a38-3f0bad766b30"
        val response =
            SidecarEvaluator().evaluate(
                EvaluateExpressionRequest(
                    libraryId = "CMS165FHIRControllingHighBloodPressure",
                    libraryVersion = "0.3.000",
                    expression = "Initial Population",
                    hfsBaseUrl = bridge,
                    htsBaseUrl = hts,
                    libraryBaseUrl = kr,
                    resolveLibraryArtifactsFromFhir = true,
                    patientId = patient,
                    parameters =
                        mapOf(
                            "Measurement Period" to
                                kotlinx.serialization.json.buildJsonObject {
                                    put("low", JsonPrimitive("2020-01-01"))
                                    put("high", JsonPrimitive("2025-12-31"))
                                    put("lowClosed", JsonPrimitive(true))
                                    put("highClosed", JsonPrimitive(true))
                                },
                        ),
                ),
            )
        assertTrue(
            response.result.toString() == "true" || response.result.toString() == "false",
            "expected boolean Initial Population result, got ${response.result}",
        )
    }

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
