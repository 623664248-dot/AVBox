package com.github.avbox.osc.music

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of py music spider sources for the music tab. Since v1.15.0
 * only user-imported spiders are registered: the six spiders that used
 * to ship in assets/py_sources/ are no longer released or offered (the
 * asset files stay in the APK, unreferenced). Imports mirror the LX
 * user-script storage: files are copied into filesDir/py_music_sources/
 * and their metadata (id, display name, file name) is kept as a JSON
 * array in KV under [KV_USER_PY_SOURCES]. Running the spiders is
 * [PyMusicBridge]'s job; this object only owns defs and files.
 */
object PyMusicSources {

    class PySourceException(message: String) : Exception(message)

    data class PyDef(
        /** Stable slug; MusicTrack.source is "py:" + id. */
        val id: String,
        val name: String,
        val user: Boolean,
        /** Legacy asset path of the removed built-ins; always null now. */
        val assetPath: String?,
        /** Absolute file path of the imported spider. */
        val filePath: String?,
    )

    private const val KV_USER_PY_SOURCES = "py_music_sources"
    private const val USER_DIR_NAME = "py_music_sources"
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

    /** All selectable py sources: the user imports, in import order. */
    fun loadDefs(context: Context): List<PyDef> {
        val dir = userDir(context)
        return readMetas().mapNotNull { meta ->
            val file = File(dir, meta.fileName)
            if (!file.isFile) {
                AppLog.i("PyMusicSources missing file for " + meta.id)
                null
            } else {
                PyDef(
                    id = meta.id,
                    name = meta.name,
                    user = true,
                    assetPath = null,
                    filePath = file.absolutePath,
                )
            }
        }
    }

    fun findDef(context: Context, id: String): PyDef? =
        loadDefs(context).firstOrNull { it.id == id }

    /**
     * The on-disk .py file for [def]; every registered def is a user
     * import with a real file path. (The [context] parameter is kept
     * for the existing call sites in PyMusicBridge.)
     */
    fun resolveFile(context: Context, def: PyDef): File {
        return File(requireNotNull(def.filePath) { "py def without file: " + def.id })
    }

    /**
     * Reads the picked document, sanity-checks it as a base.spider music
     * spider, copies it into private storage and registers it.
     * @throws PySourceException when the content is not a usable spider.
     */
    fun importScript(context: Context, uri: Uri): PyDef {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(MAX_SCRIPT_BYTES + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                if (total > MAX_SCRIPT_BYTES) {
                    throw PySourceException("脚本文件过大")
                }
                buffer.copyOf(total)
            } ?: throw PySourceException("无法读取所选文件")
        } catch (e: PySourceException) {
            throw e
        } catch (e: Exception) {
            throw PySourceException("无法读取所选文件：" + (e.message ?: ""))
        }
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        validate(text)
        val name = displayName(context, uri)
        val id = "pyuser_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        val fileName = id + ".py"
        val dir = userDir(context)
        try {
            File(dir, fileName).writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
            throw PySourceException("保存脚本失败：" + (e.message ?: ""))
        }
        val metas = readMetas().toMutableList()
        metas.add(Meta(id = id, name = name, fileName = fileName))
        writeMetas(metas)
        return PyDef(
            id = id,
            name = name,
            user = true,
            assetPath = null,
            filePath = File(dir, fileName).absolutePath,
        )
    }

    /** Removes a user-imported spider file and its metadata entry. */
    fun delete(context: Context, id: String) {
        val metas = readMetas()
        val meta = metas.firstOrNull { it.id == id } ?: return
        try {
            File(userDir(context), meta.fileName).delete()
        } catch (e: Exception) {
            AppLog.e("PyMusicSources", e)
        }
        writeMetas(metas.filterNot { it.id == id })
    }

    // ------------------------------------------------------------- internals

    private data class Meta(
        val id: String,
        val name: String,
        val fileName: String,
    )

    /**
     * Loose sanity check: the file must be non-blank and look like a
     * base.spider spider with content entry points. Real conformance is
     * proven when the bridge loads it; failures there surface with a
     * toast and fall back to the previous source.
     */
    private fun validate(text: String) {
        if (text.isBlank()) throw PySourceException("脚本内容为空")
        if (!text.contains("Spider")) {
            throw PySourceException("未检测到爬虫特征")
        }
        if (!text.contains("homeContent") && !text.contains("categoryContent")) {
            throw PySourceException("未检测到爬虫分类入口")
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        } catch (e: Exception) {
            AppLog.e("PyMusicSources", e)
        } finally {
            cursor?.close()
        }
        val cleaned = name?.substringBeforeLast('.')?.trim().orEmpty()
        return cleaned.ifEmpty { "自定义 py 音源" }
    }

    private fun userDir(context: Context): File {
        val dir = File(context.filesDir, USER_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun readMetas(): List<Meta> {
        // Must pass a String default: KV returns null for keys that are not
        // registered in the KVKeySpec table when no default is given (the
        // v1.9.0 music-import bug). "" means "no py sources registered".
        val raw: String = KV.get(KV_USER_PY_SOURCES, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<Meta>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val fileName = obj.optString("fileName")
                if (id.isEmpty() || fileName.isEmpty()) continue
                list.add(
                    Meta(
                        id = id,
                        name = obj.optString("name").ifEmpty { id },
                        fileName = fileName,
                    ),
                )
            }
            list
        } catch (e: Exception) {
            AppLog.e("PyMusicSources", e)
            emptyList()
        }
    }

    private fun writeMetas(metas: List<Meta>) {
        val array = JSONArray()
        for (meta in metas) {
            val obj = JSONObject()
            obj.put("id", meta.id)
            obj.put("name", meta.name)
            obj.put("fileName", meta.fileName)
            array.put(obj)
        }
        KV.put(KV_USER_PY_SOURCES, array.toString())
    }
}
