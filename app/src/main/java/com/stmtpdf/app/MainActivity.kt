package com.stmtpdf.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.stmtpdf.app.engine.PdfEngine
import com.stmtpdf.app.ui.AppEvents
import com.stmtpdf.app.ui.AppRoot
import com.stmtpdf.app.ui.StmtTheme

class MainActivity : ComponentActivity() {

    private lateinit var engine: PdfEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        engine = PdfEngine(this)
        setContent {
            StmtTheme {
                AppRoot(engine, intent?.data)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 深链回跳：stmtpdf://checkout/return?order=xxx
        intent.data?.let { AppEvents.pendingReturn.value = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }
}
