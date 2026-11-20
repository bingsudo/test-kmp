package com.example.kmptest.data.backend

import android.content.Context
import android.os.Build
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.port.VideoBackends

/** MediaCodec and OpenCV everywhere; FFmpegKit only ships ARM native code. */
fun androidBackends(context: Context): VideoBackends {
    val app = context.applicationContext
    return BackendRegistry(
        listOf(
            BackendRegistry.Candidate(BackendId.PLATFORM, { true }, { PlatformBackend(app) }),
            BackendRegistry.Candidate(BackendId.OPENCV, { true }, { OpenCvBackend(app) }),
            BackendRegistry.Candidate(BackendId.FFMPEG, { Build.SUPPORTED_ABIS.any { it.startsWith("arm") } }, { FfmpegBackend(app) }),
        ),
    )
}
