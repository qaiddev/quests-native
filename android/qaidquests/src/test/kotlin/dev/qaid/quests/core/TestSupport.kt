package dev.qaid.quests.core

import java.io.IOException

/** A scripted server: records every request and answers through [respond]. */
class FakeTransport(var respond: (HttpRequest) -> HttpResponse = ::defaultResponse) : QuestTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return respond(request)
    }

    fun patches(): List<String> = requests.filter { it.method == "PATCH" }.map { it.body.orEmpty() }
    fun count(method: String, suffix: String = ""): Int = requests.count { it.method == method && it.url.endsWith(suffix) }

    companion object {
        fun defaultResponse(request: HttpRequest): HttpResponse = when {
            request.method == "GET" && request.url.endsWith("/definition") -> HttpResponse(200, SAMPLE_QUEST)
            request.method == "GET" -> HttpResponse(200, """{"preset":"pill","mode":"dark","tokens":{"--qaid-q-accent":"#ff0000"}}""")
            request.method == "POST" && request.url.endsWith("/responses") -> HttpResponse(201, """{"id":"r1"}""")
            request.method == "PATCH" -> HttpResponse(200, """{"ok":true}""")
            else -> HttpResponse(200, """{"ok":true}""")
        }

        val offline: (HttpRequest) -> HttpResponse = { throw IOException("offline") }
    }
}

const val SAMPLE_QUEST = """
{
  "id": "quest1", "slug": "s", "versionId": "v1", "versionNumber": 2,
  "title": "Tell us", "description": "Two minutes",
  "questions": [
    {"id": "role", "label": "Your role", "type": "multiple-choice", "required": true,
     "options": [{"value": "dev", "label": "Developer"}, {"value": "pm", "label": "PM", "description": "Product"}]},
    {"id": "why", "label": "Why?", "type": "text", "visibleIf": {"questionId": "role", "equals": "dev"}},
    {"id": "score", "label": "Score", "type": "range", "min": 0, "max": 10}
  ]
}
"""

fun quest(vararg questions: Question, configure: (Questionnaire) -> Questionnaire = { it }) =
    configure(Questionnaire(id = "q", questions = questions.toList()))

fun text(id: String, required: Boolean = false, rule: VisibilityRule? = null, minLength: Int? = null) =
    TextQuestion(id, id, required = required, visibleIf = rule, minLength = minLength)

fun choice(id: String, vararg values: String, multiple: Boolean = false, required: Boolean = false, rule: VisibilityRule? = null) =
    ChoiceQuestion(id, id, required = required, visibleIf = rule, options = values.map { ChoiceOption(it, it) }, multiple = multiple)

/** Records what a session asks of the response traffic. */
class RecordingEffects : QuestSession.Effects {
    val saves = mutableListOf<Triple<String, QuestAnswer, Boolean>>()
    var flushes = 0
    override fun save(questionId: String, value: QuestAnswer, immediate: Boolean) {
        saves += Triple(questionId, value, immediate)
    }

    override fun flush() {
        flushes++
    }
}
