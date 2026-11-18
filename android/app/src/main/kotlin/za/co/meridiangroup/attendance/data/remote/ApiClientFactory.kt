package za.co.meridiangroup.attendance.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.create
import za.co.meridiangroup.attendance.BuildConfig
import za.co.meridiangroup.attendance.core.ServerUrls
import za.co.meridiangroup.attendance.data.local.TokenHolder

/**
 * The backend address currently in use (Settings → Server). Retrofit is built once against [PLACEHOLDER_BASE];
 * [BaseUrlInterceptor] rewrites every request to the current root, so changing the server needs no rebuild.
 */
class BaseUrlHolder(initialRoot: String) {
    @Volatile
    private var apiBase: HttpUrl = ServerUrls.apiBase(initialRoot).toHttpUrl()

    /** Server root with trailing slash, e.g. `https://attendance.example.co.za/`. */
    val root: String get() = ServerUrls.rootOf(apiBase.toString())
    val apiBaseUrl: String get() = apiBase.toString()

    fun set(root: String) { apiBase = ServerUrls.apiBase(root).toHttpUrl() }

    /** Maps a placeholder URL (`https://meridian.invalid/api/v1/…`) onto the current base; other URLs are untouched. */
    fun rewrite(url: HttpUrl): HttpUrl {
        if (url.host != PLACEHOLDER_HOST) return url
        val relative = url.encodedPath.removePrefix("/" + ServerUrls.API_PATH).removePrefix("/")
        val query = url.encodedQuery?.let { "?$it" } ?: ""
        return apiBase.resolve(relative + query) ?: url
    }

    companion object {
        const val PLACEHOLDER_HOST = "meridian.invalid"
        const val PLACEHOLDER_BASE = "https://$PLACEHOLDER_HOST/" + ServerUrls.API_PATH
    }
}

/** Application interceptor (first in the chain) that points requests at the current server. */
class BaseUrlInterceptor(private val holder: BaseUrlHolder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val target = holder.rewrite(request.url)
        return chain.proceed(if (target == request.url) request else request.newBuilder().url(target).build())
    }
}

/**
 * Builds the OkHttp/Retrofit stack. Two clients exist:
 *  * `refreshApi` — bare client (no authenticator) used only by [TokenAuthenticator] for POST /auth/refresh;
 *  * `api` — authenticated client with [AuthInterceptor] + [TokenAuthenticator].
 */
object ApiClientFactory {
    const val API_PATH = "api/v1/"

    /**
     * Certificate pinning hook (docs/ARCHITECTURE.md §5). Disabled by default: add the SHA-256 pins of the
     * production leaf/intermediate certificates here (and a backup pin) before enabling, e.g.
     * `add("attendance.example.co.za", "sha256/AAAA…=", "sha256/BBBB…=")`. Pins must be rotated before the
     * certificate is, otherwise the app locks itself out.
     */
    private val certificatePinner: CertificatePinner? = null

    /** Server root configured at build time (BuildConfig.API_BASE_URL), used until the user picks another server. */
    fun defaultRoot(): String = ServerUrls.rootOf(BuildConfig.API_BASE_URL.ifBlank { "https://attendance.invalid/" })

    fun baseUrl(override: String? = null): String {
        val root = (override ?: BuildConfig.API_BASE_URL).trim().let { if (it.endsWith("/")) it else "$it/" }
        return if (root.endsWith(API_PATH)) root else root + API_PATH
    }

    fun userAgent(): String = "MeridianAttendance/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}; Android ${android.os.Build.VERSION.SDK_INT})"

    private fun baseClientBuilder(holder: BaseUrlHolder? = null): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        certificatePinner?.let { builder.certificatePinner(it) }
        holder?.let { builder.addInterceptor(BaseUrlInterceptor(it)) }
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                },
            )
        }
        return builder
    }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BaseUrlHolder.PLACEHOLDER_BASE)
        .client(client)
        .addConverterFactory(UnitConverterFactory)
        .addConverterFactory(ApiJson.json.asConverterFactory("application/json; charset=utf-8".toMediaType()))
        .build()

    fun createRefreshApi(holder: BaseUrlHolder): AttendanceApi = retrofit(baseClientBuilder(holder).build()).create()

    fun createApi(tokenStore: TokenHolder, refreshApi: AttendanceApi, forcedLogout: ForcedLogoutListener, holder: BaseUrlHolder): AttendanceApi {
        val authenticator = TokenAuthenticator(tokenStore, { refreshApi.refresh(it) }, forcedLogout)
        val client = baseClientBuilder(holder)
            .addInterceptor(AuthInterceptor(tokenStore, userAgent()))
            .authenticator(authenticator)
            .build()
        return retrofit(client).create()
    }

    /** Bare client for GET /healthz probes of a server address that is not saved yet (no auth, short timeouts). */
    fun probeClient(): OkHttpClient = baseClientBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Lets endpoints that answer 202/204 with an empty body be declared as `Response<Unit>`. */
    private object UnitConverterFactory : Converter.Factory() {
        override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? =
            if (type == Unit::class.java) Converter<ResponseBody, Unit> { body -> body.close(); Unit } else null
    }
}
