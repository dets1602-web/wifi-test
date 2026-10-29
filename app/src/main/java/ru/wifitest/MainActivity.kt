package ru.wifitest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
    }
}

fun rssiLevel(rssi: Int): Level = when {
    rssi >= -65 -> Level.GOOD
    rssi >= -72 -> Level.WARN
    else -> Level.BAD
}

fun rssiWord(rssi: Int): String = when {
    rssi >= -55 -> "Отличный"
    rssi >= -65 -> "Хороший"
    rssi >= -72 -> "Средний"
    rssi >= -80 -> "Слабый"
    else -> "Очень слабый"
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val c = LocalColors.current
    val scope = rememberCoroutineScope()

    var now by remember { mutableStateOf(WifiNow(false)) }
    var hasLoc by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasLoc = it }
    LaunchedEffect(Unit) {
        if (!hasLoc) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = withContext(Dispatchers.IO) { WifiReader.read(ctx) }
            delay(1000)
        }
    }

    var running by remember { mutableStateOf(false) }
    var stepName by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(0) } // 0 — поиск места, 1 — полная проверка Wi-Fi, 2 — сотовая
    var cellResult by remember { mutableStateOf<CellResult?>(null) }
    var cellError by remember { mutableStateOf<String?>(null) }
    val spots = remember { mutableStateListOf<Spot>() }

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Тест связи", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.text)

            ModeSwitch(mode, enabled = !running) { mode = it }

            if (mode == 0) {
                NetLine(now, hasLoc) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                if (now.connected) LiveScreen(spots)
            } else if (mode == 2) {
                if (!running) CellIntro()
                if (running) ProgressCard(stepName, progress)
                cellError?.let { Card { Text(it, fontSize = 16.sp, color = c.bad) } }
                cellResult?.let { r ->
                    if (!running) CellResultCards(r) {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, CellTest.shareText(r))
                        }
                        ctx.startActivity(Intent.createChooser(i, "Отправить результат"))
                    }
                }
                if (cellResult == null && cellError == null && !running) {
                    Text(
                        "Проверка обмена с сервером ZONT через мобильную сеть и режима белых списков. " +
                            "Мобильные данные должны быть включены, VPN — выключен. Около 30 секунд.",
                        fontSize = 15.sp, color = c.muted,
                    )
                }
            } else {

            ConnectionCard(now, hasLoc) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

            if (running) ProgressCard(stepName, progress)

            error?.let {
                Card { Text(it, fontSize = 16.sp, color = c.bad) }
            }

            result?.let { r ->
                if (!running) {
                    VerdictCard(r) {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, Tester.shareText(r))
                        }
                        ctx.startActivity(Intent.createChooser(i, "Отправить результат"))
                    }
                    DetailsCard(r)
                }
            }

            if (result == null && !running) {
                Text(
                    "Встаньте с телефоном у места, где стоит контроллер, и нажмите «Проверить сеть». " +
                        "Проверка идёт около минуты.",
                    fontSize = 15.sp, color = c.muted,
                )
            }
            }
        }

        if (mode == 2) Box(Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        cellError = null
                        cellResult = null
                        progress = 0f
                        stepName = "Начинаю"
                        val (r, err) = try {
                            CellTest.run(ctx) { n, p -> stepName = n; progress = p }
                        } catch (e: Exception) {
                            null to "Проверка прервалась: ${e.message ?: e.javaClass.simpleName}"
                        }
                        cellResult = r
                        cellError = err
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.primary,
                    contentColor = c.onPrimary,
                    disabledContainerColor = c.line,
                    disabledContentColor = c.muted,
                ),
            ) {
                Text(
                    when {
                        running -> "Идёт проверка…"
                        cellResult != null || cellError != null -> "Проверить ещё раз"
                        else -> "Проверить сотовую"
                    },
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (mode == 1) Box(Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        error = null
                        result = null
                        progress = 0f
                        stepName = "Начинаю"
                        val r = try {
                            Tester.run(ctx) { n, p -> stepName = n; progress = p }
                        } catch (e: Exception) {
                            error = "Проверка прервалась: ${e.message ?: e.javaClass.simpleName}"
                            null
                        }
                        if (r == null && error == null) error = "Телефон не подключён к Wi-Fi"
                        result = r
                        running = false
                    }
                },
                enabled = !running && now.connected,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.primary,
                    contentColor = c.onPrimary,
                    disabledContainerColor = c.line,
                    disabledContentColor = c.muted,
                ),
            ) {
                Text(
                    when {
                        running -> "Идёт проверка…"
                        !now.connected -> "Подключитесь к Wi-Fi"
                        result != null -> "Проверить ещё раз"
                        else -> "Проверить сеть"
                    },
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = c.muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 16.sp, color = valueColor ?: c.text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConnectionCard(w: WifiNow, hasLoc: Boolean, askLoc: () -> Unit) {
    val c = LocalColors.current
    Card {
        if (!w.connected) {
            Text("Wi-Fi не подключён", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.bad)
            Text("Подключите телефон к сети заказчика — та же сеть, что у контроллера.", fontSize = 15.sp, color = c.muted)
            return@Card
        }
        Text(w.ssid ?: "Сеть без имени", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        if (w.ssid == null) {
            Text(
                if (hasLoc) "Имя сети не видно — включите геолокацию на телефоне" else "Имя сети не видно без доступа к местоположению",
                fontSize = 14.sp, color = c.muted,
            )
            if (!hasLoc) TextButton(onClick = askLoc, modifier = Modifier.height(48.dp)) {
                Text("Разрешить", fontSize = 16.sp, color = c.primary)
            }
        }

        val lvl = rssiLevel(w.rssi)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(dbm(w.rssi), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = c.of(lvl))
            Spacer(Modifier.width(12.dp))
            Text(rssiWord(w.rssi), fontSize = 18.sp, color = c.of(lvl), modifier = Modifier.padding(bottom = 6.dp))
        }
        SignalBar(w.rssi, c.of(lvl))

        Spacer(Modifier.height(4.dp))
        InfoRow(
            "Диапазон",
            if (w.freq > 0) "${w.band} · канал ${w.channel}" else "—",
            if (w.freq > 0 && !w.is24) c.warn else null,
        )
        if (w.freq > 0 && !w.is24) {
            Text("Контроллер подключается к 2,4 ГГц", fontSize = 14.sp, color = c.warn)
        }
        if (w.vpn) {
            Text("Включён VPN — выключите его на время проверки, иначе связь с интернетом и ZONT меряется через VPN", fontSize = 14.sp, color = c.warn)
        }
        InfoRow(
            "Скорость канала",
            when {
                w.txMbps > 0 && w.rxMbps > 0 -> "${w.txMbps} / ${w.rxMbps} Мбит/с"
                w.txMbps > 0 -> "${w.txMbps} Мбит/с"
                else -> "—"
            },
        )
        w.standard?.let { InfoRow("Стандарт", it) }
        InfoRow(
            "Интернет",
            if (w.validated) "есть" else "нет",
            if (w.validated) c.good else c.bad,
        )
        w.gateway?.let { InfoRow("Роутер", it) }
        w.ip?.let { InfoRow("IP телефона", it) }
    }
}

@Composable
fun SignalBar(rssi: Int, color: Color) {
    val c = LocalColors.current
    val frac = ((rssi + 90).coerceIn(0, 40)) / 40f
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.line)) {
        Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.03f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
fun ProgressCard(name: String, p: Float) {
    val c = LocalColors.current
    Card {
        Text(name, fontSize = 17.sp, color = c.text)
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.line)) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(c.primary))
        }
        Text("Не уходите с места, пока идёт проверка", fontSize = 14.sp, color = c.muted)
    }
}

