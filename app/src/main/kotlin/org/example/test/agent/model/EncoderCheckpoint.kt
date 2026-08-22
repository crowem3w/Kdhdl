package org.example.test.agent.model

import java.io.File

/**
 * Persists a [CompactLatentStateEncoder]'s GRU weights and vectorizer
 * normalizer stats to a flat text file, and restores them - design doc
 * §7.3's "replay buffer, ensemble weights, online model parameters... must
 * persist to disk/DB. A crash or redeploy must not wipe learning progress"
 * applied to this specific model, plus the mechanism by which weights
 * produced by [org.example.test.agent.pretrain.ServerPretrainingRunner]
 * (run offline/server-side) reach the on-device live encoder (§7.6's
 * "training/inference split": "the live inference service periodically
 * picks up new checkpoints rather than retraining every tick").
 *
 * Deliberately a plain whitespace-delimited text format rather than a binary
 * one or a JSON library dependency the app module doesn't already pull in
 * (see `app/build.gradle.kts`) - at "compact" encoder sizes this is a few
 * hundred KB at most, and human-readability makes a bad checkpoint easy to
 * spot in a diff.
 */
object EncoderCheckpoint {
    private const val FORMAT_VERSION = 1

    fun save(encoder: CompactLatentStateEncoder, file: File) {
        val sb = StringBuilder()
        sb.appendLine("format_version $FORMAT_VERSION")
        sb.appendLine("input_dim ${encoder.config.inputDim}")
        sb.appendLine("hidden_dim ${encoder.config.hiddenDim}")
        sb.appendLine("seed ${encoder.config.seed}")

        writeMatrix(sb, "wz", encoder.gru.wz)
        writeMatrix(sb, "wr", encoder.gru.wr)
        writeMatrix(sb, "wh", encoder.gru.wh)
        writeMatrix(sb, "uz", encoder.gru.uz)
        writeMatrix(sb, "ur", encoder.gru.ur)
        writeMatrix(sb, "uh", encoder.gru.uh)
        writeVector(sb, "bz", encoder.gru.bz)
        writeVector(sb, "br", encoder.gru.br)
        writeVector(sb, "bh", encoder.gru.bh)

        val norm = encoder.vectorizer.exportNormalizer()
        sb.appendLine("normalizer_count ${norm.count}")
        writeVector(sb, "normalizer_mean", norm.mean)
        writeVector(sb, "normalizer_std", norm.std)

        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    /** Loads a checkpoint into a fresh [CompactLatentStateEncoder], failing loudly on a dimension mismatch rather than silently loading garbage into the wrong-shaped matrices. */
    fun load(file: File): CompactLatentStateEncoder {
        val lines = file.readLines().filter { it.isNotBlank() }.iterator()
        fun nextTokens(): List<String> = lines.next().trim().split(Regex("\\s+"))

        val version = nextTokens()[1].toInt()
        require(version == FORMAT_VERSION) { "unsupported checkpoint format_version $version (expected $FORMAT_VERSION)" }
        val inputDim = nextTokens()[1].toInt()
        val hiddenDim = nextTokens()[1].toInt()
        val seed = nextTokens()[1].toLong()

        val encoder = CompactLatentStateEncoder(LatentStateEncoderConfig(hiddenDim = hiddenDim, seed = seed))
        require(encoder.config.inputDim == inputDim) {
            "checkpoint input_dim=$inputDim does not match current FeatureVectorizer width=${encoder.config.inputDim} " +
                "(the feature schema changed since this checkpoint was pretrained - retrain, don't load)"
        }

        readMatrix(lines, "wz", encoder.gru.wz)
        readMatrix(lines, "wr", encoder.gru.wr)
        readMatrix(lines, "wh", encoder.gru.wh)
        readMatrix(lines, "uz", encoder.gru.uz)
        readMatrix(lines, "ur", encoder.gru.ur)
        readMatrix(lines, "uh", encoder.gru.uh)
        readVectorInto(lines, "bz", encoder.gru.bz)
        readVectorInto(lines, "br", encoder.gru.br)
        readVectorInto(lines, "bh", encoder.gru.bh)

        val normCount = nextTokens()[1].toLong()
        val normMean = readVector(lines, "normalizer_mean")
        val normStd = readVector(lines, "normalizer_std")
        encoder.vectorizer.importNormalizer(FeatureVectorizer.Normalizer.Snapshot(normCount, normMean, normStd))

        return encoder
    }

    private fun writeMatrix(sb: StringBuilder, name: String, m: GRUCell.Matrix) {
        sb.append(name).append(' ')
        sb.appendLine(m.data.joinToString(" ") { it.toString() })
    }

    private fun writeVector(sb: StringBuilder, name: String, v: DoubleArray) {
        sb.append(name).append(' ')
        sb.appendLine(v.joinToString(" ") { it.toString() })
    }

    private fun readMatrix(lines: Iterator<String>, expectedName: String, into: GRUCell.Matrix) {
        val tokens = lines.next().trim().split(Regex("\\s+"))
        require(tokens[0] == expectedName) { "expected '$expectedName' row, got '${tokens[0]}'" }
        require(tokens.size - 1 == into.data.size) { "'$expectedName' has ${tokens.size - 1} values, expected ${into.data.size}" }
        for (i in into.data.indices) into.data[i] = tokens[i + 1].toDouble()
    }

    private fun readVectorInto(lines: Iterator<String>, expectedName: String, into: DoubleArray) {
        val v = readVector(lines, expectedName)
        require(v.size == into.size) { "'$expectedName' has ${v.size} values, expected ${into.size}" }
        v.copyInto(into)
    }

    private fun readVector(lines: Iterator<String>, expectedName: String): DoubleArray {
        val tokens = lines.next().trim().split(Regex("\\s+"))
        require(tokens[0] == expectedName) { "expected '$expectedName' row, got '${tokens[0]}'" }
        return DoubleArray(tokens.size - 1) { tokens[it + 1].toDouble() }
    }
}
