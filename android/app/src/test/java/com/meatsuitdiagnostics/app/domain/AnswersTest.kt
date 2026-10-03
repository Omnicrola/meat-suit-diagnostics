package com.meatsuitdiagnostics.app.domain

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnswersTest {
    private val bp = QuestionSpec.Numeric(
        listOf(
            NumericField("systolic", "Systolic", "mmHg", min = 50.0, max = 250.0),
            NumericField("temp", "Temperature", "C", min = 30.0, max = 45.0, decimals = 1),
        )
    )

    private fun config(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun parsesServerConfigs() {
        val scale = parseSpec("scale", config("""{"min":1,"max":10,"step":1,"min_label":"Wide awake","max_label":null}"""))
        assertEquals(QuestionSpec.Scale(1, 10, 1, "Wide awake", null), scale)
        assertEquals(QuestionSpec.Time, parseSpec("time", config("{}")))
        assertEquals(QuestionSpec.YesNo("Yes", "No"), parseSpec("boolean", config("""{"true_label":"Yes","false_label":"No"}""")))
    }

    @Test
    fun unknownTypeOrBadConfigIsUnsupported() {
        assertEquals(QuestionSpec.Unsupported("photo"), parseSpec("photo", config("{}")))
        assertEquals(QuestionSpec.Unsupported("scale"), parseSpec("scale", config("""{"min":"one"}""")))
    }

    @Test
    fun jsonMatchesServerFormat() {
        assertEquals("7", Answer.ScaleValue(7).toJson().toString())
        assertEquals("true", Answer.YesNoValue(true).toJson().toString())
        assertEquals("\"07:05\"", Answer.TimeValue(7, 5).toJson().toString())
        assertEquals("""["a","c"]""", Answer.MultiValue(listOf("a", "c")).toJson().toString())
        val numeric = Answer.NumericValue(mapOf("systolic" to BigDecimal("120.0"), "temp" to BigDecimal("36.60")))
        assertEquals("""{"systolic":120,"temp":36.6}""", numeric.toJson().toString())
    }

    @Test
    fun largeWholeNumbersAreNotScientific() {
        val numeric = Answer.NumericValue(mapOf("steps" to BigDecimal("12000")))
        assertEquals("""{"steps":12000}""", numeric.toJson().toString())
    }

    @Test
    fun roundTripsThroughJson() {
        val answers = listOf(
            QuestionSpec.Scale(1, 10) to Answer.ScaleValue(4),
            QuestionSpec.Text() to Answer.TextValue("Toast"),
            QuestionSpec.Time to Answer.TimeValue(23, 15),
            bp to Answer.NumericValue(mapOf("systolic" to BigDecimal("121"), "temp" to BigDecimal("36.6"))),
            QuestionSpec.MultiSelect(listOf(Option("a", "A"), Option("b", "B"))) to Answer.MultiValue(listOf("b")),
        )
        for ((spec, answer) in answers) {
            val back = answerFromJson(spec, answer.toJson())
            assertEquals(answer.toJson(), back?.toJson())
        }
    }

    @Test
    fun validation() {
        val scale = QuestionSpec.Scale(0, 100, step = 5)
        assertNull(scale.validate(Answer.ScaleValue(35)))
        assertNotNull(scale.validate(Answer.ScaleValue(33)))
        assertNotNull(scale.validate(Answer.ScaleValue(105)))
        assertNotNull(scale.validate(Answer.TextValue("35")))

        assertNotNull(QuestionSpec.Text(maxLength = 3).validate(Answer.TextValue("toolong")))
        assertNotNull(QuestionSpec.Text().validate(Answer.TextValue("   ")))

        val multi = QuestionSpec.MultiSelect(listOf(Option("a", "A"), Option("b", "B")), min = 1)
        assertNotNull(multi.validate(Answer.MultiValue(emptyList())))
        assertNotNull(multi.validate(Answer.MultiValue(listOf("z"))))
        assertNull(multi.validate(Answer.MultiValue(listOf("a", "b"))))
    }

    @Test
    fun numericInput() {
        val systolic = bp.fields[0]
        val temp = bp.fields[1]
        assertEquals(NumericInput(null, null), parseNumericInput(systolic, " "))
        assertEquals(BigDecimal("120"), parseNumericInput(systolic, "120").value)
        assertEquals("Whole numbers only", parseNumericInput(systolic, "120.5").error)
        assertEquals("At most 250", parseNumericInput(systolic, "300").error)
        assertEquals("Not a number", parseNumericInput(systolic, "12a").error)
        assertEquals(BigDecimal("36.6"), parseNumericInput(temp, "36,6").value) // comma decimal separator
        assertEquals("At most 1 decimal places", parseNumericInput(temp, "36.65").error)
        assertNull(parseNumericInput(temp, "36.60").error) // trailing zero is fine
    }
}
