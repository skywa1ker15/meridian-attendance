package za.co.meridiangroup.attendance.core

import java.net.URI
import java.net.URLDecoder

/** What an emailed link asks the app to do. */
enum class AppLinkKind { INVITE, RESET }

/**
 * A parsed invitation / password-reset link.
 *
 * [serverRoot] is the backend the link came from (`https://host:port/<prefix>/`), present only for
 * http(s) links — the custom-scheme fallback carries no host, so the configured server is used.
 */
data class AppLink(val kind: AppLinkKind, val token: String, val serverRoot: String? = null)

/**
 * Links in invitation and reset emails: `https://<server>/app/invite?token=…` and
 * `https://<server>/app/reset?token=…`, plus the custom-scheme fallback the web page's
 * "Open in the app" button fires: `meridian://invite?token=…` / `meridian://reset?token=…`.
 *
 * The server address is configurable, so no host can be baked in (see docs/android/DEEP-LINKS.md).
 * The server a link came from is derived here and offered as the app's backend, which is what makes
 * a brand-new install from an emailed link work without anybody typing an address.
 */
object AppLinks {
    /** Short scheme used by the "Open in the app" button on the web page. */
    const val CUSTOM_SCHEME = "meridian"

    /** The app's own notification scheme also accepts these links. */
    const val LEGACY_SCHEME = "meridianattendance"

    const val INVITE_PATH = "/app/invite"
    const val RESET_PATH = "/app/reset"
    const val INVITE_HOST = "invite"
    const val RESET_HOST = "reset"
    const val TOKEN_PARAM = "token"

    /**
     * Returns null when [uri] is not an invitation / reset link or carries no token.
     * [allowCleartext] follows BuildConfig.ALLOW_CLEARTEXT: an `http://` link only yields a
     * [AppLink.serverRoot] in builds that may talk to a cleartext server.
     */
    fun parse(uri: String?, allowCleartext: Boolean = false): AppLink? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        val token = queryParam(parsed.rawQuery, TOKEN_PARAM)?.takeIf { it.isNotBlank() } ?: return null

        if (scheme == CUSTOM_SCHEME || scheme == LEGACY_SCHEME) {
            // meridian://invite?token=… — the host carries the action, there is no server in the link.
            val kind = when (parsed.host?.lowercase() ?: parsed.schemeSpecificPart.removePrefix("//").substringBefore('?').trim('/').lowercase()) {
                INVITE_HOST -> AppLinkKind.INVITE
                RESET_HOST -> AppLinkKind.RESET
                else -> return null
            }
            return AppLink(kind, token)
        }
        if (scheme != "https" && scheme != "http") return null
        val path = (parsed.rawPath ?: "").trimEnd('/')
        val kind = when {
            path.endsWith(INVITE_PATH) -> AppLinkKind.INVITE
            path.endsWith(RESET_PATH) -> AppLinkKind.RESET
            else -> return null
        }
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return AppLink(kind, token)
        val prefix = path.removeSuffix(if (kind == AppLinkKind.INVITE) INVITE_PATH else RESET_PATH)
        val port = if (parsed.port == -1) "" else ":${parsed.port}"
        val root = when (val r = ServerUrls.normalise("$scheme://$host$port$prefix", allowCleartext)) {
            is ServerUrlResult.Valid -> r.root
            is ServerUrlResult.Invalid -> null
        }
        return AppLink(kind, token, root)
    }

    /** The https link an email carries, for documentation and tests. */
    fun webUrl(serverRoot: String, kind: AppLinkKind, token: String): String =
        serverRoot.trimEnd('/') + (if (kind == AppLinkKind.INVITE) INVITE_PATH else RESET_PATH) + "?$TOKEN_PARAM=$token"

    /** The custom-scheme link the web page's "Open in the app" button fires. */
    fun appUrl(kind: AppLinkKind, token: String): String =
        "$CUSTOM_SCHEME://" + (if (kind == AppLinkKind.INVITE) INVITE_HOST else RESET_HOST) + "?$TOKEN_PARAM=$token"

    private fun queryParam(rawQuery: String?, name: String): String? {
        val query = rawQuery ?: return null
        for (part in query.split('&')) {
            val key = part.substringBefore('=')
            if (decode(key) != name) continue
            return decode(part.substringAfter('=', ""))
        }
        return null
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
