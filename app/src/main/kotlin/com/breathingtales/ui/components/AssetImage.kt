package com.breathingtales.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `assets/<path>` 그림 (장면 일러스트·표지). 경로가 없거나 읽지 못하면 null */
@Composable
fun rememberAssetImage(path: String?): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching { context.assets.open(it).use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return image
}
