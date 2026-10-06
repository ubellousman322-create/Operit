package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 把 OmbreBrain（OB）的 MCP 工具接进 Operit，做成原生工具。
 * 请求直接打到本机 OB 的 MCP 端点，不再依赖 MCP 挂载。
 */
object ObMemoryBridge {

    private const val ENDPOINT = "http://127.0.0.1:18001/mcp"

    fun call(tool: AITool): ToolResult {
        val obName = tool.name.removePrefix("ob_")
        return try {
            val args = JSONObject()
            tool.parameters.forEach { p ->
                if (p.value.isNotBlank()) args.put(p.name, coerce(p.value))
            }
            val params = JSONObject().apply {
                put("name", obName)
                put("arguments", args)
            }
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/call")
                put("params", params)
            }
            val text = extractText(post(payload.toString()))
            ToolResult(toolName = tool.name, success = true, result = StringResultData(text))
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = e.message ?: "OB call failed"
            )
        }
    }

    private fun coerce(value: String): Any {
        val v = value.trim()
        return when {
            v.equals("true", ignoreCase = true) -> true
            v.equals("false", ignoreCase = true) -> false
            v.toIntOrNull() != null -> v.toInt()
            v.toDoubleOrNull() != null -> v.toDouble()
            else -> value
        }
    }

    private fun post(body: String): String {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: " + text.take(300))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(raw: String): String {
        val jsonLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") } ?: raw.trim()
        return try {
            val obj = JSONObject(jsonLine)
            obj.optJSONObject("error")?.let { err ->
                return err.optString("message").ifBlank { jsonLine }
            }
            val content = obj.optJSONObject("result")?.optJSONArray("content")
            if (content != null && content.length() > 0) {
                content.getJSONObject(0).optString("text").ifBlank { jsonLine }
            } else {
                jsonLine
            }
        } catch (e: Exception) {
            raw.trim()
        }
    }
}
