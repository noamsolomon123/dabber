package com.dabber.npu

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Decode-only Whisper tokenizer for the HF `tokenizer.json` (ivrit-ai
 * whisper-large-v3-turbo), parsed with org.json.
 *
 * It rebuilds the id -> token-string map from `model.vocab` + `added_tokens`, then
 * reverses GPT-2 byte-level BPE: each token character is mapped back to its original
 * byte via the standard bytes-to-unicode table, the bytes are assembled, and decoded
 * as UTF-8. Special tokens (`<|...|>`, ids >= 50257) are skipped.
 *
 * Only [decode] is implemented — generation is greedy argmax in [OnnxWhisperEngine],
 * which seeds the prompt with the resolved special ids exposed here.
 */
object WhisperTokenizer {

    @Volatile
    private var loaded = false

    private val idToToken = HashMap<Int, String>(64000)
    private val tokenToId = HashMap<String, Int>(4096)
    private val specialIds = HashSet<Int>()
    private val byteDecoder = HashMap<Char, Int>(256)

    // Resolved-by-string special ids (valid after [ensureLoaded]).
    var sot = -1; private set
    var eot = -1; private set
    var transcribe = -1; private set
    var notimestamps = -1; private set
    var he = -1; private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            load(context)
            loaded = true
        }
    }

    private fun load(context: Context) {
        buildByteDecoder()

        val text = context.assets.open("npu/tokenizer.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)

        // Base BPE vocab: token-string -> id.
        val vocab = root.getJSONObject("model").getJSONObject("vocab")
        val keys = vocab.keys()
        while (keys.hasNext()) {
            val tok = keys.next()
            idToToken[vocab.getInt(tok)] = tok
        }

        // Added tokens (the `<|...|>` specials).
        val added = root.optJSONArray("added_tokens")
        if (added != null) {
            for (i in 0 until added.length()) {
                val o = added.getJSONObject(i)
                val id = o.getInt("id")
                val content = o.getString("content")
                idToToken[id] = content
                tokenToId[content] = id
                if (o.optBoolean("special", false) ||
                    (content.startsWith("<|") && content.endsWith("|>"))
                ) {
                    specialIds.add(id)
                }
            }
        }

        sot = tokenToId["<|startoftranscript|>"] ?: -1
        eot = tokenToId["<|endoftext|>"] ?: -1
        transcribe = tokenToId["<|transcribe|>"] ?: -1
        notimestamps = tokenToId["<|notimestamps|>"] ?: -1
        he = tokenToId["<|he|>"] ?: -1
    }

    /** Resolve a language special id, e.g. lang="he" -> id of `<|he|>`; falls back to he. */
    fun langToken(lang: String): Int = tokenToId["<|$lang|>"] ?: he

    /**
     * Decodes generated ids to text. Special tokens are skipped; remaining token strings
     * are concatenated and reversed through the byte-level table back to UTF-8 bytes.
     */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in specialIds) continue
            val tok = idToToken[id] ?: continue
            sb.append(tok)
        }
        val bytes = ByteArrayOutputStream(sb.length)
        for (c in sb) {
            val b = byteDecoder[c] ?: continue
            bytes.write(b)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Standard GPT-2 bytes<->unicode table (inverse direction): printable code points are
     * mapped to themselves; the remaining bytes are shifted into the 256+ range so every
     * byte becomes a single visible char. [byteDecoder] is the char -> byte inverse.
     */
    private fun buildByteDecoder() {
        val bs = ArrayList<Int>()
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in '¡'.code..'¬'.code) bs.add(b)
        for (b in '®'.code..'ÿ'.code) bs.add(b)
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (!bs.contains(b)) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        byteDecoder.clear()
        for (i in bs.indices) {
            byteDecoder[cs[i].toChar()] = bs[i]
        }
    }
}
