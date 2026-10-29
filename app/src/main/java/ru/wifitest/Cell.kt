package ru.wifitest

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import kotlin.coroutines.resume

data class CellInfo(val operator: String?, val type: String?, val dbm: Int?)

data class SiteCheck(val host: String, val ok: Boolean, val ms: Double?)

data class HttpStat(val sent: Int, val ok: Int, val avgMs: Double)

data class CellResult(
    val info: CellInfo,
    val zont: PingResult,
    val zontHttp: HttpStat,
    val white: List<SiteCheck>,
    val other: List<SiteCheck>,
    val level: Level,
    val headline: String,
    val reasons: List<Reason>,
)

/**
 * Проверка через мобильную сеть: Android поднимает сотовый интернет отдельно,
 * даже если телефон сидит на Wi-Fi. Все сокеты строго привязаны к нему — без запасного пути.
 */
object CellTest {
    private val ZONT_HOSTS = listOf("zont.online", "my.zont.online", "zont-online.ru")
    private val WHITE = listOf("ya.ru", "vk.com", "gosuslugi.ru")
    private val OTHER = listOf("google.com", "wikipedia.org", "github.com")
    private const val COUNT = 20

    fun cellInfo(ctx: Context): CellInfo = try {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val op = tm.networkOperatorName?.takeIf { it.isNotBlank() }
        var type: String? = null
        var dbm: Int? = null
        if (Build.VERSION.SDK_INT >= 29) {
            val ss = tm.signalStrength
            val list = ss?.cellSignalStrengths ?: emptyList()
            val best = list.firstOrNull { it is CellSignalStrengthNr }
                ?: list.firstOrNull { it is CellSignalStrengthLte }
                ?: list.firstOrNull { it is CellSignalStrengthWcdma }
                ?: list.firstOrNull { it is CellSignalStrengthGsm }
            type = when (best) {
                is CellSignalStrengthNr -> "5G"
                is CellSignalStrengthLte -> "4G (LTE)"
                is CellSignalStrengthWcdma -> "3G"
                is CellSignalStrengthGsm -> "2G"
                else -> null
            }
            dbm = best?.dbm?.takeIf { it in -150..-20 }
        }
        CellInfo(op, type, dbm)
    } catch (e: Exception) {
        CellInfo(null, null, null)
    }

