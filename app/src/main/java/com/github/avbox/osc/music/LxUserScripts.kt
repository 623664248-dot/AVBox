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
 * Storage for user-imported LX source scripts (the LX Music "custom source"
 * equivalent): each import copies the .js file into the app's private
 * files/lx_user_sources/ directory and registers its metadata (id, display
 * name, version, file name) as a JSON array in KV under [KV_USER_SCRIPTS].
 * Loading/running a script is LxBridge's job; this object only owns the
 * files and the metadata list.
 */
object LxUserScripts {

    private const val KV_USER_SCRIPTS = "lx_user_scripts"
    private const val DIR_NAME = "lx_user_sources"
    private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024

    private data class Meta(
        val id: String,
        val name: String,
        val version: String,
        val fileName: String,
    )

    /** ScriptDefs for every registered user script whose file still exists. */
    fun loadDefs(context: Context): List<LxBridge.ScriptDef> {
        val dir = dir(context)
        return readMetas().mapNotNull { meta ->
            val file = File(dir, meta.fileName)
            if (!file.isFile) {
                AppLog.i("LxUserScripts missing file for " + meta.id)
                null
            } else {
                LxBridge.ScriptDef(
                    id = meta.id,
                    assetPath = null,
                    fallbackName = meta.name,
                    filePath = file.absolutePath,
                    version = meta.version,
                )
            }
        }
    }

    /**
     * Reads the picked document, validates it as an LX source script,
     * copies it into private storage and registers it.
     * @throws LxBridge.LxException when the content is not a usable script.
     */
    fun importScript(context: Context, uri: Uri): LxBridge.ScriptDef {
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
                    throw LxBridge.LxException("脚本文件过大")
                }
                buffer.copyOf(total)
            } ?: throw LxBridge.LxException("无法读取所选文件")
        } catch (e: LxBridge.LxException) {
            throw e
        } catch (e: Exception) {
            throw LxBridge.LxException("无法读取所选文件：" + (e.message ?: ""))
        }
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        validate(text)
        val info = LxBridge.parseScriptInfoOf(text, displayName(context, uri))
        val id = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        val fileName = id + ".js"
        val dir = dir(context)
        try {
            File(dir, fileName).writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
            throw LxBridge.LxException("保存脚本失败：" + (e.message ?: ""))
        }
        val metas = readMetas().toMutableList()
        metas.add(Meta(id = id, name = info.name, version = info.version, fileName = fileName))
        writeMetas(metas)
        return LxBridge.ScriptDef(
            id = id,
            assetPath = null,
            fallbackName = info.name,
            filePath = File(dir, fileName).absolutePath,
            version = info.version,
        )
    }

    /** Removes the script file and its metadata entry. Unknown ids are ignored. */
    fun delete(context: Context, id: String) {
        val metas = readMetas()
        val meta = metas.firstOrNull { it.id == id } ?: return
        try {
            File(dir(context), meta.fileName).delete()
        } catch (e: Exception) {
            AppLog.e("LxUserScripts", e)
        }
        writeMetas(metas.filterNot { it.id == id })
    }

    // ------------------------------------------------------------- internals

    /**
     * Deliberately loose sanity check: the file must be non-blank and carry
     * some sign of registering with the LX host API. Obfuscated scripts may
     * hide these tokens; those simply fail later at init with a visible
     * error instead of being rejected here.
     */
    private fun validate(text: String) {
        if (text.isBlank()) throw LxBridge.LxException("脚本内容为空")
        val signs = listOf("globalThis.lx", "EVENT_NAMES", "lx.on(", "lx.send(", "window.lx")
        if (signs.none { text.contains(it) }) {
            throw LxBridge.LxException("未检测到洛雪音源脚本特征")
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
            AppLog.e("LxUserScripts", e)
        } finally {
            cursor?.close()
        }
        val cleaned = name?.substringBeforeLast('.')?.trim().orEmpty()
        return cleaned.ifEmpty { "自定义音源" }
    }

    private fun dir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun readMetas(): List<Meta> {
        // Must pass a String default: KV returns null for keys that are not
        // registered in the KVKeySpec table when no default is given, which
        // made imported scripts invisible. "" means "no scripts registered".
        val raw: String = KV.get(KV_USER_SCRIPTS, "")
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
                        version = obj.optString("version"),
                        fileName = fileName,
                    ),
                )
            }
            list
        } catch (e: Exception) {
            AppLog.e("LxUserScripts", e)
            emptyList()
        }
    }

    private fun writeMetas(metas: List<Meta>) {
        val array = JSONArray()
        for (meta in metas) {
            val obj = JSONObject()
            obj.put("id", meta.id)
            obj.put("name", meta.name)
            obj.put("version", meta.version)
            obj.put("fileName", meta.fileName)
            array.put(obj)
        }
        KV.put(KV_USER_SCRIPTS, array.toString())
    }
}
