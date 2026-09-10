package varro.host

import org.cef.callback.CefDragData
import java.util.Vector

/** JCEF renamed the full-path accessor; older runtimes expose it as getFileNames. */
internal object JcefDragFiles {
    private val getFilePaths = try {
        CefDragData::class.java.getMethod("getFilePaths", Vector::class.java)
    } catch (_: NoSuchMethodException) {
        null
    }

    fun paths(data: CefDragData): List<String> {
        val paths = Vector<String>()
        if (getFilePaths != null) {
            getFilePaths.invoke(data, paths)
        } else {
            data.getFileNames(paths)
        }
        return paths
    }
}
