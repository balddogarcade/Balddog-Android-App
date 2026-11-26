package dog.bald.arcade.data

data class CountryOption(
    val code: String,
    val name: String,
) {
    fun flagEmoji(): String = CountryCatalog.flagEmoji(code)
}

object CountryCatalog {
    val countries: List<CountryOption> =
        listOf(
            CountryOption("AR", "Argentina"),
            CountryOption("BO", "Bolivia"),
            CountryOption("BR", "Brasil"),
            CountryOption("CL", "Chile"),
            CountryOption("CO", "Colombia"),
            CountryOption("CR", "Costa Rica"),
            CountryOption("CU", "Cuba"),
            CountryOption("DO", "República Dominicana"),
            CountryOption("EC", "Ecuador"),
            CountryOption("SV", "El Salvador"),
            CountryOption("ES", "España"),
            CountryOption("US", "Estados Unidos"),
            CountryOption("GT", "Guatemala"),
            CountryOption("HN", "Honduras"),
            CountryOption("MX", "México"),
            CountryOption("NI", "Nicaragua"),
            CountryOption("PA", "Panamá"),
            CountryOption("PY", "Paraguay"),
            CountryOption("PE", "Perú"),
            CountryOption("PR", "Puerto Rico"),
            CountryOption("UY", "Uruguay"),
            CountryOption("VE", "Venezuela"),
            CountryOption("CA", "Canadá"),
            CountryOption("DE", "Alemania"),
            CountryOption("FR", "Francia"),
            CountryOption("GB", "Reino Unido"),
            CountryOption("IT", "Italia"),
            CountryOption("PT", "Portugal"),
            CountryOption("JP", "Japón"),
            CountryOption("KR", "Corea del Sur"),
            CountryOption("TW", "Taiwán"),
            CountryOption("AU", "Australia"),
        )

    fun flagEmoji(code: String?): String {
        val clean = code?.trim()?.uppercase().orEmpty()
        if (clean.length != 2 || !clean.all { it in 'A'..'Z' }) return ""
        val first = Character.codePointAt(clean, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(clean, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun findByCode(code: String?): CountryOption? {
        val clean = code?.trim()?.uppercase() ?: return null
        return countries.firstOrNull { it.code == clean }
    }
}
