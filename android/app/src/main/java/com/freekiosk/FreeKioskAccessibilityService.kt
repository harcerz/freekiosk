package com.freekiosk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * FreeKiosk Accessibility Service
 * 
 * Enables key/text injection into ANY app (including external apps).
 * Uses proper AccessibilityService APIs — NOT shell commands.
 * 
 * Injection strategy (in priority order):
 * 1. performGlobalAction() — for Back, Home, Recents (all API levels)
 * 2. InputMethod.sendKeyEvent() / commitText() — API 33+ (Android 13+)
 *    Works in any focused input field across all apps, like a real keyboard.
 * 3. ACTION_SET_TEXT on focused node — fallback for printable keys & text (all API levels)
 *    Converts keyCodes to characters via KeyCharacterMap, appends to focused field.
 *    Also handles Backspace (remove last char) and Shift+letter (uppercase).
 * 4. "input keyevent" shell command — last resort (requires root/shell, usually fails)
 * 
 * Compatibility:
 * - API 33+ (Android 13+): Full support — all keys, combos, text via InputMethod
 * - API 21-32 (Android 5-12): Partial — global actions + printable chars/text via ACTION_SET_TEXT
 *   Non-printable keys (arrows, Tab, Escape) and non-Shift combos (Ctrl+C) limited.
 * 
 * The user must enable this service in:
 *   Settings > Accessibility > FreeKiosk
 * In Device Owner mode, it can be enabled programmatically.
 */
class FreeKioskAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FreeKioskA11y"
        
        @Volatile
        var instance: FreeKioskAccessibilityService? = null
            private set
        
        fun isRunning(): Boolean = instance != null
        
        /**
         * Send a single key press.
         * Strategy: globalAction → InputMethod (API 33+) → ACTION_SET_TEXT (printable) → input keyevent
         */
        fun sendKey(keyCode: Int): Boolean {
            val service = instance ?: return false
            
            // 1. Global actions (Back, Home, Recents) — always works, all API levels
            val globalAction = mapToGlobalAction(keyCode)
            if (globalAction != null) {
                val ok = service.performGlobalAction(globalAction)
                Log.d(TAG, "Global action: keyCode=$keyCode, action=$globalAction, ok=$ok")
                return ok
            }
            
            // 2. API 33+: InputMethod.sendKeyEvent (works in focused input fields across apps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val connection = service.inputMethod?.currentInputConnection
                    if (connection != null) {
                        val now = SystemClock.uptimeMillis()
                        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                        Log.d(TAG, "Key via InputMethod: keyCode=$keyCode")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "InputMethod unavailable: ${e.message}")
                }
            }
            
            // 3. Backspace: remove last char via ACTION_SET_TEXT (all API levels)
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (deleteLastCharViaSetText(service)) {
                    Log.d(TAG, "Backspace via ACTION_SET_TEXT")
                    return true
                }
            }
            
            // 4. Printable char: convert keyCode → char, append via ACTION_SET_TEXT (all API levels)
            val char = keyCodeToChar(keyCode, 0)
            if (char != null) {
                if (injectTextViaSetText(service, char.toString())) {
                    Log.d(TAG, "Key via ACTION_SET_TEXT: keyCode=$keyCode -> '$char'")
                    return true
                }
            }
            
            // 5. Last resort: input keyevent shell command (requires root/shell, usually fails)
            return execInputCommand("keyevent", keyCode.toString(), "Key fallback: keyCode=$keyCode")
        }
        
        /**
         * Send a key press with modifier meta state (e.g., Ctrl+C, Alt+F4).
         * Strategy: InputMethod (API 33+) → ACTION_SET_TEXT for Shift+char → input keyevent
         */
        fun sendKeyWithMeta(keyCode: Int, metaState: Int): Boolean {
            val service = instance ?: return false
            
            // 1. API 33+: InputMethod.sendKeyEvent with meta state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val connection = service.inputMethod?.currentInputConnection
                    if (connection != null) {
                        val now = SystemClock.uptimeMillis()
                        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
                        Log.d(TAG, "Key combo via InputMethod: keyCode=$keyCode, metaState=$metaState")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "InputMethod unavailable for combo: ${e.message}")
                }
            }
            
            // 2. Shift + printable char: get shifted character (e.g. Shift+A → 'A') via ACTION_SET_TEXT
            //    Only for Shift-only combos (no Ctrl, no Alt) since those are system shortcuts.
            val isShiftOnly = (metaState and KeyEvent.META_SHIFT_ON) != 0
                    && (metaState and KeyEvent.META_CTRL_ON) == 0
                    && (metaState and KeyEvent.META_ALT_ON) == 0
            if (isShiftOnly) {
                val char = keyCodeToChar(keyCode, metaState)
                if (char != null) {
                    if (injectTextViaSetText(service, char.toString())) {
                        Log.d(TAG, "Shift combo via ACTION_SET_TEXT: keyCode=$keyCode -> '$char'")
                        return true
                    }
                }
            }
            
            // 3. Last resort: input keyevent (meta state is lost, limited)
            return execInputCommand("keyevent", keyCode.toString(), "Combo fallback: keyCode=$keyCode (meta=$metaState lost)")
        }
        
        /**
         * Type text into the focused input field.
         * Strategy: InputMethod.commitText (API 33+) → ACTION_SET_TEXT on focused node (all APIs)
         */
        fun sendText(text: String): Boolean {
            val service = instance ?: return false
            
            // 1. API 33+: InputMethod.commitText (best — acts like real keyboard typing)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val connection = service.inputMethod?.currentInputConnection
                    if (connection != null) {
                        connection.commitText(text, 1, null)
                        Log.d(TAG, "Text via InputMethod.commitText: '${text.take(50)}'")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "InputMethod unavailable for text: ${e.message}")
                }
            }
            
            // 2. ACTION_SET_TEXT on focused input node (all API levels)
            if (injectTextViaSetText(service, text)) {
                Log.d(TAG, "Text via ACTION_SET_TEXT: '${text.take(50)}'")
                return true
            }
            
            Log.w(TAG, "All text injection methods failed")
            return false
        }

        /**
         * Perform a global action (Back, Home, Recents, etc.)
         */
        fun performAction(action: Int): Boolean {
            val service = instance ?: return false
            return try {
                service.performGlobalAction(action)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform global action: ${e.message}")
                false
            }
        }

        private fun mapToGlobalAction(keyCode: Int): Int? {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
                KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
                KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
                else -> null
            }
        }
        
        /**
         * Convert a keyCode (with optional metaState) to its printable character.
         * Uses the Android virtual keyboard character map.
         * Returns null for non-printable keys (arrows, Tab, Escape, etc.).
         */
        private fun keyCodeToChar(keyCode: Int, metaState: Int): Char? {
            return try {
                val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                val unicodeChar = kcm.get(keyCode, metaState)
                if (unicodeChar > 0) unicodeChar.toChar() else null
            } catch (e: Exception) {
                Log.w(TAG, "keyCodeToChar failed: ${e.message}")
                null
            }
        }
        
        /**
         * Inject text into the currently focused input field via ACTION_SET_TEXT.
         * Appends the text to any existing content in the field.
         * Works on all API levels. Requires canRetrieveWindowContent="true" in config.
         */
        private fun injectTextViaSetText(service: FreeKioskAccessibilityService, text: String): Boolean {
            try {
                val rootNode = service.rootInActiveWindow ?: return false
                val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val existing = focusedNode.text?.toString() ?: ""
                    val bundle = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            existing + text
                        )
                    }
                    val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    focusedNode.recycle()
                    rootNode.recycle()
                    return ok
                } else {
                    Log.w(TAG, "No focused input node found for ACTION_SET_TEXT")
                }
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_SET_TEXT injection failed: ${e.message}")
            }
            return false
        }
        
        /**
         * Simulate Backspace by removing the last character from the focused input field.
         * Works on all API levels via ACTION_SET_TEXT.
         */
        private fun deleteLastCharViaSetText(service: FreeKioskAccessibilityService): Boolean {
            try {
                val rootNode = service.rootInActiveWindow ?: return false
                val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val existing = focusedNode.text?.toString() ?: ""
                    if (existing.isNotEmpty()) {
                        val bundle = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                existing.dropLast(1)
                            )
                        }
                        val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                        focusedNode.recycle()
                        rootNode.recycle()
                        return ok
                    }
                    focusedNode.recycle()
                }
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Delete via ACTION_SET_TEXT failed: ${e.message}")
            }
            return false
        }
        
        /**
         * Last resort: exec "input" shell command. This requires elevated privileges
         * and will silently fail on most non-rooted devices.
         */
        private fun execInputCommand(type: String, value: String, logMsg: String): Boolean {
            return try {
                Thread {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("input", type, value))
                        val exitCode = process.waitFor()
                        Log.d(TAG, "$logMsg (exit=$exitCode)")
                    } catch (e: Exception) {
                        Log.e(TAG, "input command failed: ${e.message}")
                    }
                }.start()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exec input command: ${e.message}")
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // On API 33+, ensure InputMethod editor flag is set for text/key injection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                serviceInfo = serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                }
                Log.i(TAG, "InputMethod editor flag enabled (API ${Build.VERSION.SDK_INT})")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set InputMethod editor flag: ${e.message}")
            }
        }
        
        Log.i(TAG, "FreeKiosk Accessibility Service connected (API ${Build.VERSION.SDK_INT})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only use this service for key/text injection
    }

    override fun onInterrupt() {
        Log.w(TAG, "FreeKiosk Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "FreeKiosk Accessibility Service disconnected")
    }
}
