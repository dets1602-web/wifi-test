package ru.wifitest

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Network
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.math.abs

/** Пингер роутера: ICMP, а если роутер на него молчит — TCP по 80/443/53. */
class GwPinger(private val net: Network, private val gw: String) {
    private var mode = 0 // 0 — пробуем ICMP, 1 — ICMP работает, 2 — TCP
    private var fails = 0
    private var port: Int? = null
    private val addr: InetAddress? = try { InetAddress.getByName(gw) } catch (e: Exception) { null }

    /** мс или null — ответа нет */
    fun once(): Double? {
        if (mode != 2) {
            val r = try { NetTests.icmpOnce(gw) } catch (e: Exception) { mode = 2; null }
            if (mode != 2) {
                if (r != null && r >= 0) { mode = 1; return r }
                if (mode == 0 && ++fails >= 3) mode = 2 else return null
            }
        }
        val a = addr ?: return null
        val p = port ?: listOf(80, 443, 53).firstOrNull { NetTests.tcpOnce(net, a, it) != null }?.also { port = it }
            ?: return null
        return NetTests.tcpOnce(net, a, p)
    }
}

/** Скользящее окно: время ответа или null (потеря). */
class Window(private val size: Int) {
    private val items = ArrayDeque<Double?>()
    @Synchronized fun add(v: Double?) { items.addLast(v); while (items.size > size) items.removeFirst() }
    @Synchronized fun count() = items.size
    @Synchronized fun lossPct(): Double = if (items.isEmpty()) 0.0 else items.count { it == null } * 100.0 / items.size
    @Synchronized fun avg(): Double = items.filterNotNull().let { if (it.isEmpty()) 0.0 else it.average() }
    @Synchronized fun jitter(): Double = items.filterNotNull().let { l ->
        if (l.size < 2) 0.0 else l.zipWithNext { a, b -> abs(b - a) }.average()
    }
    @Synchronized fun clear() = items.clear()
}

data class LiveState(
    val rssi: Int = -127,
    val rssiHistory: List<Int> = emptyList(),
    val gwLoss: Double = 0.0, val gwAvg: Double = 0.0, val gwJitter: Double = 0.0, val gwCount: Int = 0,
    val zLoss: Double = 0.0, val zAvg: Double = 0.0, val zCount: Int = 0,
    val zontOk: Boolean = true,
    val score: Int = 0,
    val level: Level = Level.BAD,
    val reason: String = "Меряю…",
)

data class Spot(
    val name: String, val rssi: Int, val gwLoss: Double, val gwAvg: Double,
    val zLoss: Double, val zAvg: Double, val score: Int, val level: Level,
)

/** Оценка места 0–100: сигнал 50 %, обмен с роутером 30 %, связь с ZONT 20 %. */
fun scoreOf(rssi: Int, gwLoss: Double, gwAvg: Double, zLoss: Double, zAvg: Double, zOk: Boolean): Int {
    val sig = ((rssi + 85).coerceIn(0, 35)) / 35.0           // −85 → 0, −50 → 1
    val gw = (1 - gwLoss / 20.0).coerceIn(0.0, 1.0) * (if (gwAvg > 50) 0.7 else 1.0)
    val z = if (!zOk) 0.0 else (1 - zLoss / 20.0).coerceIn(0.0, 1.0) * (if (zAvg > 400) 0.7 else 1.0)
    return ((sig * 50 + gw * 30 + z * 20)).toInt().coerceIn(0, 100)
}

fun liveVerdict(rssi: Int, gwLoss: Double, zLoss: Double, zOk: Boolean, gwCount: Int): Pair<Level, String> {
    val sigL = rssiLevel(rssi)
    return when {
        gwCount < 3 -> Level.WARN to "Меряю…"
        gwLoss > 10 -> Level.BAD to "Пакеты до роутера теряются: ${f0(gwLoss)}%"
        !zOk -> Level.BAD to "Сервер ZONT не отвечает"
        zLoss > 10 -> Level.BAD to "До ZONT теряется ${f0(zLoss)}%"
        sigL == Level.BAD -> Level.BAD to "Слабый сигнал"
        gwLoss > 0 || zLoss > 0 -> Level.WARN to "Есть потери пакетов"
        sigL == Level.WARN -> Level.WARN to "Сигнал на грани"
        else -> Level.GOOD to "Обмен без потерь"
    }
}

