package com.ozvuchka.app.speech

import java.io.File

/** What an install holds for Vosk: nothing, the graph from before the prepared one, or the fast graph. */
internal enum class VoskInstallKind { ABSENT, LEGACY, FAST }

internal const val VOSK_FAST_GRAPH = "model-fast.onnx"

/**
 * The state of the Vosk directory, for the model card and for the in-place upgrade. [LEGACY] is every
 * install made before the prepared graph: model-5steps.onnx, model.onnx as published, or
 * model-prepared.onnx left by a failed rewrite. All of them read as they are and need only
 * model-prepared.onnx downloaded to be rewritten into [VOSK_FAST_GRAPH].
 */
internal fun voskInstallKind(directory: File): VoskInstallKind {
    val complete = SpeechModel.VOSK.requiredFiles.all { name ->
        File(directory, name).let { file -> file.isFile && file.length() > 0 }
    } && VoskTts.isInstalled(directory)
    return when {
        !complete -> VoskInstallKind.ABSENT
        File(directory, VOSK_FAST_GRAPH).length() > 0 -> VoskInstallKind.FAST
        else -> VoskInstallKind.LEGACY
    }
}
