package za.co.meridiangroup.attendance.core

/**
 * Wire enumerations from docs/API.md. DTOs keep the raw string so an unknown value coming
 * from a newer backend never crashes the client; UI code maps through these helpers.
 */
interface WireEnum {
    val wire: String
}

enum class EventType(override val wire: String) : WireEnum {
    ARRIVAL("arrival"), DEPARTURE("departure"), UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): EventType = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class EventSource(override val wire: String) : WireEnum {
    GEOFENCE("geofence"), WIFI("wifi"), MANUAL("manual"), ADMIN("admin"), UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): EventSource = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class EventStatus(override val wire: String) : WireEnum {
    PENDING("pending"), CONFIRMED("confirmed"), UNDER_REVIEW("under_review"), REJECTED("rejected"),
    EXPIRED("expired"), DISMISSED("dismissed"), UNKNOWN("unknown");

    val isOpenForEmployee: Boolean get() = this == PENDING

    companion object {
        fun fromWire(value: String?): EventStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class GeofenceTransition(override val wire: String) : WireEnum {
    ENTER("enter"), DWELL("dwell"), EXIT("exit"), NONE("none");

    companion object {
        fun fromWire(value: String?): GeofenceTransition = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

enum class SessionStatus(override val wire: String) : WireEnum {
    OPEN("open"), CLOSED("closed"), MISSING_CLOCK_OUT("missing_clock_out"), UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): SessionStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class TodayStatus(override val wire: String) : WireEnum {
    NOT_STARTED("not_started"), PENDING_ARRIVAL("pending_arrival"), CLOCKED_IN("clocked_in"),
    PENDING_DEPARTURE("pending_departure"), CLOCKED_OUT("clocked_out"), MISSING_CLOCK_OUT("missing_clock_out"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): TodayStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class RequestType(override val wire: String) : WireEnum {
    MISSED_CLOCK_IN("missed_clock_in"), MISSED_CLOCK_OUT("missed_clock_out"), FULL_DAY("full_day"),
    CORRECTION("correction"), PROFILE_CORRECTION("profile_correction"), UNKNOWN("unknown");

    val needsClockIn: Boolean get() = this == MISSED_CLOCK_IN || this == FULL_DAY || this == CORRECTION
    val needsClockOut: Boolean get() = this == MISSED_CLOCK_OUT || this == FULL_DAY || this == CORRECTION

    companion object {
        /** Types an employee may create from the app (profile corrections come from the profile screen). */
        val attendanceTypes: List<RequestType> = listOf(MISSED_CLOCK_IN, MISSED_CLOCK_OUT, FULL_DAY, CORRECTION)
        fun fromWire(value: String?): RequestType = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class RequestStatus(override val wire: String) : WireEnum {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected"), CANCELLED("cancelled"), UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): RequestStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class NotificationType(override val wire: String) : WireEnum {
    ARRIVAL_DETECTED("arrival_detected"),
    DEPARTURE_DETECTED("departure_detected"),
    CLOCK_IN_CONFIRMATION_REQUIRED("clock_in_confirmation_required"),
    CLOCK_OUT_CONFIRMATION_REQUIRED("clock_out_confirmation_required"),
    ATTENDANCE_RECORDED("attendance_recorded"),
    ATTENDANCE_SUBMISSION_FAILED("attendance_submission_failed"),
    MANUAL_REQUEST_REVIEWED("manual_request_reviewed"),
    CORRECTION_APPROVED("correction_approved"),
    CORRECTION_REJECTED("correction_rejected"),
    DEVICE_REVOKED("device_revoked"),
    SYSTEM("system"),
    UNKNOWN("unknown");

    /** Confirmation-type notifications go to the high-importance channel and deep-link to the confirmation screen. */
    val isConfirmation: Boolean
        get() = this == CLOCK_IN_CONFIRMATION_REQUIRED || this == CLOCK_OUT_CONFIRMATION_REQUIRED ||
            this == ARRIVAL_DETECTED || this == DEPARTURE_DETECTED

    companion object {
        fun fromWire(value: String?): NotificationType = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class DismissReason(override val wire: String) : WireEnum {
    NOT_AT_WORK("not_at_work"), WRONG_DETECTION("wrong_detection"), OTHER("other");

    companion object {
        fun fromWire(value: String?): DismissReason = entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

enum class DeviceStatus(override val wire: String) : WireEnum {
    ACTIVE("active"), PENDING_APPROVAL("pending_approval"), REVOKED("revoked"), UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): DeviceStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Kinds of rows in the local pending outbox. Only the first three are replayed through POST /attendance/sync. */
enum class OutboxKind(override val wire: String) : WireEnum {
    EVENT("event"), CONFIRMATION("confirmation"), DISMISSAL("dismissal"), MANUAL_REQUEST("manual_request"), FCM_TOKEN("fcm_token");

    val goesThroughSyncEndpoint: Boolean get() = this == EVENT || this == CONFIRMATION || this == DISMISSAL

    companion object {
        fun fromWire(value: String?): OutboxKind? = entries.firstOrNull { it.wire == value }
    }
}
