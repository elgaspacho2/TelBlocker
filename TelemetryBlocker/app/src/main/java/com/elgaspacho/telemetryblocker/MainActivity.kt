package com.elgaspacho.telemetryblocker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class AppEntry(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

    private enum class Mode { OFF, TELEMETRY, BLOCK_ALL }

    private companion object {
        const val REQUEST_VPN = 1
        const val REFRESH_MS = 1500L
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var googleSwitch: Switch
    private lateinit var search: EditText
    private lateinit var showSystem: CheckBox
    private val adapter = AppAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var allApps: List<AppEntry> = emptyList()
    private val telemetryApps = mutableSetOf<String>()
    private val blockAllApps = mutableSetOf<String>()

    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            adapter.notifyDataSetChanged() // refresh blocked counters
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val googleSwitchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) confirmBlockGoogle() else setGoogleBlocked(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        blockAllApps += Prefs.blockAllPackages(this)
        telemetryApps += Prefs.selectedPackages(this) - blockAllApps

        statusText = findViewById(R.id.status)
        toggleButton = findViewById(R.id.toggle)
        googleSwitch = findViewById(R.id.google_switch)
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
        list.setOnItemClickListener { _, _, position, _ -> chooseMode(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, LogActivity::class.java)
                    .putExtra(LogActivity.EXTRA_PACKAGE, adapter.getItem(position).pkg)
            )
            true
        }

        updateGoogleSwitch()
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
        val initiallySelected = telemetryApps + blockAllApps
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
            (showSystem.isChecked || !it.system || modeOf(it.pkg) != Mode.OFF) &&
                (query.isEmpty() || it.label.lowercase().contains(query) || it.pkg.contains(query))
        })
    }

    private fun modeOf(pkg: String) = when (pkg) {
        in blockAllApps -> Mode.BLOCK_ALL
        in telemetryApps -> Mode.TELEMETRY
        else -> Mode.OFF
    }

    private fun setMode(packages: Collection<String>, mode: Mode) {
        telemetryApps -= packages
        blockAllApps -= packages
        when (mode) {
            Mode.TELEMETRY -> telemetryApps += packages
            Mode.BLOCK_ALL -> blockAllApps += packages
            Mode.OFF -> {}
        }
        Prefs.setSelectedPackages(this, telemetryApps)
        Prefs.setBlockAllPackages(this, blockAllApps)
        adapter.notifyDataSetChanged()
        updateGoogleSwitch()
        if (TelemetryVpnService.isRunning) {
            // Rebuild the tunnel so the new selection applies right away.
            if (telemetryApps.isEmpty() && blockAllApps.isEmpty()) {
                TelemetryVpnService.stop(this)
            } else {
                TelemetryVpnService.start(this)
            }
            refreshSoon()
        }
    }

    private fun chooseMode(app: AppEntry) {
        val modes = Mode.values()
        val labels = arrayOf(
            getString(R.string.mode_off),
            getString(R.string.mode_telemetry),
            getString(R.string.mode_block_all),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setSingleChoiceItems(labels, modeOf(app.pkg).ordinal) { dialog, which ->
                setMode(listOf(app.pkg), modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The switch is on when every installed Google component is in "block everything" mode. */
    private fun updateGoogleSwitch() {
        val installed = GoogleServices.installed(this)
        googleSwitch.setOnCheckedChangeListener(null)
        googleSwitch.isEnabled = installed.isNotEmpty()
        googleSwitch.isChecked = installed.isNotEmpty() && blockAllApps.containsAll(installed)
        googleSwitch.setOnCheckedChangeListener(googleSwitchListener)
    }

    private fun confirmBlockGoogle() {
        AlertDialog.Builder(this)
            .setTitle(R.string.google_confirm_title)
            .setMessage(R.string.google_confirm_message)
            .setPositiveButton(R.string.google_confirm_ok) { _, _ -> setGoogleBlocked(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> updateGoogleSwitch() }
            .setOnCancelListener { updateGoogleSwitch() }
            .show()
    }

    private fun setGoogleBlocked(blocked: Boolean) {
        setMode(GoogleServices.installed(this), if (blocked) Mode.BLOCK_ALL else Mode.OFF)
        applyFilter()
        if (blocked && !TelemetryVpnService.isRunning) {
            Toast.makeText(this, R.string.google_enable_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun onToggle() {
        if (TelemetryVpnService.isRunning) {
            TelemetryVpnService.stop(this)
            refreshSoon()
            return
        }
        if (telemetryApps.isEmpty() && blockAllApps.isEmpty()) {
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
            val summary = getString(R.string.status_on, telemetryApps.size, blockAllApps.size)
            statusText.text = when {
                blockAllApps.isEmpty() -> summary
                TelemetryVpnService.firewallMode -> summary + "\n" + getString(R.string.status_firewall)
                else -> summary + "\n" + getString(R.string.status_dns_only)
            }
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
            val badge = view.findViewById<TextView>(R.id.mode)
            when (modeOf(app.pkg)) {
                Mode.OFF -> {
                    badge.setText(R.string.badge_off)
                    badge.setTextColor(Color.GRAY)
                }
                Mode.TELEMETRY -> {
                    badge.setText(R.string.badge_telemetry)
                    badge.setTextColor(Color.rgb(0x19, 0x76, 0xD2))
                }
                Mode.BLOCK_ALL -> {
                    badge.setText(R.string.badge_block_all)
                    badge.setTextColor(Color.rgb(0xD3, 0x2F, 0x2F))
                }
            }
            return view
        }
    }
}
