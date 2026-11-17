package za.co.meridiangroup.attendance.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.core.DismissReason
import za.co.meridiangroup.attendance.core.Validation
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.repository.ActionOutcome
import za.co.meridiangroup.attendance.data.repository.AttendanceRepository
import za.co.meridiangroup.attendance.detection.SignalCollector
import za.co.meridiangroup.attendance.notifications.AttendanceNotifier
import za.co.meridiangroup.attendance.notifications.ReminderScheduler
import za.co.meridiangroup.attendance.sync.SyncEngine

/** Final result shown after the employee acted. */
enum class ConfirmResult { CONFIRMED, UNDER_REVIEW, DISMISSED, QUEUED_CONFIRM, QUEUED_DISMISS }

data class ConfirmUiState(
    val note: String = "",
    val noteError: String? = null,
    val working: Boolean = false,
    val error: String? = null,
    val result: ConfirmResult? = null,
    val dismissOpen: Boolean = false,
    val dismissReason: DismissReason = DismissReason.NOT_AT_WORK,
    val dismissNote: String = "",
)

/** Thin interface so the instrumented confirmation-flow test can drive the screen with a fake. */
interface ConfirmationActions {
    suspend fun confirm(ref: String, note: String?): ActionOutcome
    suspend fun dismiss(ref: String, reason: DismissReason, note: String?): ActionOutcome
    suspend fun afterAction(event: EventEntity)
}

class GraphConfirmationActions(
    private val repo: AttendanceRepository,
    private val signals: SignalCollector,
    private val notifier: AttendanceNotifier,
    private val reminders: ReminderScheduler,
    private val sync: SyncEngine,
) : ConfirmationActions {
    // Signals at confirmation time are additional evidence (API.md confirm body: location, wifi).
    override suspend fun confirm(ref: String, note: String?): ActionOutcome = repo.confirm(ref, signals.collect(), note)
    override suspend fun dismiss(ref: String, reason: DismissReason, note: String?): ActionOutcome = repo.dismiss(ref, reason, note)
    override suspend fun afterAction(event: EventEntity) {
        notifier.cancelConfirmation(event.clientEventId)
        reminders.cancel(event.clientEventId)
        sync.run(reconcile = true)
    }
}

class ConfirmationViewModel(
    private val ref: String,
    repo: AttendanceRepository,
    private val actions: ConfirmationActions,
) : ViewModel() {
    val event: StateFlow<EventEntity?> = repo.observeEvent(ref).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _state = MutableStateFlow(ConfirmUiState())
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    init {
        // Make sure the event exists locally when opened from a push/inbox deep link.
        viewModelScope.launch { if (repo.eventByRef(ref) == null) repo.refreshPending() }
    }

    fun onNote(v: String) = _state.update { it.copy(note = v, noteError = Validation.note(v)) }
    fun openDismiss() = _state.update { it.copy(dismissOpen = true) }
    fun closeDismiss() = _state.update { it.copy(dismissOpen = false) }
    fun onDismissReason(r: DismissReason) = _state.update { it.copy(dismissReason = r) }
    fun onDismissNote(v: String) = _state.update { it.copy(dismissNote = v) }

    fun confirm() {
        val s = _state.value
        if (s.working || s.noteError != null) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val outcome = actions.confirm(ref, s.note)
            handle(outcome, dismissing = false)
        }
    }

    fun dismiss() {
        val s = _state.value
        if (s.working) return
        if (s.dismissReason == DismissReason.OTHER && s.dismissNote.isBlank()) {
            _state.update { it.copy(error = "Please tell us briefly what happened.") }
            return
        }
        _state.update { it.copy(working = true, error = null, dismissOpen = false) }
        viewModelScope.launch {
            val outcome = actions.dismiss(ref, s.dismissReason, s.dismissNote)
            handle(outcome, dismissing = true)
        }
    }

    private suspend fun handle(outcome: ActionOutcome, dismissing: Boolean) {
        when (outcome) {
            is ActionOutcome.Done -> _state.update {
                it.copy(
                    working = false,
                    result = when (outcome.status) {
                        "confirmed" -> ConfirmResult.CONFIRMED
                        "under_review" -> ConfirmResult.UNDER_REVIEW
                        "dismissed" -> ConfirmResult.DISMISSED
                        else -> if (dismissing) ConfirmResult.DISMISSED else ConfirmResult.UNDER_REVIEW
                    },
                )
            }
            ActionOutcome.Queued -> _state.update { it.copy(working = false, result = if (dismissing) ConfirmResult.QUEUED_DISMISS else ConfirmResult.QUEUED_CONFIRM) }
            is ActionOutcome.Failed -> _state.update { it.copy(working = false, error = outcome.message) }
        }
        if (outcome !is ActionOutcome.Failed) event.value?.let { actions.afterAction(it) }
    }
}
