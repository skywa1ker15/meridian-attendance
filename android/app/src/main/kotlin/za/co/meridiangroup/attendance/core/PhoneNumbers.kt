package za.co.meridiangroup.attendance.core

/**
 * South African phone numbers, mirroring the backend's `app/core/validation.py#normalise_sa_phone`
 * exactly so that a number typed on the phone during invitation acceptance is stored identically to
 * one an administrator typed into the Manage section.
 *
 * `0821234567`, `082 123 4567`, `+27821234567`, `0027821234567`, `27821234567` → `+27821234567`.
 * A national significant number is 9 digits starting 1-8 (0 and 9 never start a subscriber number).
 */
object PhoneNumbers {
    /** The hint shown under the phone field; matches the backend's `PHONE_HELP`. */
    const val HELP = "Enter a South African number, for example 082 123 4567 or +27 82 123 4567."
    const val EXAMPLE = "082 123 4567"

    /** Whitespace, the usual separators and the unicode dashes people paste out of email signatures. */
    private val separators = Regex("[\\s\\-()./ ‐-―]")
    private val nsnRegex = Regex("^[1-8]\\d{8}$")

    sealed class Parsed {
        /** Nothing was typed. The field is optional in most places; the activation screen requires it. */
        object Empty : Parsed()
        data class Valid(val e164: String) : Parsed()
        object Invalid : Parsed()
    }

    fun parse(value: String?): Parsed {
        if (value == null) return Parsed.Empty
        val cleaned = separators.replace(value, "").trim()
        if (cleaned.isEmpty()) return Parsed.Empty
        val nsn: String = when {
            cleaned.startsWith("+") -> {
                val digits = cleaned.substring(1)
                if (!digits.isDigits() || !digits.startsWith("27")) return Parsed.Invalid
                digits.substring(2)
            }
            cleaned.isDigits() -> when {
                cleaned.startsWith("0027") -> cleaned.substring(4)
                cleaned.startsWith("27") && cleaned.length == 11 -> cleaned.substring(2)
                cleaned.startsWith("0") -> cleaned.substring(1)
                else -> return Parsed.Invalid
            }
            else -> return Parsed.Invalid
        }
        return if (nsnRegex.matches(nsn)) Parsed.Valid("+27$nsn") else Parsed.Invalid
    }

    /** `+27821234567` for anything valid, otherwise null (including empty input). */
    fun normalise(value: String?): String? = (parse(value) as? Parsed.Valid)?.e164

    /** Null when acceptable, otherwise the inline error message. [required] rejects an empty field. */
    fun error(value: String?, required: Boolean): String? = when (parse(value)) {
        is Parsed.Valid -> null
        Parsed.Empty -> if (required) "Enter your phone number." else null
        Parsed.Invalid -> HELP
    }

    /** Local display form of an E.164 number: `+27821234567` → `082 123 4567`. */
    fun display(value: String?): String? {
        val e164 = normalise(value) ?: return value?.trim()?.ifBlank { null }
        val nsn = e164.removePrefix("+27")
        return "0${nsn.substring(0, 2)} ${nsn.substring(2, 5)} ${nsn.substring(5)}"
    }

    private fun String.isDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
}
