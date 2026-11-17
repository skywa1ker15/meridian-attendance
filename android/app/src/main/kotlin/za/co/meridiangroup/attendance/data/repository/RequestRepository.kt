package za.co.meridiangroup.attendance.data.repository

import java.util.UUID
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.core.OutboxResolution
import za.co.meridiangroup.attendance.core.OutboxRules
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestCreateDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto

sealed class SubmitOutcome {
    data class Created(val request: ManualRequestDto) : SubmitOutcome()
    object Queued : SubmitOutcome()
    data class Failed(val message: String, val fieldErrors: Map<String, String>) : SubmitOutcome()
}

class RequestRepository(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val outbox: OutboxWriter,
) {
    /** Creates a manual attendance (or profile correction) request; queued offline with its client_request_id. */
    suspend fun submit(build: (clientRequestId: String) -> ManualRequestCreateDto): SubmitOutcome {
        val clientId = UUID.randomUUID().toString()
        val body = build(clientId)
        val rowId = outbox.enqueue(OutboxKind.MANUAL_REQUEST, clientId, null, ManualRequestCreateDto.serializer(), body)
        return when (val r = caller.call { api.createManualRequest(clientId, body) }) {
            is ApiResult.Success -> { outbox.remove(rowId); SubmitOutcome.Created(r.value) }
            is ApiResult.Offline -> SubmitOutcome.Queued
            is ApiResult.Failure -> {
                val res = OutboxRules.resolve(OutboxKind.MANUAL_REQUEST, r.failure.httpStatus, r.failure.code, 0)
                when (res) {
                    is OutboxResolution.Dead -> { outbox.remove(rowId); SubmitOutcome.Failed(r.userMessage() ?: "Request failed.", r.failure.fieldErrors) }
                    OutboxResolution.Done -> { outbox.remove(rowId); SubmitOutcome.Queued }
                    is OutboxResolution.Retry -> SubmitOutcome.Queued
                }
            }
        }
    }

    suspend fun list(status: String?, page: Int): ApiResult<PageDto<ManualRequestDto>> =
        caller.call { api.manualRequests(status, page, 30) }

    suspend fun cancel(id: String): ApiResult<ManualRequestDto> = caller.call { api.cancelManualRequest(id) }
}
