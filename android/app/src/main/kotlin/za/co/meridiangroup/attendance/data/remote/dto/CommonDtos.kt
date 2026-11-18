package za.co.meridiangroup.attendance.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `{ "error": { "code", "message", "details" } }` */
@Serializable
data class ErrorEnvelopeDto(@SerialName("error") val error: ErrorBodyDto)

@Serializable
data class ErrorBodyDto(
    @SerialName("code") val code: String = "server_error",
    @SerialName("message") val message: String = "",
    @SerialName("details") val details: JsonObject? = null,
)

@Serializable
data class PageDto<T>(
    @SerialName("items") val items: List<T> = emptyList(),
    @SerialName("total") val total: Int = 0,
    @SerialName("page") val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 50,
) {
    val hasMore: Boolean get() = page * pageSize < total
}

@Serializable
data class ItemsDto<T>(@SerialName("items") val items: List<T> = emptyList())

@Serializable
data class CountDto(@SerialName("count") val count: Int = 0)

@Serializable
data class DepartmentDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("code") val code: String? = null,
)

@Serializable
data class LocationDto(
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
)

@Serializable
data class WifiDto(
    @SerialName("ssid") val ssid: String? = null,
    @SerialName("bssid") val bssid: String? = null,
)

/** Employee summary embedded in admin payloads (`employee: { id, employee_number, first_name, last_name, department }`). */
@Serializable
data class EmployeeSummaryDto(
    @SerialName("id") val id: String,
    @SerialName("employee_number") val employeeNumber: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("department") val department: DepartmentDto? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("status") val status: String? = null,
) {
    val fullName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

/** Keeps any extra JSON the server adds in the future without failing deserialisation. */
typealias Extra = Map<String, JsonElement>
