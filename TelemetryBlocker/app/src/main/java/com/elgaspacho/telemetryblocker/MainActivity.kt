package com.elgaspacho.telemetryblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class AppEntry(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

    private companion object {
        const val REQUEST_VPN = 1
        const val REFRESH_MS = 1500L
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var search: EditText
    private lateinit var showSystem: CheckBox
    private val adapter = AppAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var allApps: List<AppEntry> = emptyList()
    private val selected = mutableSetOf<String>()

    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            adapter.notifyDataSetChanged() // refresh blocked counters
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        selected += Prefs.selectedPackages(this)

        statusText = findViewById(R.id.status)
        toggleButton = findViewById(R.id.toggle)
        search = findViewById(R.id.search)
        showSystem = findViewById(R.id.show_system)
        val list = findViewById<ListView>(R.id.app_list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.loading)

        toggleButton.setOnClickListener { onToggle() }
        findViewById<Button>(R.id.open_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.open_domains).setOnClickListener {
            startActivity(Intent(this, DomainsActivity::class.java))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        showSystem.setOnCheckedChangeListener { _, _ -> applyFilter() }
        list.setOnItemClickListener { _, _, position, _ -> toggleApp(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, LogActivity::class.java)
                    .putExtra(LogActivity.EXTRA_PACKAGE, adapter.getItem(position).pkg)
            )
            true
        }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun loadApps() {
        val initiallySelected = selected.toSet()
        Thread {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter {
                    it.packageName != packageName &&
                        pm.checkPermission(Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED
                }
                .map {
                    AppEntry(
                        pkg = it.packageName,
                        label = it.loadLabel(pm).toString(),
                        system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        info = it,
                    )
                }
                .sortedWith(compareBy<AppEntry>({ it.pkg !in initiallySelected }, { it.label.lowercase() }))
            runOnUiThread {
                allApps = apps
                applyFilter()
                if (apps.isEmpty()) findViewById<TextView>(R.id.loading).setText(R.string.no_apps)
            }
        }.start()
    }

    private fun applyFilter() {
        val query = search.text.toString().trim().lowercase()
        adapter.setItems(allApps.filter {
            (showSystem.isChecked || !it.system || it.pkg in selected) &&
                (query.isEmpty() || it.label.lowercase().contains(query) || it.pkg.contains(query))
        })
    }

    private fun toggleApp(app: AppEntry) {
        if (!selected.add(app.pkg)) selected.remove(app.pkg)
        Prefs.setSelectedPackages(this, selected)
        adapter.notifyDataSetChanged()
        if (TelemetryVpnService.isRunning) {
            // Rebuild the tunnel so the new selection applies right away.
            if (selected.isEmpty()) TelemetryVpnService.stop(this) else TelemetryVpnService.start(this)
            refreshSoon()
        }
    }

    private fun onToggle() {
        if (TelemetryVpnService.isRunning) {
            TelemetryVpnService.stop(this)
            refreshSoon()
            return
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_first, Toast.LENGTH_LONG).show()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQUEST_VPN) else startProtection()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            startProtection()
        } else {
            Toast.makeText(this, R.string.vpn_refused, Toast.LENGTH_LONG).show()
        }
    }

    private fun startProtection() {
        TelemetryVpnService.start(this)
        refreshSoon()
    }

    private fun refreshSoon() {
        handler.postDelayed({ updateStatus() }, 400)
    }

    private fun updateStatus() {
        if (TelemetryVpnService.isRunning) {
            statusText.text = getString(R.string.status_on, selected.size)
            toggleButton.setText(R.string.stop)
        } else {
            statusText.setText(R.string.status_off)
            toggleButton.setText(R.string.start)
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        private var items: List<AppEntry> = emptyList()
        private val icons = HashMap<String, Drawable>()

        fun setItems(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = items[position]
            view.findViewById<ImageView>(R.id.icon)
                .setImageDrawable(icons.getOrPut(app.pkg) { app.info.loadIcon(packageManager) })
            view.findViewById<TextView>(R.id.label).text = app.label
            val blocked = QueryLog.blockedCount(app.pkg)
            view.findViewById<TextView>(R.id.details).text =
                if (blocked > 0) getString(R.string.app_details_blocked, app.pkg, blocked) else app.pkg
            view.findViewById<CheckBox>(R.id.check).isChecked = app.pkg in selected
            return view
        }
    }
}
