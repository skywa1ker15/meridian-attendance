package za.co.meridiangroup.attendance.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import za.co.meridiangroup.attendance.core.ApiErrorMapper
import za.co.meridiangroup.attendance.core.ApiFailure
import za.co.meridiangroup.attendance.data.remote.dto.ErrorBodyDto
import za.co.meridiangroup.attendance.data.remote.dto.ErrorEnvelopeDto

// No Retrofit/OkHttp/Android imports here: this file is compiled and tested by tools/core-check.

/** Shared kotlinx-serialization configuration for the whole app (lenient to additive server changes). */
object ApiJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = false
        encodeDefaults = true
    }
}

/** Parses the standard error envelope into an [ApiFailure]. */
object ApiErrorParser {
    fun parse(status: Int, body: String?, retryAfter: String? = null): ApiFailure {
        val retryAfterSeconds = retryAfter?.trim()?.toIntOrNull()
        if (body.isNullOrBlank()) return ApiErrorMapper.fallback(status).copy(retryAfterSeconds = retryAfterSeconds)
        val parsed: ErrorBodyDto? = runCatching { ApiJson.json.decodeFromString(ErrorEnvelopeDto.serializer(), body).error }.getOrNull()
            ?: runCatching { ApiJson.json.decodeFromString(ErrorBodyDto.serializer(), body) }.getOrNull()
        if (parsed == null) return ApiErrorMapper.fallback(status).copy(retryAfterSeconds = retryAfterSeconds)
        return ApiFailure(
            httpStatus = status,
            code = parsed.code.ifBlank { ApiErrorMapper.fallback(status).code },
            message = parsed.message,
            fieldErrors = fieldErrors(parsed),
            retryAfterSeconds = retryAfterSeconds,
        )
    }

    private fun fieldErrors(body: ErrorBodyDto): Map<String, String> {
        val fields = body.details?.get("fields") ?: return emptyMap()
        return runCatching {
            fields.jsonObject.entries.associate { (k, v) ->
                val text = runCatching { v.jsonPrimitive.content }.getOrElse { v.toString() }
                k to text
            }
        }.getOrDefault(emptyMap())
    }

    fun fromError(status: Int, error: ErrorBodyDto?): ApiFailure =
        if (error == null) ApiErrorMapper.fallback(status)
        else ApiFailure(status, error.code.ifBlank { ApiErrorMapper.fallback(status).code }, error.message, fieldErrors(error))
}
