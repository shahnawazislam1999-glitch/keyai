package com.sentencebuddy.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.sentencebuddy.app.data.TranslateLanguage
import com.sentencebuddy.app.MainActivity
import com.sentencebuddy.app.R
import com.sentencebuddy.app.ai.SuggestionRepository
import com.sentencebuddy.app.ai.SuggestionResult
import com.sentencebuddy.app.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the focused text box in other apps (WhatsApp, etc.).
 *
 * Flow: you type -> you pause -> the text is sent to Gemini -> alternative sentences are shown above the
 * keyboard -> you tap one -> it replaces the text in the box.
 */
class SuggestionAccessibilityService : AccessibilityService(), OverlayController.Listener {

    private lateinit var prefs: AppPreferences
    private lateinit var repository: SuggestionRepository
    private lateinit var overlay: OverlayController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var debounceJob: Job? = null
    private var requestJob: Job? = null

    private var targetNode: AccessibilityNodeInfo? = null
    private var currentText = ""
    private var lastAppliedText: String? = null
    private var pending: List<String>? = null
    private var pendingFor: String? = null
    private var keyHintShown = false

    // Copy -> translate
    private lateinit var clipboard: ClipboardManager
    private var translateJob: Job? = null
    private var translationMode = false
    private var lastClipHandled: String? = null
    private var lastOwnClip: String? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        onPreferenceChanged(key)
    }
    private val keyboardCheck = Runnable { checkKeyboardState() }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::overlay.isInitialized) overlay.destroy()
        prefs = AppPreferences.get(this)
        repository = SuggestionRepository(prefs)
        overlay = OverlayController(this, prefs, this) { keyboardTop() }
        prefs.registerListener(prefListener)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hideAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        scope.coroutineContext.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterListener(prefListener)
        if (::clipboard.isInitialized) clipboard.removePrimaryClipChangedListener(clipListener)
        if (::overlay.isInitialized) overlay.destroy()
        targetNode = null
    }

    // ---------------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized || !prefs.assistantEnabled) return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event, pkg)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> onFocusChanged(event, pkg)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleKeyboardCheck(250)
        }
    }

    private fun onFocusChanged(event: AccessibilityEvent, pkg: String) {
        if (!isPackageAllowed(pkg)) return
        val node = event.source ?: return
        if (!isEligibleField(node)) return

        debounceJob?.cancel()
        requestJob?.cancel()
        // A translation card stays up so the user can still read it while they tap into a chat box.
        if (overlay.isPanelShowing && !translationMode) overlay.hidePanel()

        targetNode = node
        currentText = readNodeText(node)
        pending = null
        pendingFor = null
        lastAppliedText = null
        scheduleKeyboardCheck(300)
    }

    private fun onTextChanged(event: AccessibilityEvent, pkg: String) {
        if (!isPackageAllowed(pkg) || event.isPassword) return
        val node = event.source ?: return
        if (!isEligibleField(node)) return

        val text = if (node.isShowingHintText) "" else (event.text?.firstOrNull()?.toString() ?: node.text?.toString().orEmpty())
        targetNode = node

        // Ignore the text change that we caused ourselves by applying a suggestion.
        if (text == lastAppliedText) return
        lastAppliedText = null

        currentText = text
        pending = null
        pendingFor = null
        debounceJob?.cancel()
        requestJob?.cancel()
        translationMode = false
        if (overlay.isPanelShowing) overlay.hidePanel()
        overlay.showBubble()

        // Paused ("minimized"): only the bubble stays; suggestions come when the user taps it.
        if (prefs.paused) return

        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS || trimmed.length > MAX_CHARS) return

        debounceJob = scope.launch {
            delay(prefs.debounceMs.toLong())
            requestSuggestions(trimmed, userInitiated = false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Suggestions
    // ---------------------------------------------------------------------------------------------

    private fun requestSuggestions(text: String, userInitiated: Boolean) {
        if (prefs.apiKey.isBlank()) {
            if (userInitiated || !keyHintShown) {
                keyHintShown = true
                overlay.showMessage(getString(R.string.msg_no_key), showOpenApp = true)
            }
            return
        }

        val showNow = userInitiated || prefs.autoShowPanel || !prefs.showBubble
        if (showNow) {
            translationMode = false
            overlay.showLoading(prefs.tone)
        }

        requestJob?.cancel()
        requestJob = scope.launch {
            val result = repository.suggest(text)
            // The user kept typing, or the keyboard closed: drop this answer.
            if (text != currentText.trim() || !overlay.isBubbleShowing) return@launch

            when (result) {
                is SuggestionResult.Success -> {
                    pending = result.items
                    pendingFor = text
                    if (showNow) overlay.showSuggestions(result.items, prefs.tone) else overlay.pulseBubble()
                }
                is SuggestionResult.Failure -> {
                    val quietNetworkError = !userInitiated && result.kind == SuggestionResult.Kind.NETWORK
                    if (showNow && !quietNetworkError) {
                        overlay.showMessage(result.message, showOpenApp = result.kind == SuggestionResult.Kind.INVALID_KEY)
                    } else if (showNow) {
                        overlay.hidePanel()
                    }
                }
            }
        }
    }

    override fun onBubbleClicked() {
        if (overlay.isPanelShowing) {
            overlay.hidePanel()
            return
        }
        val live = readTargetText() ?: currentText
        val trimmed = live.trim()
        if (trimmed.length < 2) {
            overlay.showMessage(getString(R.string.msg_type_first), showOpenApp = false)
            return
        }
        currentText = live
        // Tapping the bubble while paused turns suggestions back on.
        if (prefs.paused) prefs.paused = false
        translationMode = false
        val cached = pending
        if (cached != null && pendingFor == trimmed) {
            overlay.showSuggestions(cached, prefs.tone)
        } else {
            requestSuggestions(trimmed, userInitiated = true)
        }
    }

    override fun onToneClicked() {
        prefs.tone = prefs.tone.next()
        pending = null
        pendingFor = null
        val trimmed = currentText.trim()
        if (trimmed.length >= 2) requestSuggestions(trimmed, userInitiated = true)
    }

    override fun onMinimizeClicked() {
        prefs.paused = true
        debounceJob?.cancel()
        requestJob?.cancel()
        pending = null
        pendingFor = null
        translationMode = false
        overlay.hidePanel()
        overlay.showBubble()
        overlay.refreshBubble()
    }

    override fun onTranslationChosen(text: String) {
        lastOwnClip = text
        lastClipHandled = text
        val clip = ClipData.newPlainText("Sentence Buddy translation", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.msg_translation_copied, Toast.LENGTH_SHORT).show()
        translationMode = false
        overlay.hidePanel()
    }

    // ---------------------------------------------------------------------------------------------
    // Copy -> translate
    // ---------------------------------------------------------------------------------------------

    private fun onClipChanged() {
        if (!::prefs.isInitialized || !prefs.assistantEnabled || !prefs.translateOnCopy) return
        val clip = try {
            clipboard.primaryClip
        } catch (e: Exception) {
            null
        } ?: return
        if (clip.itemCount == 0) return

        // Password managers mark what they copy as sensitive: never send that anywhere.
        val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        if (sensitive) return

        val text = try {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim()
        } catch (e: Exception) {
            return
        }
        if (text.length < 2 || text.length > MAX_TRANSLATE_CHARS) return
        // Ignore what we copied ourselves (the translation) and repeated notifications for the same copy.
        if (text == lastOwnClip || text == lastClipHandled) return
        lastClipHandled = text

        translateCopied(text, prefs.translateLanguage)
    }

    private fun translateCopied(text: String, language: TranslateLanguage) {
        debounceJob?.cancel()
        requestJob?.cancel()
        translateJob?.cancel()

        if (prefs.apiKey.isBlank()) {
            translationMode = true
            overlay.showTranslationMessage(getString(R.string.msg_no_key), language.label, showOpenApp = true)
            return
        }

        translationMode = true
        overlay.showTranslationLoading(language.label)
        translateJob = scope.launch {
            when (val result = repository.translate(text, language)) {
                is SuggestionResult.Success -> overlay.showTranslation(result.items.first(), language.label)
                is SuggestionResult.Failure -> overlay.showTranslationMessage(
                    result.message,
                    language.label,
                    showOpenApp = result.kind == SuggestionResult.Kind.INVALID_KEY
                )
            }
        }
    }

    override fun onOpenApp() {
        overlay.hidePanel()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onSuggestionChosen(text: String) {
        applySuggestion(text)
    }

    // ---------------------------------------------------------------------------------------------
    // Writing the chosen sentence back
    // ---------------------------------------------------------------------------------------------

    private fun applySuggestion(suggestion: String) {
        val node = resolveTargetNode()
        var done = false
        if (node != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, suggestion)
            }
            done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (done) {
                lastAppliedText = suggestion
                currentText = suggestion
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, suggestion.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, suggestion.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
            }
        }

        if (!done) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Sentence Buddy", suggestion))
            Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_LONG).show()
        }

        overlay.hidePanel()
        pending = null
        pendingFor = null

        if (done && prefs.autoSend) {
            handler.postDelayed({ clickSendButton() }, 250)
        }
    }

    private fun resolveTargetNode(): AccessibilityNodeInfo? {
        val cached = targetNode
        if (cached != null && cached.refresh() && cached.isEditable) return cached
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.takeIf { it.isEditable }
    }

    private fun readTargetText(): String? {
        val node = resolveTargetNode() ?: return null
        return readNodeText(node)
    }

    private fun readNodeText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    /** Best-effort: finds the "Send" button of the chat screen and taps it. */
    private fun clickSendButton() {
        val root = rootInActiveWindow ?: return
        val button = findSendButton(root) ?: return
        var node: AccessibilityNodeInfo? = button
        var depth = 0
        while (node != null && depth < 4) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            node = node.parent
            depth++
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString()
        if (pkg != null) {
            val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            byId?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEND_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            val description = node.contentDescription?.toString()
            if (description != null && node.isVisibleToUser &&
                (description.equals("Send", ignoreCase = true) || description.equals("Send message", ignoreCase = true))
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Keyboard / window tracking
    // ---------------------------------------------------------------------------------------------

    private fun scheduleKeyboardCheck(delayMs: Long) {
        handler.removeCallbacks(keyboardCheck)
        handler.postDelayed(keyboardCheck, delayMs)
    }

    private fun checkKeyboardState() {
        if (!prefs.assistantEnabled) {
            overlay.hideAll()
            return
        }
        val node = targetNode
        val nodeValid = node != null && node.refresh()
        if (nodeValid && isKeyboardVisible()) {
            overlay.showBubble()
        } else {
            if (translationMode && overlay.isPanelShowing) overlay.hideBubble() else overlay.hideAll()
            if (!nodeValid) targetNode = null
        }
    }

    private fun isKeyboardVisible(): Boolean {
        val list = try {
            windows
        } catch (e: Exception) {
            null
        }
        if (list.isNullOrEmpty()) return targetNode != null
        return list.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    /** Top edge of the on-screen keyboard in screen pixels, or null when it is not showing. */
    private fun keyboardTop(): Int? {
        val list = try {
            windows
        } catch (e: Exception) {
            return null
        }
        val ime = list.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val bounds = Rect()
        ime.getBoundsInScreen(bounds)
        return if (bounds.height() > 0) bounds.top else null
    }

    // ---------------------------------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------------------------------

    private fun isPackageAllowed(pkg: String): Boolean {
        if (pkg == packageName) return false
        return if (prefs.whatsappOnly) pkg in WHATSAPP_PACKAGES else true
    }

    /** Only normal text boxes: never passwords, e-mail/URL fields or number pads. */
    private fun isEligibleField(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable || node.isPassword) return false
        val inputType = node.inputType
        if (inputType == 0) return true
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI -> false
            else -> true
        }
    }

    private fun onPreferenceChanged(key: String?) {
        if (!::overlay.isInitialized) return
        when (key) {
            AppPreferences.KEY_OPACITY, AppPreferences.KEY_SIZE -> overlay.updateAppearance()
            AppPreferences.KEY_BUBBLE_X, AppPreferences.KEY_BUBBLE_Y -> {
                if (prefs.bubbleX < 0 || prefs.bubbleY < 0) overlay.resetPosition()
            }
            AppPreferences.KEY_PAUSED, AppPreferences.KEY_SHOW_BUBBLE -> overlay.refreshBubble()
            AppPreferences.KEY_TRANSLATE_COPY -> if (!prefs.translateOnCopy) {
                translateJob?.cancel()
                if (translationMode) {
                    translationMode = false
                    overlay.hidePanel()
                }
            }
            AppPreferences.KEY_ENABLED -> if (!prefs.assistantEnabled) {
                debounceJob?.cancel()
                requestJob?.cancel()
                translateJob?.cancel()
                translationMode = false
                overlay.hideAll()
            }
        }
    }

    private companion object {
        const val MIN_CHARS = 3
        const val MAX_CHARS = 500
        const val MAX_TRANSLATE_CHARS = 1500
        const val MAX_SEND_SEARCH_NODES = 400
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
