package com.example.restaurantmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 監聽系統開機廣播
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "偵測到系統開機，啟動 TAKO 管家")

            // 建立啟動 MainActivity 的指令
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 必須加這行，才能從背景啟動畫面
            }
            context.startActivity(i)
        }
    }
}