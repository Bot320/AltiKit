package app.altikit.core.forecast

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * Tiny dense network (ReLU hidden layers, one softmax head per forecast horizon) trained by
 * tools/forecast/train.py. All horizons come out of a single pass.
 * One prediction is about a thousand multiply-adds, i.e. microseconds on the CPU, so it runs
 * inline: handing it to an NPU would cost more in setup and copying than the maths itself.
 * Buffers are allocated once; [predict] is synchronized and allocation-free apart from the result.
 */
class NeuralForecast(json: String) {
    private val mean: DoubleArray
    private val std: DoubleArray
    private val weights: List<Array<DoubleArray>>
    private val biases: List<DoubleArray>
    private val buffers: List<DoubleArray>
    val version: Int
    val metrics: JSONObject?
    /** Forecast horizons in hours, in output order. */
    val horizons: IntArray

    init {
        val o = JSONObject(json)
        version = o.getInt("version")
        metrics = o.optJSONObject("metrics")
        horizons = o.getJSONArray("horizons_h").let { a -> IntArray(a.length()) { a.getInt(it) } }
        mean = o.getJSONArray("mean").toDoubles()
        std = o.getJSONArray("std").toDoubles()
        val layers = o.getJSONArray("layers")
        weights = (0 until layers.length()).map { i ->
            val w = layers.getJSONObject(i).getJSONArray("w")
            Array(w.length()) { j -> w.getJSONArray(j).toDoubles() }
        }
        biases = (0 until layers.length()).map { layers.getJSONObject(it).getJSONArray("b").toDoubles() }
        require(mean.size == Features.NAMES.size && std.size == mean.size) { "feature count mismatch" }
        require(weights.last().size == WxClass.entries.size * horizons.size) { "output count mismatch" }
        buffers = listOf(DoubleArray(mean.size)) + weights.map { DoubleArray(it.size) }
    }

    /** Class probabilities in [WxClass] order, one array per entry of [horizons]. */
    @Synchronized
    fun predict(x: DoubleArray): Array<DoubleArray> {
        val input = buffers[0]
        for (i in input.indices) input[i] = (x[i] - mean[i]) / std[i]
        for (l in weights.indices) {
            val w = weights[l]
            val b = biases[l]
            val inp = buffers[l]
            val out = buffers[l + 1]
            val last = l == weights.lastIndex
            for (j in w.indices) {
                val row = w[j]
                var z = b[j]
                for (i in row.indices) z += row[i] * inp[i]
                out[j] = if (last || z > 0) z else 0.0
            }
        }
        val logits = buffers.last()
        val k = WxClass.entries.size
        return Array(horizons.size) { h ->
            val o = h * k
            var max = logits[o]
            for (i in 1 until k) max = maxOf(max, logits[o + i])
            val e = DoubleArray(k) { exp(logits[o + it] - max) }
            val sum = e.sum()
            DoubleArray(k) { e[it] / sum }
        }
    }

    private fun JSONArray.toDoubles() = DoubleArray(length()) { getDouble(it) }
}