    /** Поднять мобильный интернет. Вернёт сеть и функцию «отпустить». */
    private suspend fun acquire(ctx: Context): Pair<Network, () -> Unit>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        var cb: ConnectivityManager.NetworkCallback? = null
        val release = { cb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; Unit }
        val net = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Network> { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                cb = callback
                try {
                    cm.requestNetwork(req, callback)
                } catch (e: Exception) {
                    if (cont.isActive) cont.cancel(e)
                }
            }
        }
        if (net == null) { release(); return null }
        return net to release
    }

    private fun resolve(net: Network, host: String): InetAddress? = try {
        val all = net.getAllByName(host)
        all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    } catch (e: Exception) {
        null
    }

    private fun tcpOnce(net: Network, addr: InetAddress, port: Int): Double? {
        val t0 = System.nanoTime()
        val s = net.socketFactory.createSocket() // без запасного пути: только сотовая
        return try {
            s.connect(InetSocketAddress(addr, port), 2000)
            (System.nanoTime() - t0) / 1e6
        } catch (e: ConnectException) {
            val ms = (System.nanoTime() - t0) / 1e6
            if (e.message?.contains("refused", true) == true && ms < 2000) ms else null
        } catch (e: Exception) {
            null
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /** Настоящий обмен: HTTPS-запрос, ответ получен — значит данные ходят. */
    private fun httpsOnce(net: Network, host: String): Double? {
        val t0 = System.nanoTime()
        var c: HttpURLConnection? = null
        return try {
            c = net.openConnection(URL("https://$host/")) as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.responseCode // любой код ответа = сервер ответил
            (System.nanoTime() - t0) / 1e6
        } catch (e: Exception) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun run(ctx: Context, progress: (String, Float) -> Unit): Pair<CellResult?, String?> {
        progress("Включаю мобильный интернет", 0.02f)
        val info = cellInfo(ctx)
        val acq = acquire(ctx)
            ?: return null to "Мобильный интернет не поднялся. Включите мобильные данные и проверьте, что есть сеть оператора."
        val (net, release) = acq
        try {
            // VPN может запретить привязку к сотовой сети — тогда честно мерить нельзя
            val bindOk = withContext(Dispatchers.IO) {
                try { net.socketFactory.createSocket().close(); true } catch (e: Exception) { false }
            }
            if (!bindOk) return null to "VPN не даёт проверить сотовую отдельно. Выключите VPN и повторите."

            val total = COUNT + 5 + WHITE.size + OTHER.size + 1f
            var done = 0f
            fun step(n: String) { done += 1f; progress(n, done / total) }

            // 1. Пинг до ZONT
            val zont = withContext(Dispatchers.IO) {
                var host = ZONT_HOSTS.first()
                var addr: InetAddress? = null
                for (h in ZONT_HOSTS) { addr = resolve(net, h); if (addr != null) { host = h; break } }
                val times = mutableListOf<Double>()
                if (addr == null) {
                    repeat(COUNT) { step("Пинг до ZONT") }
                    PingResult.from("Сервер ZONT", host, COUNT, times, "Адрес не находится (DNS)")
                } else {
                    repeat(COUNT) {
                        tcpOnce(net, addr, 443)?.let { times += it }
                        step("Пинг до ZONT по сотовой")
                        delay(200)
                    }
                    PingResult.from("Сервер ZONT", host, COUNT, times, if (times.isEmpty()) "Сервер не отвечает" else null)
                }
            }

            // 2. Обмен данными с ZONT (HTTPS)
            val httpTimes = mutableListOf<Double>()
            withContext(Dispatchers.IO) {
                repeat(5) {
                    httpsOnce(net, zont.target)?.let { httpTimes += it }
                    step("Обмен данными с ZONT")
                }
            }
            val zHttp = HttpStat(5, httpTimes.size, if (httpTimes.isEmpty()) 0.0 else httpTimes.average())

            // 3. Белый список
            val white = withContext(Dispatchers.IO) {
                WHITE.map { h -> httpsOnce(net, h).let { SiteCheck(h, it != null, it) }.also { step("Сайты из белого списка") } }
            }
            val other = withContext(Dispatchers.IO) {
                OTHER.map { h -> httpsOnce(net, h).let { SiteCheck(h, it != null, it) }.also { step("Обычные сайты") } }
            }
            progress("Готово", 1f)
            return build(cellInfo(ctx).let { if (it.dbm == null) info else it }, zont, zHttp, white, other) to null
        } finally {
            release()
        }
    }

    private fun build(info: CellInfo, zont: PingResult, zHttp: HttpStat, white: List<SiteCheck>, other: List<SiteCheck>): CellResult {
        val reasons = mutableListOf<Reason>()
        val whiteOk = white.count { it.ok }
        val otherOk = other.count { it.ok }
        val zontWorks = zont.got > 0 || zHttp.ok > 0

        info.dbm?.let { d ->
            reasons += when {
                d >= -95 -> Reason(Level.GOOD, "Сигнал сотовой хороший: ${dbm(d)}")
                d >= -105 -> Reason(Level.WARN, "Сигнал сотовой средний: ${dbm(d)}")
                else -> Reason(Level.BAD, "Сигнал сотовой слабый: ${dbm(d)}. Антенну контроллера — повыше, наружу")
            }
        }

        when {
            whiteOk == 0 && otherOk == 0 && !zontWorks ->
                reasons += Reason(Level.BAD, "Интернета по сотовой нет совсем — ни сайты, ни ZONT")
            whiteOk > 0 && otherOk == 0 ->
                reasons += Reason(Level.WARN, "Похоже, действуют белые списки: обычные сайты не открываются, разрешённые — открываются")
            otherOk > 0 ->
                reasons += Reason(Level.GOOD, "Мобильный интернет без ограничений")
            else -> {}
        }

        reasons += when {
            !zontWorks -> Reason(Level.BAD, "Сервер ZONT по сотовой недоступен" +
                if (whiteOk > 0 && otherOk == 0) " — его нет в белом списке этого оператора" else "")
            zHttp.ok < zHttp.sent -> Reason(Level.WARN, "Обмен с ZONT с перебоями: ${zHttp.ok} из ${zHttp.sent} запросов")
            zont.lossPct > 10 -> Reason(Level.BAD, "До ZONT теряется ${f0(zont.lossPct)}% пакетов")
            zont.lossPct > 2 -> Reason(Level.WARN, "До ZONT потери ${f0(zont.lossPct)}%")
            zont.avgMs > 600 -> Reason(Level.WARN, "До ZONT долго: ${f0(zont.avgMs)} мс")
            else -> Reason(
                Level.GOOD,
                "Обмен с ZONT стабильный, ${f0(zont.avgMs)} мс" + if (whiteOk > 0 && otherOk == 0) " — ZONT проходит через белый список" else "",
            )
        }

        val level = when {
            !zontWorks -> Level.BAD
            reasons.any { it.level == Level.BAD } -> Level.BAD
            reasons.any { it.level == Level.WARN } -> Level.WARN
            else -> Level.GOOD
        }
        val headline = when (level) {
            Level.GOOD -> "ZONT по сотовой работает"
            Level.WARN -> "Работает с оговорками"
            Level.BAD -> if (zontWorks) "Связь плохая" else "ZONT не работает"
        }
        return CellResult(info, zont, zHttp, white, other, level, headline, reasons.sortedBy { -it.level.ordinal })
    }

    fun shareText(r: CellResult): String = buildString {
        appendLine("Проверка сотовой связи для контроллера ZONT")
        appendLine("Оператор: ${r.info.operator ?: "—"} · ${r.info.type ?: "—"} · ${r.info.dbm?.let { dbm(it) } ?: "—"}")
        appendLine("Итог: ${r.headline}")
        appendLine()
        r.reasons.forEach { appendLine("• ${it.text}") }
        appendLine()
        appendLine(Tester.pingLine(r.zont))
        appendLine("HTTPS-запросы к ZONT: ${r.zontHttp.ok} из ${r.zontHttp.sent}")
        appendLine("Белый список: " + r.white.joinToString { "${it.host} ${if (it.ok) "✓" else "✗"}" })
        appendLine("Обычные сайты: " + r.other.joinToString { "${it.host} ${if (it.ok) "✓" else "✗"}" })
    }
}

@Composable
fun CellIntro() {
    val c = LocalColors.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val info = CellTest.cellInfo(ctx)
    Card {
        Text(info.operator ?: "Оператор не определён", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        InfoRow("Сеть", info.type ?: "—")
        InfoRow("Сигнал", info.dbm?.let { dbm(it) } ?: "—")
        Text(
            "Проверка идёт через SIM телефона, даже если включён Wi-Fi. У контроллера своя SIM — " +
                "у другого оператора белый список и покрытие могут отличаться.",
            fontSize = 14.sp, color = c.muted,
        )
    }
}

@Composable
fun CellResultCards(r: CellResult, onShare: () -> Unit) {
    val c = LocalColors.current
    Card {
        Text(r.headline, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.of(r.level))
        Spacer(Modifier.height(4.dp))
        r.reasons.forEach { reason ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(10.dp).clip(CircleShape).background(c.of(reason.level)))
                Spacer(Modifier.width(10.dp))
                Text(reason.text, fontSize = 16.sp, color = c.text)
            }
        }
        TextButton(onClick = onShare, modifier = Modifier.height(48.dp)) {
            Text("Отправить результат", fontSize = 16.sp, color = c.primary)
        }
    }
    Card {
        Text("Подробно", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        InfoRow("Оператор", listOfNotNull(r.info.operator, r.info.type).joinToString(" · ").ifBlank { "—" })
        r.info.dbm?.let { InfoRow("Сигнал", dbm(it)) }
        PingBlock(r.zont)
        InfoRow(
            "HTTPS к ZONT",
            "${r.zontHttp.ok} из ${r.zontHttp.sent}" + if (r.zontHttp.ok > 0) " · ${f0(r.zontHttp.avgMs)} мс" else "",
            c.of(if (r.zontHttp.ok == r.zontHttp.sent) Level.GOOD else if (r.zontHttp.ok > 0) Level.WARN else Level.BAD),
        )
        Spacer(Modifier.height(4.dp))
        Text("Белый список", fontSize = 15.sp, color = c.muted)
        r.white.forEach { SiteRow(it) }
        Text("Обычные сайты", fontSize = 15.sp, color = c.muted)
        r.other.forEach { SiteRow(it) }
    }
}

@Composable
fun SiteRow(s: SiteCheck) {
    val c = LocalColors.current
    InfoRow(
        s.host,
        if (s.ok) "открывается · ${f0(s.ms ?: 0.0)} мс" else "не открывается",
        if (s.ok) c.good else c.bad,
    )
}
