package org.example.test.agent.model

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A single GRU layer, hand-rolled (forward + backward-through-time) rather
 * than pulled from an ML framework: the app module has no ND4J/DL4J/KotlinDL
 * dependency, and a GRU's parameter count at the "compact" sizes design doc
 * §3.1 calls for (tens of features in, tens of hidden units) is small enough
 * that plain array arithmetic is both sufficient and easy to audit.
 *
 * GRU over LSTM specifically because it has one fewer gate (no separate cell
 * state) for roughly comparable sequence-modeling quality - fewer parameters
 * to fit from a synthetic-only pretraining corpus, and a lighter forward pass
 * for the on-device live inference path this same class also serves.
 *
 * Equations (standard GRU, z=update gate, r=reset gate, h~=candidate):
 * ```
 * z_t  = sigmoid(Wz·x_t + Uz·h_{t-1} + bz)
 * r_t  = sigmoid(Wr·x_t + Ur·h_{t-1} + br)
 * h~_t = tanh(Wh·x_t + Uh·(r_t ⊙ h_{t-1}) + bh)
 * h_t  = (1 - z_t) ⊙ h_{t-1} + z_t ⊙ h~_t
 * ```
 */
class GRUCell(val inputDim: Int, val hiddenDim: Int, seed: Long = 42L) {

    // Input->hidden weights [hiddenDim][inputDim], hidden->hidden [hiddenDim][hiddenDim], biases [hiddenDim].
    val wz = Matrix(hiddenDim, inputDim)
    val wr = Matrix(hiddenDim, inputDim)
    val wh = Matrix(hiddenDim, inputDim)
    val uz = Matrix(hiddenDim, hiddenDim)
    val ur = Matrix(hiddenDim, hiddenDim)
    val uh = Matrix(hiddenDim, hiddenDim)
    val bz = DoubleArray(hiddenDim)
    val br = DoubleArray(hiddenDim)
    val bh = DoubleArray(hiddenDim)

    /** Gradient accumulators, same shapes as the params above. Cleared by [zeroGrad]. */
    val dWz = Matrix(hiddenDim, inputDim)
    val dWr = Matrix(hiddenDim, inputDim)
    val dWh = Matrix(hiddenDim, inputDim)
    val dUz = Matrix(hiddenDim, hiddenDim)
    val dUr = Matrix(hiddenDim, hiddenDim)
    val dUh = Matrix(hiddenDim, hiddenDim)
    val dbz = DoubleArray(hiddenDim)
    val dbr = DoubleArray(hiddenDim)
    val dbh = DoubleArray(hiddenDim)

    init {
        val rng = Random(seed)
        // Glorot/Xavier uniform init keeps early-training activations from saturating the sigmoid/tanh gates.
        wz.fillGlorot(rng); wr.fillGlorot(rng); wh.fillGlorot(rng)
        uz.fillGlorot(rng); ur.fillGlorot(rng); uh.fillGlorot(rng)
    }

    /** Everything [backwardStep] needs from a forward step to compute gradients, without recomputing activations. */
    class StepCache(hiddenDim: Int) {
        lateinit var x: DoubleArray
        lateinit var hPrev: DoubleArray
        val z = DoubleArray(hiddenDim)
        val r = DoubleArray(hiddenDim)
        val hCand = DoubleArray(hiddenDim)
        val rh = DoubleArray(hiddenDim) // r ⊙ hPrev
        val h = DoubleArray(hiddenDim)
    }

    /** One timestep forward. [hPrev] is not mutated; returns the cache backward needs and writes h_t into it. */
    fun forwardStep(x: DoubleArray, hPrev: DoubleArray, cache: StepCache = StepCache(hiddenDim)): StepCache {
        cache.x = x
        cache.hPrev = hPrev
        for (k in 0 until hiddenDim) {
            val zPre = wz.dot(k, x) + uz.dot(k, hPrev) + bz[k]
            val rPre = wr.dot(k, x) + ur.dot(k, hPrev) + br[k]
            cache.z[k] = sigmoid(zPre)
            cache.r[k] = sigmoid(rPre)
        }
        for (k in 0 until hiddenDim) cache.rh[k] = cache.r[k] * hPrev[k]
        for (k in 0 until hiddenDim) {
            val hPre = wh.dot(k, x) + uh.dot(k, cache.rh) + bh[k]
            cache.hCand[k] = tanh(hPre)
        }
        for (k in 0 until hiddenDim) {
            cache.h[k] = (1.0 - cache.z[k]) * hPrev[k] + cache.z[k] * cache.hCand[k]
        }
        return cache
    }

