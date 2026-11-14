package io.parchment.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.parchment.ai.platform.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class FileImage(val bitmap: ImageBitmap?, val isLoading: Boolean)

/** Shows an already decoded image at once and decodes anything else away from the UI thread. */
@Composable
internal fun rememberFileImage(path: String): FileImage {
    var state by remember(path) {
        val cached = PlatformStorage.cachedImageBitmap(path)
        mutableStateOf(FileImage(cached, isLoading = cached == null && path.isNotBlank()))
    }
    LaunchedEffect(path) {
        if (state.isLoading) {
            state = FileImage(withContext(Dispatchers.Default) { PlatformStorage.loadImageBitmapFromFile(path) }, isLoading = false)
        }
    }
    return state
}
