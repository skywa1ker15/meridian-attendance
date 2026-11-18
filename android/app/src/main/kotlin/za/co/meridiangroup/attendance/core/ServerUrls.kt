package za.co.meridiangroup.attendance.core

import java.net.URI

/** Result of validating a backend address typed by a user. */
sealed class ServerUrlResult {
    /** [root] is the server root with a trailing slash, e.g. `https://attendance.example.co.za/`. */
    data class Valid(val root: String) : ServerUrlResult()
    data class Invalid(val message: String) : ServerUrlResult()
}

/**
 * Backend address rules for the "Server" setting (pure Kotlin, unit-tested).
 *  * scheme https:// always allowed; http:// only when [allowCleartext] (debug builds, LAN testing);
 *  * a missing scheme defaults to https://;
 *  * a pasted `/api/v1` suffix is stripped — the app appends it itself;
 *  * the result always ends with `/`, so `healthz` and `api/v1/` resolve under any path prefix.
 */
object ServerUrls {
    const val API_PATH = "api/v1/"
    const val HEALTH_PATH = "healthz"

    fun normalise(input: String, allowCleartext: Boolean): ServerUrlResult {
        var v = input.trim()
        if (v.isEmpty()) return ServerUrlResult.Invalid("Enter the server address.")
        if (!v.contains("://")) v = "https://$v"
        val uri = runCatching { URI(v) }.getOrNull() ?: return ServerUrlResult.Invalid("That is not a valid address.")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return ServerUrlResult.Invalid("The address must start with https://")
        if (scheme == "http" && !allowCleartext) return ServerUrlResult.Invalid("Only secure https:// addresses are allowed in this build.")
        val host = uri.host
        if (host.isNullOrBlank()) return ServerUrlResult.Invalid("The address has no host name.")
        if (uri.rawQuery != null || uri.rawFragment != null) return ServerUrlResult.Invalid("Remove the ?query or #fragment from the address.")
        if (uri.port != -1 && uri.port !in 1..65535) return ServerUrlResult.Invalid("The port number is not valid.")
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith("/api/v1")) path = path.removeSuffix("/api/v1")
        else if (path.endsWith("/api")) path = path.removeSuffix("/api")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return ServerUrlResult.Valid("$scheme://${hostPart.lowercase()}$port$path/")
    }

    /** `<root>api/v1/` — the Retrofit base for a normalised [root]. */
    fun apiBase(root: String): String = root.trimEnd('/') + "/" + API_PATH

    /** `/healthz` lives at the server root, not under `/api/v1`. */
    fun healthUrl(root: String): String = root.trimEnd('/') + "/" + HEALTH_PATH

    /** Root of an api base or root URL (`…/api/v1/` → `…/`). */
    fun rootOf(url: String): String {
        val t = url.trim().trimEnd('/')
        return (if (t.endsWith("/api/v1")) t.removeSuffix("/api/v1") else t) + "/"
    }

    /** Human-friendly label: host[:port][/path], no scheme or trailing slash. */
    fun display(root: String): String = root.substringAfter("://").trimEnd('/')
}
