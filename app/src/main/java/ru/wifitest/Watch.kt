package ru.wifitest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал и последнее состояние наблюдения — в SharedPreferences. */
object WatchLog {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))

    fun add(ctx: Context, text: String) {
        val line = "${fmt.format(Date())}  $text"
        val old = sp(ctx).getString("log", "") ?: ""
        val lines = (listOf(line) + old.split("\n").filter { it.isNotBlank() }).take(100)
        sp(ctx).edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun lines(ctx: Context): List<String> =
        (sp(ctx).getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun clear(ctx: Context) = sp(ctx).edit().remove("log").apply()

    fun interval(ctx: Context) = sp(ctx).getInt("min", 5)
    fun setInterval(ctx: Context, m: Int) = sp(ctx).edit().putInt("min", m).apply()

    fun wanted(ctx: Context) = sp(ctx).getBoolean("on", false)
    fun setWanted(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("on", v).apply()

    fun lastNet(ctx: Context) = sp(ctx).getString("net", null)
    fun lastZont(ctx: Context): Boolean? = if (sp(ctx).contains("zont")) sp(ctx).getBoolean("zont", false) else null
    fun saveState(ctx: Context, net: String, zont: Boolean?) {
        val e = sp(ctx).edit().putString("net", net)
        if (zont == null) e.remove("zont") else e.putBoolean("zont", zont)
        e.apply()
    }
    fun status(ctx: Context) = sp(ctx).getString("status", null)
    fun setStatus(ctx: Context, s: String) = sp(ctx).edit().putString("status", s).apply()
}

fun netWord(n: CellTest.Net) = when (n) {
    CellTest.Net.FULL -> "интернет без ограничений"
    CellTest.Net.WHITELIST -> "белые списки"
    CellTest.Net.NONE -> "мобильного интернета нет"
    CellTest.Net.NO_CELL -> "нет мобильной сети"
    CellTest.Net.VPN -> "мешает VPN"
}

/**
 * Фоновое наблюдение за сотовой: раз в N минут через SIM проверяет,
 * открывается ли обычный интернет, только белый список или ничего, и отвечает ли сервер ZONT.
 * При смене — уведомление и запись в журнал.
 */
class WatchService : Service() {
    companion object {
        const val CH_STATUS = "watch_status"
        const val CH_ALERT = "watch_alert"
        const val ACTION_STOP = "ru.wifitest.STOP"
        const val ACTION_NOW = "ru.wifitest.NOW"
        private const val ID_STATUS = 1
        private var alertId = 100
        val running = MutableStateFlow(false)

        fun start(ctx: Context) {
            WatchLog.setWanted(ctx, true)
            ctx.startForegroundService(Intent(ctx, WatchService::class.java))
        }

        fun stop(ctx: Context) {
            WatchLog.setWanted(ctx, false)
            ctx.startService(Intent(ctx, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun checkNow(ctx: Context) {
            ctx.startService(Intent(ctx, WatchService::class.java).setAction(ACTION_NOW))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.value = false
            job?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!WatchLog.wanted(this)) { stopSelf(); return START_NOT_STICKY }
        channels()
        try {
            val n = statusNotif(WatchLog.status(this) ?: "Запускаю проверку…")
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID_STATUS, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_NOW || job?.isActive != true) {
            if (!running.value) WatchLog.add(this, "Наблюдение включено, раз в ${WatchLog.interval(this)} мин")
            running.value = true
            job?.cancel()
            job = scope.launch {
                while (isActive) {
                    checkOnce()
                    delay(WatchLog.interval(this@WatchService) * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun checkOnce() {
        val q = try { CellTest.quick(this) } catch (e: Exception) { CellTest.Quick(CellTest.Net.NONE, null) }
        val prevNet = WatchLog.lastNet(this)
        val prevZont = WatchLog.lastZont(this)
        val nowNet = q.net.name

        // Смена режима интернета (NO_CELL и VPN — не повод будить, только журнал)
        if (prevNet != nowNet) {
            val msg = when (q.net) {
                CellTest.Net.FULL -> if (prevNet == CellTest.Net.WHITELIST.name || prevNet == CellTest.Net.NONE.name)
                    "Интернет снова без ограничений — Google и GitHub открываются" else "Интернет без ограничений"
                CellTest.Net.WHITELIST -> "Включились белые списки — открываются только разрешённые сайты"
                CellTest.Net.NONE -> "Мобильного интернета нет совсем"
                CellTest.Net.NO_CELL -> "Мобильная сеть недоступна (данные выключены?)"
                CellTest.Net.VPN -> "Включён VPN — проверять сотовую отдельно нельзя"
            }
            WatchLog.add(this, msg)
            val important = q.net == CellTest.Net.FULL || q.net == CellTest.Net.WHITELIST || q.net == CellTest.Net.NONE
            if (prevNet != null && important) alert("Сотовая связь", msg)
        }
        if (q.zont != null && prevZont != q.zont) {
            val msg = if (q.zont) "Сервер ZONT по сотовой доступен" else "Сервер ZONT по сотовой недоступен"
            WatchLog.add(this, msg)
            if (prevZont != null) alert("ZONT", msg)
        }
        WatchLog.saveState(this, nowNet, q.zont ?: prevZont)

        val time = SimpleDateFormat("HH:mm", Locale("ru")).format(Date())
        val z = when (q.zont) { true -> "ZONT ✓"; false -> "ZONT ✗"; null -> "ZONT ?" }
        val status = "Сейчас: ${netWord(q.net)} · $z · проверено $time"
        WatchLog.setStatus(this, status)
        getSystemService(NotificationManager::class.java).notify(ID_STATUS, statusNotif(status))
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Наблюдение (постоянное)", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Изменения связи", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusNotif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Слежу за сотовой связью")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "Остановить", stop).build())
            .build()
    }

    private fun alert(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(alertId++, n)
    }
}

@Composable
fun WatchCard() {
    val ctx = LocalContext.current
    val c = LocalColors.current
    val running by WatchService.running.collectAsState()
    var minutes by remember { mutableStateOf(WatchLog.interval(ctx)) }
    var log by remember { mutableStateOf(WatchLog.lines(ctx)) }
    var status by remember { mutableStateOf(WatchLog.status(ctx)) }

    // Журнал обновляется, пока вкладка открыта
    LaunchedEffect(Unit) {
        while (true) {
            log = WatchLog.lines(ctx); status = WatchLog.status(ctx)
            delay(3000)
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { WatchService.start(ctx) }

    Card {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Следить в фоне", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
            Switch(
                checked = running,
                onCheckedChange = { on ->
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else WatchService.start(ctx)
                    } else {
                        WatchService.stop(ctx)
                        WatchLog.add(ctx, "Наблюдение выключено")
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary),
            )
        }
        Text(
            "Раз в несколько минут проверяю через SIM: весь интернет, белые списки или ничего, и сервер ZONT. " +
                "При изменении — уведомление.",
            fontSize = 14.sp, color = c.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Как часто:", fontSize = 15.sp, color = c.muted, modifier = Modifier.weight(1f))
            listOf(1, 5, 15).forEach { m ->
                val sel = minutes == m
                Box(
                    Modifier.height(48.dp).clip(CircleShape)
                        .background(if (sel) c.primary else c.bg)
                        .clickable {
                            minutes = m
                            WatchLog.setInterval(ctx, m)
                            if (running) WatchService.checkNow(ctx)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$m мин", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (sel) c.onPrimary else c.text)
                }
            }
        }
        if (running) status?.let { Text(it, fontSize = 15.sp, color = c.text) }
        if (running) Text("Чаще — точнее, но больше расход батареи. VPN на время наблюдения лучше выключить.", fontSize = 13.sp, color = c.muted)
    }

    if (log.isNotEmpty()) {
        Card {
            Text("Журнал", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            log.take(30).forEach { Text(it, fontSize = 14.sp, color = c.text) }
            TextButton(onClick = { WatchLog.clear(ctx); log = emptyList() }, modifier = Modifier.height(48.dp)) {
                Text("Очистить журнал", fontSize = 15.sp, color = c.muted)
            }
        }
    }
}
