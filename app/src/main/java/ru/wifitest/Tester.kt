package ru.wifitest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale

enum class Level { GOOD, WARN, BAD }

data class Reason(val level: Level, val text: String)

data class TestResult(
    val ssid: String?,
    val band: String,
    val channel: Int,
    val is24: Boolean,
    val rssiMin: Int,
    val rssiMax: Int,
    val rssiAvg: Int,
    val txMbps: Int,
    val gateway: PingResult?,
    val internet: PingResult,
    val zont: PingResult,
    val downMbps: Double?,
    val upMbps: Double?,
    val level: Level,
    val headline: String,
    val reasons: List<Reason>,
)

private val RU = Locale("ru")
fun f1(v: Double): String = String.format(RU, "%.1f", v)
fun f0(v: Double): String = String.format(RU, "%.0f", v)
fun dbm(v: Int): String = "$v dBm".replace("-", "−")

object Tester {
    const val COUNT = 25
    private const val SPEED_SEC = 7.0

    suspend fun run(ctx: Context, progress: (String, Float) -> Unit): TestResult? = coroutineScope {
        val start = WifiReader.read(ctx)
        val net = start.network ?: return@coroutineScope null
        withContext(Dispatchers.IO) { Bind.probe(net) }

        // Пока идёт проверка — следим, как гуляет сигнал
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val s24 = if (start.is24) null else WifiReader.scan24(ctx, start.ssid)
        if (start.is24 && start.rssi in -126..-1) samples += start.rssi
        if (s24 != null) samples += s24
        val sampler = launch {
            var n = 0
            while (isActive) {
                val w = WifiReader.read(ctx)
                // Телефон на 5 ГГц — берём уровень 2,4 ГГц этой же сети из эфира
                val r = if (w.is24) w.rssi else (if (n % 6 == 0) WifiReader.scan24(ctx, w.ssid) else null) ?: (if (s24 != null) -127 else w.rssi)
                n++
                if (r in -126..-1) samples += r
                delay(500)
            }
        }

        val totalSteps = 3f * COUNT + 2f
        var done = 0f
        fun step(name: String) { done += 1f; progress(name, done / totalSteps) }

        val gw = start.gateway?.let { NetTests.pingGateway(net, it, COUNT) { step("Пинг до роутера") } }
        if (gw == null) repeat(COUNT) { step("Пинг до роутера") }

        val inet = NetTests.tcpPing(net, "Интернет", listOf("ya.ru", "google.com"), 443, COUNT) {
            step("Пинг в интернет")
        }
        val zont = NetTests.tcpPing(net, "Сервер ZONT", listOf("zont.online", "my.zont.online", "zont-online.ru"), 443, COUNT) {
            step("Пинг до сервера ZONT")
        }

        progress("Скорость: скачивание", done / totalSteps)
        val down = NetTests.download(net, SPEED_SEC) { progress("Скорость: скачивание · ${f1(it)} Мбит/с", done / totalSteps) }
        done += 1f
        progress("Скорость: отдача", done / totalSteps)
        val up = NetTests.upload(net, SPEED_SEC) { progress("Скорость: отдача · ${f1(it)} Мбит/с", done / totalSteps) }
        done += 1f
        progress("Готово", 1f)

        sampler.cancel()
        val s = synchronized(samples) { samples.toList() }.ifEmpty { listOf(start.rssi) }

        buildResult(start, s, s24 != null, gw, inet, zont, down, up)
    }

