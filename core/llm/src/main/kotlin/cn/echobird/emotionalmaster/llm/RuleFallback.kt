package cn.echobird.emotionalmaster.llm

import cn.echobird.emotionalmaster.model.ReplySuggestion
import cn.echobird.emotionalmaster.model.ReplySuggestionSet

class RuleFallback {
    fun suggestions(): ReplySuggestionSet = ReplySuggestionSet.of(
        listOf(
            suggestion(
                text = "听起来这件事对你挺重要的，愿意多说一点吗？",
                strategy = "共情倾听",
                intent = "先确认对方感受并邀请补充",
                risk = "避免替对方定义情绪",
                applicableWhen = "对方正在分享经历或情绪时",
            ),
            suggestion(
                text = "我明白了。你现在更希望我听你说，还是一起想办法？",
                strategy = "需求确认",
                intent = "确认对方期待的回应方式",
                risk = "不要连续追问",
                applicableWhen = "不确定对方需要陪伴还是建议时",
            ),
            suggestion(
                text = "谢谢你告诉我这些，我会认真听。",
                strategy = "稳定回应",
                intent = "提供低压力的接纳信号",
                risk = "表达可能略显保守",
                applicableWhen = "信息不足或关系阶段不明确时",
            ),
            suggestion(
                text = "你先按自己的节奏来，想聊的时候我都在。",
                strategy = "留出空间",
                intent = "尊重边界并降低回复压力",
                risk = "对方可能暂时结束话题",
                applicableWhen = "对方回复简短或表现出疲惫时",
            ),
        ),
    )

    private fun suggestion(
        text: String,
        strategy: String,
        intent: String,
        risk: String,
        applicableWhen: String,
    ) = ReplySuggestion(text, strategy, intent, risk, applicableWhen)
}