    /**
     * Backpropagates one timestep given `dhNext` = dL/dh_t (the sum of any
     * loss attached directly to this timestep's output plus whatever flowed
     * back from timestep t+1). Accumulates into the `d*` gradient fields and
     * returns dL/dh_{t-1} for the caller to chain into the previous step.
     */
    fun backwardStep(cache: StepCache, dhNext: DoubleArray): DoubleArray {
        val dhPrev = DoubleArray(hiddenDim)
        val dPreZ = DoubleArray(hiddenDim)
        val dPreR = DoubleArray(hiddenDim)
        val dPreH = DoubleArray(hiddenDim)
        val dRh = DoubleArray(hiddenDim)

        for (k in 0 until hiddenDim) {
            val hPrevK = cache.hPrev[k]
            val zK = cache.z[k]
            val hCandK = cache.hCand[k]

            val dz = dhNext[k] * (hCandK - hPrevK)
            val dhCand = dhNext[k] * zK
            dhPrev[k] += dhNext[k] * (1.0 - zK)

            dPreH[k] = dhCand * (1.0 - hCandK * hCandK) // tanh'
            dPreZ[k] = dz * zK * (1.0 - zK) // sigmoid'
        }

        // dPreH depends on Uh·rh, so d(rh) = Uh^T · dPreH; split into dr and dhPrev contribution.
        for (k in 0 until hiddenDim) {
            var acc = 0.0
            for (j in 0 until hiddenDim) acc += uh.get(j, k) * dPreH[j]
            dRh[k] = acc
        }
        for (k in 0 until hiddenDim) {
            val rK = cache.r[k]
            val hPrevK = cache.hPrev[k]
            val dr = dRh[k] * hPrevK
            dhPrev[k] += dRh[k] * rK
            dPreR[k] = dr * rK * (1.0 - rK) // sigmoid'
        }

        // Accumulate weight/bias gradients (outer products) and hidden-state gradient from the Uz/Ur paths.
        for (k in 0 until hiddenDim) {
            dbz[k] += dPreZ[k]
            dbr[k] += dPreR[k]
            dbh[k] += dPreH[k]
            for (j in 0 until inputDim) {
                dWz.add(k, j, dPreZ[k] * cache.x[j])
                dWr.add(k, j, dPreR[k] * cache.x[j])
                dWh.add(k, j, dPreH[k] * cache.x[j])
            }
            for (j in 0 until hiddenDim) {
                dUz.add(k, j, dPreZ[k] * cache.hPrev[j])
                dUr.add(k, j, dPreR[k] * cache.hPrev[j])
                dUh.add(k, j, dPreH[k] * cache.rh[j])
                dhPrev[j] += uz.get(k, j) * dPreZ[k] + ur.get(k, j) * dPreR[k]
            }
        }
        return dhPrev
    }

    fun zeroGrad() {
        dWz.zero(); dWr.zero(); dWh.zero()
        dUz.zero(); dUr.zero(); dUh.zero()
        dbz.fill(0.0); dbr.fill(0.0); dbh.fill(0.0)
    }

    /** All (param, grad) array pairs, flattened, for a generic optimizer ([AdamOptimizer]) to walk without knowing GRU internals. */
    fun parameters(): List<ParamRef> = listOf(
        wz.asParamRef(dWz), wr.asParamRef(dWr), wh.asParamRef(dWh),
        uz.asParamRef(dUz), ur.asParamRef(dUr), uh.asParamRef(dUh),
        ParamRef(bz, dbz), ParamRef(br, dbr), ParamRef(bh, dbh),
    )

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
    private fun tanh(x: Double): Double = kotlin.math.tanh(x)

    /** A dense weight matrix stored row-major as a flat [DoubleArray], to avoid `Array<DoubleArray>` boxing overhead in the hot BPTT loop. */
    class Matrix(val rows: Int, val cols: Int) {
        val data = DoubleArray(rows * cols)
        fun get(r: Int, c: Int): Double = data[r * cols + c]
        fun set(r: Int, c: Int, v: Double) { data[r * cols + c] = v }
        fun add(r: Int, c: Int, delta: Double) { data[r * cols + c] += delta }
        fun zero() = data.fill(0.0)

        fun dot(row: Int, vec: DoubleArray): Double {
            var acc = 0.0
            val base = row * cols
            for (c in 0 until cols) acc += data[base + c] * vec[c]
            return acc
        }

        fun fillGlorot(rng: Random) {
            val limit = sqrt(6.0 / (rows + cols))
            for (i in data.indices) data[i] = (rng.nextDouble() * 2.0 - 1.0) * limit
        }

        fun asParamRef(grad: Matrix): ParamRef = ParamRef(data, grad.data)
    }

    /** A flat (parameter, gradient) array pair, the unit [AdamOptimizer] steps over. */
    data class ParamRef(val values: DoubleArray, val grads: DoubleArray)
}
