package gr.alexandros.radar

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    private const val BASE = "https://aatahzoqkxmymlzufhti.supabase.co"
    private const val KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFhdGFoem9xa3hteW1senVmaHRpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQwNjE2NTUsImV4cCI6MjEwOTYzNzY1NX0.AybrDnmofK1t03-42uF_sWZcjOdwceglLn_7O8Pwn_I"

    /** Calls a Supabase RPC function. Must NOT run on the main thread. */
    fun rpc(fn: String, body: JSONObject): String {
        val c = URL("$BASE/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer $KEY")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(txt).optString("message", txt) } catch (e: Exception) { txt }
                throw ApiException(msg)
            }
            return txt
        } finally {
            c.disconnect()
        }
    }
}

class ApiException(msg: String) : Exception(msg)

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("radar", Context.MODE_PRIVATE)
    fun token(c: Context): String? = p(c).getString("token", null)
    fun name(c: Context): String = p(c).getString("name", "") ?: ""
    fun lost(c: Context): Boolean = p(c).getBoolean("lost", false)
    fun setLost(c: Context, v: Boolean) = p(c).edit().putBoolean("lost", v).apply()
    fun save(c: Context, token: String, name: String) =
        p(c).edit().putString("token", token).putString("name", name).apply()
    fun clear(c: Context) = p(c).edit().clear().apply()
}
