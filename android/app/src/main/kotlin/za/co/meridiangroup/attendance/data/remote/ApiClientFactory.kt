package za.co.meridiangroup.attendance.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.create
import za.co.meridiangroup.attendance.BuildConfig
import za.co.meridiangroup.attendance.data.local.TokenHolder

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

    fun baseUrl(override: String? = null): String {
        val root = (override ?: BuildConfig.API_BASE_URL).trim().let { if (it.endsWith("/")) it else "$it/" }
        return if (root.endsWith(API_PATH)) root else root + API_PATH
    }

    fun userAgent(): String = "MeridianAttendance/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}; Android ${android.os.Build.VERSION.SDK_INT})"

    private fun baseClientBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        certificatePinner?.let { builder.certificatePinner(it) }
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

    private fun retrofit(client: OkHttpClient, baseUrlOverride: String?): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl(baseUrlOverride))
        .client(client)
        .addConverterFactory(UnitConverterFactory)
        .addConverterFactory(ApiJson.json.asConverterFactory("application/json; charset=utf-8".toMediaType()))
        .build()

    fun createRefreshApi(baseUrlOverride: String? = null): AttendanceApi = retrofit(baseClientBuilder().build(), baseUrlOverride).create()

    fun createApi(tokenStore: TokenHolder, refreshApi: AttendanceApi, forcedLogout: ForcedLogoutListener, baseUrlOverride: String? = null): AttendanceApi {
        val authenticator = TokenAuthenticator(tokenStore, { refreshApi.refresh(it) }, forcedLogout)
        val client = baseClientBuilder()
            .addInterceptor(AuthInterceptor(tokenStore, userAgent()))
            .authenticator(authenticator)
            .build()
        return retrofit(client, baseUrlOverride).create()
    }

    /** Lets endpoints that answer 202/204 with an empty body be declared as `Response<Unit>`. */
    private object UnitConverterFactory : Converter.Factory() {
        override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? =
            if (type == Unit::class.java) Converter<ResponseBody, Unit> { body -> body.close(); Unit } else null
    }
}