fun placeWord(l: Level) = when (l) {
    Level.GOOD -> "Хорошее место"
    Level.WARN -> "Терпимо"
    Level.BAD -> "Плохое место"
}

/** Живой замер: пока экран открыт — каждые 0,4 с пинг роутера, каждые ~1,2 с пинг ZONT. */
suspend fun runLive(ctx: Context, onState: (LiveState) -> Unit) = coroutineScope {
    val gwWin = Window(25)    // ~10 секунд
    val zWin = Window(10)     // ~12 секунд
    val hist = ArrayDeque<Int>()
    var w = WifiReader.read(ctx)
    var net = w.network
    var pinger: GwPinger? = null
    var pingerKey = ""
    var zAddr: InetAddress? = null
    var zResolveTried = 0L

    // ZONT пингуем отдельным потоком, чтобы не тормозить роутер
    launch(Dispatchers.IO) {
        while (isActive) {
            val n = net
            if (n != null) {
                if (zAddr == null && System.currentTimeMillis() - zResolveTried > 5000) {
                    zResolveTried = System.currentTimeMillis()
                    zAddr = listOf("zont.online", "my.zont.online", "zont-online.ru")
                        .firstNotNullOfOrNull { NetTests.resolve(n, it) }
                }
                val a = zAddr
                zWin.add(if (a == null) null else NetTests.tcpOnce(n, a, 443))
            }
            delay(1200)
        }
    }

    var tick = 0
    while (isActive) {
        w = withContext(Dispatchers.IO) { WifiReader.read(ctx) }
        net = w.network
        val n = net
        val gw = w.gateway
        if (n != null && gw != null) {
            val key = "$gw/${w.ssid}"
            if (key != pingerKey) { pinger = GwPinger(n, gw); pingerKey = key; gwWin.clear(); zWin.clear() }
            val p = pinger!!
            gwWin.add(withContext(Dispatchers.IO) { p.once() })
        } else {
            gwWin.add(null)
        }
        if (w.rssi in -126..-1 && tick % 2 == 0) {
            hist.addLast(w.rssi); while (hist.size > 120) hist.removeFirst() // ~1,5 мин
        }
        val zOk = !(zWin.count() >= 5 && zWin.lossPct() >= 100.0)
        val (lvl, why) = if (!w.connected) Level.BAD to "Нет Wi-Fi" else
            liveVerdict(w.rssi, gwWin.lossPct(), zWin.lossPct(), zOk, gwWin.count())
        onState(
            LiveState(
                rssi = w.rssi, rssiHistory = hist.toList(),
                gwLoss = gwWin.lossPct(), gwAvg = gwWin.avg(), gwJitter = gwWin.jitter(), gwCount = gwWin.count(),
                zLoss = zWin.lossPct(), zAvg = zWin.avg(), zCount = zWin.count(), zontOk = zOk,
                score = if (w.connected) scoreOf(w.rssi, gwWin.lossPct(), gwWin.avg(), zWin.lossPct(), zWin.avg(), zOk) else 0,
                level = lvl, reason = why,
            )
        )
        tick++
        delay(400)
    }
}

