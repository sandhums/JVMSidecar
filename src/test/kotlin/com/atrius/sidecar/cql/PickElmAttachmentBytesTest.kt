package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ElmFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.cqframework.cql.cql2elm.LibraryContentType
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Library

class PickElmAttachmentBytesTest {

    @Test
    fun picksElmXmlByContentType() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml"
                        data = "<library/>".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        assertContentEquals(
            "<library/>".toByteArray(Charsets.UTF_8),
            pickElmAttachmentBytes(lib, LibraryContentType.XML),
        )
    }

    @Test
    fun picksElmXmlWithCharsetParameter() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml; charset=UTF-8"
                        data = byteArrayOf(1, 2, 3)
                    },
                )
            }
        assertContentEquals(byteArrayOf(1, 2, 3), pickElmAttachmentBytes(lib, LibraryContentType.XML))
    }

    @Test
    fun prefersXmlMimeOrderOverJsonAttachmentWhenXmlRequested() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/json"
                        data = "{}".toByteArray(Charsets.UTF_8)
                    },
                )
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml"
                        data = "<e/>".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        assertContentEquals("<e/>".toByteArray(Charsets.UTF_8), pickElmAttachmentBytes(lib, LibraryContentType.XML))
    }

    @Test
    fun ignoresTextCql() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "text/cql"
                        data = "library Foo".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        assertNull(pickElmAttachmentBytes(lib, LibraryContentType.XML))
        assertNull(pickElmAttachmentBytes(lib, LibraryContentType.JSON))
    }

    @Test
    fun cacheKeyEncodesIdAndVersion() {
        val a =
            VersionedIdentifier().apply {
                id = "HelloWorld"
                version = "1.0.0"
            }
        val b =
            VersionedIdentifier().apply {
                id = "HelloWorld"
                version = "2.0.0"
            }
        kotlin.test.assertTrue(cacheKey(a) != cacheKey(b))
    }

    @Test
    fun versionsCompatibleHandlesMissingSides() {
        val req =
            VersionedIdentifier().apply {
                id = "X"
                version = "1.0"
            }
        kotlin.test.assertTrue(versionsCompatible(null, req))
        kotlin.test.assertTrue(versionsCompatible("", VersionedIdentifier().apply { id = "X" }))
        kotlin.test.assertTrue(versionsCompatible("1.0", req))
        kotlin.test.assertFalse(versionsCompatible("2.0", req))
    }

    @Test
    fun decodePreferredPrefersElmXmlOverElmJson() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+json"
                        data = "{}".toByteArray(Charsets.UTF_8)
                    },
                )
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml"
                        data = "<library/>".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        val (text, fmt) = decodePreferredElmString(lib)
        assertEquals("<library/>", text)
        assertEquals(ElmFormat.XML, fmt)
    }

    @Test
    fun decodePreferredUsesJsonWhenNoXml() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+json"
                        data = "{\"x\":1}".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        val (text, fmt) = decodePreferredElmString(lib)
        assertEquals("{\"x\":1}", text)
        assertEquals(ElmFormat.JSON, fmt)
    }

    @Test
    fun decodePreferredThrowsWhenOnlyTextCql() {
        val lib =
            Library().apply {
                setId("HelloWorld")
                addContent(
                    Attachment().apply {
                        contentType = "text/cql"
                        data = "library X".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        assertFailsWith<IllegalArgumentException> { decodePreferredElmString(lib) }
    }
}
