package com.osone.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Ponte opcional ativada pelo usuário em Ajustes > Acessibilidade; só executa chamadas explícitas. */
class OsoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var active: OsoneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() { active = this }
    private var lastWindowUpdate = 0L
    private var lastAction = 0L
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            lastWindowUpdate = SystemClock.uptimeMillis()
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    fun inspect(): JSONObject {
        val root = rootInActiveWindow ?: return error("A janela atual não expôs controles acessíveis. Tente mostrar a tela.")
        val nodes = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && nodes.length() < 120) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            if (!node.isVisibleToUser) continue
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = if (node.isPassword) "[campo protegido]" else
                (node.text?.toString() ?: node.contentDescription?.toString() ?: "").take(100)
            if (label.isBlank() && !node.isClickable && !node.isEditable && !node.isScrollable) continue
            nodes.put(JSONObject().put("texto", label).put("tipo", node.className?.toString()?.substringAfterLast('.'))
                .put("clicavel", node.isClickable).put("editavel", node.isEditable)
                .put("rolavel", node.isScrollable)
                .put("centro", JSONArray().put(bounds.centerX()).put(bounds.centerY())))
        }
        return JSONObject().put("app", root.packageName?.toString() ?: "desconhecido")
            .put("controles", nodes).put("limite", nodes.length() == 120)
            .put("atualizado_ha_ms", if (lastWindowUpdate > 0) SystemClock.uptimeMillis() - lastWindowUpdate else -1)
    }

    fun checkUi(expected: String): JSONObject {
        if (expected.isBlank()) return error("Informe o texto esperado na tela.")
        val root = rootInActiveWindow ?: return error("Sem janela acessível para verificar.")
        return JSONObject().put("app", root.packageName?.toString() ?: "desconhecido")
            .put("texto", expected.take(120)).put("encontrado", find(expected) != null)
            .put("tela_mudou_apos_acao", lastWindowUpdate > lastAction)
            .put("atualizado_ha_ms", if (lastWindowUpdate > 0) SystemClock.uptimeMillis() - lastWindowUpdate else -1)
    }

    fun interact(label: String, action: String): JSONObject {
        val node = find(label) ?: return error("Controle '$label' não encontrado na tela. Inspecione a tela novamente.")
        val flag = when (action) {
            "tocar" -> AccessibilityNodeInfo.ACTION_CLICK
            "segurar" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            else -> return error("Ação inválida: use tocar ou segurar.")
        }
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null && !candidate.isClickable && action == "tocar") candidate = candidate.parent
        val target = candidate ?: node
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", target.performAction(flag))
    }

    fun type(label: String, text: String): JSONObject {
        if (text.length > 3000) return error("Texto grande demais para o campo.")
        val node = if (label.isBlank()) rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            else find(label)
        if (node?.isEditable != true) return error("Campo editável não encontrado. Inspecione a tela.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
    }

    fun scroll(direction: String): JSONObject {
        val root = rootInActiveWindow ?: return error("Sem janela ativa.")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val action = if (direction == "cima") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (node.isVisibleToUser && node.isScrollable && node.performAction(action)) {
                lastAction = SystemClock.uptimeMillis()
                return JSONObject().put("aceito_pelo_android", true)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return error("Nenhuma área rolável disponível; tente um gesto na tela.")
    }

    fun navigate(action: String): JSONObject {
        val global = when (action) {
            "voltar" -> GLOBAL_ACTION_BACK
            "inicio" -> GLOBAL_ACTION_HOME
            "recentes" -> GLOBAL_ACTION_RECENTS
            "notificacoes" -> GLOBAL_ACTION_NOTIFICATIONS
            "atalhos", "ajustes_rapidos" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return error("Navegação desconhecida.")
        }
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", performGlobalAction(global))
    }

    fun gesture(x: Int, y: Int, endX: Int?, endY: Int?): JSONObject {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels; val height = metrics.heightPixels
        if (listOfNotNull(x, y, endX, endY).any { it < 0 } || x >= width || y >= height ||
            (endX != null && endX >= width) || (endY != null && endY >= height))
            return error("Coordenadas fora da tela (${width}x$height).")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); if (endX != null && endY != null)
            lineTo(endX.toFloat(), endY.toFloat()) }
        val swipe = endX != null && endY != null
        val stroke = GestureDescription.StrokeDescription(path, 0, if (swipe) 420 else 70)
        lastAction = SystemClock.uptimeMillis()
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return JSONObject().put("gesto_aceito", accepted).put("concluido", "Ainda não confirmado; inspecione a tela.")
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        if (label.isBlank()) return null
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var partial: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && !node.isPassword) {
                val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                if (text.equals(label, true) || node.viewIdResourceName?.equals(label, true) == true) return node
                if (partial == null && text.contains(label, true)) partial = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return partial
    }

    private fun error(message: String) = JSONObject().put("erro", message)
}
