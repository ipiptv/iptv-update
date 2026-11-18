package tv.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

class BootReceiver : BroadcastReceiver() {

    companion object {
        /** 开机后稍等系统网络就绪再启动，避免抢在开机瞬间拉流失败 */
        const val START_DELAY_MS = 3000L
        const val RETRY_INTERVAL_MS = 4000L
        const val MAX_RETRY = 1
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val sp = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        if (!sp.getBoolean("boot", true)) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())

        val attempt = object : Runnable {
            var tries = 0
            override fun run() {
                if (tries < MAX_RETRY && !hasNetwork(appContext)) {
                    tries++
                    handler.postDelayed(this, RETRY_INTERVAL_MS)
                    return
                }
                val i = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(i)
                pendingResult.finish()
            }
        }

        handler.postDelayed(attempt, START_DELAY_MS)
    }

    private fun hasNetwork(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}
