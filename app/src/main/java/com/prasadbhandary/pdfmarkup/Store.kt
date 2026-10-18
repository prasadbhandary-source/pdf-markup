package com.prasadbhandary.pdfmarkup

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Recent(val uri: String, val name: String, val page: Int, val time: Long)

/** Recently opened files (only ones the app can reopen later). */
class Recents(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(): List<Recent> = try {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Recent(o.getString("u"), o.getString("n"), o.optInt("p", 0), o.optLong("t", 0))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(items: List<Recent>) {
        val arr = JSONArray()
        items.take(25).forEach {
            arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("p", it.page).put("t", it.time))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun add(uri: Uri, name: String) {
        val old = list()
        val page = old.firstOrNull { it.uri == uri.toString() }?.page ?: 0
        save(listOf(Recent(uri.toString(), name, page, System.currentTimeMillis())) +
            old.filter { it.uri != uri.toString() })
    }

    fun pageOf(uri: Uri): Int = list().firstOrNull { it.uri == uri.toString() }?.page ?: 0

    fun setPage(uri: Uri, page: Int) {
        val items = list()
        if (items.none { it.uri == uri.toString() }) return
        save(items.map { if (it.uri == uri.toString()) it.copy(page = page) else it })
    }

    fun remove(uri: String) = save(list().filter { it.uri != uri })
}

/** Unsaved annotations, kept so nothing is lost if Android closes the app. */
class Drafts(context: Context) {
    private val dir = File(context.filesDir, "drafts").apply { mkdirs() }

    private fun file(uri: Uri): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    fun save(uri: Uri, marks: List<Mark>) {
        val arr = JSONArray()
        marks.forEach { arr.put(it.toJson()) }
        try { file(uri).writeText(arr.toString()) } catch (_: Exception) {}
    }

    fun load(uri: Uri, pageCount: Int): List<Mark>? {
        val f = file(uri)
        if (!f.exists()) return null
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                markFromJson(o, o.optInt("p", 0))
            }.filter { it.page < pageCount }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(uri: Uri) {
        file(uri).delete()
    }
}
