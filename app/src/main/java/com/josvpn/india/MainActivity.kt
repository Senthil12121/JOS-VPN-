package com.josvpn.india

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var profileView: TextView
    private lateinit var appsView: TextView
    private lateinit var connectButton: Button

    private var connected = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(buildUi())
        VpnController.tunnel.listener = { state ->
            runOnUiThread {
                connected = state == Tunnel.State.UP
                refreshUi()
            }
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        syncState()
    }

    override fun onDestroy() {
        VpnController.tunnel.listener = null
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi(): LinearLayout {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        // Keep content clear of the status and navigation bars (Android 15 edge-to-edge).
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }

        val title = TextView(this).apply {
            text = "JOS VPN India"
            textSize = 26f
            setPadding(0, dp(8), 0, dp(16))
        }
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, dp(12))
        }
        profileView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        appsView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(20))
        }
        val importButton = Button(this).apply {
            text = "Import WireGuard .conf"
            setOnClickListener { pickProfile() }
        }
        val appsButton = Button(this).apply {
            text = "Choose apps (optional)"
            setOnClickListener { chooseApps() }
        }
        connectButton = Button(this).apply {
            setOnClickListener { onConnectClicked() }
        }
        val note = TextView(this).apply {
            text = "No VPN servers are included. The profile you import decides the server and country."
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        }

        val params = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        listOf(title, statusView, profileView, appsView, importButton, appsButton, connectButton, note)
            .forEach { root.addView(it, LinearLayout.LayoutParams(params, wrap)) }
        return root
    }

    private fun refreshUi() {
        val hasProfile = prefs.getString(KEY_CONF, null) != null
        val apps = prefs.getStringSet(KEY_APPS, emptySet()) ?: emptySet()
        statusView.text = when {
            busy -> "Status: working..."
            connected -> "Status: connected"
            else -> "Status: disconnected"
        }
        profileView.text = if (hasProfile) "Profile: imported" else "Profile: none imported yet"
        appsView.text = if (apps.isEmpty()) {
            "Apps: all apps use the VPN"
        } else {
            "Apps: only ${apps.size} selected app(s) use the VPN"
        }
        connectButton.text = if (connected) "Disconnect VPN" else "Connect VPN"
        connectButton.isEnabled = !busy
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- profile import

    private fun pickProfile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_IMPORT -> if (resultCode == RESULT_OK) {
                data?.data?.let { importProfile(it) }
            }
            REQ_VPN -> if (resultCode == RESULT_OK) {
                startTunnel()
            } else {
                toast("VPN permission was not granted")
            }
        }
    }

    private fun importProfile(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank() || text.length > MAX_CONF_CHARS) {
            toast("Could not read that file as a WireGuard profile")
            return
        }
        try {
            buildConfig(text, emptySet())
        } catch (e: Exception) {
            toast("Invalid WireGuard profile: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        // The profile contains a private key and is stored in this app's private storage.
        prefs.edit().putString(KEY_CONF, text).apply()
        toast("Profile imported")
        refreshUi()
    }

    /**
     * Parses the profile. When [packages] is not empty, an IncludedApplications line is
     * added to the [Interface] section so only those apps are routed through the VPN.
     */
    private fun buildConfig(text: String, packages: Set<String>): Config {
        val lines = text.lines().filterNot {
            val t = it.trim().lowercase()
            t.startsWith("includedapplications") || t.startsWith("excludedapplications")
        }
        val out = ArrayList<String>()
        var inserted = false
        for (line in lines) {
            out.add(line)
            if (!inserted && packages.isNotEmpty() &&
                line.trim().equals("[Interface]", ignoreCase = true)
            ) {
                out.add("IncludedApplications = " + packages.joinToString(", "))
                inserted = true
            }
        }
        return Config.parse(ByteArrayInputStream(out.joinToString("\n").toByteArray()))
    }

    // ---------------------------------------------------------------- per-app routing

    private fun chooseApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        if (apps.isEmpty()) {
            toast("No apps found")
            return
        }

        val selected = (prefs.getStringSet(KEY_APPS, emptySet()) ?: emptySet()).toMutableSet()
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in selected }

        AlertDialog.Builder(this)
            .setTitle("Apps that use the VPN (none = all apps)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selected.add(apps[which].first) else selected.remove(apps[which].first)
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet(KEY_APPS, selected.toSet()).apply()
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- connect / disconnect

    private fun onConnectClicked() {
        if (connected) {
            stopTunnel()
            return
        }
        if (prefs.getString(KEY_CONF, null) == null) {
            toast("Import a WireGuard profile first")
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQ_VPN)
        } else {
            startTunnel()
        }
    }

    private fun startTunnel() {
        val conf = prefs.getString(KEY_CONF, null) ?: return
        val packages = prefs.getStringSet(KEY_APPS, emptySet()) ?: emptySet()
        busy = true
        refreshUi()
        worker.execute {
            var error: String? = null
            var state = Tunnel.State.DOWN
            try {
                val config = buildConfig(conf, packages)
                state = VpnController.backendFor(this)
                    .setState(VpnController.tunnel, Tunnel.State.UP, config)
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                busy = false
                connected = state == Tunnel.State.UP
                error?.let { toast("Could not connect: $it") }
                refreshUi()
            }
        }
    }

    private fun stopTunnel() {
        busy = true
        refreshUi()
        worker.execute {
            var error: String? = null
            try {
                VpnController.backendFor(this).setState(VpnController.tunnel, Tunnel.State.DOWN, null)
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                busy = false
                connected = false
                error?.let { toast("Could not disconnect: $it") }
                refreshUi()
            }
        }
    }

    private fun syncState() {
        worker.execute {
            val state = try {
                VpnController.backendFor(this).getState(VpnController.tunnel)
            } catch (e: Throwable) {
                Tunnel.State.DOWN
            }
            runOnUiThread {
                connected = state == Tunnel.State.UP
                refreshUi()
            }
        }
    }

    companion object {
        private const val PREFS = "josvpn"
        private const val KEY_CONF = "profile_conf"
        private const val KEY_APPS = "included_apps"
        private const val REQ_IMPORT = 1
        private const val REQ_VPN = 2
        private const val MAX_CONF_CHARS = 65536
    }
}
