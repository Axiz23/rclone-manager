package app.rclonemgr

import android.net.Uri
import org.json.JSONObject

class AdapterException(message: String) : Exception(message)

class Endpoint(val method: String, val path: String, val query: Map<String, String>, val body: JSONObject?)

/**
 * Connection adapter: a JSON profile that tells the client HOW to talk to the API (URL prefix, auth scheme,
 * endpoint paths / methods / parameters, response field names, timeouts). Editing it in the app changes the
 * behaviour at runtime, so an API change does not need a new APK.
 * Placeholders: {remote} {path} {name} {dest} {destRemote} {isDir} {modtime} {id}
 */
class Adapter private constructor(val raw: JSONObject) {
    val name: String = raw.optString("name", "custom")
    val prefix: String = raw.optString("prefix", "api/v1").trim('/')
    private val auth = raw.optJSONObject("auth") ?: JSONObject()
    val authType: String = auth.optString("type", "bearer")          // bearer | header | query
    val authName: String = auth.optString("name", if (authType == "query") "token" else "Authorization")
    val authPrefix: String = auth.optString("prefix", "")
    private val timeouts = raw.optJSONObject("timeouts") ?: JSONObject()
    private val fields = raw.optJSONObject("fields") ?: JSONObject()
    private val eps = raw.getJSONObject("endpoints")
    val headers: Map<String, String> = raw.optJSONObject("headers")?.let { h ->
        h.keys().asSequence().associateWith { h.getString(it) }
    } ?: emptyMap()

    fun field(key: String, def: String): String = fields.optString(key, def)
    fun seconds(key: String, def: Int): Long = timeouts.optInt(key, def).coerceAtLeast(1).toLong()

    /** Header (name, value) to send with every request, or null when the token goes in the query string. */
    fun authHeader(token: String): Pair<String, String>? = when (authType) {
        "query" -> null
        "header" -> authName to (authPrefix + token)
        else -> "Authorization" to "Bearer $token"
    }

    fun endpoint(key: String): Endpoint {
        val e = eps.optJSONObject(key) ?: throw AdapterException("Adapter has no endpoint \"$key\"")
        val q = e.optJSONObject("query")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()
        return Endpoint(e.optString("method", "GET").uppercase(), e.getString("path").trimStart('/'), q, e.optJSONObject("body"))
    }

    companion object {
        private val REQUIRED = listOf("remotes", "settings", "list", "stream", "thumbnail", "mkdir", "rename", "delete", "copy", "move", "upload")
        private val VAR = Regex("\\{(\\w+)\\}")

        fun parse(text: String): Adapter {
            val o = try { JSONObject(text) } catch (e: Exception) { throw AdapterException("Invalid JSON: ${e.message}") }
            val eps = o.optJSONObject("endpoints") ?: throw AdapterException("Missing \"endpoints\"")
            for (k in REQUIRED) {
                val e = eps.optJSONObject(k) ?: throw AdapterException("Missing endpoint \"$k\"")
                if (!e.has("path")) throw AdapterException("Endpoint \"$k\" has no path")
            }
            return Adapter(o)
        }

        fun default(): Adapter = parse(DEFAULT_JSON)

        fun sub(s: String, v: Map<String, Any?>, encode: Boolean): String =
            VAR.replace(s) { m ->
                val x = v[m.groupValues[1]]?.toString() ?: ""
                if (encode) Uri.encode(x) else x
            }

        /** A value that is exactly "{var}" keeps the variable's type (boolean / number) inside JSON bodies. */
        private fun typed(s: String, v: Map<String, Any?>): Any {
            val m = Regex("^\\{(\\w+)\\}$").matchEntire(s)
            if (m != null) {
                val x = v[m.groupValues[1]]
                if (x is Boolean || x is Number) return x
            }
            return sub(s, v, false)
        }

        fun fill(o: JSONObject, v: Map<String, Any?>): JSONObject {
            val out = JSONObject()
            for (k in o.keys()) {
                val x = o.get(k)
                out.put(k, when (x) {
                    is String -> typed(x, v)
                    is JSONObject -> fill(x, v)
                    else -> x
                })
            }
            return out
        }

        const val DEFAULT_JSON = """{
  "name": "rcapi-v1",
  "prefix": "api/v1",
  "auth": { "type": "bearer" },
  "headers": {},
  "timeouts": { "connect": 15, "read": 60, "write": 120, "upload": 900, "thumbnail": 120 },
  "fields": {
    "remotesList": "remotes",
    "settingsRemotes": "remotes",
    "settingsMaxUpload": "maxUploadBytes",
    "listItems": "items",
    "name": "name",
    "size": "size",
    "isDir": "isDir",
    "modTime": "modTime",
    "timeFormat": "iso"
  },
  "endpoints": {
    "remotes":   { "path": "remotes" },
    "settings":  { "path": "server/settings" },
    "list":      { "path": "files", "query": { "remote": "{remote}", "path": "{path}" } },
    "stream":    { "path": "stream", "query": { "remote": "{remote}", "path": "{path}" } },
    "thumbnail": { "path": "thumbnails/{id}" },
    "mkdir":     { "method": "POST", "path": "operations/mkdir",  "body": { "remote": "{remote}", "path": "{path}" } },
    "rename":    { "method": "POST", "path": "operations/rename", "body": { "remote": "{remote}", "path": "{path}", "name": "{name}" } },
    "delete":    { "method": "POST", "path": "operations/delete", "body": { "remote": "{remote}", "path": "{path}", "isDir": "{isDir}" } },
    "copy":      { "method": "POST", "path": "operations/copy",   "body": { "remote": "{remote}", "path": "{path}", "destRemote": "{destRemote}", "dest": "{dest}" } },
    "move":      { "method": "POST", "path": "operations/move",   "body": { "remote": "{remote}", "path": "{path}", "destRemote": "{destRemote}", "dest": "{dest}" } },
    "upload":    { "method": "PUT",  "path": "operations/upload", "query": { "remote": "{remote}", "path": "{path}", "modtime": "{modtime}" } }
  }
}"""
    }
}
