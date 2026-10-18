package varro.server

import com.google.gson.JsonObject
import varro.protocol.*
import java.util.Base64

internal object NativePdfInput {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_FILENAME = 512
    const val MIME = "application/pdf"

    fun size(url: String?): Int? {
        val encoded = url?.takeIf { it.startsWith("data:$MIME;base64,") }?.substringAfter(',') ?: return null
        if (encoded.isEmpty() || encoded.length > (MAX_BYTES + 2) / 3 * 4 || encoded.length % 4 != 0 ||
            !Regex("[A-Za-z0-9+/]+={0,2}").matches(encoded)) return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        return bytes.size.takeIf { it <= MAX_BYTES && isPdf(bytes) }
    }

    fun isPdf(bytes: ByteArray) = bytes.size >= 5 && bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())

    fun validate(parts: List<JsonObject>, action: String, input: JsonObject): Boolean {
        val pdfs = parts.filter { it.str("type") == "file" && it.str("mime") == MIME }
        if (pdfs.isEmpty()) return false
        var total = 0L
        for (pdf in pdfs) {
            val size = size(pdf.str("url"))
            require(size != null && pdf.str("filename")?.length in 1..MAX_FILENAME) { "Invalid PDF attachment. Reattach the PDF and try again." }
            total += size
        }
        require(total <= MAX_BYTES) { "PDFs must total 20 MiB or less." }
        require(action != "message" || (!input.hasNonNull("system") && !input.hasNonNull("format"))) { "PDF attachments are not supported by one-shot generation." }
        require(action != "command") { "PDF input support cannot be verified for configured commands. Send the PDF as a regular prompt with a PDF-capable model." }
        return true
    }

    fun requireSupported(model: JsonObject?) {
        val input = model.obj("capabilities")?.get("input")
        val supported = when {
            input?.isJsonArray == true && input.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } -> "pdf" in input.asJsonArray.strings()
            input.asObjectOrNull().bool("pdf") != null -> input.asObjectOrNull().bool("pdf")
            else -> null
        }
        require(supported != null) { "PDF input support is unknown for the selected model or backend. Refresh the model catalog or choose a model that advertises PDF input." }
        require(supported) { "The selected model does not support PDF input. Choose a model that advertises PDF input or remove the PDF." }
    }
}
