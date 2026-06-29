package com.dabber.model

import android.content.Context
import java.io.File

/**
 * Resolves the on-device ggml model file. Prefers the internal copy (filesDir/models),
 * but also accepts a sideloaded copy under the app's external files dir — i.e. a file
 * pushed via `adb push ... /sdcard/Android/data/com.dabber/files/models/[FILE_NAME]`.
 */
object ModelStore {

    fun modelFile(context: Context): File {
        val internal = File(File(context.filesDir, "models"), ModelConfig.FILE_NAME)
        if (internal.exists()) return internal
        val ext = context.getExternalFilesDir(null)
            ?.let { File(File(it, "models"), ModelConfig.FILE_NAME) }
        return if (ext != null && ext.exists()) ext else internal
    }

    fun exists(context: Context): Boolean = modelFile(context).exists()
}
