package org.example.test.onboarding

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.example.test.MainActivity
import org.example.test.R
import org.example.test.orb.OrbView

class OnboardingActivity : AppCompatActivity() {

    private lateinit var orbView: OrbView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val headlineText = findViewById<TextView>(R.id.headlineText)
        val headlineFull = getString(R.string.onboarding_headline)
        val headlineSpannable = SpannableString(headlineFull)
        val bragStart = headlineFull.indexOf("Brag responsibly.")
        if (bragStart >= 0) {
            headlineSpannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#26C7C3")),
                bragStart,
                headlineFull.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        headlineText.text = headlineSpannable

        orbView = findViewById(R.id.orbView)
        orbView.setBackgroundColorHex("#000000")
        orbView.hue = 0f
        orbView.hoverIntensity = 0.1f
        orbView.rotateOnHover = true
        // The onboarding screen has no explicit "hover" affordance to teach, so the orb
        // animates on its own; a real touch still works too, since OrbView still tracks it.
        orbView.forceHoverState = true

        // Brand palette: a bright, near-white teal and the brand teal (#26C7C3) mix in the
        // outer glow, while a near-black teal anchors the shadow side. The shader's additive
        // highlight naturally blows out to a white-hot core, so the orb reads as "white core,
        // subtle teal outer glow" without needing to desaturate it toward gray.
        orbView.setColor1Hex("#26C7C3")
        orbView.setColor2Hex("#BFF6F2")
        orbView.setColor3Hex("#02201F")
        orbView.saturation = 0.75f

        findViewById<android.widget.ImageButton>(R.id.continueButton).setOnClickListener {
            // Persist completion so Splash never routes back here on future opens —
            // onboarding is a one-time, first-install experience only.
            OnboardingPreferences(this).hasCompletedOnboarding = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        orbView.onResume()
    }

    override fun onPause() {
        orbView.onPause()
        super.onPause()
    }
}
