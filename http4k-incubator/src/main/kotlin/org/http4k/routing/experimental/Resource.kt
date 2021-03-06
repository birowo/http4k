package org.http4k.routing.experimental

import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.ContentType.Companion.OCTET_STREAM
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.MemoryResponse
import org.http4k.core.Request
import org.http4k.core.Status.Companion.NOT_MODIFIED
import org.http4k.core.Status.Companion.OK
import org.http4k.core.etag.ETag
import org.http4k.core.etag.ETagValidationRequestParser
import org.http4k.core.etag.FieldValue
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME

interface Resource : HttpHandler {

    fun openStream(): InputStream

    val length: Long? get() = null

    val lastModified: Instant? get() = null

    val contentType: ContentType get() = OCTET_STREAM

    val etag: ETag? get() = null

    fun isModifiedSince(instant: Instant): Boolean = lastModified?.isAfter(instant) ?: true

    val headers: Headers
        get() = listOf(
            "Content-Type" to contentType.value,
            "Content-Length" to length?.toString(),
            "Last-Modified" to lastModified?.formattedWith(dateTimeFormatter),
            "ETag" to etag?.toHeaderString()
        )

    override fun invoke(request: Request) = if (notModifiedSince(request) || etagMatch(request))
        MemoryResponse(NOT_MODIFIED, headers)
    else
        MemoryResponse(OK, headers, Body(openStream(), length)) // Pipeline is responsible for closing stream

    private fun notModifiedSince(request: Request): Boolean {
        val ifModifiedSince = request.header("If-Modified-Since")?.parsedWith(dateTimeFormatter)
        return ifModifiedSince != null && !isModifiedSince(ifModifiedSince)
    }

    private fun etagMatch(request: Request): Boolean {
        val fieldValue = request.header("If-None-Match")?.parsedForEtags()
        return fieldValue != null && etagMatch(fieldValue)
    }

    private fun etagMatch(fieldValue: FieldValue): Boolean {
        val localEtag = etag
        return when {
            localEtag == null -> false
            fieldValue === FieldValue.Wildcard -> true
            fieldValue is FieldValue.ETags -> fieldValue.hasAnEtagWithValue(localEtag.value)
            else -> false
        }
    }
}

private val dateTimeFormatter = RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

private fun Instant.formattedWith(formatter: DateTimeFormatter) = formatter.format(this)

private fun String.parsedWith(formatter: DateTimeFormatter): Instant? =
    try {
        Instant.from(formatter.parse(this))
    } catch (x: Exception) {
        null // "if the passed If-Modified-Since date is invalid, the response is exactly the same as for a normal GET"
    }

private fun String.parsedForEtags(): FieldValue = ETagValidationRequestParser.parse(this)

private fun FieldValue.ETags.hasAnEtagWithValue(value: String) = this.value.map(ETag::value).contains(value)