@Composable
fun VerdictCard(r: TestResult, onShare: () -> Unit) {
    val c = LocalColors.current
    Card {
        Text(r.headline, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.of(r.level))
        Text(Tester.subline(r.level), fontSize = 17.sp, color = c.text)
        Spacer(Modifier.height(4.dp))
        r.reasons.forEach { reason ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 7.dp).size(10.dp).clip(CircleShape).background(c.of(reason.level)),
                )
                Spacer(Modifier.width(10.dp))
                Text(reason.text, fontSize = 16.sp, color = c.text)
            }
        }
        TextButton(onClick = onShare, modifier = Modifier.height(48.dp)) {
            Text("Отправить результат", fontSize = 16.sp, color = c.primary)
        }
    }
}

@Composable
fun DetailsCard(r: TestResult) {
    val c = LocalColors.current
    Card {
        Text("Подробно", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        InfoRow("Сигнал, среднее", dbm(r.rssiAvg), c.of(rssiLevel(r.rssiAvg)))
        InfoRow("Сигнал, от и до", "${dbm(r.rssiMin)} … ${dbm(r.rssiMax)}")
        InfoRow("Диапазон", "${r.band} · канал ${r.channel}")
        r.gateway?.let { PingBlock(it) }
        PingBlock(r.internet)
        PingBlock(r.zont)
        Spacer(Modifier.height(4.dp))
        InfoRow("Скачивание", r.downMbps?.let { "${f1(it)} Мбит/с" } ?: "не измерено")
        InfoRow("Отдача", r.upMbps?.let { "${f1(it)} Мбит/с" } ?: "не измерено")
        if (r.downMbps == null || r.upMbps == null) {
            Text("Сервер замера скорости недоступен — на работу контроллера это не влияет", fontSize = 14.sp, color = c.muted)
        }
    }
}

@Composable
fun PingBlock(p: PingResult) {
    val c = LocalColors.current
    val lossLvl = when {
        p.got == 0 -> Level.BAD
        p.lossPct > 8 -> Level.BAD
        p.lossPct > 0 -> Level.WARN
        else -> Level.GOOD
    }
    Spacer(Modifier.height(4.dp))
    if (p.got == 0) {
        InfoRow(p.title, p.error ?: "не отвечает", c.bad)
    } else {
        InfoRow(p.title, "${f0(p.avgMs)} мс · потери ${f0(p.lossPct)}%", c.of(lossLvl))
        Text(
            "${p.target} · разброс ${f0(p.jitterMs)} мс · макс ${f0(p.maxMs)} мс · ${p.got} из ${p.sent}",
            fontSize = 13.sp, color = c.muted,
        )
    }
}

@Composable
fun ModeSwitch(mode: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("Поиск места", "Wi-Fi", "Сотовая").forEachIndexed { i, t ->
            val sel = mode == i
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) c.primary else c.surface)
                    .clickable(enabled = enabled) { onChange(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (sel) c.onPrimary else c.text,
                )
            }
        }
    }
}

