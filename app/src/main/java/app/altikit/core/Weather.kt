package app.altikit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Sea-level pressure from Open-Meteo (free, no API key). */
object Weather {
    suspend fun fetchQnh(lat: Double, lon: Double): Double = withContext(Dispatchers.IO) {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=pressure_msl",
                lat, lon,
            )
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).getJSONObject("current").getDouble("pressure_msl")
        } finally {
            c.disconnect()
        }
    }
}
