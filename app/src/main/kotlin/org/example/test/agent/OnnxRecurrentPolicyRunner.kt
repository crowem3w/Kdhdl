package org.example.test.agent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.nio.FloatBuffer

/**
 * On-device inference for the recurrent (GRU) PPO policy with a
 * distributional (quantile) critic - rl-trading-agent-design.md sections
 * 2 and 7. This is deliberately inference-only: the model is trained
 * offline (that training pipeline is not part of this repo) and the
 * resulting ONNX file + a provenance JSON are dropped into `assets/agent/`.
 * Nothing in this class updates weights - it only runs
 * the forward pass, same guarantee [SafeFlatPolicyRunner] made trivially by
 * having no weights at all.
 *
 * The model is small (low thousands of parameters per the design doc), so
 * a synchronous forward pass on the calling thread is fine.
 */
class OnnxRecurrentPolicyRunner private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : RecurrentPolicyRunner, AutoCloseable {

    override fun infer(window: List<MarketObservation>): PolicyDecision {
        val flatInput = RecurrentFeatureExtractor.extract(window)
        val shape = longArrayOf(1L, RecurrentFeatureExtractor.SEQ_LEN.toLong(), RecurrentFeatureExtractor.FEATURE_DIM.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(flatInput), shape).use { inputTensor ->
            session.run(mapOf(INPUT_NAME to inputTensor)).use { result ->
                val positionTarget = (result.get(OUTPUT_ACTION).get().value as Array<FloatArray>)[0][0]
                val quantilesRaw = (result.get(OUTPUT_QUANTILES).get().value as Array<FloatArray>)[0]
                val quantiles = DoubleArray(quantilesRaw.size) { quantilesRaw[it].toDouble() }
                quantiles.sort() // quantile head isn't monotonicity-constrained at train time; enforce it for cvar5/spread reads.
                return PolicyDecision(positionTarget.toDouble().coerceIn(-1.0, 1.0), quantiles)
            }
        }
    }

    override fun close() {
        session.close()
        // Note: OrtEnvironment is a process-wide singleton (see loadFromAssets) - not closed here.
    }

    companion object {
        private const val TAG = "OnnxRecurrentPolicy"
        private const val INPUT_NAME = "observation_window"
        private const val OUTPUT_ACTION = "position_target"
        private const val OUTPUT_QUANTILES = "return_quantiles"
        private const val ASSET_MODEL_PATH = "agent/policy_gru_v1.onnx"
        private const val ASSET_PROVENANCE_PATH = "agent/policy_provenance.json"

        /**
         * Loads the bundled ONNX model + provenance metadata from assets, if
         * present. Returns `null` (never throws) on anything short of
         * success - missing asset, corrupt file, shape mismatch from a stale
         * bundle - so the caller can fall back to [SafeFlatPolicyRunner],
         * exactly the "cold start stays flat" guarantee the design doc
         * requires for anything not yet a validated, promoted policy.
         */
        fun loadFromAssets(context: Context): Pair<RecurrentPolicyRunner, PolicyProvenance>? {
            return try {
                val modelBytes = context.assets.open(ASSET_MODEL_PATH).use { it.readBytes() }
                val provenance = loadProvenance(context)
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(modelBytes, OrtSession.SessionOptions())
                Log.i(TAG, "Loaded recurrent policy ${provenance.version} (${modelBytes.size} bytes)")
                OnnxRecurrentPolicyRunner(env, session) to provenance
            } catch (e: Exception) {
                Log.w(TAG, "No validated recurrent policy available on device - staying flat", e)
                null
            }
        }

        private fun loadProvenance(context: Context): PolicyProvenance {
            val json = context.assets.open(ASSET_PROVENANCE_PATH).use { it.readBytes().decodeToString() }
            val root = JSONObject(json)
            return PolicyProvenance(
                version = root.getString("version"),
                trainedAtMs = root.getLong("trainedAtMs"),
                validationWindow = root.getString("validationWindow"),
                validationResult = root.getString("validationResult"),
            )
        }
    }
}