@Composable
fun NetLine(w: WifiNow, hasLoc: Boolean, askLoc: () -> Unit) {
    val c = LocalColors.current
    if (!w.connected) {
        Card {
            Text("Wi-Fi не подключён", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.bad)
            Text("Подключите телефон к сети заказчика — та же сеть, что у контроллера.", fontSize = 15.sp, color = c.muted)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.ssid ?: "Сеть без имени", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(
                "${w.band} · канал ${w.channel}" + (if (w.txMbps > 0) " · ${w.txMbps} Мбит/с" else ""),
                fontSize = 14.sp, color = if (w.freq > 0 && !w.is24) c.warn else c.muted,
            )
        }
        if (w.ssid == null && !hasLoc) TextButton(onClick = askLoc, modifier = Modifier.height(48.dp)) {
            Text("Показать имя", fontSize = 15.sp, color = c.primary)
        }
    }
    if (w.freq > 0 && !w.is24) {
        Text("Телефон на ${w.band}, а контроллер подключается к 2,4 ГГц — переключите телефон на сеть 2,4", fontSize = 14.sp, color = c.warn)
    }
    if (w.vpn) {
            Text("Включён VPN — выключите его на время проверки, иначе связь с интернетом и ZONT меряется через VPN", fontSize = 14.sp, color = c.warn)
        }
}
