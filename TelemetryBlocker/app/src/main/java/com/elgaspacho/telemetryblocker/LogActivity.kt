package com.elgaspacho.telemetryblocker

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class LogActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
        private const val REFRESH_MS = 1000L
    }

    private var filterPackage: String? = null
    private lateinit var onlyBlocked: CheckBox
    private val adapter = LogAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        filterPackage = intent.getStringExtra(EXTRA_PACKAGE)

        findViewById<TextView>(R.id.log_header).text = filterPackage
            ?.let { getString(R.string.log_header_app, labelOf(it)) }
            ?: getString(R.string.log_header_all)
        onlyBlocked = findViewById(R.id.only_blocked)
        onlyBlocked.setOnCheckedChangeListener { _, _ -> refresh() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            QueryLog.clear()
            refresh()
        }
        val list = findViewById<ListView>(R.id.log_list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.log_empty)
        list.setOnItemClickListener { _, _, position, _ -> showActions(adapter.getItem(position)) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val pkg = filterPackage
        adapter.setItems(QueryLog.snapshot().filter {
            (pkg == null || it.pkg == pkg) && (!onlyBlocked.isChecked || it.blocked)
        })
    }

    private fun labelOf(pkg: String?): String {
        if (pkg == null) return getString(R.string.unknown_app)
        return labels.getOrPut(pkg) {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }

    /** Lets the user block an allowed domain, or add an exception for a blocked one. */
    private fun showActions(entry: QueryLog.Entry) {
        val targets = listOfNotNull(entry.domain, Domains.parentDomain(entry.domain))
        val actions = targets.map { domain ->
            val label = when {
                entry.blocked && domain == entry.domain -> getString(R.string.allow_domain, domain)
                entry.blocked -> getString(R.string.allow_domain_all, domain)
                domain == entry.domain -> getString(R.string.block_domain, domain)
                else -> getString(R.string.block_domain_all, domain)
            }
            label to domain
        }
        AlertDialog.Builder(this)
            .setTitle(entry.domain)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                val domain = actions[which].second
                if (entry.blocked) Prefs.addAllowed(this, domain) else Prefs.addBlocked(this, domain)
                TelemetryVpnService.reload(this)
                Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class LogAdapter : BaseAdapter() {
        private var items: List<QueryLog.Entry> = emptyList()

        fun setItems(newItems: List<QueryLog.Entry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
            val entry = items[position]
            val status = view.findViewById<TextView>(R.id.log_status)
            status.text = getString(
                if (entry.blocked) R.string.log_blocked else R.string.log_allowed,
                timeFormat.format(Date(entry.time)),
            )
            status.setTextColor(if (entry.blocked) Color.rgb(0xD3, 0x2F, 0x2F) else Color.rgb(0x38, 0x8E, 0x3C))
            view.findViewById<TextView>(R.id.log_domain).text = entry.domain
            view.findViewById<TextView>(R.id.log_app).text = labelOf(entry.pkg)
            return view
        }
    }
}
