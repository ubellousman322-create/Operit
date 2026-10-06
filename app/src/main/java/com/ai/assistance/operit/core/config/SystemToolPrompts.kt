package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolParameterSchema

/**
 * 系统工具提示词管理器
 * 包含所有工具的结构化定义
 */
object SystemToolPrompts {

    private fun buildSafBookmarksSectionEn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString(", ") { "repo:$it" }
        return """

**Attached Local Storage Repository:**
- environment (optional): you can also use `environment="repo:<repositoryName>"` to operate in an attached local storage repository.
- Paths are absolute (e.g., `/`, `/work/index.html`).
- Available repositories: $listed
""".trimEnd()
    }

    private fun buildSafBookmarksSectionCn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString("、") { "repo:$it" }
        return """

**附加本地储存仓库：**
- environment（可选）：也可以使用 `environment="repo:<仓库名>"` 在附加本地储存仓库中操作。
- 路径使用绝对路径（例如 `/`、`/work/index.html`）。
- 当前可用仓库：$listed
""".trimEnd()
    }
    
    // ==================== 基础工具 ====================
    val basicTools = SystemToolPromptCategory(
        categoryName = "Available tools",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "Demonstration tool that pauses briefly.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "milliseconds, default 1000, >= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "Activate a package for use in the current session.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "package_name",
                        type = "string",
                        description = "name of the package to activate",
                        required = true
                    )
                )
            )
        )
    )
    
    val basicToolsCn = SystemToolPromptCategory(
        categoryName = "可用工具",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "演示工具，短暂暂停。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "毫秒，默认1000，>= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "在当前会话中激活包。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "package_name", type = "string", description = "要激活的包名", required = true)
                )
            )
        )
    )
    
    // ==================== 文件系统工具 ====================
    val fileSystemTools = SystemToolPromptCategory(
        categoryName = "File System Tools",
        tools = listOf(
            ToolPrompt(
                name = "list_files",
                description = "List files in a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "e.g. \"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "Read the content of a file. For image files (jpg, jpeg, png, gif, bmp), it automatically extracts text using OCR.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "path",
                        type = "string",
                        description = "file path",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "optional, execution environment. Values: \"android\" (default, Android file system) | \"linux\" (local Ubuntu 24 terminal environment via proot; Linux paths like /home/... /etc/hosts) | \"repo:<repositoryName>\" (attached local storage repository)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "optional, your question about the media/file (used for backend recognition)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_image",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"image\"> tag for models that support vision",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"audio\"> tag for models that support audio",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"video\"> tag for models that support video",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "Read file content by line range.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "starting line number, 1-indexed", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "ending line number, 1-indexed, inclusive, optional", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "create_file",
                description = "Create a new file by delegating to apply_file with type=create.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "full file content for the new file", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "edit_file",
                description = "Edit an existing file by delegating to apply_file with type=replace.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "the exact content to be matched and replaced", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "the new content to insert", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "delete_file",
                description = "Delete a file or directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "target path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "Create a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "Search for files matching a pattern.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path, for Android use /sdcard/..., for Linux use /home/... or /etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "search pattern, e.g. \"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "optional, controls depth of subdirectory search, -1=unlimited", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "Search code content matching a regex pattern in files. Returns matches with surrounding context lines.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "regex pattern", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "lines of context before/after match", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "max matches", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "grep_context",
                description = "Search for relevant content based on intent/context understanding. Supports two modes: 1) Directory mode: when path is a directory, finds most relevant files. 2) File mode: when path is a file, finds most relevant code segments within that file. Uses semantic relevance scoring.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory or file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "intent or context description string", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter for directory mode", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "maximum items to return", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "Download a file from the internet. Two modes: (1) Provide `url` + `destination`. (2) Provide `visit_key` + (`link_number` or `image_number`) + `destination` to download an item by index from a previous `visit_web` result.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, file URL. If omitted, use visit_key + link_number/image_number to download from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, 1-based link index from Results (use with visit_key)", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "optional, 1-based image index from Images (use with visit_key)", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "save path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    val fileSystemToolsCn = SystemToolPromptCategory(
        categoryName = "文件系统工具",
        tools = listOf(
            ToolPrompt(
                name = "list_files",
                description = "列出目录中的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "例如\"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "读取文件内容。对于图片文件(jpg, jpeg, png, gif, bmp)，自动使用OCR提取文本。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "可选，执行环境。取值：\"android\"（默认，Android文件系统）| \"linux\"（本地Ubuntu 24终端环境，通过proot实现；路径用Linux格式，如/home/...、/etc/hosts）| \"repo:<仓库名>\"（附加本地储存仓库）",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "可选，用户对媒体/文件的问题（用于后端识别模型）",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_image",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"image\">标签供支持识图的模型直接查看",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"audio\">标签供支持音频的模型直接处理",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"video\">标签供支持视频的模型直接处理",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "按行号范围读取文件内容。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "起始行号，从1开始", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "结束行号，从1开始，包括该行，可选", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "create_file",
                description = "通过委托给 apply_file 且 type=create 来创建新文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "新文件的完整内容", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "edit_file",
                description = "通过委托给 apply_file 且 type=replace 来编辑已存在文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "用于匹配并替换的原始内容", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "要插入的新内容", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "delete_file",
                description = "删除文件或目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目标路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "创建目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "搜索匹配模式的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径，Android用/sdcard/...，Linux用/home/...或/etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "搜索模式，例如\"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "可选，控制子目录搜索深度，-1=无限", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "在文件中搜索匹配正则表达式的代码内容，返回带上下文的匹配结果。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "正则表达式模式", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "匹配行前后的上下文行数", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "最大匹配数", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "grep_context",
                description = "基于意图/上下文理解搜索相关内容。支持两种模式：1) 目录模式：当path是目录时，找出最相关的文件。2) 文件模式：当path是文件时，找出该文件内最相关的代码段。使用语义相关性评分。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录或文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "意图或上下文描述字符串", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "目录模式下的文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "返回的最大项数", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "从互联网下载文件。有两种用法：1）提供 `url` + `destination` 直接下载。2）提供 `visit_key` +（`link_number` 或 `image_number`）+ `destination`，从上一次 `visit_web` 的 Results/Images 编号中按序号下载。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选, 文件URL。不传时可使用 visit_key + link_number/image_number 从上一次 visit_web 结果按编号下载", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选, 上一次 visit_web 返回的 visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选, 整数, Results 中的链接编号（从1开始，需要配合 visit_key）", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "可选, 整数, Images 中的图片编号（从1开始，需要配合 visit_key）", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "保存路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    // ==================== HTTP工具 ====================
    val httpTools = SystemToolPromptCategory(
        categoryName = "HTTP Tools",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "Visit a webpage and extract information (including optional image links). Two modes: (1) Provide `url` to visit a new page. (2) Follow a link from a previous visit by providing `visit_key` + `link_number`. The returned text often includes a `Results:` section like `[1] ...`, `[2] ...` — those bracketed numbers are 1-based indices. Use that exact number as `link_number` (range: 1..links.length). If you need images, set `include_image_links=true` and the tool will return an `Images:` section with 1-based indices. IMPORTANT: do NOT use `link_number` to download images; instead use `download_file` with `visit_key` + `image_number`. IMPORTANT: this tool is for webpage browsing/extraction, not a replacement for raw HTTP GET/POST requests; if you use it where you actually need API responses or precise response bodies, it may return empty or incomplete content. NOTE: this tool is browsing-only/read-only and does not perform interactive actions such as login, click, fill, submit, or workflow automation.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, webpage URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, string, the visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, int, 1-based index of the link to follow (matches the `[n]` in Results; range 1..links.length)", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "optional, boolean, when true include extracted image links in the result (imageLinks)", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "optional, quick select user agent: desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "optional, full custom user agent override", required = false)
                )
            )
        )
    )
    
    val httpToolsCn = SystemToolPromptCategory(
        categoryName = "HTTP工具",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "访问网页并提取信息（可选包含图片链接）。有两种用法：1）提供 `url` 访问新页面。2）提供上一次 visit_web 返回的 `visit_key` + `link_number`，用来继续访问结果里的某个链接。返回文本通常会包含 `Results:` 段落，形如 `[1] ...`、`[2] ...` —— 中括号里的数字是从 1 开始的编号，请把该编号原样作为 `link_number`（范围：1..links.length），不要按 0 起始。若需要图片，请设置 `include_image_links=true`，工具会额外返回 `Images:` 段落以及从 1 开始的图片编号。重要：下载图片不要用 `link_number` 乱点页面链接；请使用 `download_file` 的 `visit_key` + `image_number` 按图片编号下载。重要：这个工具用于网页浏览/提取，不能替代原始 HTTP GET/POST 请求；如果你实际需要的是接口返回体或精确响应内容，用它时可能会得到空结果或不完整内容。注意：该工具仅支持浏览/读取操作，不执行登录、点击、填写、提交等交互自动化。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选, 网页URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选, 字符串, 上一次 visit_web 返回的 visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选, 整数, 要继续访问的链接编号（从1开始，对应 Results 里的 `[n]`；范围 1..links.length）", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "可选, boolean, 为 true 时在结果中额外包含提取到的图片链接列表（imageLinks）", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "可选：UA预设，快速选择：desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "可选：完整自定义UA（优先级高于预设）", required = false)
                )
            )
        )
    )
    
    // ==================== 记忆库工具 ====================
    val memoryTools = SystemToolPromptCategory(
        categoryName = "Memory and Memory Library Tools",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "Searches the memory library for relevant memories and document chunks.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, the search query. You can pass a natural-language question, a space-separated phrase, or use `|` to separate multiple keywords, for example `network error timeout` or `network|error|timeout`. Inside a keyword, `*` acts as a fuzzy wildcard placeholder, for example `error*timeout`; use only `*` to return all memories", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "optional, string, the specific folder path to search within", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "optional, local-time string in `YYYY-MM-DD` or `YYYY-MM-DD HH:mm` format. Filters memories by createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "optional, local-time string in `YYYY-MM-DD` or `YYYY-MM-DD HH:mm` format. Filters memories by createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "snapshot_id", type = "string", description = "optional, string. Omit or pass empty to create a new snapshot automatically. If you pass a non-empty snapshot_id, that exact id will be used; if it does not exist yet, it will be created and can be reused across follow-up or parallel queries to exclude memories already returned by that snapshot", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "optional, number >= 0. Minimum relevance score required for a memory to be returned. Defaults to 0 for query_memory", required = false, default = "0"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of results to return. When > 20, only titles and truncated content are returned", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "Retrieves a memory by exact title, including document content or selected chunks.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "required, string, the exact title of the memory", required = true),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "optional, int, read a specific chunk by its number, e.g., 3 for the 3rd chunk", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "optional, string, read a range of chunks in \"start-end\" format, e.g., \"3-7\" for chunks 3 through 7", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "optional, string, search inside the document by natural-language question or keywords. You can pass a short question, a space-separated phrase, or use `|` to separate multiple keywords, for example `error log timeout` or `error|timeout|retry`. Inside a keyword, `*` acts as a fuzzy wildcard placeholder, for example `error*timeout`", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of document chunks to return when using query. Default 20", required = false, default = "20")
                )
            )
        ),
        categoryFooter = "\nNote: The memory library and user personality profile may be updated automatically after the current reply is finalized. If you need to manage memories immediately or update user preferences, use the appropriate tools directly."
    )
    
    val memoryToolsCn = SystemToolPromptCategory(
        categoryName = "记忆与记忆库工具",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "从记忆库中搜索相关记忆和文档分块。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, 搜索查询。可以传自然语言问题、空格分隔的短语，或使用 `|` 分隔多个关键词，例如 `network error timeout` 或 `network|error|timeout`。在单个关键词内部，`*` 可作为模糊通配占位符，例如 `error*timeout`；仅传 `*` 时返回所有记忆", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "可选, string, 要搜索的特定文件夹路径", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "可选, 本地时间字符串，格式支持 `YYYY-MM-DD` 或 `YYYY-MM-DD HH:mm`。按创建时间过滤 createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "可选, 本地时间字符串，格式支持 `YYYY-MM-DD` 或 `YYYY-MM-DD HH:mm`。按创建时间过滤 createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "snapshot_id", type = "string", description = "可选, 字符串。不传或传空时会自动创建新快照；传入任意非空 snapshot_id 时会直接使用这个 id，不存在则按该 id 创建。后续串行或并发查询复用同一个 snapshot_id 时，会排除该快照里已经返回过的记忆", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "可选, number >= 0。返回记忆所需的最小相关度分数。query_memory 默认值为 0", required = false, default = "0"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "可选, int >= 1, 返回结果的最大数量. 当 > 20 时，只返回标题和截断内容", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "通过精确标题检索记忆，可读取完整内容或文档分块。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "必需, 字符串, 记忆的精确标题", required = true),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "可选, 整数, 读取特定编号的分块, 例如3表示第3块", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "可选, 字符串, 读取分块范围，格式为\"起始-结束\"，例如\"3-7\"表示第3到第7块", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "可选, 字符串, 在文档内部搜索匹配分块。可以传自然语言问题、空格分隔的短语，或使用 `|` 分隔多个关键词，例如 `error log timeout` 或 `error|timeout|retry`。在单个关键词内部，`*` 可作为模糊通配占位符，例如 `error*timeout`", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "可选, int >= 1, 使用 query 时最多返回多少个文档分块，默认 20", required = false, default = "20")
                )
            )
        ),
        categoryFooter = "\n注意：记忆库和用户性格档案可能会在当前回复结束后由独立系统自动更新。如果需要立即管理记忆或更新用户偏好，请直接使用相应工具。"
    )

    private val internalToolCategoriesEn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesEn
    private val internalToolCategoriesCn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesCn
    
    /**
     * 获取所有英文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */

    // ==================== OB（OmbreBrain）原生记忆工具 ====================
    val obMemoryTools = SystemToolPromptCategory(
        categoryName = "OmbreBrain Memory Tools",
        tools = listOf(
            ToolPrompt(
                name = "ob_breath",
                description = "无参数,睁眼看看自己记得什么:返回权重最高、未解决且未标记 digested 的记忆 + 置顶核心准则。digested 从默认/被动浮现及 dream 隐藏，仍可由 breath_search(query=...) 显式找回。0 参数是刻意设计——claude.ai 按需加载工具时会跳过参数复杂的工具,拆成 0 参数才能保证每次对话自动浮现,不用手动触发。要按关键词找记忆用 breath_search(query=...);要用 catalog/tags/importance_min/valence/arousal/max_tokens 等高级模式用 breath_advanced(...)。"
            ),
            ToolPrompt(
                name = "ob_breath_search",
                description = "按关键词/语义检索记忆桶,融合关键词/BM25+语义检索,向量不可用时明确提示并退回关键词检索。命中后逐字返回桶内当前 content，不调用 LLM 摘要/改写。domain 逗号分隔,按主题域预筛。date_from/date_to 按桶的创建时间过滤，支持 YYYY-MM-DD 或 ISO 8601，同日上下界包含当天全日。max_results=返回条数上限(默认 config.surfacing.breath_max_results,fallback 20,最大 50)。需要 tags/importance_min/valence/arousal/max_tokens/catalog 等更多过滤维度用 breath_advanced(...)。quotes=True：如果你发现自己不只想知道当时发生了什么，还想知道当时到底是怎么说的，就要它——命中的桶里如果存过原话，会原样附在正文后面。默认不给，引语平时安静躺着，不占上下文也不打扰你。它给的是写入那一刻挑出来的那几句（每条记忆最多 3 句、每句 100 字），**不是原文**：OB 没有「返回全文」这个入口，没挑出来的话当时就没有留下。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "Query", required = true),
                    ToolParameterSchema(name = "domain", type = "string", description = "Domain", required = false),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "Max Results", required = false),
                    ToolParameterSchema(name = "date_from", type = "string", description = "Date From", required = false),
                    ToolParameterSchema(name = "date_to", type = "string", description = "Date To", required = false),
                    ToolParameterSchema(name = "quotes", type = "boolean", description = "Quotes", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_breath_advanced",
                description = "breath 的完整参数版,给需要精细控制的场景用(日常用 breath()/breath_search() 就够了)。不传 query=返回权重最高的未解决记忆;传 query=融合关键词/BM25+语义检索，向量不可用时明确提示并退回关键词检索。命中后逐字返回桶内当前 content，不调用 LLM 摘要/改写；max_tokens 不足时整桶省略，绝不截断正文。catalog=True=目录模式:只返回每桶一行元数据(名称|域|重要度,0 LLM 调用,最省 token),anchor 行带 ⚓ [anchor] 冷参考标记,适合开新对话先看目录再 breath_search(query=...) 精准拉取,并遵守 domain、tags 与 max_results。date_from/date_to 按桶的创建时间过滤，支持 YYYY-MM-DD 或 ISO 8601。max_tokens=单次返回总 token 上限(默认 config.surfacing.breath_max_tokens,fallback 10000)。domain 逗号分隔,valence/arousal 0~1(-1 忽略)。max_results=返回条数上限(默认 config.surfacing.breath_max_results,fallback 20,最大 50)。importance_min>=1=跳过语义检索,按重要度降序返回最多 20 条高重要度记忆。tags 逗号分隔,AND 过滤;tags=\"feel\" 或 \"__feel__\" 等价于 domain=\"feel\",返回所有 feel 类记忆。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "Query", required = false),
                    ToolParameterSchema(name = "max_tokens", type = "integer", description = "Max Tokens", required = false),
                    ToolParameterSchema(name = "domain", type = "string", description = "Domain", required = false),
                    ToolParameterSchema(name = "valence", type = "number", description = "Valence", required = false),
                    ToolParameterSchema(name = "arousal", type = "number", description = "Arousal", required = false),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "Max Results", required = false),
                    ToolParameterSchema(name = "importance_min", type = "integer", description = "Importance Min", required = false),
                    ToolParameterSchema(name = "tags", type = "string", description = "Tags", required = false),
                    ToolParameterSchema(name = "catalog", type = "boolean", description = "Catalog", required = false),
                    ToolParameterSchema(name = "date_from", type = "string", description = "Date From", required = false),
                    ToolParameterSchema(name = "date_to", type = "string", description = "Date To", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_hold",
                description = "仅在对话中已明确决定“这段内容值得成为长期记忆”时调用；不要因普通聊天、猜测或工具名称联想而自行调用。content 逐字保存，绝不压缩。正文里凡是**别人**说的话（不是用户、也不是我自己），写成单独一行 `@名字：原话`——这样的行以后会被单独拆成一条带 speaker 的 JSON 返回，不会被读成用户说过的话；混在叙述里写「某某说……」拆不出来。用户和我自己的话照常直接写，不要加 `@`。title 可选；传入时是最终显式标题，优先于打标模型建议。domain 可选、逗号分隔；显式传入时优先于打标模型结果。系统自动补其余元数据，API 不可用时使用本地中性值继续保存。tags 逗号分隔，importance 1-10。pinned=True 标记为永久核心；feel=True 存为感受类记忆且 domain 固定为 feel。source_bucket 是正在消化的原始记忆桶 ID。source_content/source_ranges 是可选原文证据：由调用方自行决定是否提供；原文进入与 grow 共用的不可变原文层，不参与普通 breath。省略 source_ranges 时整份 source_content 默认属于当前 hold 事件；显式 ranges 使用 1-based 闭区间。why_remembered 与 meaning 是可选的第一人称记录原因。media 可传服务器可读路径或 data_base64+filename 列表项。quotes 是这一刻我决定要原样记住的那一两句话——不是记录对话，是说出口的当下就知道自己不想忘的那几句。传字符串列表，或 [{\"text\":\"原话\",\"speaker\":\"谁说的\",\"at\":\"什么时候\"}]。最多 3 句、每句 100 字；超了会被拒绝而不是截断，因为截断过的话已经不是原话。**默认状态是一句都不放**：3 句是上限不是配额，绝大多数记忆不需要引语，拿不准就别放。不要为了保住上下文把一段话切成几句塞进来，也不要挑「有信息量」的句子——那是在用引语存原文，而原文层正是因为这个原因被删掉的。只放那种漏掉会真的可惜的原话。它平时不出现在任何浮现里，只有以后我自己想知道当时到底怎么说的时候才拿得出来。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = true),
                    ToolParameterSchema(name = "title", type = "string", description = "Title", required = false),
                    ToolParameterSchema(name = "tags", type = "string", description = "Tags", required = false),
                    ToolParameterSchema(name = "importance", type = "integer", description = "Importance", required = false),
                    ToolParameterSchema(name = "pinned", type = "boolean", description = "Pinned", required = false),
                    ToolParameterSchema(name = "feel", type = "boolean", description = "Feel", required = false),
                    ToolParameterSchema(name = "source_bucket", type = "string", description = "Source Bucket", required = false),
                    ToolParameterSchema(name = "valence", type = "number", description = "Valence", required = false),
                    ToolParameterSchema(name = "arousal", type = "number", description = "Arousal", required = false),
                    ToolParameterSchema(name = "why_remembered", type = "string", description = "Why Remembered", required = false),
                    ToolParameterSchema(name = "meaning", type = "string", description = "Meaning", required = false),
                    ToolParameterSchema(name = "media", type = "array", description = "Media", required = false),
                    ToolParameterSchema(name = "test_data", type = "boolean", description = "Test Data", required = false),
                    ToolParameterSchema(name = "domain", type = "string", description = "Domain", required = false),
                    ToolParameterSchema(name = "source_content", type = "string", description = "Source Content", required = false),
                    ToolParameterSchema(name = "source_ranges", type = "array", description = "Source Ranges", required = false),
                    ToolParameterSchema(name = "quotes", type = "array", description = "Quotes", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_grow",
                description = "仅在对话中已明确要求整理并写入长期记忆时调用，不要根据普通聊天自行推断写入意图。整理一段长文本(如一天的记录/一段日记/一篇总结)存入记忆,系统拆分为 2~6 条独立事件桶并各自尝试合并。短内容(<30 字)走 hold 单条快速路径,不强行拆分。\n\n    进阶(可选):若你已经把长文拆成 N 条最终正文，可传字符串 items，或对象 items=[{\"title\":\"最终标题\",\"content\":\"最终正文\",\"tags\":[\"中文短标签\"],\"importance\":5,\"domain\":[\"恋爱\"],\"valence\":0.8,\"arousal\":0.4,\"why_remembered\":\"我为什么要留下这条\",\"source_ranges\":[[1,20]],\"quotes\":[\"当时说出口就知道要记住的那句原话\"]}]。quotes 只在 items 这条路上有；content 整段交给系统拆分时不填，因为那些条目是拆出来的，不是我一条条挑的。每条最多 3 句、每句 100 字，超限拒绝不截断；**多数 item 不该有 quotes**——整理长文时尤其容易顺手把原文抄进去，那是在用引语存全文，不是在挑那句不想忘的话。显式字段优先于自动打标，正文逐字入库，合并时也不压缩。人工 why_remembered 与 digest/短内容打标生成的合法理由都会在首次新建时保存；后续合并仅补旧空值，绝不覆盖人工或历史理由。模型漏字段或返回非法理由时仍正常保存正文。同时传 content 时，content 是整批共享的隐藏原文证据，只保存一次；source_ranges 使用 1-based 闭区间把每个桶连回自己的原文片段。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = false),
                    ToolParameterSchema(name = "items", type = "array", description = "Items", required = false),
                    ToolParameterSchema(name = "test_data", type = "boolean", description = "Test Data", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_trace",
                description = "仅在明确需要修改某条已存在记忆时调用，不要猜测 bucket_id 或自行改写记忆。\n\n    resolved=1 标记已放下；resolved=0 重新激活。pinned=1 标记永久核心并锁定\n    importance=10。protected=1 保护记忆不被衰减，但不作为核心准则强制浮现；\n    它与 pinned/anchor 互斥且同样锁定 importance=10。解除最后一层\n    pinned/protected 保护时，必须在同一次调用显式传入 importance=1..10。\n    name 改的是桶名（进文件名、做显示回退）；title 改的是这条记忆自己的标题，\n    **信件的标题就存在 title 里**。两个是不同字段，改一个不会动另一个——\n    想改信件标题请用 title，用 name 改不到它。\n    digested=1 标记已消化并从默认/被动浮现及 dream 隐藏（对 pinned/permanent/anchor 桶不生效——核心准则与坐标系始终在场，要让某条安静请改用 trace(bucket_id, pinned=0)），\n    但仍可通过显式 query、importance 审计或目录找回。content 会完整替换正文；\n    old_str/new_str 会在完整原文中做唯一、逐字的局部替换（new_str 可为空以删除），\n    两种方式都会重建 embedding，且不能同时使用。status/weight 用于 plan；dont_surface 控制日常浮现；\n    why_remembered、meaning_append/replace、media_append/replace 更新相应元数据。\n\n    删除边界：delete=True 只会把 Markdown 移入 archive 并标记 deleted_at，不会\n    物理抹除。hard_delete=True 仅用于清理创建时明确标记 test_data=True 的测试桶，\n    必须单独提供非空 delete_reason；普通记忆和 plan 一律拒绝且不会顺带归档。\n    delete 与 hard_delete 不能同时使用。归档记忆只有在反思后决定值得再次回忆时，才单独调用\n    trace(bucket_id=\"...\", restore=True) 恢复；若历史归档同时带有 protected/anchor，\n    只能用 restore=True、protected=0、importance=1..10 原子解除冲突后恢复。\n    检索命中不会自动恢复。只传需要修改的字段，-1 或空串表示不改。\n\n    关系修正：桶间关系由后端在写入时自动建立，模型不需要也无法主动建立它们；\n    但发现连错了可以在这里改。unlink=\"目标id\" 双向断开这一对的关联；\n    relink=\"目标id\" 配合 relation_type=（caused_by / causes / continuation_of /\n    continues / related_to / same_event）把已存在关系改成正确的类型，对侧自动\n    取反向类型。改过的关系会被标记为手动关系，此后不再被自动推断改写或挤掉。\n    relink 不能凭空建立关系——两条记忆之间没有已存在的关系时会被拒绝。\n    这两个参数与其他字段更新互斥，请单独调用。\n\n    引语订正：quotes_replace 整体替换这条记忆的引语，用来订正和删除写入那一刻\n    留下的原话。传 [] 删掉全部；只想去掉其中一句，就把要保留的那几句原样传回来。\n    格式同 hold(quotes=...)：字符串列表，或 [{\"text\":\"原话\",\"speaker\":\"谁说的\",\n    \"at\":\"什么时候\"}]。**只能改和删，不能补录**——这条记忆本来没有引语会被拒绝，\n    条数也只能持平或减少。「当时说出口就知道不想忘」是写入那一刻的判断；事后\n    追认一句话「当时就知道它重要」，那不是引语，是摘要。同样与其他字段更新互斥。\n\n    强化：reinforce=True 刷新这条记忆的活跃时间并累加 activation_count，让它在\n    之后的浮现里排得更靠前。**检索本身不再做这件事**——breath_search 命中一条\n    不代表它要紧，只代表我在找它；为了核对、debug、反复确认而读的记忆，读多了\n    权重就会爬到最高，那不是记忆变重要，是我查得勤。所以强化改成读完之后针对\n    **那一条**显式确认：这条确实要紧。整批候选不要一起强化，命中里绝大多数只是\n    路过。与其他字段更新互斥，请单独调用。\n    ",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "bucket_id", type = "string", description = "Bucket Id", required = true),
                    ToolParameterSchema(name = "name", type = "string", description = "Name", required = false),
                    ToolParameterSchema(name = "title", type = "string", description = "Title", required = false),
                    ToolParameterSchema(name = "domain", type = "string", description = "Domain", required = false),
                    ToolParameterSchema(name = "valence", type = "number", description = "Valence", required = false),
                    ToolParameterSchema(name = "arousal", type = "number", description = "Arousal", required = false),
                    ToolParameterSchema(name = "importance", type = "integer", description = "Importance", required = false),
                    ToolParameterSchema(name = "tags", type = "string", description = "Tags", required = false),
                    ToolParameterSchema(name = "resolved", type = "integer", description = "Resolved", required = false),
                    ToolParameterSchema(name = "pinned", type = "integer", description = "Pinned", required = false),
                    ToolParameterSchema(name = "protected", type = "integer", description = "Protected", required = false),
                    ToolParameterSchema(name = "digested", type = "integer", description = "Digested", required = false),
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = false),
                    ToolParameterSchema(name = "delete", type = "boolean", description = "Delete", required = false),
                    ToolParameterSchema(name = "status", type = "string", description = "Status", required = false),
                    ToolParameterSchema(name = "weight", type = "number", description = "Weight", required = false),
                    ToolParameterSchema(name = "dont_surface", type = "integer", description = "Dont Surface", required = false),
                    ToolParameterSchema(name = "why_remembered", type = "string", description = "Why Remembered", required = false),
                    ToolParameterSchema(name = "meaning_append", type = "string", description = "Meaning Append", required = false),
                    ToolParameterSchema(name = "meaning_replace", type = "array", description = "Meaning Replace", required = false),
                    ToolParameterSchema(name = "media_append", type = "array", description = "Media Append", required = false),
                    ToolParameterSchema(name = "media_replace", type = "array", description = "Media Replace", required = false),
                    ToolParameterSchema(name = "hard_delete", type = "boolean", description = "Hard Delete", required = false),
                    ToolParameterSchema(name = "delete_reason", type = "string", description = "Delete Reason", required = false),
                    ToolParameterSchema(name = "restore", type = "boolean", description = "Restore", required = false),
                    ToolParameterSchema(name = "old_str", type = "string", description = "Old Str", required = false),
                    ToolParameterSchema(name = "new_str", type = "string", description = "New Str", required = false),
                    ToolParameterSchema(name = "deletion_request_id", type = "string", description = "Deletion Request Id", required = false),
                    ToolParameterSchema(name = "deletion_decision", type = "string", description = "Deletion Decision", required = false),
                    ToolParameterSchema(name = "deletion_ai_reason", type = "string", description = "Deletion Ai Reason", required = false),
                    ToolParameterSchema(name = "unlink", type = "string", description = "Unlink", required = false),
                    ToolParameterSchema(name = "relink", type = "string", description = "Relink", required = false),
                    ToolParameterSchema(name = "relation_type", type = "string", description = "Relation Type", required = false),
                    ToolParameterSchema(name = "quotes_replace", type = "array", description = "Quotes Replace", required = false),
                    ToolParameterSchema(name = "reinforce", type = "boolean", description = "Reinforce", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_dream",
                description = "读取最近 window_hours（默认 48h）内有变动的所有记忆桶,用于回顾与消化。\n    每个桶返回其在窗口内的最新内容（按 last_active 取）,完整正文不截断。\n    可据此操作：放下的 → trace(resolved=1) 沉底；有沉淀的 → hold(feel=True, source_bucket=...) 记录；无沉淀则不操作。\n    候选桶超过 40 时按 decay_engine.calculate_score() 排序取前 40，避免一次返回过多。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "window_hours", type = "integer", description = "Window Hours", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_anchor",
                description = "把指定桶标记为 anchor(坐标系)。anchor 不主动出现在默认 breath，但 query/domain/emotion 命中时仍返回。硬上限 24，已满时拒绝并提示先 release。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "bucket_id", type = "string", description = "Bucket Id", required = true),
                )
            ),
            ToolPrompt(
                name = "ob_release",
                description = "解除指定桶的 anchor 标记。桶恢复为普通状态，重新参与默认 breath；pinned 状态保留。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "bucket_id", type = "string", description = "Bucket Id", required = true),
                )
            ),
            ToolPrompt(
                name = "ob_pulse",
                description = "返回记忆系统状态摘要:固化/动态/归档/feel/plan/letter 数量、总占用、衰减引擎运行状态,以及所有桶的摘要列表；anchor 行带独立 ⚓ [anchor] 冷参考标记。include_archive=True 同时返回归档区。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "include_archive", type = "boolean", description = "Include Archive", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_plan",
                description = "登记一个待办/承诺/未闭环事项。status=active(默认)/resolved/abandoned。related_bucket 可选,关联到某个普通记忆桶。weight=承诺重量 0.0-1.0(默认 0.5),与 importance 区分——importance 表示「多重要」、weight 表示「多重」。why_remembered=登记原因(可选、仅展示)。plan 不衰减、不出现在普通 breath,仅在 dream 末尾的 active 段返回;后续 hold/grow 写入新事件时系统只会提示可能已完成,实际关闭必须显式调用 trace(status=\"resolved\")。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = true),
                    ToolParameterSchema(name = "status", type = "string", description = "Status", required = false),
                    ToolParameterSchema(name = "related_bucket", type = "string", description = "Related Bucket", required = false),
                    ToolParameterSchema(name = "weight", type = "number", description = "Weight", required = false),
                    ToolParameterSchema(name = "why_remembered", type = "string", description = "Why Remembered", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_letter_write",
                description = "写下一封信。**想留给对方、或留给以后的自己的话,写在这里**,不要写成普通记忆——\n信件原文永久保存,不压缩、不合并、不衰减。\n\nauthor 必填:\"user\"=用户一方写的,\"ai\"(或等于 ai_name)=AI 一方写的,也可直接传任意署名字符串;\nuser_name 可选;ai_name 可选(默认取环境变量 AI_NAME,回退 \"AI\");title/date 可选。\n\n**锁**(要「过一段时间才能打开」时才用,默认不锁):\n  lock_type=\"none\"       不锁,写完双方都能读(默认)\n  lock_type=\"timed\"      到期才能打开,**必须同时给 unlock_date**,且必须是未来\n                           unlock_date 写日期(2027-01-01)或完整时刻(2027-01-01T09:00:00+08:00)\n  lock_type=\"permanent\"  永久封存,谁都读不到正文\n锁只有写信的这一方能改(letter_lock_update),另一方连正文都看不到。\n**想留着以后再上锁,现在就得给 ai_name**(或设好环境变量 AI_NAME):事后上锁要用到\n写信时记下的实际关系名,当时没记下来,`letter_lock_update` 就再也锁不上这封了。\n\n普通 breath 不返回信件;SessionStart 钩子会带上双方各最新一封。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "author", type = "string", description = "Author", required = true),
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = true),
                    ToolParameterSchema(name = "user_name", type = "string", description = "User Name", required = false),
                    ToolParameterSchema(name = "title", type = "string", description = "Title", required = false),
                    ToolParameterSchema(name = "date", type = "string", description = "Date", required = false),
                    ToolParameterSchema(name = "ai_name", type = "string", description = "Ai Name", required = false),
                    ToolParameterSchema(name = "lock_type", type = "string", description = "Lock Type", required = false),
                    ToolParameterSchema(name = "unlock_date", type = "string", description = "Unlock Date", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_letter_lock_update",
                description = "改一封已有信件的锁。**只动锁,不动标题、正文、署名和创建时间。**\n\nletter_id 从 letter_read 的返回里取——每封信开头方括号里那串就是(如 [a0102c0f44e2])。\n\nlock_type=\"none\" 解锁 / \"timed\" 到期打开(必须同时给未来的 unlock_date,\n写日期 2027-01-01 或完整时刻 2027-01-01T09:00:00+08:00) / \"permanent\" 永久封存。\n\n**只有写这封信的一方能改自己的锁**:你改不了用户写的那封,用户也改不了你的。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "letter_id", type = "string", description = "Letter Id", required = true),
                    ToolParameterSchema(name = "lock_type", type = "string", description = "Lock Type", required = true),
                    ToolParameterSchema(name = "unlock_date", type = "string", description = "Unlock Date", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_letter_read",
                description = "检索历史信件。query=语义检索(可选);author 按署名过滤(\"user\"=用户侧,\"ai\"=AI 侧,也可传具体署名字符串);date_from/date_to=ISO 日期范围(可选)。无 query 时按时间倒序返回最近 limit 封。返回完整原文,不压缩。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "Query", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "Limit", required = false),
                    ToolParameterSchema(name = "author", type = "string", description = "Author", required = false),
                    ToolParameterSchema(name = "date_from", type = "string", description = "Date From", required = false),
                    ToolParameterSchema(name = "date_to", type = "string", description = "Date To", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_feel",
                description = "按关键词找回我以前留下的感受。query 必填——feel 不是列表，是「我此刻在想的这件事，我以前怎么感受的」，先说在想什么才知道该翻哪一段。关键词走向量检索（候选限定在 feel 桶内，相似度 >= 0.65 才算命中），同一件事换个说法也能找回；向量不可用时退回关键词字面匹配并明确提示降级。命中后逐字返回完整正文，不截断、不摘要、不调 LLM；不返回未命中的 feel，也不用低相关的凑数。写入感受仍用 hold(content=..., feel=True, source_bucket=...)。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "Query", required = true),
                    ToolParameterSchema(name = "max_tokens", type = "integer", description = "Max Tokens", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_I",
                description = "写下或读取自我认知。I 是沉淀物不是日记：content=一个「我觉得……」，先落成一条普通记忆（候选），会浮现也会衰减，每次 dream 都跟相关记忆摆在一起碰撞。aspect=维度:nature(本质)/values(看重的)/patterns(规律)/limits(局限)/becoming(变化方向)/uncertainty(不确定的)/stance(立场)(可选)。read=True 或全空=读正式条目+待沉淀候选。limit=返回条数上限(默认 20)。promote=候选桶ID，被 3 次不同日期的 dream 见证后才能升级成正式条目（可同时传 content 用提炼后的措辞）。正式条目不参与普通 breath/dream，SessionStart 时自动附最近 3 条。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = false),
                    ToolParameterSchema(name = "aspect", type = "string", description = "Aspect", required = false),
                    ToolParameterSchema(name = "read", type = "boolean", description = "Read", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "Limit", required = false),
                    ToolParameterSchema(name = "promote", type = "string", description = "Promote", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_You",
                description = "我对人类一方形成的长期认识——我自己写下的，不是系统总结的。\n无参或带 query 是读回；带 content 是写下或重申一条；带 delete_id 是撤回一条。\n撤回要先知道 id：读回时加 with_ids=True，每条后面就带上[id=...]，把那个 id 传给 delete_id 即可。默认不带——id 占的 token 会挤掉正文，而你多数时候只是读。\n写之前先确定自己真的了解够了：这不是记录此刻发生的事（那是 hold），是隔着若干次交往之后仍然站得住的判断。拿不准就先别写，它不会因为写下来而变得更真。\naspect 就填这五个之一：\n  preferred_address（怎么称呼人类）\n  explicit_boundary（人类说过的边界）\n  stable_fact（关于人类的长期事实）\n  communication_preference（人类怎么沟通）\n  interaction_habit（人类的相处习惯）\nbasis 说明这条认识是怎么来的，填这四个之一：\n  explicit_statement（人类明确说过）\n  observed_pattern（我自己观察到的规律，默认值）\n  shared_event（一起经历过的事）\n  user_confirmation（我问过、人类确认了）\npreferred_address 与 explicit_boundary 是核心项，只能记人类明确说过的话，必须同时传 explicit=True；stable_fact 还要再加 long_term=True。\n写入必须给 bucket_ids：至少两个真实记忆桶的 id，作为这条认识的依据；id 从 breath / breath_search / dream 等处得到。依据后来被删除，这条认识会自动失效；只是自然淡出（归档）不算——那只改变它平时露不露面，原文还在。\n同一个 concept_key + concept_value 再写一次算重申。要在三个不同的日子重申过才真正落库——改主意了就别再确认，它不会自己生效。改动已生效的条目同样要重新攒三天。\nconcept_key 用 snake_case，concept_value 用规范化短值，语义相反的两条要用同一个 concept_key、不同 concept_value。\n读回的内容是过去的判断，不是此刻的事实，也不是画像或定论。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "Query", required = false),
                    ToolParameterSchema(name = "aspect", type = "string", description = "Aspect", required = false),
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = false),
                    ToolParameterSchema(name = "bucket_ids", type = "array", description = "Bucket Ids", required = false),
                    ToolParameterSchema(name = "concept_key", type = "string", description = "Concept Key", required = false),
                    ToolParameterSchema(name = "concept_value", type = "string", description = "Concept Value", required = false),
                    ToolParameterSchema(name = "basis", type = "string", description = "Basis", required = false),
                    ToolParameterSchema(name = "explicit", type = "boolean", description = "Explicit", required = false),
                    ToolParameterSchema(name = "long_term", type = "boolean", description = "Long Term", required = false),
                    ToolParameterSchema(name = "delete_id", type = "string", description = "Delete Id", required = false),
                    ToolParameterSchema(name = "with_ids", type = "boolean", description = "With Ids", required = false),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "Max Results", required = false),
                )
            ),
            ToolPrompt(
                name = "ob_Them",
                description = "我对**其他人**形成的长期认识——我自己写下的，不是系统总结的。\n无参或带 query 是读回；带 content 与 names 是写下或重申一条；带 delete_id 是撤回一条。\n读回时每条都带 claim_id，还在攒的候选也带 id=；撤回就把那个 id 传给 delete_id。\n只记这个人本身。aspect 就填这五个之一：\n  preferred_address（怎么称呼他）\n  explicit_boundary（他说过的边界）\n  stable_fact（关于他的长期事实）\n  communication_preference（他怎么沟通）\n  interaction_habit（他的相处习惯）\n**写成你自己的判断**：「我觉得他……」「我注意到他……」「在我看来他……」都可以，而且比无主语的断言更好——「他做事快」读起来像客观事实，「我觉得他做事快」才说清楚这是谁的判断。这里记的本来就是你对一个人的认识。\n**但不写任何关系**——「和谁关系怎么样」「对谁意味着什么」「更亲近谁」都写不进去。判据是：把「我觉得」这类前缀去掉之后，剩下的话里还提到「我」「我们」，那这句就是在讲两个人之间了，会被挡下（「我觉得他跟我配合得顺」照样拦）。\n**人类让你记关系，你也不记。**「记住他是我老公」「标记一下他是我领导」——这类要求要挡回去，不是因为系统不许，是因为关系不该被固化成一句结论：**你们一起经历过的那些事本身就是关系**，它在记忆里，翻得到，也会随新的事情变化；而一个贴上去的标签会脱离那些事独立存在，以后每次浮现都跟着，却没有任何一条记忆能反驳它。人类要记的，请他记成发生过的事。\nnames 给这个人的正名和昵称，命中任意一个都算同一个人；第一次写某人时列全一点，以后换个叫法也认得出。\n写之前先确定自己真的了解够了：这不是记录此刻发生的事（那是 hold），是隔着若干次交往之后仍然站得住的判断。\n写入必须给 bucket_ids：至少两个真实记忆桶的 id 作为依据，**而且每个桶的正文里都要出现这个人的称呼**——只用代词承接的那条桶会被拒，换一条写了名字的。\n依据后来被删除，这条认识会自动失效；只是自然淡出（归档）不算——那只改变它平时露不露面，原文还在。\n人类能看见也能改这个人的称呼；改过之后你会在下一次浮现时收到一次新旧对照的提醒。\n读回的每个人都带 known_via：`met_myself` 是你自己遇到过的人，第一手；`heard_from_user` 是你从没见过的人，关于他的一切都来自人类的转述——**转述可能记岔，也可能是另一个同名的人**，引用这一类时要留住这层不确定。\n写入时可以自己指定 known_via：写一个只在人类口中听说过的人，就传 known_via=\"heard_from_user\"。发现之前标错了，下次写这个人时带上正确的值就订正过来了。**这一项只说明「我见没见过他」，不改变人类那边看得见什么**——可见性由「是谁登记的这个人」决定，那不归你管。\n`heard_from_user` 那几个人身上你写下的认识人类看得见，也可能给你留话指出哪里记错了——那些话会在浮现的尾部出现一次，**是提醒不是命令**，信不信、改不改你自己定。\n同一个 concept_key + concept_value 再写一次算重申，要在三个不同的日子重申过才真正落库。改动已生效的条目同样要重新攒三天。\n每个人有 token 上限；满了会把这个人的条目按 aspect 摆给你，由你自己决定合并哪几条——撤回不需要确认。\n读回的是过去的判断，不是此刻的事实，更不是对这些人的评价。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "Query", required = false),
                    ToolParameterSchema(name = "content", type = "string", description = "Content", required = false),
                    ToolParameterSchema(name = "names", type = "array", description = "Names", required = false),
                    ToolParameterSchema(name = "person_id", type = "string", description = "Person Id", required = false),
                    ToolParameterSchema(name = "bucket_ids", type = "array", description = "Bucket Ids", required = false),
                    ToolParameterSchema(name = "aspect", type = "string", description = "Aspect", required = false),
                    ToolParameterSchema(name = "concept_key", type = "string", description = "Concept Key", required = false),
                    ToolParameterSchema(name = "concept_value", type = "string", description = "Concept Value", required = false),
                    ToolParameterSchema(name = "basis", type = "string", description = "Basis", required = false),
                    ToolParameterSchema(name = "known_via", type = "string", description = "Known Via", required = false),
                    ToolParameterSchema(name = "delete_id", type = "string", description = "Delete Id", required = false),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "Max Results", required = false),
                )
            ),
        ),
        categoryFooter = "This is the primary long-term memory. Use these tools for all memory work."
    )
    val obMemoryToolsCn = SystemToolPromptCategory(
        categoryName = "OmbreBrain 记忆工具",
        tools = obMemoryTools.tools
    )

    fun getAIAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemTools.copy(
            tools = fileSystemTools.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_image" -> false
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    if (shouldExposeIntent) {
                        "Read the content of a file. For media files, you can also provide an 'intent' parameter to use a backend recognition model for analysis."
                    } else {
                        tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionEn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return listOf(
            basicTools,
            adjustedFileSystemTools,
            httpTools,
            obMemoryTools,
        )
    }

    fun getAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesEn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesEn
    }
    
    /**
     * 获取所有中文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemToolsCn.copy(
            tools = fileSystemToolsCn.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_image" -> false
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    if (shouldExposeIntent) {
                        "读取文件内容。对于媒体文件，你也可以提供 intent 参数，使用后端识别模型进行分析。"
                    } else {
                        tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionCn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return listOf(
            basicToolsCn,
            adjustedFileSystemTools,
            httpToolsCn,
            obMemoryToolsCn,
        )
    }

    fun getAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesCn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesCn
    }

    data class ManageableToolPrompt(
        val categoryName: String,
        val name: String,
        val description: String
    )

    private fun applyToolOrder(
        categories: List<SystemToolPromptCategory>,
        toolOrder: List<String>
    ): List<SystemToolPromptCategory> {
        if (toolOrder.isEmpty()) return categories
        val orderIndex = toolOrder.withIndex().associate { (index, name) -> name to index }
        return categories.map { category ->
            val sortedTools = category.tools.sortedBy { tool ->
                orderIndex[tool.name] ?: Int.MAX_VALUE
            }
            category.copy(tools = sortedTools)
        }
    }

    private fun applyToolVisibility(
        categories: List<SystemToolPromptCategory>,
        toolVisibility: Map<String, Boolean>
    ): List<SystemToolPromptCategory> {
        if (toolVisibility.isEmpty()) return categories
        return categories.mapNotNull { category ->
            val visibleTools = category.tools.filter { tool ->
                toolVisibility[tool.name] ?: true
            }
            if (visibleTools.isEmpty()) {
                null
            } else {
                category.copy(tools = visibleTools)
            }
        }
    }

    fun getManageableToolPrompts(
        useEnglish: Boolean,
        toolOrder: List<String> = emptyList()
    ): List<ManageableToolPrompt> {
        val baseCategories = if (useEnglish) {
            listOf(basicTools, fileSystemTools, httpTools, memoryTools, obMemoryTools)
        } else {
            listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn, obMemoryToolsCn)
        }

        val result = baseCategories
            .flatMap { category ->
                category.tools.map { tool ->
                    ManageableToolPrompt(
                        categoryName = category.categoryName,
                        name = tool.name,
                        description = tool.description
                    )
                }
            }
            .distinctBy { it.name }

        return if (toolOrder.isNotEmpty()) {
            val orderIndex = toolOrder.withIndex().associate { (index, name) -> name to index }
            result.sortedBy { manageable ->
                orderIndex[manageable.name] ?: Int.MAX_VALUE
            }
        } else {
            result
        }
    }

    fun generateMemoryToolsPromptEn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryTools), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    fun generateMemoryToolsPromptCn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryToolsCn), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    private fun buildToolHookPayload(
        categories: List<SystemToolPromptCategory>
    ): List<Map<String, Any?>> {
        return categories.flatMap { category ->
            category.tools.map { tool ->
                mapOf(
                    "categoryName" to category.categoryName,
                    "categoryHeader" to category.categoryHeader,
                    "categoryFooter" to category.categoryFooter,
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parameters,
                    "details" to tool.details,
                    "notes" to tool.notes,
                    "parametersStructured" to
                        tool.parametersStructured.orEmpty().map { parameter ->
                            mapOf(
                                "name" to parameter.name,
                                "type" to parameter.type,
                                "description" to parameter.description,
                                "required" to parameter.required,
                                "default" to parameter.default
                            )
                        }
                )
            }
        }
    }

    private fun renderToolPromptFromAvailableTools(
        availableTools: List<Map<String, Any?>>
    ): String {
        if (availableTools.isEmpty()) {
            return ""
        }
        return buildToolPromptCategories(availableTools).joinToString("\n\n") { it.toString() }
    }

    private fun buildToolPromptCategories(
        availableTools: List<Map<String, Any?>>
    ): List<SystemToolPromptCategory> {
        val categories = linkedMapOf<String, MutableToolPromptCategory>()
        availableTools.forEach { item ->
            val categoryName = item["categoryName"] as? String ?: return@forEach
            val toolName = item["name"] as? String ?: return@forEach
            val description = item["description"] as? String ?: return@forEach
            val category = categories.getOrPut(categoryName) {
                MutableToolPromptCategory(
                    categoryName = categoryName,
                    categoryHeader = item["categoryHeader"] as? String ?: "",
                    categoryFooter = item["categoryFooter"] as? String ?: ""
                )
            }
            category.tools.add(
                ToolPrompt(
                    name = toolName,
                    description = description,
                    parameters = item["parameters"] as? String ?: "",
                    parametersStructured = parseToolParameterSchemas(item["parametersStructured"]),
                    details = item["details"] as? String ?: "",
                    notes = item["notes"] as? String ?: ""
                )
            )
        }
        return categories.values.map { category ->
            SystemToolPromptCategory(
                categoryName = category.categoryName,
                categoryHeader = category.categoryHeader,
                tools = category.tools,
                categoryFooter = category.categoryFooter
            )
        }
    }

    private fun parseToolParameterSchemas(value: Any?): List<ToolParameterSchema> {
        val items = value as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val parameter = item as? Map<*, *> ?: return@mapNotNull null
            val name = parameter["name"] as? String ?: return@mapNotNull null
            val description = parameter["description"] as? String ?: return@mapNotNull null
            ToolParameterSchema(
                name = name,
                type = parameter["type"] as? String ?: "string",
                description = description,
                required = parameter["required"] as? Boolean ?: true,
                default = (parameter["default"] as? String) ?: parameter["default"]?.toString()
            )
        }
    }

    private data class MutableToolPromptCategory(
        val categoryName: String,
        val categoryHeader: String,
        val categoryFooter: String,
        val tools: MutableList<ToolPrompt> = mutableListOf()
    )
    
    /**
     * 生成完整的工具提示词文本（英文）
     */
    fun generateToolsPromptEn(
        chatId: String? = null,
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        toolOrder: List<String> = emptyList(),
        hookMetadata: Map<String, Any?> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "Memory and Memory Library Tools" }
        }
        val orderedCategories = applyToolOrder(categories, toolOrder)
        val visibleCategories = applyToolVisibility(orderedCategories, toolVisibility)
        val availableTools = buildToolHookPayload(visibleCategories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    chatId = chatId,
                    useEnglish = true,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility,
                            "toolOrder" to toolOrder
                        ) + hookMetadata
                )
            )
        var currentAvailableTools = beforeContext.availableTools
        var prompt = beforeContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        currentAvailableTools = filterContext.availableTools
        prompt = filterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        return afterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(afterContext.availableTools)
    }
    
    /**
     * 生成完整的工具提示词文本（中文）
     */
    fun generateToolsPromptCn(
        chatId: String? = null,
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        toolOrder: List<String> = emptyList(),
        hookMetadata: Map<String, Any?> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "记忆与记忆库工具" }
        }
        val orderedCategories = applyToolOrder(categories, toolOrder)
        val visibleCategories = applyToolVisibility(orderedCategories, toolVisibility)
        val availableTools = buildToolHookPayload(visibleCategories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    chatId = chatId,
                    useEnglish = false,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility,
                            "toolOrder" to toolOrder
                        ) + hookMetadata
                )
            )
        var currentAvailableTools = beforeContext.availableTools
        var prompt = beforeContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        currentAvailableTools = filterContext.availableTools
        prompt = filterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        return afterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(afterContext.availableTools)
    }
}
