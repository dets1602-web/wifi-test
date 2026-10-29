package ru.wifitest

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/** Что сейчас показывает Wi-Fi телефона. */
data class WifiNow(
    val connected: Boolean,
    val ssid: String? = null,
    val rssi: Int = -127,
    val freq: Int = 0,
    val txMbps: Int = -1,
    val rxMbps: Int = -1,
    val standard: String? = null,
    val ip: String? = null,
    val gateway: String? = null,
    val validated: Boolean = false,
    val vpn: Boolean = false,
    val network: Network? = null,
) {
    val band: String
        get() = when {
            freq in 2400..2500 -> "2,4 ГГц"
            freq in 4900..5900 -> "5 ГГц"
            freq >= 5925 -> "6 ГГц"
            else -> "—"
        }
    val channel: Int
        get() = when {
            freq == 2484 -> 14
            freq in 2400..2500 -> (freq - 2407) / 5
            freq in 4900..5900 -> (freq - 5000) / 5
            freq >= 5925 -> (freq - 5950) / 5
            else -> 0
        }
    val is24: Boolean get() = freq in 2400..2500
}

object WifiReader {
    private var lastScan = 0L

    /** Уровни 2,4 и 5 ГГц той же сети из эфира (сканирование): (2,4; 5). */
    @Suppress("DEPRECATION")
    fun scanBands(ctx: Context, ssid: String?): Pair<Int?, Int?> {
        if (ssid == null) return null to null
        return try {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            val now = System.currentTimeMillis()
            if (now - lastScan > 15_000) { lastScan = now; wm.startScan() } // Android сам ограничивает частоту
            val mine = wm.scanResults.filter { it.SSID?.trim('"') == ssid }
            mine.filter { it.frequency in 2400..2500 }.maxOfOrNull { it.level } to
                mine.filter { it.frequency in 4900..5900 }.maxOfOrNull { it.level }
        } catch (e: Exception) {
            null to null
        }
    }

    /** Уровень 2,4 ГГц той же сети из эфира, даже если телефон сидит на 5 ГГц. */
    fun scan24(ctx: Context, ssid: String?): Int? = scanBands(ctx, ssid).first

    fun wifiNetwork(cm: ConnectivityManager): Network? =
        cm.allNetworks.firstOrNull {
            val caps = cm.getNetworkCapabilities(it)
            // VPN тоже «носит» признак Wi-Fi — его отбрасываем, нужна сама сеть Wi-Fi
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

    @Suppress("DEPRECATION")
    fun read(ctx: Context): WifiNow {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            val net = wifiNetwork(cm) ?: return WifiNow(false)
            val info = wm.connectionInfo ?: return WifiNow(false)
            val caps = cm.getNetworkCapabilities(net)
            val lp = cm.getLinkProperties(net)
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            val tx = if (Build.VERSION.SDK_INT >= 29) info.txLinkSpeedMbps else info.linkSpeed
            val rx = if (Build.VERSION.SDK_INT >= 29) info.rxLinkSpeedMbps else -1
            val std = if (Build.VERSION.SDK_INT >= 30) {
                when (info.wifiStandard) {
                    ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
                    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
                    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
                    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
                    else -> null
                }
            } else null
            val ip = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
            val gwRoute = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
                ?: lp?.routes?.map { it.gateway }?.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress
            val dhcpGw = wm.dhcpInfo?.gateway ?: 0
            val gw = gwRoute ?: if (dhcpGw != 0) {
                "${dhcpGw and 0xFF}.${dhcpGw shr 8 and 0xFF}.${dhcpGw shr 16 and 0xFF}.${dhcpGw shr 24 and 0xFF}"
            } else null
            val vpn = cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            WifiNow(
                connected = true,
                ssid = ssid,
                rssi = info.rssi,
                freq = info.frequency,
                txMbps = tx,
                rxMbps = rx,
                standard = std,
                ip = ip,
                gateway = gw,
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                vpn = vpn,
                network = net,
            )
        } catch (e: Exception) {
            WifiNow(false)
        }
    }
}

