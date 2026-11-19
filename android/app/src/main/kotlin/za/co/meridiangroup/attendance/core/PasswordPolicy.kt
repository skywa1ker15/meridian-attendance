package za.co.meridiangroup.attendance.core

/**
 * The password rules the server enforces (`backend/app/core/security.py#validate_password_policy`),
 * repeated client-side so the activation / reset screens can show a live checklist.
 *
 * The server owns the final verdict: it also checks a 10 000-entry common-password list that is far
 * too large to ship in the APK. [Rule.NOT_COMMON] therefore catches only the obvious cases locally
 * (the company-predictable words and very low character variety) and a server `too_common` rejection
 * is mapped back onto the password field by [messageForCode].
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    enum class Rule { LENGTH, NOT_IDENTIFIER, NOT_COMMON, MATCH }

    data class Check(val rule: Rule, val satisfied: Boolean)

    /** Mirrors the backend's `_PREDICTABLE_WORDS`. */
    private val predictable = setOf(
        "password", "passw0rd", "welcome", "letmein", "qwerty", "qwertyuiop", "admin", "administrator",
        "meridian", "meridiangroup", "umhlanga", "durban", "attendance", "employee", "changeme", "summer",
        "winter", "spring", "autumn", "january", "february", "monday", "friday", "secret", "abcdef",
    )

    /**
     * The checklist for the UI. [identifiers] are the employee number and email address;
     * [confirm] is null when there is no confirm field (then [Rule.MATCH] is left out).
     */
    fun check(password: String, identifiers: List<String> = emptyList(), confirm: String? = null): List<Check> {
        val lowered = password.lowercase()
        val checks = mutableListOf(
            Check(Rule.LENGTH, password.length >= MIN_LENGTH),
            Check(
                Rule.NOT_IDENTIFIER,
                // Only check identifiers ≥3 chars to avoid false positives from short first names
                // like "Al" matching inside unrelated passwords. Mirrors the backend's minimum.
                password.isNotEmpty() && identifiers.none { id ->
                    val trimmed = id.trim().lowercase()
                    trimmed.length >= 3 && lowered.contains(trimmed)
                },
            ),
            Check(Rule.NOT_COMMON, password.isNotEmpty() && !looksCommon(lowered)),
        )
        if (confirm != null) checks += Check(Rule.MATCH, password.isNotEmpty() && password == confirm)
        return checks
    }

    /** True when every rule in [check] passes. */
    fun isAcceptable(password: String, identifiers: List<String> = emptyList(), confirm: String? = null): Boolean =
        check(password, identifiers, confirm).all { it.satisfied }

    private fun looksCommon(lowered: String): Boolean {
        if (lowered.isEmpty()) return true
        // "Password123!" / "Meridian2026": a predictable word padded with digits and symbols.
        val core = lowered.trim { it !in 'a'..'z' }
        return lowered in predictable || core in predictable || lowered.toSet().size <= 3
    }

    /** Maps the server's `details.fields.{password,new_password}` code onto a message for the input. */
    fun messageForCode(code: String): String = when (code) {
        "too_short" -> "Use at least $MIN_LENGTH characters."
        "contains_identifier" -> "Your password can't contain your email address or employee number."
        "too_common" -> "That password is too common. Choose a stronger one."
        else -> code.takeIf { it.isNotBlank() && it.contains(' ') } ?: "Choose a different password."
    }
}
