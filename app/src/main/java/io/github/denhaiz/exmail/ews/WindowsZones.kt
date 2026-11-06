package io.github.denhaiz.exmail.ews

/**
 * Часовой пояс телефона (IANA, например `Asia/Yekaterinburg`) → идентификатор Windows для EWS
 * (`Ekaterinburg Standard Time`). Exchange понимает только идентификаторы Windows.
 */
object WindowsZones {
    /**
     * Запасной вариант по смещению для поясов без перехода на летнее время (вся Россия и большинство соседей),
     * если ICU не знает пояс (редкие псевдонимы, старые версии Android).
     */
    private val byOffsetMinutes = mapOf(
        0 to "UTC",
        120 to "Kaliningrad Standard Time",
        180 to "Russian Standard Time",
        240 to "Russia Time Zone 3",
        300 to "Ekaterinburg Standard Time",
        360 to "Omsk Standard Time",
        420 to "North Asia Standard Time",
        480 to "North Asia East Standard Time",
        540 to "Yakutsk Standard Time",
        600 to "Vladivostok Standard Time",
        660 to "Magadan Standard Time",
        720 to "Russia Time Zone 11",
    )

    /**
     * @param ianaId пояс телефона.
     * @param toWindows преобразование ICU (на Android — `android.icu.util.TimeZone.getWindowsID`).
     * @param canonical каноническое имя пояса (псевдоним `Europe/Kirov` и т.п. → основной).
     * @param offsetMinutes текущее смещение от UTC; [observesDst] — переходит ли пояс на летнее время.
     */
    fun resolve(
        ianaId: String,
        toWindows: (String) -> String?,
        canonical: (String) -> String?,
        offsetMinutes: Int,
        observesDst: Boolean,
    ): String? {
        runCatching { toWindows(ianaId) }.getOrNull()?.let { return it }
        runCatching { canonical(ianaId)?.let(toWindows) }.getOrNull()?.let { return it }
        return if (!observesDst) byOffsetMinutes[offsetMinutes] else null
    }
}
