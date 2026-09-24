package com.osone.app

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Testes de tela na JVM: pegam regressões como o preview que não abria na Aba de Escrita. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun writingScreen(workspace: WritingWorkspace) = compose.setContent {
        OstieTheme(false) {
            WritingScreen(workspace, LiveSession.get(app), CodeAuthor.get(app), AppDiagnostics.get(app),
                onDiagnostics = {}, onBack = {}, onLive = {})
        }
    }

    @Test fun svgSentByOstieOpensPreviewAutomatically() {
        val workspace = WritingWorkspace.get(app).apply { clear() }
        writingScreen(workspace)
        compose.runOnIdle {
            workspace.publish(JSONObject().put("titulo", "Desenho").put("formato", "svg")
                .put("conteudo", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>"))
        }
        compose.onNodeWithContentDescription("Fechar visualização").assertExists()
        compose.onNodeWithContentDescription("Fechar visualização").performClick()
        compose.onNodeWithContentDescription("Visualizar HTML ou SVG").assertIsEnabled()
    }

    @Test fun typedHtmlCanBePreviewedAndPlainTextCannot() {
        val workspace = WritingWorkspace.get(app).apply { clear(); updateContent("Lista de compras") }
        writingScreen(workspace)
        compose.onNodeWithContentDescription("Visualizar HTML ou SVG").assertIsNotEnabled()
        compose.runOnIdle { workspace.updateContent("<h1>Olá</h1><p>Página de teste</p>") }
        compose.onNodeWithContentDescription("Visualizar HTML ou SVG").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Fechar visualização").assertExists()
    }

    @Test fun routinesScreenCreatesReminderAndAsksForCalendar() {
        var calendarAsked = false
        var notificationsAsked = false
        compose.setContent {
            OstieTheme(false) {
                RoutinesScreen(RoutineStore.get(app), AppDiagnostics.get(app), onDiagnostics = {}, onBack = {},
                    onRunNow = {}, onNeedNotifications = { notificationsAsked = true },
                    calendarAccess = false, onCalendar = { calendarAsked = true })
            }
        }
        compose.onNodeWithText("Permitir agenda").performClick()
        compose.onNodeWithText("Nova rotina").performClick()
        compose.onNodeWithText("Salvar").assertIsNotEnabled()
        compose.onNodeWithText("Só lembrete").assertExists()
        compose.onNodeWithText("Nome").performTextInput("Remédio de teste")
        compose.onNodeWithText("O que o OSTIE deve fazer").performTextInput("Tomar o remédio")
        compose.onNodeWithText("Horário (HH:MM)").performTextReplacement("21:15")
        compose.onNodeWithText("Salvar").assertIsEnabled().performClick()
        compose.onNodeWithText("Remédio de teste").assertExists()
        compose.runOnIdle {
            assertTrue(calendarAsked)
            assertTrue(notificationsAsked)
            val saved = RoutineStore.get(app).routines.first { it.title == "Remédio de teste" }
            assertEquals(21, saved.hour)
            assertEquals(15, saved.minute)
            RoutineStore.get(app).remove(saved.id)
        }
    }

    @Test fun codeAuthorDialogReportsTheChosenModel() {
        var choice: Boolean? = null
        compose.setContent {
            OstieTheme(false) {
                CodeAuthorDialog(CodeRequest(null, "Jogo", "Um jogo da velha em HTML", "html", false) {},
                    voiceLabel = null, textLabel = "Gemini 3.8 Flash",
                    onChoose = { useText, _ -> choice = useText }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Qual modelo deve codar?").assertExists()
        // Sem Live conectado, o modelo de voz não pode ser escolhido.
        compose.onNodeWithText("Modelo de voz").performClick()
        compose.runOnIdle { assertEquals(null, choice) }
        compose.onNodeWithText("Modelo de texto").performClick()
        compose.runOnIdle { assertEquals(true, choice) }
    }
}
