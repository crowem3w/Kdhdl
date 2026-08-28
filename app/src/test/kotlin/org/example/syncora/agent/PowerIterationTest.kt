package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PowerIterationTest {

    private val epsilon = 1e-3f

    @Test
    fun `diagonal matrix spectral radius is the largest absolute diagonal entry`() {
        // diag(0.2, -0.9, 0.5) -> rho == 0.9
        val n = 3
        val m = FloatArray(n * n)
        m[0 * n + 0] = 0.2f
        m[1 * n + 1] = -0.9f
        m[2 * n + 2] = 0.5f

        val rho = PowerIteration.estimateSpectralRadius(m, n, Random(1L))
        assertEquals(0.9f, rho, epsilon)
    }

    @Test
    fun `identity matrix has spectral radius 1`() {
        val n = 5
        val m = FloatArray(n * n)
        for (i in 0 until n) m[i * n + i] = 1f

        val rho = PowerIteration.estimateSpectralRadius(m, n, Random(2L))
        assertEquals(1.0f, rho, epsilon)
    }

    @Test
    fun `zero matrix has spectral radius 0`() {
        val n = 4
        val m = FloatArray(n * n)
        val rho = PowerIteration.estimateSpectralRadius(m, n, Random(3L))
        assertEquals(0f, rho, epsilon)
    }

    @Test
    fun `converges to dominant magnitude even with a complex-conjugate dominant pair`() {
        // 2x2 rotation-scaling block [[0, -r], [r, 0]] has eigenvalues +/- i*r,
        // i.e. a complex-conjugate pair of magnitude r, with no real dominant
        // eigenvalue at all - the case PowerIteration's KDoc calls out.
        val n = 2
        val r = 0.7f
        val m = FloatArray(n * n)
        m[0 * n + 1] = -r
        m[1 * n + 0] = r

        val rho = PowerIteration.estimateSpectralRadius(m, n, Random(4L), iterations = 500)
        assertEquals(r, rho, 1e-2f)
    }

    @Test
    fun `scaling a matrix by a factor scales its spectral radius by the same factor`() {
        val n = 20
        val rng = Random(6L)
        val base = FloatArray(n * n) { rng.nextFloat() }
        val baseRho = PowerIteration.estimateSpectralRadius(base, n, Random(7L))

        val factor = 0.37f
        val scaled = FloatArray(n * n) { base[it] * factor }
        val scaledRho = PowerIteration.estimateSpectralRadius(scaled, n, Random(8L))

        assertEquals(baseRho * factor, scaledRho, epsilon)
    }
}
