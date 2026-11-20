package com.example.kmptest.data.queue

import com.example.kmptest.domain.model.ClockNotFoundException
import com.example.kmptest.domain.model.ItemStatus
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.QueueItem
import com.example.kmptest.domain.model.QueueState
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.usecase.FixVideoUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lets the app module keep the process alive (foreground service) while the queue runs. */
interface WorkKeeper {
    fun workStarted()
}

class ProcessingQueueImpl(
    private val fix: FixVideoUseCase,
    private val keeper: WorkKeeper,
) : ProcessingQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(QueueState())
    private var job: Job? = null

    override val state: StateFlow<QueueState> = mutableState.asStateFlow()

    override fun add(sources: List<VideoSource>) {
        mutableState.update { s ->
            val known = s.items.map { it.source.uri }.toSet()
            s.copy(items = s.items + sources.filter { it.uri !in known }.map { QueueItem(it) })
        }
    }

    override fun remove(source: VideoSource) {
        if (mutableState.value.running) return
        mutableState.update { s -> s.copy(items = s.items.filterNot { it.source.uri == source.uri }) }
    }

    override fun clear() {
        if (mutableState.value.running) return
        mutableState.update { QueueState() }
    }

    override fun clearDone() {
        if (mutableState.value.running) return
        mutableState.update { s -> s.copy(items = s.items.filterNot { it.status == ItemStatus.DONE }) }
    }

    override fun cancel() {
        job?.cancel()
    }

    override fun start(options: ProcessingOptions, outputSuffix: String) {
        if (job?.isActive == true) return
        mutableState.update { s ->
            s.copy(items = s.items.map { if (it.status == ItemStatus.DONE) it else QueueItem(it.source) }, running = true)
        }
        keeper.workStarted()
        job = scope.launch {
            try {
                for (source in mutableState.value.items.filter { it.status == ItemStatus.QUEUED }.map { it.source }) {
                    runOne(source, options, outputSuffix)
                }
            } finally {
                mutableState.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun runOne(source: VideoSource, options: ProcessingOptions, suffix: String) {
        fun change(transform: (QueueItem) -> QueueItem) =
            mutableState.update { s -> s.copy(items = s.items.map { if (it.source.uri == source.uri) transform(it) else it }) }
        change { it.copy(status = ItemStatus.RUNNING, progress = null) }
        try {
            val out = fix(source, options, suffix) { p -> change { it.copy(progress = p) } }
            change { it.copy(status = ItemStatus.DONE, result = out.result, outputName = out.target.displayName, progress = null) }
        } catch (e: CancellationException) {
            change { it.copy(status = ItemStatus.CANCELLED, progress = null) }
            throw e
        } catch (e: ClockNotFoundException) {
            change { it.copy(status = ItemStatus.FAILED, clockError = e.error, progress = null) }
        } catch (e: Throwable) {
            change { it.copy(status = ItemStatus.FAILED, errorMessage = e.message ?: e.javaClass.simpleName, progress = null) }
        }
    }
}
