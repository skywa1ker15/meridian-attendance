package za.co.meridiangroup.attendance.core

import java.time.Instant
import java.time.LocalDate

/** Client-side input validation. The server re-validates everything; these only give instant feedback. */
object Validation {
    const val MIN_PASSWORD_LENGTH = 10
    const val MIN_REASON_LENGTH = 5
    const val MAX_REASON_LENGTH = 500
    const val MAX_NOTE_LENGTH = 500

    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val employeeNumberRegex = Regex("^[A-Za-z0-9][A-Za-z0-9\\-_/]{1,31}$")
    private val phoneRegex = Regex("^\\+?[0-9 ()\\-]{7,20}$")

    fun identifier(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return "Enter your employee number or email address."
        if (v.contains('@')) return if (emailRegex.matches(v)) null else "That email address doesn't look right."
        return if (employeeNumberRegex.matches(v)) null else "Enter a valid employee number (e.g. MG-0042)."
    }

    fun loginPassword(value: String): String? = if (value.isEmpty()) "Enter your password." else null

    /** Mirrors the server policy from docs/API.md (length, not equal to identifier). The common-password list is server-side. */
    fun newPassword(password: String, identifier: String?, confirm: String? = null): List<String> {
        val issues = mutableListOf<String>()
        if (password.length < MIN_PASSWORD_LENGTH) issues += "Use at least $MIN_PASSWORD_LENGTH characters."
        if (!identifier.isNullOrBlank() && password.equals(identifier.trim(), ignoreCase = true)) issues += "Your password can't be the same as your employee number or email."
        if (password.isNotEmpty() && password.trim() != password) issues += "Passwords can't start or end with spaces."
        if (confirm != null && confirm != password) issues += "The two passwords don't match."
        return issues
    }

    fun phone(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return if (phoneRegex.matches(v)) null else "Enter a valid phone number, e.g. +27 82 123 4567."
    }

    fun reason(value: String): String? {
        val v = value.trim()
        return when {
            v.length < MIN_REASON_LENGTH -> "Please give a short reason (at least $MIN_REASON_LENGTH characters)."
            v.length > MAX_REASON_LENGTH -> "Keep the reason under $MAX_REASON_LENGTH characters."
            else -> null
        }
    }

    fun note(value: String?): String? =
        if (value != null && value.length > MAX_NOTE_LENGTH) "Keep the note under $MAX_NOTE_LENGTH characters." else null

    data class ManualRequestInput(
        val type: RequestType,
        val workDate: String?,
        val clockInAt: Instant?,
        val clockOutAt: Instant?,
        val reason: String,
        val nowMillis: Long,
        val maxOfflineAgeHours: Int = 24 * 60,
    )

    /** Returns a map of field → message. Empty map means valid. */
    fun manualRequest(input: ManualRequestInput): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        if (input.type == RequestType.UNKNOWN || input.type == RequestType.PROFILE_CORRECTION) errors["type"] = "Choose a request type."
        val date = input.workDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (date == null) {
            errors["work_date"] = "Choose the date."
        } else {
            val today = LocalDate.parse(JohannesburgTime.workDateOf(input.nowMillis))
            if (date.isAfter(today)) errors["work_date"] = "The date can't be in the future."
        }
        if (input.type.needsClockIn && input.clockInAt == null) errors["requested_clock_in"] = "Enter the clock-in time."
        if (input.type.needsClockOut && input.clockOutAt == null) errors["requested_clock_out"] = "Enter the clock-out time."
        if (input.clockInAt != null && input.clockOutAt != null && !input.clockOutAt.isAfter(input.clockInAt)) {
            errors["requested_clock_out"] = "Clock-out must be after clock-in."
        }
        if (input.clockInAt != null && input.clockInAt.toEpochMilli() > input.nowMillis) errors["requested_clock_in"] = "Clock-in can't be in the future."
        if (input.clockOutAt != null && input.clockOutAt.toEpochMilli() > input.nowMillis) errors["requested_clock_out"] = "Clock-out can't be in the future."
        reason(input.reason)?.let { errors["reason"] = it }
        return errors
    }

    fun profileCorrection(field: String, value: String): String? {
        val v = value.trim()
        return when (field) {
            "first_name", "last_name" -> if (v.length in 1..80) null else "Enter a name between 1 and 80 characters."
            "email" -> if (emailRegex.matches(v)) null else "Enter a valid email address."
            "phone" -> phone(v) ?: if (v.isEmpty()) "Enter a phone number." else null
            "department" -> if (v.isNotEmpty()) null else "Enter the department name or code."
            else -> "Choose which detail to correct."
        }
    }
}
