package com.example.zagent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * PluginActivity —— 插件库页面（MainActivity「插件」入口）。
 *
 * 数据流：PythonBridge.listPlugins → JSON{plugins:[{file,name,desc,size,mtime}]}
 * 交互：单击看源码（dialog_source 弹窗）；长按删除（确认框 → deletePlugin → reload）。
 * 所有 Python 桥接在后台线程执行，UI 更新切回 runOnUiThread。
 */
class PluginActivity : AppCompatActivity() {

    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private lateinit var pluginList: ListView
    private var loading = false
    private val plugins = ArrayList<PluginItem>()

    /** 插件数据项（与 zagent_app.plugins_list 的 JSON 字段一一对应）。 */
    data class PluginItem(
        val file: String,
        val name: String,
        val desc: String,
        val size: Long,
        val mtime: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin)

        countText = findViewById(R.id.countText)
        emptyText = findViewById(R.id.emptyText)
        pluginList = findViewById(R.id.pluginList)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { reload() }

        pluginList.adapter = PluginAdapter()
        // 单击 → 查看源码
        pluginList.setOnItemClickListener { _, _, position, _ ->
            plugins.getOrNull(position)?.let { viewSource(it) }
        }
        // 长按 → 确认删除
        pluginList.setOnItemLongClickListener { _, _, position, _ ->
            plugins.getOrNull(position)?.let { confirmDelete(it) }
            true
        }

        reload()
    }

    /** 解析 plugins_list 的 JSON 为插件列表。 */
    private fun parseList(json: String): List<PluginItem> {
        val arr: JSONArray = JSONObject(json).optJSONArray("plugins") ?: return emptyList()
        val out = ArrayList<PluginItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PluginItem(
                    file = o.optString("file"),
                    name = o.optString("name"),
                    desc = o.optString("desc"),
                    size = o.optLong("size"),
                    mtime = o.optString("mtime")
                )
            )
        }
        return out
    }

    /** 后台拉取插件列表并刷新 UI。 */
    private fun reload() {
        if (loading) return
        loading = true
        Thread {
            try {
                val list = parseList(PythonBridge.INSTANCE.listPlugins(this))
                runOnUiThread { applyList(list) }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    Toast.makeText(this, "读取失败：$e", Toast.LENGTH_SHORT).show()
                    applyList(emptyList())
                }
            }
        }.start()
    }

    /** UI 线程：应用列表数据（计数/空态/适配器刷新）。 */
    private fun applyList(list: List<PluginItem>) {
        loading = false
        plugins.clear()
        plugins.addAll(list)
        countText.text = "共 ${plugins.size} 个"
        emptyText.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
        pluginList.visibility = if (plugins.isEmpty()) View.GONE else View.VISIBLE
        (pluginList.adapter as? PluginAdapter)?.notifyDataSetChanged()
    }

    /** 后台读取源码 → 弹窗展示（失败 toast 错误信息）。 */
    private fun viewSource(item: PluginItem) {
        Thread {
            val json = try {
                PythonBridge.INSTANCE.readPlugin(this, item.file)
            } catch (e: Exception) {
                """{"ok":false,"err":"$e"}"""
            }
            runOnUiThread {
                loading = false
                val o = try {
                    JSONObject(json)
                } catch (e: Exception) {
                    null
                }
                if (o != null && o.optBoolean("ok")) {
                    val view = layoutInflater.inflate(R.layout.dialog_source, null)
                    view.findViewById<TextView>(R.id.sourceTitle).text =
                        "${item.file}.py（${item.size / 1024} KB）"
                    view.findViewById<TextView>(R.id.sourceText).text =
                        o.optString("content", "")
                    AlertDialog.Builder(this@PluginActivity)
                        .setView(view)
                        .setPositiveButton("关闭", null)
                        .show()
                } else {
                    Toast.makeText(
                        this@PluginActivity,
                        o?.optString("err") ?: "读取失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /** 长按删除确认（.py 不可恢复提示）。 */
    private fun confirmDelete(item: PluginItem) {
        AlertDialog.Builder(this)
            .setTitle("删除插件")
            .setMessage("确定删除「${item.name}」（${item.file}.py，不可恢复）？")
            .setPositiveButton("删除") { _, _ -> doDelete(item) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 后台执行删除并重载列表。 */
    private fun doDelete(item: PluginItem) {
        Thread {
            val json = try {
                PythonBridge.INSTANCE.deletePlugin(this, item.file)
            } catch (e: Exception) {
                """{"ok":false,"err":"$e"}"""
            }
            runOnUiThread {
                loading = false
                val ok = try {
                    JSONObject(json).optBoolean("ok")
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    Toast.makeText(this, "已删除 ${item.file}", Toast.LENGTH_SHORT).show()
                } else {
                    val err = try {
                        JSONObject(json).optString("err")
                    } catch (e: Exception) {
                        "删除失败"
                    }
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
                reload()
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 列表适配器
    // ------------------------------------------------------------------
    private inner class PluginAdapter : BaseAdapter() {

        override fun getCount(): Int = plugins.size

        override fun getItem(position: Int): PluginItem = plugins[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.plugin_row, parent, false)
            val item = getItem(position)
            v.findViewById<TextView>(R.id.pluginName).text = item.name
            v.findViewById<TextView>(R.id.pluginMtime).text = item.mtime
            val desc = v.findViewById<TextView>(R.id.pluginDesc)
            desc.text = if (item.desc.isEmpty()) "（无描述）" else item.desc
            desc.visibility = if (item.desc.isEmpty()) View.GONE else View.VISIBLE
            v.findViewById<TextView>(R.id.pluginMeta).text =
                "${item.size / 1024} KB · 单击看源码 · 长按删除"
            return v
        }
    }
}
