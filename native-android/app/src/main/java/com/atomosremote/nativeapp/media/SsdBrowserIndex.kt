package com.atomosremote.nativeapp.media

/** Pure, read-only projection of REAL recorder-supplied file paths. No synthetic clips. */
data class SsdBrowserClip(val index: Int, val path: String)
data class SsdBrowserFolderView(
    val directory: String,
    val folders: List<String>,
    val files: List<SsdBrowserClip>
)

object SsdBrowserIndex {
    /** Avoid treating '..' or malformed paths as navigable recorder paths. */
    fun canonical(raw: String): String? {
        val components = raw.replace('\\', '/').trim('/').split('/')
        if (components.isEmpty() || components.any {
                it.isBlank() || it == "." || it == ".." || it.any { ch -> ch.code < 32 || ch.code == 127 }
            }) return null
        return components.joinToString("/")
    }

    fun view(entries: List<SsdBrowserClip>, directory: String = ""): SsdBrowserFolderView {
        val dir = if (directory.isBlank()) "" else (canonical(directory) ?: return SsdBrowserFolderView("", emptyList(), emptyList()))
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val folderNames = sortedSetOf<String>()
        val files = ArrayList<SsdBrowserClip>()
        for (item in entries) {
            val path = canonical(item.path) ?: continue
            if (!path.startsWith(prefix)) continue
            val tail = path.removePrefix(prefix)
            if ('/' in tail) folderNames.add(tail.substringBefore('/'))
            else files.add(SsdBrowserClip(item.index, path))
        }
        return SsdBrowserFolderView(dir, folderNames.toList(), files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path }))
    }

    fun parent(directory: String): String = (canonical(directory) ?: "").substringBeforeLast('/', "")
    fun enter(directory: String, child: String): String? {
        val safe = canonical(child) ?: return null
        if ('/' in safe) return null
        return canonical(if (directory.isBlank()) safe else "$directory/$safe")
    }
}
