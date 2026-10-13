package cn.rain.zagent

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

// 崩溃详情页：显示未捕获异常堆栈，可长按复制
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        val tv = findViewById<TextView>(R.id.crashText)
        tv.text = CrashBus.last ?: readLatestCrash() ?: "（无崩溃详情）"
    }

    private fun readLatestCrash(): String? {
        val dir = File(filesDir, "crashes")
        if (!dir.isDirectory) return null
        val f = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
        return try { f.readText() } catch (e: Exception) { null }
    }
}