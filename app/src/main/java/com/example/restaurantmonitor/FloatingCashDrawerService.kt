package com.example.restaurantmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class FloatingCashDrawerService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    private fun showFloatingButton() {
        val prefs = getSharedPreferences("TakoAppPrefs", Context.MODE_PRIVATE)
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt("floating_btn_x", 100)
        params.y = prefs.getInt("floating_btn_y", 100)

        val btnOpenDrawer = floatingView?.findViewById<ImageButton>(R.id.btn_open_drawer)

        // 🎯 修正拖動與點擊邏輯
        btnOpenDrawer?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return false // 返回 false 讓按鈕可以維持按下的視覺效果
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        
                        // 移動超過 10 像素才判定為拖動
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(floatingView, params)
                            return true // 攔截事件，不讓按鈕觸發 Click
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isMoving) {
                            // 儲存新位置
                            prefs.edit()
                                .putInt("floating_btn_x", params.x)
                                .putInt("floating_btn_y", params.y)
                                .apply()
                            return true // 攔截事件
                        }
                    }
                }
                return false
            }
        })

        // 設置點擊事件
        btnOpenDrawer?.setOnClickListener {
            triggerOpenDrawer()
        }

        windowManager.addView(floatingView, params)
    }

    private fun triggerOpenDrawer() {
        val devices = loadDevicesFromPrefs(this)
        val targetIps = devices.filter { it.hasCashDrawer }.map { it.ip }

        if (targetIps.isEmpty()) {
            Toast.makeText(this, "未設定任何錢箱出單機", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            var successCount = 0
            withContext(Dispatchers.IO) {
                targetIps.forEach { ip ->
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(ip, 9100), 2000)
                        val out = socket.getOutputStream()
                        val openCommand = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())
                        out.write(openCommand)
                        out.flush()
                        out.close()
                        socket.close()
                        successCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            if (successCount > 0) {
                Toast.makeText(this@FloatingCashDrawerService, "已送出開錢箱指令 ($successCount)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@FloatingCashDrawerService, "錢箱連線失敗", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) windowManager.removeView(floatingView)
    }
}