/**
 * Привязка к Wi-Fi: замеры идут именно через Wi-Fi, даже при включённых мобильных данных.
 * Если Android запрещает привязку (включён VPN — EPERM), работаем обычным путём.
 */
object Bind {
    @Volatile var direct = false

    fun probe(net: Network) {
        direct = try { net.socketFactory.createSocket().close(); false } catch (e: Exception) { true }
    }

    fun socket(net: Network): Socket {
        if (!direct) try { return net.socketFactory.createSocket() } catch (e: Exception) { direct = true }
        return Socket()
    }

    fun open(net: Network, url: URL): URLConnection {
        if (!direct) try { return net.openConnection(url) } catch (e: Exception) { direct = true }
        return url.openConnection()
    }

    fun lookup(net: Network, host: String): Array<InetAddress> {
        if (!direct) try { return net.getAllByName(host) } catch (e: Exception) { /* ниже без привязки */ }
        return InetAddress.getAllByName(host)
    }
}

/** Итог серии пингов до одной цели. */
data class PingResult(
    val title: String,
    val target: String,
    val sent: Int,
    val got: Int,
    val avgMs: Double,
    val maxMs: Double,
    val jitterMs: Double,
    val error: String? = null,
) {
    val lossPct: Double get() = if (sent == 0) 100.0 else (sent - got) * 100.0 / sent

    companion object {
        fun from(title: String, target: String, sent: Int, times: List<Double>, error: String? = null): PingResult {
            val jit = if (times.size < 2) 0.0 else times.zipWithNext { a, b -> abs(b - a) }.average()
            return PingResult(
                title, target, sent, times.size,
                if (times.isEmpty()) 0.0 else times.average(),
                times.maxOrNull() ?: 0.0,
                jit,
                error,
            )
        }
    }
}

/** Сервер, к которому подключаются контроллеры ZONT (по данным ZONT: s1.zont-online.ru, 77.223.120.219). */
val ZONT_SERVER = listOf("s1.zont-online.ru", "77.223.120.219")
val ZONT_PORTS = listOf(443, 80, 8080)

object NetTests {
    private const val TIMEOUT_MS = 1000
    private const val PAUSE_MS = 150L

    /** Один TCP-«пинг»: соединение или отказ (RST) — значит узел ответил. Время в мс или null. */
    fun tcpOnce(net: Network, addr: InetAddress, port: Int): Double? {
        val t0 = System.nanoTime()
        val s = Bind.socket(net)
        return try {
            s.connect(InetSocketAddress(addr, port), TIMEOUT_MS)
            (System.nanoTime() - t0) / 1e6
        } catch (e: ConnectException) {
            val ms = (System.nanoTime() - t0) / 1e6
            if (e.message?.contains("refused", ignoreCase = true) == true && ms < TIMEOUT_MS) ms else null
        } catch (e: Exception) {
            null
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    fun resolve(net: Network, host: String): InetAddress? = try {
        val all = Bind.lookup(net, host)
        all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Серия TCP-пингов до сервера в интернете (через Wi-Fi, даже если включены мобильные данные).
     *  Порт выбирается сам: первый из списка, на который сервер ответил. */
    suspend fun tcpPing(
        net: Network, title: String, hosts: List<String>, ports: List<Int>, count: Int, onStep: () -> Unit,
    ): PingResult = withContext(Dispatchers.IO) {
        var host = hosts.first()
        var addr: InetAddress? = null
        for (h in hosts) {
            addr = resolve(net, h)
            if (addr != null) { host = h; break }
        }
        if (addr == null) {
            repeat(count) { onStep() }
            return@withContext PingResult.from(title, host, count, emptyList(), "Адрес не находится (DNS не работает)")
        }
        val times = mutableListOf<Double>()
        var port: Int? = null
        repeat(count) {
            val p = port
            if (p != null) {
                tcpOnce(net, addr, p)?.let { times += it }
            } else {
                for (pp in ports) {
                    val t = tcpOnce(net, addr, pp)
                    if (t != null) { port = pp; times += t; break }
                }
            }
            onStep()
            delay(PAUSE_MS)
        }
        PingResult.from(title, host, count, times, if (times.isEmpty()) "Сервер не отвечает" else null)
    }

    /** ICMP-пинг системной утилитой. null — если утилита недоступна. */
    fun icmpOnce(host: String): Double? {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "1", "-W", "1", host))
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return -1.0 }
        val out = p.inputStream.bufferedReader().readText()
        if (p.exitValue() != 0) return -1.0
        val m = Regex("time[=<]([0-9.]+)").find(out) ?: return -1.0
        return m.groupValues[1].toDoubleOrNull() ?: -1.0
    }

