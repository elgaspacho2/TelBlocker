package com.elgaspacho.telemetryblocker

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class DomainsActivity : Activity() {

    private lateinit var blockedInput: EditText
    private lateinit var allowedInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_domains)

        val builtIn = Blocklist.builtIn(this)
        findViewById<TextView>(R.id.builtin_info).text = getString(R.string.builtin_info, builtIn.size)
        findViewById<Button>(R.id.show_builtin).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.builtin_title)
                .setMessage(builtIn.sorted().joinToString("\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        blockedInput = findViewById(R.id.custom_blocked)
        allowedInput = findViewById(R.id.custom_allowed)
        blockedInput.setText(Prefs.customBlocked(this).sorted().joinToString("\n"))
        allowedInput.setText(Prefs.customAllowed(this).sorted().joinToString("\n"))

        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    private fun save() {
        Prefs.setCustomBlocked(this, Domains.parseLines(blockedInput.text.toString()))
        Prefs.setCustomAllowed(this, Domains.parseLines(allowedInput.text.toString()))
        TelemetryVpnService.reload(this)
        Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
