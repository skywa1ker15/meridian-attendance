package za.co.meridiangroup.attendance.data.repository

import kotlinx.coroutines.flow.Flow
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.db.WorkplaceDao
import za.co.meridiangroup.attendance.data.local.db.WorkplaceEntity
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.toEntity

/** Registers/removes OS geofences. Implemented by detection.GeofenceManager; abstracted for tests. */
interface GeofenceRegistrar {
    /** Registers geofences for exactly these workplaces (already filtered) and removes all others. Returns true on success. */
    suspend fun registerOnly(workplaces: List<WorkplaceEntity>): Boolean
    suspend fun removeAll()
}

/**
 * Pulls /config/workplaces and /config/policy, caches them, and (re)registers geofences ONLY for workplaces
 * whose `coordinates_confirmed` is true and that have entry or exit detection enabled.
 */
class ConfigRepository(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val dao: WorkplaceDao,
    private val prefs: AppPreferences,
    private val registrar: GeofenceRegistrar,
    private val now: () -> Long,
) {
    val workplaces: Flow<List<WorkplaceEntity>> = dao.observeAll()
    val policy: Flow<PolicyDto?> = prefs.policyFlow

    suspend fun cachedWorkplaces(): List<WorkplaceEntity> = dao.all()
    suspend fun workplace(id: String): WorkplaceEntity? = dao.byId(id)

    suspend fun refresh(forceGeofences: Boolean = false): ApiResult<List<WorkplaceEntity>> {
        caller.call { api.policy() }.getOrNull()?.let { prefs.savePolicy(it) }
        val r = caller.call { api.workplaces() }
        if (r !is ApiResult.Success) {
            // Offline: keep using the cache, but still make sure geofences exist (e.g. after reboot).
            if (forceGeofences) ensureGeofences(force = true)
            return r.map { emptyList() }
        }
        val version = r.value.configVersion
        val entities = r.value.items.map { it.toEntity(version) }
        dao.replaceAll(entities)
        prefs.setConfigVersion(version, now())
        ensureGeofences(force = forceGeofences)
        return ApiResult.Success(entities)
    }

    /** Re-registers geofences when the config version changed since the last registration, or when forced. */
    suspend fun ensureGeofences(force: Boolean) {
        val version = prefs.configVersion()
        if (!force && prefs.geofencesRegisteredVersion() == version && version >= 0) return
        val eligible = dao.all().filter { it.geofenceEligible }
        if (eligible.isEmpty()) {
            registrar.removeAll()
            prefs.setGeofencesRegisteredVersion(version)
            return
        }
        if (registrar.registerOnly(eligible)) prefs.setGeofencesRegisteredVersion(version)
    }

    /** Forces the next [ensureGeofences] to register again (permission granted, location turned on, boot). */
    suspend fun invalidateGeofences() = prefs.setGeofencesRegisteredVersion(-1)
}