    /** Пинг роутера: сначала ICMP, если роутер на него молчит — TCP по портам 80/443/53. */
    suspend fun pingGateway(net: Network, gw: String, count: Int, onStep: () -> Unit): PingResult =
        withContext(Dispatchers.IO) {
            val title = "Роутер"
            val times = mutableListOf<Double>()
            var icmpWorks = true
            var done = 0
            // Первые 3 попытки решают, отвечает ли роутер на ICMP
            while (done < count) {
                val r = try { icmpOnce(gw) } catch (e: Exception) { icmpWorks = false; null }
                if (!icmpWorks) break
                if (r != null && r >= 0) times += r
                done++
                onStep()
                if (done == 3 && times.isEmpty()) { icmpWorks = false; break }
                delay(PAUSE_MS)
            }
            if (icmpWorks) return@withContext PingResult.from(title, gw, count, times)

            // Запасной путь: TCP
            times.clear()
            val addr = InetAddress.getByName(gw)
            val port = listOf(80, 443, 53).firstOrNull { tcpOnce(net, addr, it) != null }
            if (port == null) {
                repeat(count - done) { onStep() }
                return@withContext PingResult.from(title, gw, count, emptyList(), "Роутер не отвечает на проверку")
            }
            repeat(count) { i ->
                tcpOnce(net, addr, port)?.let { times += it }
                if (i >= done) onStep()
                delay(PAUSE_MS)
            }
            PingResult.from(title, gw, count, times)
        }

    /** Скорость скачивания, Мбит/с. null — сервер недоступен. */
    suspend fun download(net: Network, seconds: Double, onLive: (Double) -> Unit): Double? =
        withContext(Dispatchers.IO) {
            var c: HttpURLConnection? = null
            try {
                c = Bind.open(net, URL("https://speed.cloudflare.com/__down?bytes=100000000")) as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                val input = c.inputStream
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val start = System.nanoTime()
                var last = start
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    val now = System.nanoTime()
                    val el = (now - start) / 1e9
                    if (now - last > 300_000_000L && el > 0) { onLive(total * 8 / el / 1e6); last = now }
                    if (el > seconds) break
                }
                val el = (System.nanoTime() - start) / 1e9
                if (el <= 0 || total == 0L) null else total * 8 / el / 1e6
            } catch (e: Exception) {
                null
            } finally {
                try { c?.disconnect() } catch (_: Exception) {}
            }
        }

    /** Скорость отдачи, Мбит/с. null — сервер недоступен. */
    suspend fun upload(net: Network, seconds: Double, onLive: (Double) -> Unit): Double? =
        withContext(Dispatchers.IO) {
            var c: HttpURLConnection? = null
            try {
                c = Bind.open(net, URL("https://speed.cloudflare.com/__up")) as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 10000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setChunkedStreamingMode(64 * 1024)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                val buf = Random.nextBytes(64 * 1024)
                val out = c.outputStream
                var total = 0L
                val start = System.nanoTime()
                var last = start
                while (total < 50_000_000L) {
                    out.write(buf)
                    total += buf.size
                    val now = System.nanoTime()
                    val el = (now - start) / 1e9
                    if (now - last > 300_000_000L && el > 0) { onLive(total * 8 / el / 1e6); last = now }
                    if (el > seconds) break
                }
                out.flush()
                out.close()
                val el = (System.nanoTime() - start) / 1e9
                val code = c.responseCode
                if (code !in 200..299 || el <= 0) null else total * 8 / el / 1e6
            } catch (e: Exception) {
                null
            } finally {
                try { c?.disconnect() } catch (_: Exception) {}
            }
        }
}