    private fun buildResult(
        w: WifiNow, s: List<Int>, used24: Boolean, gw: PingResult?, inet: PingResult, zont: PingResult,
        down: Double?, up: Double?,
    ): TestResult {
        val rMin = s.min()
        val rMax = s.max()
        val rAvg = s.average().toInt()
        val reasons = mutableListOf<Reason>()

        if (w.vpn) {
            reasons += Reason(Level.WARN, "Включён VPN — интернет и ZONT мерились через него. Для точной проверки выключите VPN")
        }
        if (!w.is24 && w.freq > 0) {
            reasons += Reason(
                Level.WARN,
                if (used24)
                    "Телефон на ${w.band}: сигнал оценён по 2,4 ГГц из эфира, а пинги шли через ${w.band}. Для точности выключите 5 ГГц в роутере"
                else
                    "Телефон на ${w.band}, точку 2,4 ГГц не видно — оценка по ${w.band}. Выключите 5 ГГц в роутере"
            )
        }

        // Сигнал оцениваем по худшему за проверку
        reasons += when {
            rMin >= -65 -> Reason(Level.GOOD, "Сигнал хороший: ${dbm(rMin)} в худший момент")
            rMin >= -72 -> Reason(Level.WARN, "Сигнал средний: проседал до ${dbm(rMin)}")
            else -> Reason(Level.BAD, "Сигнал слабый: до ${dbm(rMin)}. Нужен репитер или роутер ближе")
        }
        if (rMax - rMin >= 12) {
            reasons += Reason(Level.WARN, "Сигнал гуляет: от ${dbm(rMin)} до ${dbm(rMax)}")
        }

        if (gw != null) {
            reasons += when {
                gw.error != null -> Reason(Level.WARN, "Роутер не отвечает на пинг — оцениваю по интернету")
                gw.lossPct > 4 -> Reason(Level.BAD, "До роутера теряется ${f0(gw.lossPct)}% пакетов — Wi-Fi рвётся")
                gw.lossPct > 0 -> Reason(Level.WARN, "До роутера потери ${f0(gw.lossPct)}%")
                gw.avgMs > 50 -> Reason(Level.WARN, "Роутер отвечает медленно: ${f0(gw.avgMs)} мс. Эфир забит или далеко")
                gw.jitterMs > 30 -> Reason(Level.WARN, "Задержка до роутера скачет: разброс ${f0(gw.jitterMs)} мс")
                else -> Reason(Level.GOOD, "До роутера без потерь, ${f0(gw.avgMs)} мс")
            }
        }

        val gwOk = gw != null && gw.error == null && gw.lossPct <= 4
        when {
            inet.got == 0 && gwOk ->
                reasons += Reason(Level.BAD, "Роутер есть, интернета нет — проблема в роутере или у провайдера")
            inet.got == 0 ->
                reasons += Reason(Level.BAD, "Интернета через этот Wi-Fi нет")
            inet.lossPct > 8 ->
                reasons += Reason(Level.BAD, "В интернет теряется ${f0(inet.lossPct)}% пакетов")
            inet.lossPct > 2 ->
                reasons += Reason(Level.WARN, "В интернет потери ${f0(inet.lossPct)}%")
            else -> {}
        }

        reasons += when {
            zont.got == 0 -> Reason(Level.BAD, "Сервер ZONT недоступен через этот Wi-Fi (${zont.error ?: "не отвечает"})")
            zont.lossPct > 8 -> Reason(Level.BAD, "До сервера ZONT теряется ${f0(zont.lossPct)}% пакетов")
            zont.lossPct > 2 -> Reason(Level.WARN, "До сервера ZONT потери ${f0(zont.lossPct)}%")
            zont.avgMs > 400 -> Reason(Level.WARN, "До сервера ZONT долго: ${f0(zont.avgMs)} мс")
            else -> Reason(Level.GOOD, "Сервер ZONT отвечает стабильно, ${f0(zont.avgMs)} мс")
        }

        if (down != null && down < 1.0) {
            reasons += Reason(Level.WARN, "Очень низкая скорость: ${f1(down)} Мбит/с")
        }

        val level = when {
            reasons.any { it.level == Level.BAD } -> Level.BAD
            reasons.any { it.level == Level.WARN } -> Level.WARN
            else -> Level.GOOD
        }
        val headline = when (level) {
            Level.GOOD -> "Подходит"
            Level.WARN -> "Подходит с оговорками"
            Level.BAD -> "Не подходит"
        }

        return TestResult(
            ssid = w.ssid, band = w.band, channel = w.channel, is24 = w.is24,
            rssiMin = rMin, rssiMax = rMax, rssiAvg = rAvg, txMbps = w.txMbps,
            gateway = gw, internet = inet, zont = zont, downMbps = down, upMbps = up,
            level = level, headline = headline,
            reasons = reasons.sortedBy { it.level.ordinal * -1 },
        )
    }

    fun subline(level: Level): String = when (level) {
        Level.GOOD -> "Контроллер будет на связи стабильно"
        Level.WARN -> "Работать будет, но возможны обрывы"
        Level.BAD -> "Контроллер будет терять связь"
    }

    fun shareText(r: TestResult): String = buildString {
        appendLine("Проверка Wi-Fi для контроллера ZONT")
        appendLine("Сеть: ${r.ssid ?: "—"} · ${r.band}, канал ${r.channel}")
        appendLine("Итог: ${r.headline}. ${subline(r.level)}")
        appendLine()
        r.reasons.forEach { appendLine("• ${it.text}") }
        appendLine()
        appendLine("Сигнал: ${dbm(r.rssiAvg)} (от ${dbm(r.rssiMin)} до ${dbm(r.rssiMax)})")
        r.gateway?.let { appendLine(pingLine(it)) }
        appendLine(pingLine(r.internet))
        appendLine(pingLine(r.zont))
        appendLine("Скачивание: ${r.downMbps?.let { f1(it) + " Мбит/с" } ?: "не измерено"}")
        appendLine("Отдача: ${r.upMbps?.let { f1(it) + " Мбит/с" } ?: "не измерено"}")
    }

    fun pingLine(p: PingResult): String =
        if (p.got == 0) "${p.title}: ${p.error ?: "не отвечает"}"
        else "${p.title}: ${f0(p.avgMs)} мс, потери ${f0(p.lossPct)}%, разброс ${f0(p.jitterMs)} мс"
}
