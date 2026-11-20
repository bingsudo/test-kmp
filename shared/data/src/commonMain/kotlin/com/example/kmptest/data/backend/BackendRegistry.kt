package com.example.kmptest.data.backend

import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.port.VideoBackend
import com.example.kmptest.domain.port.VideoBackends

/**
 * The engines of this platform. [candidates] are created lazily and only those whose [isAvailable] check passes
 * are offered (the settings screen shows exactly [available]).
 */
class BackendRegistry(candidates: List<Candidate>) : VideoBackends {
    class Candidate(val id: BackendId, val isAvailable: () -> Boolean, val create: () -> VideoBackend)

    private val usable = candidates.filter { runCatching { it.isAvailable() }.getOrDefault(false) }
    private val created = HashMap<BackendId, VideoBackend>()

    override val available: List<BackendId> = usable.map { it.id }

    @Synchronized
    override fun get(id: BackendId): VideoBackend {
        val candidate = usable.firstOrNull { it.id == id } ?: usable.first()
        return created.getOrPut(candidate.id) { candidate.create() }
    }
}
