package com.example.generator

import org.json.JSONArray
import org.json.JSONObject

object NumberGenerator {

    data class GenerationResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val numbers: List<String> = emptyList(),
        val totalCount: Int = 0,
        val duplicateCount: Int = 0
    )

    fun generateNumbers(
        prefix: String,
        startSuffix: String,
        endSuffix: String,
        maxLimit: Int = 1000
    ): GenerationResult {
        val cleanPrefix = prefix.trim()
        if (cleanPrefix.isBlank()) {
            return GenerationResult(success = false, errorMessage = "Prefix series cannot be empty.")
        }
        // Validate prefix format: digits and optional leading plus
        if (!cleanPrefix.matches(Regex("""^\+?[0-9]{3,12}$"""))) {
            return GenerationResult(
                success = false,
                errorMessage = "Malformed prefix. Must contain 3 to 12 digits (with optional leading '+')."
            )
        }

        // Validate 4-digit start and end
        if (!startSuffix.matches(Regex("""^[0-9]{4}$"""))) {
            return GenerationResult(
                success = false,
                errorMessage = "Start suffix must be exactly 4 digits (0000-9999)."
            )
        }
        if (!endSuffix.matches(Regex("""^[0-9]{4}$"""))) {
            return GenerationResult(
                success = false,
                errorMessage = "End suffix must be exactly 4 digits (0000-9999)."
            )
        }

        val startVal = startSuffix.toInt()
        val endVal = endSuffix.toInt()

        if (startVal >= endVal) {
            return GenerationResult(
                success = false,
                errorMessage = "Start suffix ($startSuffix) must be strictly less than end suffix ($endSuffix)."
            )
        }

        val expectedCount = endVal - startVal
        if (expectedCount > maxLimit) {
            return GenerationResult(
                success = false,
                errorMessage = "Requested range ($expectedCount numbers) exceeds the maximum limit of $maxLimit."
            )
        }

        // Start-inclusive, end-exclusive loop preserving leading zeros
        val uniqueNumbers = LinkedHashSet<String>()
        var duplicates = 0

        for (i in startVal until endVal) {
            val suffixString = String.format(java.util.Locale.US, "%04d", i)
            val fullNumber = cleanPrefix + suffixString
            if (!uniqueNumbers.add(fullNumber)) {
                duplicates++
            }
        }

        val list = uniqueNumbers.toList()
        return GenerationResult(
            success = true,
            numbers = list,
            totalCount = list.size,
            duplicateCount = duplicates
        )
    }

    fun resultToJson(result: GenerationResult): JSONObject {
        val obj = JSONObject()
        obj.put("success", result.success)
        if (!result.success) {
            obj.put("errorMessage", result.errorMessage ?: "Unknown error")
        } else {
            obj.put("totalCount", result.totalCount)
            obj.put("duplicateCount", result.duplicateCount)
            val arr = JSONArray()
            result.numbers.forEach { arr.put(it) }
            obj.put("numbers", arr)
        }
        return obj
    }
}
