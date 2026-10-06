package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.core.tools.defaultTool.standard.ObMemoryBridge
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
import com.ai.assistance.operit.integrations.tasker.triggerAIAgentAction
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.ui.common.displays.VirtualDisplayOverlay
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * This file contains all tool registrations centralized for easier maintenance and integration It
 * extracts the registerTools logic from AIToolHandler into a dedicated file
 */

/**
 * Register all available tools with the AIToolHandler
 * @param handler The AIToolHandler instance to register tools with
 * @param context Application context for tools that need it
 */
fun registerAllTools(handler: AIToolHandler, context: Context) {

    // Helper function to wrap UI tool execution with visibility changes
    suspend fun executeUiToolWithVisibility(
        tool: AITool,
        showStatusIndicator: Boolean = true,
        delayMs: Long = 50,
        action: suspend (AITool) -> ToolResult
    ): ToolResult {
        val floatingService = FloatingChatService.getInstance()
        return try {
            floatingService?.setFloatingWindowVisible(false)
            if (showStatusIndicator) {
                floatingService?.setStatusIndicatorVisible(true)
            } else {
                floatingService?.setStatusIndicatorVisible(false)
            }
            delay(delayMs)
            action(tool)
        } finally {
            floatingService?.setFloatingWindowVisible(true)
            floatingService?.setStatusIndicatorVisible(false)
        }
    }

    fun s(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    fun formatEnvInfo(environment: String?): String {
        return if (!environment.isNullOrBlank() && environment != "android") {
            s(R.string.toolreg_env_info, environment)
        } else {
            ""
        }
    }

    fun formatEnvArrowInfo(sourceEnv: String, destEnv: String): String {
        return if (sourceEnv != "android" || destEnv != "android") {
            s(R.string.toolreg_env_arrow_info, sourceEnv, destEnv)
        } else {
            ""
        }
    }

    val packageContextParamNames = setOf(
        "__operit_package_caller_name",
        "__operit_package_chat_id",
        "__operit_package_caller_card_id"
    )

    class ParsedProxyInvocation(
        val targetToolName: String,
        val forwardedParameters: MutableList<ToolParameter>
    )

    fun isEnglishLanguage(): Boolean {
        return !LocaleUtils.usesChineseContent(context)
    }

    fun buildToolErrorResult(tool: AITool, error: String): ToolResult {
        return ToolResult(
            toolName = tool.name,
            success = false,
            result = StringResultData(""),
            error = error
        )
    }

    fun parseProxyInvocation(
        tool: AITool,
        requireQualifiedTarget: Boolean
    ): Pair<ParsedProxyInvocation?, ToolResult?> {
        val allowedParamNames = setOf("tool_name", "params") + packageContextParamNames
        val unknownParamNames = tool.parameters.map { it.name }.filter { it !in allowedParamNames }
        if (unknownParamNames.isNotEmpty()) {
            return null to buildToolErrorResult(
                tool,
                "Unexpected parameters: ${unknownParamNames.joinToString(", ")}. Only tool_name, params, and supported system context parameters are allowed"
            )
        }

        val toolNameParams = tool.parameters.filter { it.name == "tool_name" }
        if (toolNameParams.size != 1) {
            return null to buildToolErrorResult(
                tool,
                "Exactly one tool_name parameter is required"
            )
        }
        val targetToolName = toolNameParams.first().value.trim()
        if (targetToolName.isBlank()) {
            return null to buildToolErrorResult(
                tool,
                "Missing required parameter: tool_name"
            )
        }

        if (requireQualifiedTarget && !targetToolName.contains(':')) {
            return null to buildToolErrorResult(
                tool,
                "tool_name must use packageName:toolName format"
            )
        }

        val paramsParams = tool.parameters.filter { it.name == "params" }
        if (paramsParams.size != 1) {
            return null to buildToolErrorResult(
                tool,
                "Exactly one params parameter is required"
            )
        }
        val paramsRaw = paramsParams.first().value.trim()
        if (paramsRaw.isBlank()) {
            return null to buildToolErrorResult(tool, "params must be a JSON object")
        }

        val paramsObject = try {
            JSONObject(paramsRaw)
        } catch (_: Exception) {
            return null to buildToolErrorResult(tool, "params must be a valid JSON object")
        }

        val forwardedParameters = mutableListOf<ToolParameter>()
        val keys = paramsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = paramsObject.opt(key)
            val valueString = when (value) {
                null, JSONObject.NULL -> "null"
                is String -> value
                else -> value.toString()
            }
            forwardedParameters.add(ToolParameter(name = key, value = valueString))
        }

        packageContextParamNames.forEach { paramName ->
            val value = tool.parameters
                .firstOrNull { it.name == paramName }
                ?.value
                ?.trim()
            if (!value.isNullOrBlank() && forwardedParameters.none { it.name == paramName }) {
                forwardedParameters.add(ToolParameter(name = paramName, value = value))
            }
        }

        return ParsedProxyInvocation(
            targetToolName = targetToolName,
            forwardedParameters = forwardedParameters
        ) to null
    }

    fun resolveCurrentRoleCardToolAccess(): ResolvedCharacterCardToolAccess {
        val runtimeContext = ToolExecutionManager.currentToolRuntimeContext()
        return runBlocking {
            CharacterCardToolAccessResolver
                .getInstance(context)
                .resolve(
                    roleCardId = runtimeContext?.callerCardId,
                    packageManager = handler.getOrCreatePackageManager()
                )
        }
    }

    fun isProxyTargetAllowedForRoleCard(
        targetToolName: String,
        forwardedParameters: List<ToolParameter>,
        roleCardToolAccess: ResolvedCharacterCardToolAccess
    ): Boolean {
        val usePackageSourceName =
            if (targetToolName == "use_package") {
                forwardedParameters
                    .firstOrNull { it.name == "package_name" }
                    ?.value
                    ?.trim()
                    .orEmpty()
                    .ifBlank { null }
            } else {
                null
            }

        return CliToolModeSupport.isToolNameAllowedForRoleCard(
            toolName = targetToolName,
            usePackageSourceName = usePackageSourceName,
            roleCardToolAccess = roleCardToolAccess
        )
    }

    fun executeProxyTargetWithPermissionCheck(
        targetToolName: String,
        forwardedParameters: List<ToolParameter>,
        useEnglish: Boolean
    ): ToolResult {
        val proxiedTool = AITool(
            name = targetToolName,
            parameters = forwardedParameters
        )
        val executor = handler.getToolExecutorOrActivate(targetToolName)
        if (executor == null) {
            return ToolResult(
                toolName = targetToolName,
                success = false,
                result = StringResultData(""),
                error = CliToolModeSupport.buildProxyTargetUnavailableMessage(targetToolName, useEnglish)
            )
        }

        val permissionResult = runBlocking {
            handler.getToolPermissionSystem().checkToolPermission(proxiedTool)
        }
        if (!permissionResult.isGranted) {
            val errorMessage = context.getString(requireNotNull(permissionResult.errorMessageResId))
            handler.notifyToolPermissionChecked(
                proxiedTool,
                granted = false,
                reason = errorMessage
            )
            return ToolResult(
                toolName = targetToolName,
                success = false,
                result = StringResultData(""),
                error = errorMessage
            )
        }

        handler.notifyToolPermissionChecked(proxiedTool, granted = true)
        val proxiedResult = handler.executeTool(proxiedTool)
        return ToolResult(
            toolName = targetToolName,
            success = proxiedResult.success,
            result = proxiedResult.result,
            error = proxiedResult.error
        )
    }

    // 不在提示词加入的工具
    // ==================== OB（OmbreBrain）原生记忆工具 ====================
    handler.registerTool(
            name = "ob_breath",
            descriptionGenerator = { tool -> "无参数,睁眼看看自己记得什么:返回权重最高、未解决且未标记 digested 的记忆 + 置顶核心准则。digested 从默认/被动浮现及 dream 隐藏，仍可由 breath_search(query=...) 显式找回。0 参数是刻意设计——claude.ai 按需加载工具时会跳过参数复杂的工具,拆成 0 参数才能保证每次对话自动浮现,不用手动触发。要按关键词找记忆用 breath_search(query=...);要用 catalog/tags/importance_min/valence/arousal/max_tokens 等高级模式用 breath_advanced(...)。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_breath_search",
            descriptionGenerator = { tool -> "按关键词/语义检索记忆桶,融合关键词/BM25+语义检索,向量不可用时明确提示并退回关键词检索。命中后逐字返回桶内当前 content，不调用 LLM 摘要/改写。domain 逗号分隔,按主题域预筛。date_from/date_to 按桶的创建时间过滤，支持 YYYY-MM-DD 或 ISO 8601，同日上下界包含当天全日。max_results=返回条数上限(默认 config.surfacing.breath_max_results,fallback 20,最大 50)。需要 tags/importance_min/valence/arousal/max_tokens/catalog 等更多过滤维度用 breath_advanced(...)。quotes=True：如果你发现自己不只想知道当时发生了什么，还想知道当时到底是怎么说的，就要它——命中的桶里如果存过原话，会原样附在正文后面。默认不给，引语平时安静躺着，不占上下文也不打扰你。它给的是写入那一刻挑出来的那几句（每条记忆最多 3 句、每句 100 字），**不是原文**：OB 没有「返回全文」这个入口，没挑出来的话当时就没有留下。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_breath_advanced",
            descriptionGenerator = { tool -> "breath 的完整参数版,给需要精细控制的场景用(日常用 breath()/breath_search() 就够了)。不传 query=返回权重最高的未解决记忆;传 query=融合关键词/BM25+语义检索，向量不可用时明确提示并退回关键词检索。命中后逐字返回桶内当前 content，不调用 LLM 摘要/改写；max_tokens 不足时整桶省略，绝不截断正文。catalog=True=目录模式:只返回每桶一行元数据(名称|域|重要度,0 LLM 调用,最省 token),anchor 行带 ⚓ [anchor] 冷参考标记,适合开新对话先看目录再 breath_search(query=...) 精准拉取,并遵守 domain、tags 与 max_results。date_from/date_to 按桶的创建时间过滤，支持 YYYY-MM-DD 或 ISO 8601。max_tokens=单次返回总 token 上限(默认 config.surfacing.breath_max_tokens,fallback 10000)。domain 逗号分隔,valence/arousal 0~1(-1 忽略)。max_results=返回条数上限(默认 config.surfacing.breath_max_results,fallback 20,最大 50)。importance_min>=1=跳过语义检索,按重要度降序返回最多 20 条高重要度记忆。tags 逗号分隔,AND 过滤;tags=\"feel\" 或 \"__feel__\" 等价于 domain=\"feel\",返回所有 feel 类记忆。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_hold",
            descriptionGenerator = { tool -> "仅在对话中已明确决定“这段内容值得成为长期记忆”时调用；不要因普通聊天、猜测或工具名称联想而自行调用。content 逐字保存，绝不压缩。正文里凡是**别人**说的话（不是用户、也不是我自己），写成单独一行 `@名字：原话`——这样的行以后会被单独拆成一条带 speaker 的 JSON 返回，不会被读成用户说过的话；混在叙述里写「某某说……」拆不出来。用户和我自己的话照常直接写，不要加 `@`。title 可选；传入时是最终显式标题，优先于打标模型建议。domain 可选、逗号分隔；显式传入时优先于打标模型结果。系统自动补其余元数据，API 不可用时使用本地中性值继续保存。tags 逗号分隔，importance 1-10。pinned=True 标记为永久核心；feel=True 存为感受类记忆且 domain 固定为 feel。source_bucket 是正在消化的原始记忆桶 ID。source_content/source_ranges 是可选原文证据：由调用方自行决定是否提供；原文进入与 grow 共用的不可变原文层，不参与普通 breath。省略 source_ranges 时整份 source_content 默认属于当前 hold 事件；显式 ranges 使用 1-based 闭区间。why_remembered 与 meaning 是可选的第一人称记录原因。media 可传服务器可读路径或 data_base64+filename 列表项。quotes 是这一刻我决定要原样记住的那一两句话——不是记录对话，是说出口的当下就知道自己不想忘的那几句。传字符串列表，或 [{\"text\":\"原话\",\"speaker\":\"谁说的\",\"at\":\"什么时候\"}]。最多 3 句、每句 100 字；超了会被拒绝而不是截断，因为截断过的话已经不是原话。**默认状态是一句都不放**：3 句是上限不是配额，绝大多数记忆不需要引语，拿不准就别放。不要为了保住上下文把一段话切成几句塞进来，也不要挑「有信息量」的句子——那是在用引语存原文，而原文层正是因为这个原因被删掉的。只放那种漏掉会真的可惜的原话。它平时不出现在任何浮现里，只有以后我自己想知道当时到底怎么说的时候才拿得出来。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_grow",
            descriptionGenerator = { tool -> "仅在对话中已明确要求整理并写入长期记忆时调用，不要根据普通聊天自行推断写入意图。整理一段长文本(如一天的记录/一段日记/一篇总结)存入记忆,系统拆分为 2~6 条独立事件桶并各自尝试合并。短内容(<30 字)走 hold 单条快速路径,不强行拆分。\n\n    进阶(可选):若你已经把长文拆成 N 条最终正文，可传字符串 items，或对象 items=[{\"title\":\"最终标题\",\"content\":\"最终正文\",\"tags\":[\"中文短标签\"],\"importance\":5,\"domain\":[\"恋爱\"],\"valence\":0.8,\"arousal\":0.4,\"why_remembered\":\"我为什么要留下这条\",\"source_ranges\":[[1,20]],\"quotes\":[\"当时说出口就知道要记住的那句原话\"]}]。quotes 只在 items 这条路上有；content 整段交给系统拆分时不填，因为那些条目是拆出来的，不是我一条条挑的。每条最多 3 句、每句 100 字，超限拒绝不截断；**多数 item 不该有 quotes**——整理长文时尤其容易顺手把原文抄进去，那是在用引语存全文，不是在挑那句不想忘的话。显式字段优先于自动打标，正文逐字入库，合并时也不压缩。人工 why_remembered 与 digest/短内容打标生成的合法理由都会在首次新建时保存；后续合并仅补旧空值，绝不覆盖人工或历史理由。模型漏字段或返回非法理由时仍正常保存正文。同时传 content 时，content 是整批共享的隐藏原文证据，只保存一次；source_ranges 使用 1-based 闭区间把每个桶连回自己的原文片段。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_trace",
            descriptionGenerator = { tool -> "仅在明确需要修改某条已存在记忆时调用，不要猜测 bucket_id 或自行改写记忆。\n\n    resolved=1 标记已放下；resolved=0 重新激活。pinned=1 标记永久核心并锁定\n    importance=10。protected=1 保护记忆不被衰减，但不作为核心准则强制浮现；\n    它与 pinned/anchor 互斥且同样锁定 importance=10。解除最后一层\n    pinned/protected 保护时，必须在同一次调用显式传入 importance=1..10。\n    name 改的是桶名（进文件名、做显示回退）；title 改的是这条记忆自己的标题，\n    **信件的标题就存在 title 里**。两个是不同字段，改一个不会动另一个——\n    想改信件标题请用 title，用 name 改不到它。\n    digested=1 标记已消化并从默认/被动浮现及 dream 隐藏（对 pinned/permanent/anchor 桶不生效——核心准则与坐标系始终在场，要让某条安静请改用 trace(bucket_id, pinned=0)），\n    但仍可通过显式 query、importance 审计或目录找回。content 会完整替换正文；\n    old_str/new_str 会在完整原文中做唯一、逐字的局部替换（new_str 可为空以删除），\n    两种方式都会重建 embedding，且不能同时使用。status/weight 用于 plan；dont_surface 控制日常浮现；\n    why_remembered、meaning_append/replace、media_append/replace 更新相应元数据。\n\n    删除边界：delete=True 只会把 Markdown 移入 archive 并标记 deleted_at，不会\n    物理抹除。hard_delete=True 仅用于清理创建时明确标记 test_data=True 的测试桶，\n    必须单独提供非空 delete_reason；普通记忆和 plan 一律拒绝且不会顺带归档。\n    delete 与 hard_delete 不能同时使用。归档记忆只有在反思后决定值得再次回忆时，才单独调用\n    trace(bucket_id=\"...\", restore=True) 恢复；若历史归档同时带有 protected/anchor，\n    只能用 restore=True、protected=0、importance=1..10 原子解除冲突后恢复。\n    检索命中不会自动恢复。只传需要修改的字段，-1 或空串表示不改。\n\n    关系修正：桶间关系由后端在写入时自动建立，模型不需要也无法主动建立它们；\n    但发现连错了可以在这里改。unlink=\"目标id\" 双向断开这一对的关联；\n    relink=\"目标id\" 配合 relation_type=（caused_by / causes / continuation_of /\n    continues / related_to / same_event）把已存在关系改成正确的类型，对侧自动\n    取反向类型。改过的关系会被标记为手动关系，此后不再被自动推断改写或挤掉。\n    relink 不能凭空建立关系——两条记忆之间没有已存在的关系时会被拒绝。\n    这两个参数与其他字段更新互斥，请单独调用。\n\n    引语订正：quotes_replace 整体替换这条记忆的引语，用来订正和删除写入那一刻\n    留下的原话。传 [] 删掉全部；只想去掉其中一句，就把要保留的那几句原样传回来。\n    格式同 hold(quotes=...)：字符串列表，或 [{\"text\":\"原话\",\"speaker\":\"谁说的\",\n    \"at\":\"什么时候\"}]。**只能改和删，不能补录**——这条记忆本来没有引语会被拒绝，\n    条数也只能持平或减少。「当时说出口就知道不想忘」是写入那一刻的判断；事后\n    追认一句话「当时就知道它重要」，那不是引语，是摘要。同样与其他字段更新互斥。\n\n    强化：reinforce=True 刷新这条记忆的活跃时间并累加 activation_count，让它在\n    之后的浮现里排得更靠前。**检索本身不再做这件事**——breath_search 命中一条\n    不代表它要紧，只代表我在找它；为了核对、debug、反复确认而读的记忆，读多了\n    权重就会爬到最高，那不是记忆变重要，是我查得勤。所以强化改成读完之后针对\n    **那一条**显式确认：这条确实要紧。整批候选不要一起强化，命中里绝大多数只是\n    路过。与其他字段更新互斥，请单独调用。\n    " },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_dream",
            descriptionGenerator = { tool -> "读取最近 window_hours（默认 48h）内有变动的所有记忆桶,用于回顾与消化。\n    每个桶返回其在窗口内的最新内容（按 last_active 取）,完整正文不截断。\n    可据此操作：放下的 → trace(resolved=1) 沉底；有沉淀的 → hold(feel=True, source_bucket=...) 记录；无沉淀则不操作。\n    候选桶超过 40 时按 decay_engine.calculate_score() 排序取前 40，避免一次返回过多。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_anchor",
            descriptionGenerator = { tool -> "把指定桶标记为 anchor(坐标系)。anchor 不主动出现在默认 breath，但 query/domain/emotion 命中时仍返回。硬上限 24，已满时拒绝并提示先 release。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_release",
            descriptionGenerator = { tool -> "解除指定桶的 anchor 标记。桶恢复为普通状态，重新参与默认 breath；pinned 状态保留。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_pulse",
            descriptionGenerator = { tool -> "返回记忆系统状态摘要:固化/动态/归档/feel/plan/letter 数量、总占用、衰减引擎运行状态,以及所有桶的摘要列表；anchor 行带独立 ⚓ [anchor] 冷参考标记。include_archive=True 同时返回归档区。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_plan",
            descriptionGenerator = { tool -> "登记一个待办/承诺/未闭环事项。status=active(默认)/resolved/abandoned。related_bucket 可选,关联到某个普通记忆桶。weight=承诺重量 0.0-1.0(默认 0.5),与 importance 区分——importance 表示「多重要」、weight 表示「多重」。why_remembered=登记原因(可选、仅展示)。plan 不衰减、不出现在普通 breath,仅在 dream 末尾的 active 段返回;后续 hold/grow 写入新事件时系统只会提示可能已完成,实际关闭必须显式调用 trace(status=\"resolved\")。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_letter_write",
            descriptionGenerator = { tool -> "写下一封信。**想留给对方、或留给以后的自己的话,写在这里**,不要写成普通记忆——\n信件原文永久保存,不压缩、不合并、不衰减。\n\nauthor 必填:\"user\"=用户一方写的,\"ai\"(或等于 ai_name)=AI 一方写的,也可直接传任意署名字符串;\nuser_name 可选;ai_name 可选(默认取环境变量 AI_NAME,回退 \"AI\");title/date 可选。\n\n**锁**(要「过一段时间才能打开」时才用,默认不锁):\n  lock_type=\"none\"       不锁,写完双方都能读(默认)\n  lock_type=\"timed\"      到期才能打开,**必须同时给 unlock_date**,且必须是未来\n                           unlock_date 写日期(2027-01-01)或完整时刻(2027-01-01T09:00:00+08:00)\n  lock_type=\"permanent\"  永久封存,谁都读不到正文\n锁只有写信的这一方能改(letter_lock_update),另一方连正文都看不到。\n**想留着以后再上锁,现在就得给 ai_name**(或设好环境变量 AI_NAME):事后上锁要用到\n写信时记下的实际关系名,当时没记下来,`letter_lock_update` 就再也锁不上这封了。\n\n普通 breath 不返回信件;SessionStart 钩子会带上双方各最新一封。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_letter_lock_update",
            descriptionGenerator = { tool -> "改一封已有信件的锁。**只动锁,不动标题、正文、署名和创建时间。**\n\nletter_id 从 letter_read 的返回里取——每封信开头方括号里那串就是(如 [a0102c0f44e2])。\n\nlock_type=\"none\" 解锁 / \"timed\" 到期打开(必须同时给未来的 unlock_date,\n写日期 2027-01-01 或完整时刻 2027-01-01T09:00:00+08:00) / \"permanent\" 永久封存。\n\n**只有写这封信的一方能改自己的锁**:你改不了用户写的那封,用户也改不了你的。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_letter_read",
            descriptionGenerator = { tool -> "检索历史信件。query=语义检索(可选);author 按署名过滤(\"user\"=用户侧,\"ai\"=AI 侧,也可传具体署名字符串);date_from/date_to=ISO 日期范围(可选)。无 query 时按时间倒序返回最近 limit 封。返回完整原文,不压缩。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_feel",
            descriptionGenerator = { tool -> "按关键词找回我以前留下的感受。query 必填——feel 不是列表，是「我此刻在想的这件事，我以前怎么感受的」，先说在想什么才知道该翻哪一段。关键词走向量检索（候选限定在 feel 桶内，相似度 >= 0.65 才算命中），同一件事换个说法也能找回；向量不可用时退回关键词字面匹配并明确提示降级。命中后逐字返回完整正文，不截断、不摘要、不调 LLM；不返回未命中的 feel，也不用低相关的凑数。写入感受仍用 hold(content=..., feel=True, source_bucket=...)。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_I",
            descriptionGenerator = { tool -> "写下或读取自我认知。I 是沉淀物不是日记：content=一个「我觉得……」，先落成一条普通记忆（候选），会浮现也会衰减，每次 dream 都跟相关记忆摆在一起碰撞。aspect=维度:nature(本质)/values(看重的)/patterns(规律)/limits(局限)/becoming(变化方向)/uncertainty(不确定的)/stance(立场)(可选)。read=True 或全空=读正式条目+待沉淀候选。limit=返回条数上限(默认 20)。promote=候选桶ID，被 3 次不同日期的 dream 见证后才能升级成正式条目（可同时传 content 用提炼后的措辞）。正式条目不参与普通 breath/dream，SessionStart 时自动附最近 3 条。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_You",
            descriptionGenerator = { tool -> "我对人类一方形成的长期认识——我自己写下的，不是系统总结的。\n无参或带 query 是读回；带 content 是写下或重申一条；带 delete_id 是撤回一条。\n撤回要先知道 id：读回时加 with_ids=True，每条后面就带上[id=...]，把那个 id 传给 delete_id 即可。默认不带——id 占的 token 会挤掉正文，而你多数时候只是读。\n写之前先确定自己真的了解够了：这不是记录此刻发生的事（那是 hold），是隔着若干次交往之后仍然站得住的判断。拿不准就先别写，它不会因为写下来而变得更真。\naspect 就填这五个之一：\n  preferred_address（怎么称呼人类）\n  explicit_boundary（人类说过的边界）\n  stable_fact（关于人类的长期事实）\n  communication_preference（人类怎么沟通）\n  interaction_habit（人类的相处习惯）\nbasis 说明这条认识是怎么来的，填这四个之一：\n  explicit_statement（人类明确说过）\n  observed_pattern（我自己观察到的规律，默认值）\n  shared_event（一起经历过的事）\n  user_confirmation（我问过、人类确认了）\npreferred_address 与 explicit_boundary 是核心项，只能记人类明确说过的话，必须同时传 explicit=True；stable_fact 还要再加 long_term=True。\n写入必须给 bucket_ids：至少两个真实记忆桶的 id，作为这条认识的依据；id 从 breath / breath_search / dream 等处得到。依据后来被删除，这条认识会自动失效；只是自然淡出（归档）不算——那只改变它平时露不露面，原文还在。\n同一个 concept_key + concept_value 再写一次算重申。要在三个不同的日子重申过才真正落库——改主意了就别再确认，它不会自己生效。改动已生效的条目同样要重新攒三天。\nconcept_key 用 snake_case，concept_value 用规范化短值，语义相反的两条要用同一个 concept_key、不同 concept_value。\n读回的内容是过去的判断，不是此刻的事实，也不是画像或定论。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "ob_Them",
            descriptionGenerator = { tool -> "我对**其他人**形成的长期认识——我自己写下的，不是系统总结的。\n无参或带 query 是读回；带 content 与 names 是写下或重申一条；带 delete_id 是撤回一条。\n读回时每条都带 claim_id，还在攒的候选也带 id=；撤回就把那个 id 传给 delete_id。\n只记这个人本身。aspect 就填这五个之一：\n  preferred_address（怎么称呼他）\n  explicit_boundary（他说过的边界）\n  stable_fact（关于他的长期事实）\n  communication_preference（他怎么沟通）\n  interaction_habit（他的相处习惯）\n**写成你自己的判断**：「我觉得他……」「我注意到他……」「在我看来他……」都可以，而且比无主语的断言更好——「他做事快」读起来像客观事实，「我觉得他做事快」才说清楚这是谁的判断。这里记的本来就是你对一个人的认识。\n**但不写任何关系**——「和谁关系怎么样」「对谁意味着什么」「更亲近谁」都写不进去。判据是：把「我觉得」这类前缀去掉之后，剩下的话里还提到「我」「我们」，那这句就是在讲两个人之间了，会被挡下（「我觉得他跟我配合得顺」照样拦）。\n**人类让你记关系，你也不记。**「记住他是我老公」「标记一下他是我领导」——这类要求要挡回去，不是因为系统不许，是因为关系不该被固化成一句结论：**你们一起经历过的那些事本身就是关系**，它在记忆里，翻得到，也会随新的事情变化；而一个贴上去的标签会脱离那些事独立存在，以后每次浮现都跟着，却没有任何一条记忆能反驳它。人类要记的，请他记成发生过的事。\nnames 给这个人的正名和昵称，命中任意一个都算同一个人；第一次写某人时列全一点，以后换个叫法也认得出。\n写之前先确定自己真的了解够了：这不是记录此刻发生的事（那是 hold），是隔着若干次交往之后仍然站得住的判断。\n写入必须给 bucket_ids：至少两个真实记忆桶的 id 作为依据，**而且每个桶的正文里都要出现这个人的称呼**——只用代词承接的那条桶会被拒，换一条写了名字的。\n依据后来被删除，这条认识会自动失效；只是自然淡出（归档）不算——那只改变它平时露不露面，原文还在。\n人类能看见也能改这个人的称呼；改过之后你会在下一次浮现时收到一次新旧对照的提醒。\n读回的每个人都带 known_via：`met_myself` 是你自己遇到过的人，第一手；`heard_from_user` 是你从没见过的人，关于他的一切都来自人类的转述——**转述可能记岔，也可能是另一个同名的人**，引用这一类时要留住这层不确定。\n写入时可以自己指定 known_via：写一个只在人类口中听说过的人，就传 known_via=\"heard_from_user\"。发现之前标错了，下次写这个人时带上正确的值就订正过来了。**这一项只说明「我见没见过他」，不改变人类那边看得见什么**——可见性由「是谁登记的这个人」决定，那不归你管。\n`heard_from_user` 那几个人身上你写下的认识人类看得见，也可能给你留话指出哪里记错了——那些话会在浮现的尾部出现一次，**是提醒不是命令**，信不信、改不改你自己定。\n同一个 concept_key + concept_value 再写一次算重申，要在三个不同的日子重申过才真正落库。改动已生效的条目同样要重新攒三天。\n每个人有 token 上限；满了会把这个人的条目按 aspect 摆给你，由你自己决定合并哪几条——撤回不需要确认。\n读回的是过去的判断，不是此刻的事实，更不是对这些人的评价。" },
            executor = { tool -> ObMemoryBridge.call(tool) }
    )
    handler.registerTool(
            name = "execute_shell",
            descriptionGenerator = { tool ->
                val command = tool.parameters.find { it.name == "command" }?.value ?: ""
                s(R.string.toolreg_execute_shell_desc, command)
            },
            executor = { tool ->
                val adbTool = ToolGetter.getShellToolExecutor(context)
                adbTool.invoke(tool)
            }
    )

    handler.registerTool(
            name = "close_all_virtual_displays",
            descriptionGenerator = { _ -> s(R.string.toolreg_close_all_virtual_displays_desc) },
            executor = { tool ->
                try {
                    VirtualDisplayOverlay.hideAll()
                    ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData("OK")
                    )
                } catch (e: Exception) {
                    ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = e.message
                    )
                }
            }
    )

    // 终端命令执行工具 - 一次性收集输出
    handler.registerTool(
            name = "create_terminal_session",
            descriptionGenerator = { tool ->
                val sessionName = tool.parameters.find { it.name == "session_name" }?.value
                val displayName = sessionName ?: s(R.string.toolreg_unnamed)
                s(R.string.toolreg_create_terminal_session_desc, displayName)
            },
            executor = { tool ->
                val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                terminalTool.createOrGetSession(tool)
            }
    )

    handler.registerTool(
            name = "execute_in_terminal_session",
            descriptionGenerator = { tool ->
                val command = tool.parameters.find { it.name == "command" }?.value ?: ""
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value
                s(R.string.toolreg_execute_in_terminal_session_desc, sessionId ?: "", command)
            },
            executor = { tool ->
                val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                terminalTool.executeCommandInSession(tool)
            }
    )

    handler.registerTool(
            name = "execute_in_terminal_session_streaming",
            descriptionGenerator = { tool ->
                val command = tool.parameters.find { it.name == "command" }?.value ?: ""
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value
                s(R.string.toolreg_execute_in_terminal_session_desc, sessionId ?: "", command)
            },
            executor =
                    object : ToolExecutor {
                        override fun invoke(tool: AITool): ToolResult {
                            val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                            return terminalTool.executeCommandInSession(tool)
                        }

                        override fun invokeAndStream(
                                tool: AITool
                        ): kotlinx.coroutines.flow.Flow<ToolResult> {
                            val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                            return terminalTool.executeCommandInSessionStream(tool)
                        }
                    }
    )

    handler.registerTool(
            name = "execute_hidden_terminal_command",
            descriptionGenerator = { tool ->
                val command = tool.parameters.find { it.name == "command" }?.value ?: ""
                val executorKey =
                        tool.parameters.find { it.name == "executor_key" }?.value ?: "default"
                s(R.string.toolreg_execute_hidden_terminal_command_desc, executorKey, command)
            },
            executor = { tool ->
                val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                terminalTool.executeHiddenCommand(tool)
            }
    )

    handler.registerTool(
            name = "close_terminal_session",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value
                s(R.string.toolreg_close_terminal_session_desc, sessionId ?: "")
            },
            executor = { tool ->
                val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                terminalTool.closeSession(tool)
            }
    )

    handler.registerTool(
            name = "input_in_terminal_session",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value
                val control = tool.parameters.find { it.name == "control" }?.value ?: "-"
                s(R.string.toolreg_input_in_terminal_session_desc, sessionId ?: "", control)
            },
            executor = { tool ->
                val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                terminalTool.inputInSession(tool)
            }
    )

    handler.registerTool(
            name = "get_terminal_session_screen",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                s(R.string.toolreg_get_terminal_session_screen_desc, sessionId)
            },
            executor = { tool ->
                val terminalTool = ToolGetter.getTerminalCommandExecutor(context)
                terminalTool.getSessionScreen(tool)
            }
    )

    // 音乐播放工具
    val musicPlaybackTools = ToolGetter.getMusicPlaybackTools(context)

    handler.registerTool(
            name = "music_play",
            descriptionGenerator = { tool ->
                val source = tool.parameters.find { it.name == "source" }?.value ?: ""
                "Play music: $source"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.play(tool) }
            }
    )

    handler.registerTool(
            name = "music_play_queue",
            descriptionGenerator = { tool ->
                val items = tool.parameters.find { it.name == "items" }?.value ?: ""
                "Play music queue: $items"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.playQueue(tool) }
            }
    )

    handler.registerTool(
            name = "music_pause",
            descriptionGenerator = { _ -> "Pause music playback" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.pause(tool) }
            }
    )

    handler.registerTool(
            name = "music_resume",
            descriptionGenerator = { _ -> "Resume music playback" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.resume(tool) }
            }
    )

    handler.registerTool(
            name = "music_stop",
            descriptionGenerator = { _ -> "Stop music playback" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.stop(tool) }
            }
    )

    handler.registerTool(
            name = "music_seek",
            descriptionGenerator = { tool ->
                val positionMs = tool.parameters.find { it.name == "position_ms" }?.value ?: ""
                "Seek music playback to ${positionMs}ms"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.seek(tool) }
            }
    )

    handler.registerTool(
            name = "music_set_volume",
            descriptionGenerator = { tool ->
                val volume = tool.parameters.find { it.name == "volume" }?.value ?: ""
                "Set music playback volume to $volume"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.setVolume(tool) }
            }
    )

    handler.registerTool(
            name = "music_status",
            descriptionGenerator = { _ -> "Get music playback status" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { musicPlaybackTools.status(tool) }
            }
    )

    handler.registerTool(
            name = "read_environment_variable",
            descriptionGenerator = { tool ->
                val key = tool.parameters.find { it.name == "key" }?.value ?: ""
                "Read environment variable: $key"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                softwareSettingsTools.readEnvironmentVariable(tool)
            }
    )

    handler.registerTool(
            name = "write_environment_variable",
            descriptionGenerator = { tool ->
                val key = tool.parameters.find { it.name == "key" }?.value ?: ""
                val value = tool.parameters.find { it.name == "value" }?.value
                val mode = if (value.isNullOrBlank()) "clear" else "set"
                "Write environment variable: $key ($mode)"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                softwareSettingsTools.writeEnvironmentVariable(tool)
            }
    )

    handler.registerTool(
            name = "list_sandbox_packages",
            descriptionGenerator = { _ ->
                "List sandbox packages and their enabled states"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                val packageManager = handler.getOrCreatePackageManager()
                softwareSettingsTools.listSandboxPackages(tool, packageManager)
            }
    )

    handler.registerTool(
            name = "set_sandbox_package_enabled",
            descriptionGenerator = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
                val enabled = tool.parameters.find { it.name == "enabled" }?.value ?: ""
                "Set sandbox package enabled state: $packageName -> $enabled"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                val packageManager = handler.getOrCreatePackageManager()
                softwareSettingsTools.setSandboxPackageEnabled(tool, packageManager)
            }
    )

    handler.registerTool(
            name = "execute_sandbox_script_direct",
            descriptionGenerator = { tool ->
                val sourcePath = tool.parameters.find { it.name == "source_path" }?.value?.trim().orEmpty()
                val hasInlineCode =
                        tool.parameters.find { it.name == "source_code" }?.value?.isNotBlank() == true
                val label =
                        tool.parameters.find { it.name == "script_label" }?.value?.trim().orEmpty()
                val target =
                        when {
                            sourcePath.isNotBlank() -> sourcePath
                            label.isNotBlank() -> label
                            hasInlineCode -> "inline code"
                            else -> "sandbox script"
                        }
                "Execute sandbox script directly: $target"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                softwareSettingsTools.executeSandboxScriptDirect(tool)
            }
    )

    handler.registerTool(
            name = "restart_mcp_with_logs",
            descriptionGenerator = { tool ->
                val timeoutMs = tool.parameters.find { it.name == "timeout_ms" }?.value ?: "120000"
                "Restart MCP startup and return per-plugin logs (timeout=${timeoutMs}ms)"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.restartMcpWithLogs(tool) }
            }
    )

    handler.registerTool(
            name = "get_speech_services_config",
            descriptionGenerator = { _ ->
                "Get current TTS/STT speech services configuration"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.getSpeechServicesConfig(tool) }
            }
    )

    handler.registerTool(
            name = "set_speech_services_config",
            descriptionGenerator = { _ ->
                "Update TTS/STT speech services configuration"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.setSpeechServicesConfig(tool) }
            }
    )

    handler.registerTool(
            name = "test_tts_playback",
            descriptionGenerator = { tool ->
                val text = tool.parameters.find { it.name == "text" }?.value.orEmpty()
                val preview = text.take(24).replace('\n', ' ')
                "Play one TTS test utterance using current speech settings: $preview"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.testTtsPlayback(tool) }
            }
    )

    handler.registerTool(
            name = "list_model_configs",
            descriptionGenerator = { _ ->
                "List all model configs and current function-to-config mappings"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.listModelConfigs(tool) }
            }
    )

    handler.registerTool(
            name = "create_model_config",
            descriptionGenerator = { tool ->
                val name = tool.parameters.find { it.name == "name" }?.value ?: "New Model Config"
                "Create model config: $name"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.createModelConfig(tool) }
            }
    )

    handler.registerTool(
            name = "update_model_config",
            descriptionGenerator = { tool ->
                val configId = tool.parameters.find { it.name == "config_id" }?.value ?: ""
                "Update model config: $configId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.updateModelConfig(tool) }
            }
    )

    handler.registerTool(
            name = "delete_model_config",
            descriptionGenerator = { tool ->
                val configId = tool.parameters.find { it.name == "config_id" }?.value ?: ""
                "Delete model config: $configId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.deleteModelConfig(tool) }
            }
    )

    handler.registerTool(
            name = "list_function_model_configs",
            descriptionGenerator = { _ ->
                "List function model bindings only (function -> config_id + model_index)"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.listFunctionModelConfigs(tool) }
            }
    )

    handler.registerTool(
            name = "get_function_model_config",
            descriptionGenerator = { tool ->
                val functionType = tool.parameters.find { it.name == "function_type" }?.value ?: ""
                "Get function model config: $functionType"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.getFunctionModelConfig(tool) }
            }
    )

    handler.registerTool(
            name = "set_function_model_config",
            descriptionGenerator = { tool ->
                val functionType = tool.parameters.find { it.name == "function_type" }?.value ?: ""
                val configId = tool.parameters.find { it.name == "config_id" }?.value ?: ""
                val modelIndex = tool.parameters.find { it.name == "model_index" }?.value ?: "0"
                "Set function model config: $functionType -> $configId (model_index=$modelIndex)"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.setFunctionModelConfig(tool) }
            }
    )

    handler.registerTool(
            name = "test_model_config_connection",
            descriptionGenerator = { tool ->
                val configId = tool.parameters.find { it.name == "config_id" }?.value ?: ""
                val modelIndex = tool.parameters.find { it.name == "model_index" }?.value ?: "0"
                "Test model config connection: $configId (model_index=$modelIndex)"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.testModelConfigConnection(tool) }
            }
    )

    handler.registerTool(
            name = "list_character_cards_settings",
            descriptionGenerator = { _ ->
                "List full character card settings and the active character card"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.listCharacterCards(tool) }
            }
    )

    handler.registerTool(
            name = "get_character_card",
            descriptionGenerator = { tool ->
                val characterCardId = tool.parameters.find { it.name == "character_card_id" }?.value ?: ""
                "Get character card settings: $characterCardId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.getCharacterCard(tool) }
            }
    )

    handler.registerTool(
            name = "create_character_card",
            descriptionGenerator = { tool ->
                val name = tool.parameters.find { it.name == "name" }?.value ?: ""
                "Create character card: $name"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.createCharacterCard(tool) }
            }
    )

    handler.registerTool(
            name = "update_character_card",
            descriptionGenerator = { tool ->
                val characterCardId = tool.parameters.find { it.name == "character_card_id" }?.value ?: ""
                "Update character card: $characterCardId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.updateCharacterCard(tool) }
            }
    )

    handler.registerTool(
            name = "delete_character_card",
            descriptionGenerator = { tool ->
                val characterCardId = tool.parameters.find { it.name == "character_card_id" }?.value ?: ""
                "Delete character card: $characterCardId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.deleteCharacterCard(tool) }
            }
    )

    handler.registerTool(
            name = "set_active_character_card",
            descriptionGenerator = { tool ->
                val characterCardId = tool.parameters.find { it.name == "character_card_id" }?.value ?: ""
                "Set active character card: $characterCardId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.setActiveCharacterCard(tool) }
            }
    )

    handler.registerTool(
            name = "clear_active_character_card",
            descriptionGenerator = { _ -> "Clear the active character card" },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) { softwareSettingsTools.clearActiveCharacterCard(tool) }
            }
    )

    handler.registerTool(
            name = "import_character_card_from_tavern_json",
            descriptionGenerator = { _ -> "Import one character card from Tavern JSON" },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) {
                    softwareSettingsTools.importCharacterCardFromTavernJson(tool)
                }
            }
    )

    handler.registerTool(
            name = "export_character_card_to_tavern_json",
            descriptionGenerator = { tool ->
                val characterCardId = tool.parameters.find { it.name == "character_card_id" }?.value ?: ""
                "Export character card to Tavern JSON: $characterCardId"
            },
            executor = { tool ->
                val softwareSettingsTools = ToolGetter.getSoftwareSettingsModifyTools(context)
                runBlocking(Dispatchers.IO) {
                    softwareSettingsTools.exportCharacterCardToTavernJson(tool)
                }
            }
    )

    // 系统操作工具
    handler.registerTool(
            name = "use_package",
            descriptionGenerator = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
                s(R.string.toolreg_use_package_desc, packageName)
            },
            executor = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
                handler
                    .getOrCreatePackageManager()
                    .executeUsePackageTool(tool.name, packageName)
            }
    )

    handler.registerTool(
            name = CliToolModeSupport.SEARCH_TOOL_NAME,
            descriptionGenerator = { tool ->
                val query = tool.parameters.find { it.name == "query" }?.value ?: ""
                "Search hidden tool catalog: $query"
            },
            executor = { tool ->
                val useEnglish = isEnglishLanguage()
                val runtimeContext = ToolExecutionManager.currentToolRuntimeContext()
                if (runtimeContext?.toolExposureMode != ToolExposureMode.CLI) {
                    return@registerTool ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = CliToolModeSupport.buildCliModeUnavailableMessage(useEnglish)
                    )
                }

                val query = tool.parameters
                    .firstOrNull { it.name == "query" }
                    ?.value
                    ?.trim()
                    .orEmpty()
                if (query.isBlank()) {
                    return@registerTool buildToolErrorResult(tool, "Missing required parameter: query")
                }

                val limit = tool.parameters
                    .firstOrNull { it.name == "limit" }
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.toIntOrNull()
                    ?: CliToolModeSupport.defaultSearchLimit()

                val roleCardToolAccess = resolveCurrentRoleCardToolAccess()
                val hiddenCatalog = runBlocking {
                    CliToolModeSupport.buildHiddenToolCatalog(
                        context = context,
                        packageManager = handler.getOrCreatePackageManager(),
                        roleCardToolAccess = roleCardToolAccess,
                        useEnglish = useEnglish
                    )
                }
                val results = CliToolModeSupport.searchHiddenToolCatalog(
                    catalog = hiddenCatalog,
                    query = query,
                    limit = limit
                )
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(
                        CliToolModeSupport.formatSearchResults(query, results, useEnglish)
                    )
                )
            }
    )

    handler.registerTool(
            name = CliToolModeSupport.PROXY_TOOL_NAME,
            descriptionGenerator = { tool ->
                val targetToolName = tool.parameters.find { it.name == "tool_name" }?.value ?: ""
                "Proxy call to hidden tool: $targetToolName"
            },
            executor = { tool ->
                val useEnglish = isEnglishLanguage()
                val runtimeContext = ToolExecutionManager.currentToolRuntimeContext()
                if (runtimeContext?.toolExposureMode != ToolExposureMode.CLI) {
                    return@registerTool ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = CliToolModeSupport.buildCliModeUnavailableMessage(useEnglish)
                    )
                }

                val (parsedInvocation, parseError) = parseProxyInvocation(
                    tool = tool,
                    requireQualifiedTarget = false
                )
                if (parseError != null) {
                    return@registerTool parseError
                }
                val resolvedInvocation = parsedInvocation ?: return@registerTool buildToolErrorResult(
                    tool,
                    "Missing required parameter: tool_name"
                )

                if (CliToolModeSupport.isReservedProxyTarget(resolvedInvocation.targetToolName)) {
                    return@registerTool ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = CliToolModeSupport.buildReservedProxyTargetMessage(
                            resolvedInvocation.targetToolName,
                            useEnglish
                        )
                    )
                }

                val roleCardToolAccess = resolveCurrentRoleCardToolAccess()
                if (!isProxyTargetAllowedForRoleCard(
                        targetToolName = resolvedInvocation.targetToolName,
                        forwardedParameters = resolvedInvocation.forwardedParameters,
                        roleCardToolAccess = roleCardToolAccess
                    )
                ) {
                    return@registerTool ToolResult(
                        toolName = resolvedInvocation.targetToolName,
                        success = false,
                        result = StringResultData(""),
                        error = CliToolModeSupport.buildRoleAccessDeniedMessage(useEnglish)
                    )
                }

                executeProxyTargetWithPermissionCheck(
                    targetToolName = resolvedInvocation.targetToolName,
                    forwardedParameters = resolvedInvocation.forwardedParameters,
                    useEnglish = useEnglish
                )
            }
    )

    handler.registerTool(
            name = "package_proxy",
            descriptionGenerator = { tool ->
                val targetToolName = tool.parameters.find { it.name == "tool_name" }?.value ?: ""
                "Proxy call to package tool: $targetToolName"
            },
            executor = { tool ->
                val (parsedInvocation, parseError) = parseProxyInvocation(
                    tool = tool,
                    requireQualifiedTarget = true
                )
                if (parseError != null) {
                    return@registerTool parseError
                }
                val resolvedInvocation = parsedInvocation ?: return@registerTool buildToolErrorResult(
                    tool,
                    "Missing required parameter: tool_name"
                )
                if (resolvedInvocation.targetToolName == CliToolModeSupport.PACKAGE_PROXY_TOOL_NAME) {
                    return@registerTool buildToolErrorResult(tool, "tool_name cannot be package_proxy")
                }

                val proxiedTool = AITool(
                    name = resolvedInvocation.targetToolName,
                    parameters = resolvedInvocation.forwardedParameters
                )
                val proxiedResult = handler.executeTool(proxiedTool)
                ToolResult(
                    toolName = resolvedInvocation.targetToolName,
                    success = proxiedResult.success,
                    result = proxiedResult.result,
                    error = proxiedResult.error
                )
            }
    )

    // ADB命令执行工具

    // 计算器工具
    handler.registerTool(
            name = "calculate",
            descriptionGenerator = { tool ->
                val expression = tool.parameters.find { it.name == "expression" }?.value ?: ""
                s(R.string.toolreg_calculate_desc, expression)
            },
            executor = { tool ->
                val expression = tool.parameters.find { it.name == "expression" }?.value ?: ""
                try {
                    val result = ToolGetter.getCalculator().evalExpression(expression)
                    ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData("Calculation result: $result")
                    )
                } catch (e: Exception) {
                    ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "Calculation error: ${e.message}"
                    )
                }
            }
    )

    // Web搜索工具
    handler.registerTool(
            name = "visit_web",
            descriptionGenerator = { tool ->
                val url = tool.parameters.find { it.name == "url" }?.value
                val visitKey = tool.parameters.find { it.name == "visit_key" }?.value
                val linkNumber = tool.parameters.find { it.name == "link_number" }?.value

                when {
                    !visitKey.isNullOrBlank() && !linkNumber.isNullOrBlank() ->
                            s(
                                    R.string.toolreg_visit_web_search_link_desc,
                                    linkNumber,
                                    visitKey.take(8)
                            )
                    !url.isNullOrBlank() -> s(R.string.toolreg_visit_web_url_desc, url)
                    else -> s(R.string.toolreg_visit_web_desc)
                }
            },
            executor = { tool ->
                val webVisitTool = ToolGetter.getWebVisitTool(context)
                webVisitTool.invoke(tool)
            }
    )

    handler.registerTool(
            name = "browser_click",
            descriptionGenerator = { tool ->
                val ref = tool.parameters.find { it.name == "ref" }?.value ?: ""
                val selector = tool.parameters.find { it.name == "selector" }?.value ?: ""
                when {
                    ref.isNotBlank() -> "Click browser element ref $ref from browser_snapshot"
                    selector.isNotBlank() -> "Click browser element by selector $selector"
                    else -> "Click browser element (missing ref/selector)"
                }
            },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_close",
            descriptionGenerator = { "Close the current browser tab" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_close_all",
            descriptionGenerator = { "Close all browser tabs" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_console_messages",
            descriptionGenerator = { "Read browser console messages" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_drag",
            descriptionGenerator = { "Drag between browser element refs" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_evaluate",
            descriptionGenerator = { "Evaluate JavaScript against the current browser page" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_file_upload",
            descriptionGenerator = { "Resolve the active browser file chooser" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_fill_form",
            descriptionGenerator = { "Fill multiple browser form fields" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_handle_dialog",
            descriptionGenerator = { "Handle the current browser dialog" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_hover",
            descriptionGenerator = { "Hover a browser element by ref" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_navigate",
            descriptionGenerator = { tool ->
                val url = tool.parameters.find { it.name == "url" }?.value ?: ""
                "Navigate browser to ${url.ifBlank { "(missing url)" }}"
            },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_navigate_back",
            descriptionGenerator = { "Navigate browser back" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_network_requests",
            descriptionGenerator = { "Read browser network requests" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_press_key",
            descriptionGenerator = { tool ->
                val key = tool.parameters.find { it.name == "key" }?.value ?: ""
                "Press browser key ${key.ifBlank { "(missing key)" }}"
            },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_resize",
            descriptionGenerator = { "Resize browser viewport" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_run_code",
            descriptionGenerator = { "Run Playwright-like browser code" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_select_option",
            descriptionGenerator = { "Select options in a browser control" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_snapshot",
            descriptionGenerator = { "Capture a browser accessibility snapshot, including same-origin iframe content" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_take_screenshot",
            descriptionGenerator = { "Take a browser screenshot" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_tabs",
            descriptionGenerator = { tool ->
                val action = tool.parameters.find { it.name == "action" }?.value ?: ""
                "Manage browser tabs with action ${action.ifBlank { "(missing action)" }}"
            },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_type",
            descriptionGenerator = { "Type into a browser element by ref" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    handler.registerTool(
            name = "browser_wait_for",
            descriptionGenerator = { "Wait for browser text or time conditions" },
            executor = { tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }
    )

    // 休眠工具
    handler.registerTool(
            name = "sleep",
            descriptionGenerator = { tool ->
                val durationMs =
                        tool.parameters.find { it.name == "duration_ms" }?.value?.toIntOrNull()
                                ?: 1000
                s(R.string.toolreg_sleep_desc, durationMs)
            },
            executor = { tool ->
                val durationMs =
                        tool.parameters.find { it.name == "duration_ms" }?.value?.toIntOrNull()
                                ?: 1000

                val safeDuration = durationMs.coerceAtLeast(0)

                // Use runBlocking with Dispatchers.IO to ensure sleep happens on background thread
                runBlocking(Dispatchers.IO) {
                    delay(safeDuration.toLong())
                }

                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = SleepResultData(
                                requestedMs = durationMs,
                                sleptMs = safeDuration
                        )
                )
            }
    )

    // Intent工具
    handler.registerTool(
            name = "execute_intent",
            descriptionGenerator = { tool ->
                val action = tool.parameters.find { it.name == "action" }?.value
                val packageName = tool.parameters.find { it.name == "package" }?.value
                val component = tool.parameters.find { it.name == "component" }?.value
                val type = tool.parameters.find { it.name == "type" }?.value ?: "activity"

                when {
                    !component.isNullOrBlank() ->
                            s(R.string.toolreg_execute_intent_component_desc, component, type)
                    !packageName.isNullOrBlank() && !action.isNullOrBlank() ->
                            s(
                                    R.string.toolreg_execute_intent_action_package_desc,
                                    action,
                                    packageName,
                                    type
                            )
                    !action.isNullOrBlank() -> s(R.string.toolreg_execute_intent_action_desc, action, type)
                    else -> s(R.string.toolreg_execute_android_intent_desc, type)
                }
            },
            executor = { tool ->
                val intentTool = ToolGetter.getIntentToolExecutor(context)
                runBlocking(Dispatchers.IO) { intentTool.invoke(tool) }
            }
    )

    handler.registerTool(
            name = "send_broadcast",
            descriptionGenerator = { tool ->
                val action = tool.parameters.find { it.name == "action" }?.value
                val preview = action?.takeIf { it.isNotBlank() } ?: "(no action)"
                "Send broadcast: $preview"
            },
            executor = { tool ->
                val sendBroadcastTool = ToolGetter.getSendBroadcastToolExecutor(context)
                runBlocking(Dispatchers.IO) { sendBroadcastTool.invoke(tool) }
            }
    )

    // 设备信息工具
    handler.registerTool(
            name = "device_info",
            descriptionGenerator = { _ -> s(R.string.toolreg_device_info_desc) },
            executor = { tool ->
                val deviceInfoTool = ToolGetter.getDeviceInfoToolExecutor(context)
                deviceInfoTool.invoke(tool)
            }
    )
    
    // Tasker事件触发工具
    handler.registerTool(
            name = "trigger_tasker_event",
            descriptionGenerator = { tool ->
                val taskType = tool.parameters.find { it.name == "task_type" }?.value ?: ""
                val args = tool.parameters.filter { it.name.startsWith("arg1") }.joinToString(",")
                s(R.string.toolreg_trigger_tasker_event_desc, taskType, args)
            },
            executor = { tool ->
                val params = tool.parameters.associate { it.name to it.value }
                val taskType = params["task_type"]
                if (taskType.isNullOrBlank()) {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = s(R.string.toolreg_missing_required_param, "task_type")
                    )
                } else {
                    val args = params.filterKeys { it != "task_type" }
                    try {
                        context.triggerAIAgentAction(
                            taskType,
                            args
                        )
                        ToolResult(
                            toolName = tool.name,
                            success = true,
                            result =
                                    StringResultData(
                                            s(R.string.toolreg_tasker_event_triggered_result, taskType)
                                    )
                        )
                    } catch (e: Exception) {
                        ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error =
                                    s(
                                            R.string.toolreg_failed_trigger_tasker_event,
                                            e.message ?: ""
                                    )
                        )
                    }
                }
            }
    )

    
    // 工作流工具
    val workflowTools = ToolGetter.getWorkflowTools(context)

    // 获取所有工作流
    handler.registerTool(
            name = "get_all_workflows",
            descriptionGenerator = { _ -> s(R.string.toolreg_get_all_workflows_desc) },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.getAllWorkflows(tool) } }
    )

    // 创建工作流
    handler.registerTool(
            name = "create_workflow",
            descriptionGenerator = { tool ->
                val name = tool.parameters.find { it.name == "name" }?.value ?: ""
                s(R.string.toolreg_create_workflow_desc, name)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.createWorkflow(tool) } }
    )

    // 获取工作流详情
    handler.registerTool(
            name = "get_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                s(R.string.toolreg_get_workflow_desc, id)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.getWorkflow(tool) } }
    )

    // 更新工作流
    handler.registerTool(
            name = "update_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                val name = tool.parameters.find { it.name == "name" }?.value
                if (name != null) {
                    s(R.string.toolreg_update_workflow_with_name_desc, id, name)
                } else {
                    s(R.string.toolreg_update_workflow_desc, id)
                }
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.updateWorkflow(tool) } }
    )

    // 差异更新工作流
    handler.registerTool(
            name = "patch_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                s(R.string.toolreg_patch_workflow_desc, id)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.patchWorkflow(tool) } }
    )

    // 启用工作流
    handler.registerTool(
            name = "enable_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                s(R.string.toolreg_enable_workflow_desc, id)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.enableWorkflow(tool) } }
    )

    // 禁用工作流
    handler.registerTool(
            name = "disable_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                s(R.string.toolreg_disable_workflow_desc, id)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.disableWorkflow(tool) } }
    )

    // 删除工作流
    handler.registerTool(
            name = "delete_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                s(R.string.toolreg_delete_workflow_desc, id)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.deleteWorkflow(tool) } }
    )

    // 触发工作流执行
    handler.registerTool(
            name = "trigger_workflow",
            descriptionGenerator = { tool ->
                val id = tool.parameters.find { it.name == "workflow_id" }?.value ?: ""
                s(R.string.toolreg_trigger_workflow_desc, id)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { workflowTools.triggerWorkflow(tool) } }
    )

    // 对话管理工具
    val chatManagerTool = ToolGetter.getChatManagerTool(context)

    // 启动聊天服务
    handler.registerTool(
            name = "start_chat_service",
            descriptionGenerator = { _ -> s(R.string.toolreg_start_chat_service_desc) },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.startChatService(tool) } }
    )

    // 停止聊天服务
    handler.registerTool(
            name = "stop_chat_service",
            descriptionGenerator = { _ -> s(R.string.toolreg_stop_chat_service_desc) },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.stopChatService(tool) } }
    )

    // 新建对话
    handler.registerTool(
            name = "create_new_chat",
            descriptionGenerator = { tool ->
                val group = tool.parameters.find { it.name == "group" }?.value
                if (group.isNullOrBlank()) {
                    s(R.string.toolreg_create_new_chat_desc)
                } else {
                    s(R.string.toolreg_create_new_chat_in_group_desc, group)
                }
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.createNewChat(tool) } }
    )

    // 列出所有对话
    handler.registerTool(
            name = "list_chats",
            descriptionGenerator = { _ -> s(R.string.toolreg_list_chats_desc) },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.listChats(tool) } }
    )

    // 查找对话
    handler.registerTool(
            name = "find_chat",
            descriptionGenerator = { tool ->
                val query = tool.parameters.find { it.name == "query" }?.value ?: ""
                s(R.string.toolreg_find_chat_desc, query)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.findChat(tool) } }
    )

    // 查询对话输入状态
    handler.registerTool(
            name = "agent_status",
            descriptionGenerator = { tool ->
                val chatId = tool.parameters.find { it.name == "chat_id" }?.value ?: ""
                s(R.string.toolreg_agent_status_desc, chatId)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.agentStatus(tool) } }
    )

    // 切换对话
    handler.registerTool(
            name = "switch_chat",
            descriptionGenerator = { tool ->
                val chatId = tool.parameters.find { it.name == "chat_id" }?.value ?: ""
                s(R.string.toolreg_switch_chat_desc, chatId)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.switchChat(tool) } }
    )

    // 更新对话标题
    handler.registerTool(
            name = "update_chat_title",
            descriptionGenerator = { tool ->
                val chatId = tool.parameters.find { it.name == "chat_id" }?.value ?: ""
                s(R.string.toolreg_update_chat_title_desc, chatId)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.updateChatTitle(tool) } }
    )

    // 删除对话
    handler.registerTool(
            name = "delete_chat",
            descriptionGenerator = { tool ->
                val chatId = tool.parameters.find { it.name == "chat_id" }?.value ?: ""
                s(R.string.toolreg_delete_chat_desc, chatId)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.deleteChat(tool) } }
    )

    // 发送消息给AI
    handler.registerTool(
            name = "send_message_to_ai",
            descriptionGenerator = { tool ->
                val message = tool.parameters.find { it.name == "message" }?.value ?: ""
                val preview = if (message.length > 30) "${message.take(30)}..." else message
                s(R.string.toolreg_send_message_to_ai_desc, preview)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.sendMessageToAI(tool) } }
    )

    handler.registerTool(
            name = "send_message_to_ai_streaming",
            descriptionGenerator = { tool ->
                val message = tool.parameters.find { it.name == "message" }?.value ?: ""
                val preview = if (message.length > 30) "${message.take(30)}..." else message
                s(R.string.toolreg_send_message_to_ai_desc, preview)
            },
            executor =
                    object : ToolExecutor {
                        override fun invoke(tool: AITool): ToolResult {
                            return runBlocking(Dispatchers.IO) { chatManagerTool.sendMessageToAI(tool) }
                        }

                        override fun invokeAndStream(
                                tool: AITool
                        ): kotlinx.coroutines.flow.Flow<ToolResult> {
                            return chatManagerTool.sendMessageToAIStream(tool)
                        }
                    }
    )

    handler.registerTool(
            name = "call_chat_model",
            descriptionGenerator = { tool ->
                val functionType = tool.parameters.find { it.name == "function_type" }?.value ?: ""
                s(R.string.toolreg_call_chat_model_desc, functionType)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.callChatModel(tool) } }
    )

    // 列出所有角色卡
    handler.registerTool(
            name = "list_character_cards",
            descriptionGenerator = { _ -> s(R.string. toolreg_list_character_cards_desc) },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.listCharacterCards(tool) } }
    )

    handler.registerTool(
            name = "get_chat_messages",
            descriptionGenerator = { tool ->
                val chatId = tool.parameters.find { it.name == "chat_id" }?.value ?: ""
                val order = tool.parameters.find { it.name == "order" }?.value
                val limit = tool.parameters.find { it.name == "limit" }?.value
                val orderInfo = if (!order.isNullOrBlank()) " ($order)" else ""
                val limitInfo = if (!limit.isNullOrBlank()) " ($limit)" else ""
                s(R.string.toolreg_get_chat_messages_desc, chatId, orderInfo, limitInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.getChatMessages(tool) } }
    )

    handler.registerTool(
            name = "get_chat_messages_range",
            descriptionGenerator = { tool ->
                val chatId = tool.parameters.find { it.name == "chat_id" }?.value ?: ""
                val order = tool.parameters.find { it.name == "order" }?.value
                val start = tool.parameters.find { it.name == "start" }?.value
                val end = tool.parameters.find { it.name == "end" }?.value
                val orderInfo = if (!order.isNullOrBlank()) " ($order)" else ""
                val rangeInfo = if (!start.isNullOrBlank() && !end.isNullOrBlank()) " ($start-$end)" else ""
                s(R.string.toolreg_get_chat_messages_desc, chatId, orderInfo, rangeInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { chatManagerTool.getChatMessagesRange(tool) } }
    )

    // 文件系统工具
    val fileSystemTools = ToolGetter.getFileSystemTools(context)

    // 列出目录内容
    handler.registerTool(
            name = "list_files",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_list_files_desc, path, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.listFiles(tool) }
            }
    )

    // 读取文件内容
    handler.registerTool(
            name = "read_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_read_file_desc, path, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.readFile(tool) } }
    )

    // 按行号范围读取文件内容
    handler.registerTool(
            name = "read_file_part",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val startLine = tool.parameters.find { it.name == "start_line" }?.value ?: "1"
                val endLine = tool.parameters.find { it.name == "end_line" }?.value
                val envInfo = formatEnvInfo(environment)
                val rangeInfo =
                        if (endLine != null) {
                            s(R.string.toolreg_read_file_part_range_lines, startLine, endLine)
                        } else {
                            s(R.string.toolreg_read_file_part_range_from, startLine)
                        }
                s(R.string.toolreg_read_file_part_desc, rangeInfo, path, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.readFilePart(tool) }
            }
    )

    // 读取完整文件内容
    handler.registerTool(
            name = "read_file_full",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_read_file_full_desc, path, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.readFileFull(tool) } }
    )

    // 读取二进制文件内容（Base64编码）
    handler.registerTool(
            name = "read_file_binary",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_read_file_binary_desc, path, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.readFileBinary(tool) } }
    )

    // 写入文件
    handler.registerTool(
            name = "write_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val append = tool.parameters.find { it.name == "append" }?.value == "true"
                val envInfo = formatEnvInfo(environment)
                val operation =
                        if (append) {
                            s(R.string.toolreg_write_file_append_operation)
                        } else {
                            s(R.string.toolreg_write_file_overwrite_operation)
                        }
                s(R.string.toolreg_write_file_desc, operation, path, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.writeFile(tool) }
            }
    )

    // 写入二进制文件
    handler.registerTool(
        name = "write_file_binary",
        descriptionGenerator = { tool ->
            val path = tool.parameters.find { it.name == "path" }?.value ?: ""
            val environment = tool.parameters.find { it.name == "environment" }?.value
            val envInfo = formatEnvInfo(environment)
            s(R.string.toolreg_write_file_binary_desc, path, envInfo)
        },
        executor = { tool ->
            runBlocking(Dispatchers.IO) { fileSystemTools.writeFileBinary(tool) }
        }
    )

    // 删除文件/目录
    handler.registerTool(
            name = "delete_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val recursive = tool.parameters.find { it.name == "recursive" }?.value == "true"
                val envInfo = formatEnvInfo(environment)
                val operation =
                        if (recursive) {
                            s(R.string.toolreg_delete_file_recursive_operation)
                        } else {
                            s(R.string.toolreg_delete_file_operation)
                        }
                s(R.string.toolreg_delete_file_desc, operation, path, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.deleteFile(tool) } }
    )

    // UI自动化工具
    val uiTools = ToolGetter.getUITools(context)

    // 点击元素
    handler.registerTool(
            name = "click_element",
            descriptionGenerator = { tool ->
                val resourceId = tool.parameters.find { it.name == "resourceId" }?.value
                val className = tool.parameters.find { it.name == "className" }?.value
                val bounds = tool.parameters.find { it.name == "bounds" }?.value
                val index = tool.parameters.find { it.name == "index" }?.value ?: "0"
                val indexSuffix =
                        if (index != "0") {
                            s(R.string.toolreg_index_suffix, index)
                        } else {
                            ""
                        }

                when {
                    resourceId != null ->
                            s(R.string.toolreg_click_element_resourceid_desc, resourceId, indexSuffix)
                    className != null ->
                            s(R.string.toolreg_click_element_classname_desc, className, indexSuffix)
                    bounds != null -> s(R.string.toolreg_click_element_bounds_desc, bounds)
                    else -> s(R.string.toolreg_click_element_desc)
                }
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.clickElement(it) }
                }
            }
    )

    // 点击屏幕坐标
    handler.registerTool(
            name = "tap",
            descriptionGenerator = { tool ->
                val x = tool.parameters.find { it.name == "x" }?.value ?: "?"
                val y = tool.parameters.find { it.name == "y" }?.value ?: "?"
                s(R.string.toolreg_tap_desc, x, y)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.tap(it) }
                }
            }
    )

    handler.registerTool(
            name = "long_press",
            descriptionGenerator = { tool ->
                val x = tool.parameters.find { it.name == "x" }?.value ?: "?"
                val y = tool.parameters.find { it.name == "y" }?.value ?: "?"
                s(R.string.toolreg_long_press_desc, x, y)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.longPress(it) }
                }
            }
    )

    // HTTP请求工具
    val httpTools = ToolGetter.getHttpTools(context)

    // 发送HTTP请求
    handler.registerTool(
            name = "http_request",
            descriptionGenerator = { tool ->
                val url = tool.parameters.find { it.name == "url" }?.value ?: ""
                val method = tool.parameters.find { it.name == "method" }?.value ?: "GET"
                s(R.string.toolreg_http_request_desc, method, url)
            },
            executor =
                    object : ToolExecutor {
                        override fun invoke(tool: AITool): ToolResult {
                            return runBlocking(Dispatchers.IO) { httpTools.httpRequest(tool) }
                        }

                        override fun invokeAndStream(
                                tool: AITool
                        ): kotlinx.coroutines.flow.Flow<ToolResult> {
                            return runBlocking(Dispatchers.IO) { httpTools.httpRequestStream(tool) }
                        }
                    }
    )

    // 多部分表单请求（文件上传）
    handler.registerTool(
            name = "multipart_request",
            descriptionGenerator = { tool ->
                val url = tool.parameters.find { it.name == "url" }?.value ?: ""
                val filesParam = tool.parameters.find { it.name == "files" }?.value ?: "[]"
                val filesCount =
                        try {
                            JSONArray(filesParam).length()
                        } catch (e: Exception) {
                            0
                        }
                s(R.string.toolreg_multipart_request_desc, url, filesCount)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { httpTools.multipartRequest(tool) }
            }
    )

    // 管理Cookie工具
    handler.registerTool(
            name = "manage_cookies",
            descriptionGenerator = { tool ->
                val action =
                        tool.parameters.find { it.name == "action" }?.value?.lowercase() ?: "get"
                val domain = tool.parameters.find { it.name == "domain" }?.value ?: ""
                when (action) {
                    "get" ->
                            if (domain.isBlank()) {
                                s(R.string.toolreg_manage_cookies_get_all_desc)
                            } else {
                                s(R.string.toolreg_manage_cookies_get_domain_desc, domain)
                            }
                    "set" -> s(R.string.toolreg_manage_cookies_set_domain_desc, domain)
                    "clear" ->
                            if (domain.isBlank()) {
                                s(R.string.toolreg_manage_cookies_clear_all_desc)
                            } else {
                                s(R.string.toolreg_manage_cookies_clear_domain_desc, domain)
                            }
                    else -> s(R.string.toolreg_manage_cookies_desc, action)
                }
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { httpTools.manageCookies(tool) } }
    )

    // 检查文件是否存在
    handler.registerTool(
            name = "file_exists",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_file_exists_desc, path, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.fileExists(tool) }
            }
    )

    // 移动/重命名文件或目录
    handler.registerTool(
            name = "move_file",
            descriptionGenerator = { tool ->
                val source = tool.parameters.find { it.name == "source" }?.value ?: ""
                val destination = tool.parameters.find { it.name == "destination" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_move_file_desc, source, destination, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.moveFile(tool) } }
    )

    // 复制文件或目录
    handler.registerTool(
            name = "copy_file",
            descriptionGenerator = { tool ->
                val source = tool.parameters.find { it.name == "source" }?.value ?: ""
                val destination = tool.parameters.find { it.name == "destination" }?.value ?: ""
                val sourceEnv = tool.parameters.find { it.name == "source_environment" }?.value
                val destEnv = tool.parameters.find { it.name == "dest_environment" }?.value
                val environment = tool.parameters.find { it.name == "environment" }?.value

                // 确定源和目标环境
                val srcEnv = sourceEnv ?: environment ?: "android"
                val dstEnv = destEnv ?: environment ?: "android"

                val envInfo = formatEnvArrowInfo(srcEnv, dstEnv)
                s(R.string.toolreg_copy_file_desc, source, destination, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.copyFile(tool) } }
    )

    // 创建目录
    handler.registerTool(
            name = "make_directory",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_make_directory_desc, path, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.makeDirectory(tool) }
            }
    )

    // 搜索文件
    handler.registerTool(
            name = "find_files",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val pattern = tool.parameters.find { it.name == "pattern" }?.value ?: "*"
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_find_files_desc, path, pattern, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.findFiles(tool) }
            }
    )

    // 获取文件信息
    handler.registerTool(
            name = "file_info",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_file_info_desc, path, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.fileInfo(tool) } }
    )

    // 智能应用文件绑定
    handler.registerTool(
            name = "apply_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_apply_file_desc, path, envInfo)
            },
            executor =
                    object : ToolExecutor {
                        override fun invoke(tool: AITool): ToolResult {
                            return runBlocking { fileSystemTools.applyFile(tool).last() }
                        }

                        override fun invokeAndStream(
                                tool: AITool
                        ): kotlinx.coroutines.flow.Flow<ToolResult> {
                            return fileSystemTools.applyFile(tool)
                        }
                    }
    )

    handler.registerTool(
            name = "create_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                "Create file $path$envInfo"
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.createFile(tool) } }
    )

    handler.registerTool(
            name = "edit_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                "Edit file $path$envInfo"
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.editFile(tool) } }
    )

    // 压缩文件/目录
    handler.registerTool(
            name = "zip_files",
            descriptionGenerator = { tool ->
                val source = tool.parameters.find { it.name == "source" }?.value ?: ""
                val destination = tool.parameters.find { it.name == "destination" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_zip_files_desc, source, destination, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.zipFiles(tool) } }
    )

    // 解压缩文件
    handler.registerTool(
            name = "unzip_files",
            descriptionGenerator = { tool ->
                val source = tool.parameters.find { it.name == "source" }?.value ?: ""
                val destination = tool.parameters.find { it.name == "destination" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_unzip_files_desc, source, destination, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.unzipFiles(tool) }
            }
    )

    // 打开文件
    handler.registerTool(
            name = "open_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_open_file_desc, path, envInfo)
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { fileSystemTools.openFile(tool) } }
    )

    // 分享文件
    handler.registerTool(
            name = "share_file",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_share_file_desc, path, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.shareFile(tool) }
            }
    )

    // Grep代码搜索
    handler.registerTool(
            name = "grep_code",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val pattern = tool.parameters.find { it.name == "pattern" }?.value ?: ""
                val filePattern = tool.parameters.find { it.name == "file_pattern" }?.value
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                if (filePattern != null && filePattern != "*") {
                    s(R.string.toolreg_grep_code_with_file_pattern_desc, path, pattern, envInfo, filePattern)
                } else {
                    s(R.string.toolreg_grep_code_desc, path, pattern, envInfo)
                }
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.grepCode(tool) }
            }
    )

    // Grep上下文搜索
    handler.registerTool(
            name = "grep_context",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                val intent = tool.parameters.find { it.name == "intent" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                val preview = if (intent.length > 40) "${intent.take(40)}..." else intent
                s(R.string.toolreg_grep_context_desc, path, preview, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.grepContext(tool) }
            }
    )

    // 下载文件
    handler.registerTool(
            name = "download_file",
            descriptionGenerator = { tool ->
                val url = tool.parameters.find { it.name == "url" }?.value ?: ""
                val destination = tool.parameters.find { it.name == "destination" }?.value ?: ""
                val environment = tool.parameters.find { it.name == "environment" }?.value
                val envInfo = formatEnvInfo(environment)
                s(R.string.toolreg_download_file_desc, url, destination, envInfo)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { fileSystemTools.downloadFile(tool) }
            }
    )

    // 系统操作工具
    val systemOperationTools = ToolGetter.getSystemOperationTools(context)

    handler.registerTool(
            name = "toast",
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.toast(tool) }
            }
    )

    handler.registerTool(
            name = "send_notification",
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.sendNotification(tool) }
            }
    )

    // 修改系统设置
    handler.registerTool(
            name = "modify_system_setting",
            descriptionGenerator = { tool ->
                val key = tool.parameters.find { it.name == "key" }?.value ?: ""
                val value = tool.parameters.find { it.name == "value" }?.value ?: ""
                s(R.string.toolreg_modify_system_setting_desc, key, value)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.modifySystemSetting(tool) }
            }
    )

    // 获取系统设置
    handler.registerTool(
            name = "get_system_setting",
            descriptionGenerator = { tool ->
                val key = tool.parameters.find { it.name == "key" }?.value ?: ""
                s(R.string.toolreg_get_system_setting_desc, key)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.getSystemSetting(tool) }
            }
    )

    // 安装应用
    handler.registerTool(
            name = "install_app",
            descriptionGenerator = { tool ->
                val path = tool.parameters.find { it.name == "path" }?.value ?: ""
                s(R.string.toolreg_install_app_desc, path)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.installApp(tool) }
            }
    )

    // 卸载应用
    handler.registerTool(
            name = "uninstall_app",
            descriptionGenerator = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
                s(R.string.toolreg_uninstall_app_desc, packageName)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.uninstallApp(tool) }
            }
    )

    // 获取已安装应用列表
    handler.registerTool(
            name = "list_installed_apps",
            descriptionGenerator = { _ -> s(R.string.toolreg_list_installed_apps_desc) },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.listInstalledApps(tool) }
            }
    )

    // 启动应用
    handler.registerTool(
            name = "start_app",
            descriptionGenerator = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
                s(R.string.toolreg_start_app_desc, packageName)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.startApp(tool) }
            }
    )

    // 停止应用
    handler.registerTool(
            name = "stop_app",
            descriptionGenerator = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
                s(R.string.toolreg_stop_app_desc, packageName)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.stopApp(tool) }
            }
    )

    // 获取设备通知
    handler.registerTool(
            name = "get_notifications",
            descriptionGenerator = { tool ->
                val limit = tool.parameters.find { it.name == "limit" }?.value ?: "10"
                val includeOngoing =
                        tool.parameters.find { it.name == "include_ongoing" }?.value == "true"

                if (includeOngoing) {
                    s(R.string.toolreg_get_notifications_desc_with_ongoing, limit)
                } else {
                    s(R.string.toolreg_get_notifications_desc, limit)
                }
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.getNotifications(tool) }
            }
    )

    // 获取应用使用时长
    handler.registerTool(
            name = "get_app_usage_time",
            descriptionGenerator = { tool ->
                val packageName = tool.parameters.find { it.name == "package_name" }?.value.orEmpty()
                val sinceHours = tool.parameters.find { it.name == "since_hours" }?.value ?: "24"
                if (packageName.isNotBlank()) {
                    "Get app usage time for $packageName in the last ${sinceHours} hours"
                } else {
                    "Get app usage time ranking in the last ${sinceHours} hours"
                }
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.getAppUsageTime(tool) }
            }
    )

    // 获取设备位置
    handler.registerTool(
            name = "get_device_location",
            descriptionGenerator = { tool ->
                val highAccuracy =
                        tool.parameters.find { it.name == "high_accuracy" }?.value == "true"
                if (highAccuracy) {
                    s(R.string.toolreg_get_device_location_high_accuracy_desc)
                } else {
                    s(R.string.toolreg_get_device_location_desc)
                }
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.getDeviceLocation(tool) }
            }
    )

    handler.registerTool(
            name = "request_bluetooth_permission",
            descriptionGenerator = { _ -> "Request Bluetooth nearby devices permission" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.requestBluetoothPermission(tool) }
            }
    )

    handler.registerTool(
            name = "get_bluetooth_state",
            descriptionGenerator = { _ -> "Get Bluetooth adapter state" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.getBluetoothState(tool) }
            }
    )

    handler.registerTool(
            name = "request_enable_bluetooth",
            descriptionGenerator = { _ -> "Open the system dialog to enable Bluetooth" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.requestEnableBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "list_bluetooth_bonded_devices",
            descriptionGenerator = { _ -> "List bonded Bluetooth devices" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.listBluetoothBondedDevices(tool) }
            }
    )

    handler.registerTool(
            name = "scan_bluetooth_devices",
            descriptionGenerator = { _ -> "Scan nearby Bluetooth classic and BLE devices" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.scanBluetoothDevices(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_connect",
            descriptionGenerator = { tool ->
                val address = tool.parameters.find { it.name == "address" }?.value ?: ""
                "Connect to Bluetooth classic device $address"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.connectBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_listen",
            descriptionGenerator = { _ -> "Listen for an incoming Bluetooth classic connection" },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.listenBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_accept",
            descriptionGenerator = { tool ->
                val listenerId = tool.parameters.find { it.name == "listener_session_id" }?.value ?: ""
                "Accept an incoming Bluetooth classic connection from listener $listenerId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.acceptBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_send",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                "Send data to Bluetooth session $sessionId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.sendBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_read",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                "Read data from Bluetooth session $sessionId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.readBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_send_and_read",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                "Send data and read response from Bluetooth session $sessionId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.sendAndReadBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_close",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                "Close Bluetooth session $sessionId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.closeBluetooth(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_connect",
            descriptionGenerator = { tool ->
                val address = tool.parameters.find { it.name == "address" }?.value ?: ""
                "Connect to BLE device $address"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.connectBle(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_discover_services",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                "Discover BLE services for session $sessionId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.discoverBleServices(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_read_characteristic",
            descriptionGenerator = { tool ->
                val characteristicUuid = tool.parameters.find { it.name == "characteristic_uuid" }?.value ?: ""
                "Read BLE characteristic $characteristicUuid"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.readBleCharacteristic(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_write_characteristic",
            descriptionGenerator = { tool ->
                val characteristicUuid = tool.parameters.find { it.name == "characteristic_uuid" }?.value ?: ""
                "Write BLE characteristic $characteristicUuid"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.writeBleCharacteristic(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_write_and_read_characteristic",
            descriptionGenerator = { tool ->
                val writeCharacteristicUuid = tool.parameters.find { it.name == "write_characteristic_uuid" }?.value ?: ""
                val readCharacteristicUuid = tool.parameters.find { it.name == "read_characteristic_uuid" }?.value ?: ""
                "Write BLE characteristic $writeCharacteristicUuid and read $readCharacteristicUuid"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.writeAndReadBleCharacteristic(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_subscribe_characteristic",
            descriptionGenerator = { tool ->
                val characteristicUuid = tool.parameters.find { it.name == "characteristic_uuid" }?.value ?: ""
                "Subscribe BLE characteristic $characteristicUuid"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.subscribeBleCharacteristic(tool) }
            }
    )

    handler.registerTool(
            name = "bluetooth_ble_read_notifications",
            descriptionGenerator = { tool ->
                val sessionId = tool.parameters.find { it.name == "session_id" }?.value ?: ""
                "Read BLE notifications from session $sessionId"
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) { systemOperationTools.readBleNotifications(tool) }
            }
    )

    // 获取当前页面/窗口信息
    handler.registerTool(
            name = "get_page_info",
            descriptionGenerator = { _ -> s(R.string.toolreg_get_page_info_desc) },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.getPageInfo(it) }
                }
            }
    )

    handler.registerTool(
            name = "capture_screenshot",
            descriptionGenerator = { _ -> s(R.string.toolreg_capture_screenshot_desc) },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(
                        tool = tool,
                        showStatusIndicator = false,
                        delayMs = 200
                    ) { t ->
                        val (path, _) = uiTools.captureScreenshot(t)
                        if (path.isNullOrBlank()) {
                            ToolResult(toolName = t.name, success = false, result = StringResultData(""), error = "Screenshot failed")
                        } else {
                            ToolResult(toolName = t.name, success = true, result = StringResultData(path), error = null)
                        }
                    }
                }
            }
    )

    handler.registerTool(
            name = "run_ui_subagent",
            descriptionGenerator = { tool ->
                val intent = tool.parameters.find { it.name == "intent" }?.value ?: ""
                val maxSteps = tool.parameters.find { it.name == "max_steps" }?.value ?: "20"
                val agentId = tool.parameters.find { it.name == "agent_id" }?.value
                buildString {
                    append(s(R.string.toolreg_run_ui_subagent_desc, intent, maxSteps))
                    if (!agentId.isNullOrBlank()) {
                        append(s(R.string.toolreg_agent_id_suffix, agentId))
                    }
                    append(s(R.string.toolreg_run_ui_subagent_hint))
                }
            },
            executor = { tool -> runBlocking(Dispatchers.IO) { uiTools.runUiSubAgent(tool) } }
    )

    // 在输入框中设置文本
    handler.registerTool(
            name = "set_input_text",
            descriptionGenerator = { tool ->
                val text = tool.parameters.find { it.name == "text" }?.value ?: ""
                s(R.string.toolreg_set_input_text_desc, text)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.setInputText(it) }
                }
            }
    )

    // 按下特定按键
    handler.registerTool(
            name = "press_key",
            descriptionGenerator = { tool ->
                val keyCode = tool.parameters.find { it.name == "key_code" }?.value ?: ""
                s(R.string.toolreg_press_key_desc, keyCode)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.pressKey(it) }
                }
            }
    )

    // 执行滑动手势
    handler.registerTool(
            name = "swipe",
            descriptionGenerator = { tool ->
                val startX = tool.parameters.find { it.name == "start_x" }?.value ?: "?"
                val startY = tool.parameters.find { it.name == "start_y" }?.value ?: "?"
                val endX = tool.parameters.find { it.name == "end_x" }?.value ?: "?"
                val endY = tool.parameters.find { it.name == "end_y" }?.value ?: "?"
                s(R.string.toolreg_swipe_desc, startX, startY, endX, endY)
            },
            executor = { tool ->
                runBlocking(Dispatchers.IO) {
                    executeUiToolWithVisibility(tool) { uiTools.swipe(it) }
                }
            }
    )

    // FFmpeg工具 - 执行通用FFmpeg命令
    handler.registerTool(
            name = "ffmpeg_execute",
            descriptionGenerator = { tool ->
                val command = tool.parameters.find { it.name == "command" }?.value ?: ""
                s(R.string.toolreg_ffmpeg_execute_desc, command)
            },
            executor = { tool ->
                val ffmpegTool = ToolGetter.getFFmpegToolExecutor(context)
                ffmpegTool.invoke(tool)
            }
    )

    // FFmpeg信息工具 - 获取FFmpeg信息
    handler.registerTool(
            name = "ffmpeg_info",
            descriptionGenerator = { _ -> s(R.string.toolreg_ffmpeg_info_desc) },
            executor = { tool ->
                val ffmpegInfoTool = ToolGetter.getFFmpegInfoToolExecutor()
                ffmpegInfoTool.invoke(tool)
            }
    )

    // FFmpeg视频转换工具 - 简化的视频转换接口
    handler.registerTool(
            name = "ffmpeg_convert",
            descriptionGenerator = { tool ->
                val inputPath = tool.parameters.find { it.name == "input_path" }?.value ?: ""
                val outputPath = tool.parameters.find { it.name == "output_path" }?.value ?: ""
                s(R.string.toolreg_ffmpeg_convert_desc, inputPath, outputPath)
            },
            executor = { tool ->
                val ffmpegConvertTool = ToolGetter.getFFmpegConvertToolExecutor(context)
                ffmpegConvertTool.invoke(tool)
            }
    )
}
