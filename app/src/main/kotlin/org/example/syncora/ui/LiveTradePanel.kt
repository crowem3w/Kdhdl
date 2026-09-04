package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.example.syncora.bitget.BitgetCredentials
import org.example.syncora.bitget.BitgetEnvironment
import org.example.syncora.bitget.BitgetTradingRestClient
import org.example.syncora.bitget.PaperTradingConnectionState
import java.util.Locale











class LiveTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Callbacks(
        val onCredentialsSubmitted: (BitgetCredentials) -> Unit,
        val onCredentialsCleared: () -> Unit,
    )

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")
    private val fieldBackground = Color.parseColor("#131722")
    private val liveAccentColor = Color.parseColor("#F0B90B") 

    private var callbacks: Callbacks? = null
    private var savedCredentials: BitgetCredentials? = null
    private var lastConnectionState: PaperTradingConnectionState = PaperTradingConnectionState.NOT_CONFIGURED
    private var fieldsPrefilled = false
    private var credentialScope: CoroutineScope? = null

    
    private lateinit var apiKeyField: EditText
    private lateinit var secretField: EditText
    private lateinit var passphraseField: EditText
    private lateinit var credStatusDot: View
    private lateinit var credStatusLabel: TextView
    private lateinit var credStatusDetail: TextView
    private lateinit var credUidLabel: TextView
    private lateinit var testProgress: ProgressBar
    private lateinit var testConnectionButton: TextView
    private lateinit var saveConnectButton: Button
    private lateinit var removeKeyText: TextView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), liveAccentColor.withAlpha(0x55))
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))

        
        
        
        addView(buildCredentialsSection())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        credentialScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    override fun onDetachedFromWindow() {
        credentialScope?.cancel()
        credentialScope = null
        super.onDetachedFromWindow()
    }

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun render(
        connectionState: PaperTradingConnectionState,
        credentials: BitgetCredentials?,
        lastError: String? = null,
        userId: String? = null,
    ) {
        savedCredentials = credentials
        lastConnectionState = connectionState

        if (!fieldsPrefilled && credentials != null) {
            apiKeyField.setText(credentials.apiKey)
            secretField.setText(credentials.secretKey)
            passphraseField.setText(credentials.passphrase)
            fieldsPrefilled = true
        }
        removeKeyText.visibility = if (credentials != null) View.VISIBLE else View.GONE
        applyCredentialStatus(
            connected = connectionState == PaperTradingConnectionState.LIVE,
            detail = if (connectionState == PaperTradingConnectionState.ERROR) lastError else null,
            userId = userId,
        )
    }

    







    private fun buildCredentialsSection(): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), liveAccentColor.withAlpha(0x40))
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "Live API Key"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(context).apply {
            text = "REAL FUNDS"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(liveAccentColor)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), liveAccentColor)
            }
        })
        container.addView(titleRow)
        container.addView(spacer(4))
        container.addView(TextView(context).apply {
            text = "Connect your Bitget account to enable live trading."
            textSize = 11.5f
            setTextColor(mutedColor)
        })

        container.addView(spacer(12))
        container.addView(sectionHeader("API Credentials"))
        val (apiKeyF, apiKeyRow) = secureField("API Key", "", maskByDefault = false)
        val (secretF, secretRow) = secureField("API Secret", "", maskByDefault = true)
        val (passphraseF, passphraseRow) = secureField("Passphrase (optional)", "", maskByDefault = true)
        apiKeyField = apiKeyF
        secretField = secretF
        passphraseField = passphraseF
        container.addView(apiKeyRow)
        container.addView(secretRow)
        container.addView(passphraseRow)

        container.addView(spacer(12))
        container.addView(sectionHeader("API Connection"))

        
        
        val exchangeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Bitget"))
        }
        container.addView(labeledRow("Exchange", exchangeSpinner))

        container.addView(spacer(12))
        container.addView(buildDivider())
        container.addView(spacer(10))

        
        credStatusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) }
        }
        credStatusLabel = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        credStatusDetail = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        }
        credUidLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        testProgress = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(credStatusDot)
            addView(credStatusLabel)
            addView(testProgress)
        }
        container.addView(statusRow)
        container.addView(credStatusDetail)
        container.addView(credUidLabel)
        applyCredentialStatus(connected = false)

        container.addView(spacer(10))
        testConnectionButton = TextView(context).apply {
            text = "Test Connection"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { testConnection() }
        }
        container.addView(testConnectionButton)

        container.addView(spacer(12))
        container.addView(TextView(context).apply {
            text = "Your API credentials are used only to connect to the exchange from this " +
                "device. They are never used for withdrawals - create the key with withdrawal " +
                "permissions disabled on the exchange."
            textSize = 11f
            setTextColor(mutedColor)
        })
        container.addView(spacer(14))

        saveConnectButton = Button(context).apply {
            text = "Save & Connect"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pillBackground(liveAccentColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { saveAndConnect() }
        }
        container.addView(saveConnectButton)

        removeKeyText = TextView(context).apply {
            text = "Remove saved key"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(bearColor)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
            setOnClickListener { callbacks?.onCredentialsCleared?.invoke() }
        }
        container.addView(spacer(6))
        container.addView(removeKeyText)

        return container
    }

    private fun applyCredentialStatus(connected: Boolean, detail: String? = null, userId: String? = null) {
        credStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (connected) bullColor else mutedColor)
        }
        credStatusLabel.text = if (connected) "Connected" else "Not Connected"
        credStatusLabel.setTextColor(if (connected) bullColor else mutedColor)
        credStatusDetail.text = detail.orEmpty()
        credStatusDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        credUidLabel.text = if (userId != null) "UID: $userId" else ""
        credUidLabel.visibility = if (userId != null) View.VISIBLE else View.GONE
    }

    private fun currentlyEnteredCredentials(): BitgetCredentials = BitgetCredentials(
        apiKey = apiKeyField.text?.toString()?.trim().orEmpty(),
        secretKey = secretField.text?.toString()?.trim().orEmpty(),
        passphrase = passphraseField.text?.toString()?.trim().orEmpty(),
    )

    private fun testConnection() {
        val credentials = currentlyEnteredCredentials()
        if (!credentials.isComplete) {
            apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
            secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
            return
        }
        val scope = credentialScope ?: return
        testProgress.visibility = View.VISIBLE
        testConnectionButton.isEnabled = false
        scope.launch {
            val client = BitgetTradingRestClient(
                environment = { BitgetEnvironment.LIVE },
                credentialsProvider = { credentials },
            )
            try {
                client.fetchAccountBalance()
                applyCredentialStatus(connected = true, detail = "Key is valid and reachable.")
            } catch (e: Exception) {
                applyCredentialStatus(connected = false, detail = e.message ?: "Couldn't reach the exchange")
            } finally {
                testProgress.visibility = View.GONE
                testConnectionButton.isEnabled = true
            }
        }
    }

    private fun saveAndConnect() {
        val credentials = currentlyEnteredCredentials()
        if (!credentials.isComplete) {
            apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
            secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
            return
        }
        callbacks?.onCredentialsSubmitted?.invoke(credentials)
    }


    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase(Locale.US)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(mutedColor)
        letterSpacing = 0.04f
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun labeledRow(label: String, control: View): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = label
            textSize = 12.5f
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(control)
    }

    
    private fun secureField(hint: String, initialValue: String, maskByDefault: Boolean): Pair<EditText, View> {
        val field = fieldEditTextForDialog(hint).apply {
            setText(initialValue)
            if (maskByDefault) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        var visible = !maskByDefault
        val toggle = TextView(context).apply {
            text = if (visible) "Hide" else "Show"
            textSize = 11.5f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(2), dp(6))
            setOnClickListener {
                visible = !visible
                field.inputType = if (visible) {
                    InputType.TYPE_CLASS_TEXT
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                field.setSelection(field.text?.length ?: 0)
                text = if (visible) "Hide" else "Show"
            }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(field.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(toggle)
        }
        return field to row
    }

    private fun fieldEditTextForDialog(hint: String): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 13.5f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
        }

    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }
}