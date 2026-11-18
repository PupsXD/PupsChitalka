package com.ozvuchka.app.speech

import java.io.File

/**
 * ONNX Runtime's optimized copy of an acoustic model is valid only for the exact file it was built
 * from. A stamp beside the copy holds the model's size and modification time, so replacing the model
 * (by hand or together with an update) makes the next start rebuild the copy instead of loading a
 * stale graph.
 */
internal object OrtCache {
    /** Ties a copy to one state of the model file. */
    fun fingerprint(model: File): String = "${model.length()}:${model.lastModified()}"

    /** Whether [cache] was built from this exact [model]; a missing or unreadable stamp means no. */
    fun isValid(model: File, cache: File, stamp: File): Boolean =
        model.isFile && cache.isFile && stamp.isFile &&
            runCatching { stamp.readText().trim() == fingerprint(model) }.getOrDefault(false)

    /** Records that [cache] was just built from [model]; a failure only means it is rebuilt next time. */
    fun remember(model: File, stamp: File) {
        runCatching { stamp.writeText(fingerprint(model)) }
    }

    /** Throws the copy and its stamp away; both are rebuilt from the model when it is prepared. */
    fun forget(cache: File, stamp: File) {
        cache.delete()
        stamp.delete()
    }
}
