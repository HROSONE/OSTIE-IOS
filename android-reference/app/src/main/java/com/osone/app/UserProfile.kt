package com.osone.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Nome de quem usa o OSTIE: vem dos Ajustes ou da conversa, nunca fixo no código. */
class UserProfile private constructor(context: Context) {
    companion object {
        @Volatile private var instance: UserProfile? = null
        fun get(context: Context): UserProfile = instance ?: synchronized(this) {
            instance ?: UserProfile(context.applicationContext).also { instance = it }
        }
    }

    private val preferences = context.getSharedPreferences("osone_config", 0)
    var name by mutableStateOf(preferences.getString("user_name", "").orEmpty())
        private set

    fun updateName(value: String) {
        name = value.trim().take(40)
        preferences.edit().putString("user_name", name).apply()
    }

    /** Trecho das instruções dos modelos; [canSave] quando o modelo tem a ferramenta set_user_name. */
    fun identity(canSave: Boolean): String = if (name.isNotBlank())
        " O usuário principal se chama $name; use o nome com naturalidade, sem repetir a cada frase. " +
            "Se outra pessoa estiver usando e se apresentar, trate-a pelo nome dela nesta conversa" +
            (if (canSave) " (só use set_user_name se o próprio usuário principal pedir para mudar como é chamado)." else ".") +
            " Adapte o tratamento (gênero, formalidade) ao que a pessoa mostrar; não presuma."
    else " Você ainda não sabe o nome do usuário: não invente nem use nomes de exemplo." +
        (if (canSave) " Quando for natural, pergunte como ele prefere ser chamado e salve com set_user_name." else "") +
        " Adapte o tratamento (gênero, formalidade) ao que a pessoa mostrar; não presuma."
}
