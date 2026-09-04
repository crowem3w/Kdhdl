package org.example.syncora.onboarding

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.example.syncora.MainActivity
import org.example.syncora.R
import org.example.syncora.orb.OrbView

class OnboardingActivity : AppCompatActivity() {

    private lateinit var orbView: OrbView

    
    
    
    
    
    private val batteryExemptionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishOnboardingAndContinue()
        }

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
        
        
        orbView.forceHoverState = true

        
        
        
        
        orbView.setColor1Hex("#26C7C3")
        orbView.setColor2Hex("#BFF6F2")
        orbView.setColor3Hex("#02201F")
        orbView.saturation = 0.75f

        findViewById<android.widget.ImageButton>(R.id.continueButton).setOnClickListener {
            maybeRequestBatteryOptimizationExemption()
        }
    }

    





    private fun maybeRequestBatteryOptimizationExemption() {
        val prefs = OnboardingPreferences(this)
        val alreadyExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        if (alreadyExempt || prefs.hasRequestedBatteryOptimizationExemption) {
            finishOnboardingAndContinue()
            return
        }
        prefs.hasRequestedBatteryOptimizationExemption = true

        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_battery_dialog_title)
            .setMessage(R.string.onboarding_battery_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_battery_dialog_allow) { _, _ ->
                val intent = BatteryOptimizationHelper.buildExemptionRequestIntent(this)
                if (intent != null) {
                    batteryExemptionLauncher.launch(intent)
                } else {
                    finishOnboardingAndContinue()
                }
            }
            .setNegativeButton(R.string.onboarding_battery_dialog_skip) { _, _ ->
                finishOnboardingAndContinue()
            }
            .show()
    }

    private fun finishOnboardingAndContinue() {
        
        
        OnboardingPreferences(this).hasCompletedOnboarding = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
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