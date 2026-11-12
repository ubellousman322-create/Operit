package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具 Schema 兼容层。
 *
 * 各 provider 的 buildSchemaFromStructured 只写 type / description / default 三样，于是：
 *  - `type=array` 的参数没有 `items`，Gemini 的 function declaration 校验直接 400
 *  - `default` 在 ToolParameterSchema 里是 String，原样塞进 integer / number / boolean
 *    的参数时类型对不上，同样会被拒绝
 *
 * 这里在 schema 生成之后统一洗一遍，不动上游那几十份工具定义。
 */
internal object ToolSchemaCompat {

    private val UNSUPPORTED_KEYS = listOf("additionalProperties", "\$schema", "\$ref", "definitions")

    /** 就地清洗并返回同一个对象，方便 `put("parameters", ToolSchemaCompat.sanitize(schema))`。 */
    fun sanitize(schema: JSONObject): JSONObject {
        sanitizeNode(schema)
        return schema
    }

    private fun sanitizeNode(node: JSONObject) {
        UNSUPPORTED_KEYS.forEach { key -> node.remove(key) }

        val declared = node.optString("type", "").trim().lowercase()
        val type = normalizeType(declared)
        if (declared.isNotEmpty()) {
            node.put("type", type)
        }

        if (node.has("default")) {
            val coerced = coerceDefault(type, node.opt("default"))
            if (coerced == null) {
                node.remove("default")
            } else {
                node.put("default", coerced)
            }
        }

        // 数组必须声明 items，缺了 Gemini 会拒绝整份请求。工具参数模型里没有元素类型信息，
        // 给个空 schema（不限类型）兜底。
        if (type == "array" && !node.has("items")) {
            node.put("items", JSONObject())
        }

        node.optJSONObject("items")?.let { child -> sanitizeNode(child) }

        val properties = node.optJSONObject("properties")
        if (properties != null) {
            properties.keys().forEach { key ->
                properties.optJSONObject(key)?.let { child -> sanitizeNode(child) }
            }
        }

        // required 里引用了不存在的属性，部分校验器也会直接拒。
        val required = node.optJSONArray("required")
        if (required != null) {
            val kept = JSONArray()
            for (index in 0 until required.length()) {
                val name = required.optString(index, "")
                if (name.isNotEmpty() && (properties == null || properties.has(name))) {
                    kept.put(name)
                }
            }
            node.put("required", kept)
        }
    }

    private fun normalizeType(raw: String): String =
        when (raw) {
            "int", "long", "short" -> "integer"
            "float", "double" -> "number"
            "bool" -> "boolean"
            "list" -> "array"
            "string", "number", "integer", "boolean", "array", "object" -> raw
            else -> "string"
        }

    /** default 必须符合 type，否则整份 schema 都不合法。转不过去就不要这个 default。 */
    private fun coerceDefault(type: String, value: Any?): Any? {
        if (value == null || value === JSONObject.NULL) return null
        return when (type) {
            "integer" ->
                when (value) {
                    is Int -> value
                    is Long -> value
                    is Number -> value.toInt()
                    is String -> value.trim().toIntOrNull()
                    else -> null
                }
            "number" ->
                when (value) {
                    is Number -> value.toDouble()
                    is String -> value.trim().toDoubleOrNull()
                    else -> null
                }
            "boolean" ->
                when (value) {
                    is Boolean -> value
                    is String ->
                        when (value.trim().lowercase()) {
                            "true" -> true
                            "false" -> false
                            else -> null
                        }
                    else -> null
                }
            "array", "object" -> null
            else -> if (value is String) value else value.toString()
        }
    }
}