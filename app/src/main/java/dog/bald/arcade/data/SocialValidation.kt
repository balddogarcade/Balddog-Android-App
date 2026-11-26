package dog.bald.arcade.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object SocialValidation {
    fun validateUsername(username: String): String? {
        val clean = username.trim()
        return if (!clean.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))) {
            "3 a 20 caracteres (solo letras, números y _)"
        } else {
            null
        }
    }

    fun validateSocialLinks(links: SocialLinksDto): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        checkDomain(links.x, setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com"))?.let {
            errors["x"] = it
        }
        checkDomain(links.instagram, setOf("instagram.com", "www.instagram.com"))?.let {
            errors["instagram"] = it
        }
        checkDomain(links.youtube, setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"))?.let {
            errors["youtube"] = it
        }
        checkDomain(links.twitch, setOf("twitch.tv", "www.twitch.tv"))?.let {
            errors["twitch"] = it
        }
        checkDomain(links.github, setOf("github.com", "www.github.com"))?.let {
            errors["github"] = it
        }
        checkHttps(links.website)?.let {
            errors["website"] = it
        }
        val discord = links.discord?.trim().orEmpty()
        if (discord.isNotEmpty()) {
            if (discord.startsWith("http://", ignoreCase = true)) {
                errors["discord"] = "Usa https:// o tu usuario/tag de Discord"
            } else if (discord.startsWith("https://", ignoreCase = true)) {
                checkDomain(discord, setOf("discord.gg", "discord.com", "www.discord.com"))?.let {
                    errors["discord"] = it
                }
            } else if (discord.contains(" ") || discord.length < 2 || discord.length > 37) {
                errors["discord"] = "Usuario/tag de Discord inválido (sin espacios)"
            }
        }
        return errors
    }

    private fun extractHost(url: String): String =
        url
            .substringAfter("://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
            .lowercase()

    private fun checkDomain(
        raw: String?,
        allowedHosts: Set<String>,
    ): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (!v.startsWith("https://", ignoreCase = true)) {
            return "Debe iniciar con https://"
        }
        val host = extractHost(v)
        if (host !in allowedHosts) {
            return "Dominio inválido (usa ${allowedHosts.first()})"
        }
        return null
    }

    private fun checkHttps(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (!v.startsWith("https://", ignoreCase = true)) {
            return "Debe iniciar con https://"
        }
        val host = extractHost(v)
        if (host.isBlank() || !host.contains(".")) {
            return "URL inválida"
        }
        return null
    }

    fun openSocialLinkOrCopy(
        context: Context,
        key: String,
        rawValue: String,
    ) {
        val value = rawValue.trim()
        if (value.isEmpty()) return
        if (key == "discord" && !value.startsWith("https://", ignoreCase = true)) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Discord", value))
            Toast.makeText(context, "Tag de Discord copiado: $value", Toast.LENGTH_SHORT).show()
            return
        }
        if (!value.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(context, "Solo se permiten enlaces seguros https://", Toast.LENGTH_SHORT).show()
            return
        }
        val host = extractHost(value)
        if (host.isBlank() || !host.contains(".")) {
            Toast.makeText(context, "Solo se permiten enlaces seguros https://", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "Enlace inválido", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
        }
    }
}