@Composable
fun LiveScreen(spots: MutableList<Spot>) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    var st by remember { mutableStateOf(LiveState()) }
    var sound by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { runLive(ctx) { st = it } }

    // Экран не гаснет, пока ищем место
    DisposableEffect(Unit) {
        val win = (ctx as? Activity)?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // «Металлоискатель»: чем лучше место, тем чаще пищит
    LaunchedEffect(sound) {
        if (!sound) return@LaunchedEffect
        val tg = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: Exception) { null }
        try {
            while (isActive) {
                val s = st.score
                tg?.startTone(if (st.level == Level.BAD) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_BEEP, 60)
                delay((1500 - s * 13L).coerceIn(200L, 1500L))
            }
        } finally {
            tg?.release()
        }
    }

    Card {
        Text(placeWord(st.level), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.of(st.level))
        Text(st.reason, fontSize = 17.sp, color = c.text)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${st.score}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = c.of(st.level))
            Text(" из 100", fontSize = 18.sp, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(dbm(st.rssi), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.of(rssiLevel(st.rssi)))
                Text(rssiWord(st.rssi), fontSize = 14.sp, color = c.muted)
            }
        }
        Sparkline(st.rssiHistory)
        Text("Сигнал за последние ~1,5 мин", fontSize = 13.sp, color = c.muted)
    }

    Card {
        Text("Обмен данными (последние ~10 с)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        val gwL = when { st.gwLoss > 10 -> Level.BAD; st.gwLoss > 0 -> Level.WARN; else -> Level.GOOD }
        InfoRow(
            "Роутер",
            if (st.gwCount == 0) "—" else if (st.gwLoss >= 100) "не отвечает"
            else "${f0(st.gwAvg)} мс · потери ${f0(st.gwLoss)}%",
            c.of(gwL),
        )
        val zL = when { !st.zontOk || st.zLoss > 10 -> Level.BAD; st.zLoss > 0 -> Level.WARN; else -> Level.GOOD }
        InfoRow(
            "Сервер ZONT",
            if (st.zCount == 0) "—" else if (!st.zontOk) "не отвечает"
            else "${f0(st.zAvg)} мс · потери ${f0(st.zLoss)}%",
            c.of(zL),
        )
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Звук (чаще — лучше)", fontSize = 16.sp, color = c.text, modifier = Modifier.weight(1f))
            Switch(
                checked = sound, onCheckedChange = { sound = it },
                colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary),
            )
        }
        TextButton(
            onClick = { name = "Точка ${spots.size + 1}"; naming = true },
            enabled = st.gwCount >= 10,
            modifier = Modifier.height(48.dp),
        ) {
            Text(
                if (st.gwCount >= 10) "Запомнить эту точку" else "Постойте пару секунд…",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = if (st.gwCount >= 10) c.primary else c.muted,
            )
        }
    }

    if (spots.isNotEmpty()) {
        Card {
            Text("Точки", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            val best = spots.maxByOrNull { it.score }
            spots.sortedByDescending { it.score }.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.of(s.level)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name + if (s === best && spots.size > 1) " · лучшая" else "",
                            fontSize = 16.sp, fontWeight = if (s === best) FontWeight.SemiBold else FontWeight.Normal,
                            color = c.text,
                        )
                        Text(
                            "${dbm(s.rssi)} · роутер потери ${f0(s.gwLoss)}% · ZONT ${f0(s.zAvg)} мс, потери ${f0(s.zLoss)}%",
                            fontSize = 13.sp, color = c.muted,
                        )
                    }
                    Text("${s.score}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.of(s.level))
                }
            }
            TextButton(onClick = { spots.clear() }, modifier = Modifier.height(48.dp)) {
                Text("Очистить точки", fontSize = 15.sp, color = c.muted)
            }
        }
    } else {
        Text(
            "Ходите с телефоном там, где можно поставить контроллер. В каждом подходящем месте " +
                "постойте 10 секунд и нажмите «Запомнить эту точку» — лучшая будет сверху.",
            fontSize = 15.sp, color = c.muted,
        )
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            containerColor = c.surface,
            title = { Text("Название точки", color = c.text) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text,
                        focusedBorderColor = c.primary, cursorColor = c.primary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    spots += Spot(
                        name.ifBlank { "Точка ${spots.size + 1}" }, st.rssi, st.gwLoss, st.gwAvg,
                        st.zLoss, st.zAvg, st.score, st.level,
                    )
                    naming = false
                }) { Text("Сохранить", color = c.primary, fontSize = 16.sp) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Отмена", color = c.muted, fontSize = 16.sp) }
            },
        )
    }
}

@Composable
fun Sparkline(values: List<Int>) {
    val c = LocalColors.current
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val top = -40f
        val bottom = -90f
        fun y(v: Int) = size.height * ((top - v.coerceIn(bottom.toInt(), top.toInt())) / (top - bottom))
        // линии порогов −65 и −72
        listOf(-65 to c.good, -72 to c.warn).forEach { (v, col) ->
            drawLine(col.copy(alpha = 0.35f), Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 2f)
        }
        if (values.size < 2) return@Canvas
        val step = size.width / 119f
        val start = size.width - step * (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = start + i * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(path, c.primary, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}
