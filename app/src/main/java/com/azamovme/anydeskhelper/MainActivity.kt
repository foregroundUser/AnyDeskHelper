package com.azamovme.anydeskhelper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.azamovme.anydeskhelper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var accessibilityManager: AccessibilityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        checkServiceStatus()
    }

    @SuppressLint("SetTextI18n")
    private fun setupUI() {
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = "Version: $versionName"
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            if (AnyDeskAccessibilityService.isServiceEnabled) {
                showDisableServiceDialog()
            } else {
                openAccessibilitySettings()
            }
        }

        binding.btnOpenSettings.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnTestService.setOnClickListener {
            if (AnyDeskAccessibilityService.isServiceEnabled) {
                showTestDialog()
            } else {
                showToast("Please enable accessibility service first")
            }
        }

        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun checkServiceStatus() {
        val isEnabled = AnyDeskAccessibilityService.isServiceEnabled

        if (isEnabled) {
            binding.apply {
                tvStatus.text = "Service Status: ACTIVE"
                btnToggleService.text = "Disable Service"
            }
        } else {
            binding.apply {
                tvStatus.text = "Service Status: INACTIVE"
                btnToggleService.text = "Enable Service"
            }
        }

        val serviceEnabled = isAccessibilityServiceEnabled()
        if (isEnabled != serviceEnabled) {
            AnyDeskAccessibilityService.isServiceEnabled = serviceEnabled
            checkServiceStatus()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${AnyDeskAccessibilityService::class.java.canonicalName}"

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        return enabledServices?.contains(serviceName, ignoreCase = true) == true
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            intent.putExtra(":settings:fragment_args_key", "com.azamovme.anydeskhelper/.AnyDeskAccessibilityService")
            startActivity(intent)

            showToast("Please enable 'AnyDesk Helper' in accessibility services")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening accessibility settings", e)
            showToast("Cannot open settings. Please enable manually in Settings > Accessibility")
        }
    }

    private fun showDisableServiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disable Service")
            .setMessage("Do you want to disable the AnyDesk Helper service?")
            .setPositiveButton("Disable") { _, _ ->
                disableService()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disableService() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            showToast("Please disable 'AnyDesk Helper' in accessibility services")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error disabling service", e)
        }
    }

    private fun showTestDialog() {
        AlertDialog.Builder(this)
            .setTitle("Test Service")
            .setMessage("The service is active and monitoring AnyDesk.\n\n" +
                    "When an incoming connection appears, it will be automatically accepted.\n\n" +
                    "Make sure AnyDesk is installed and running.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAboutDialog() {
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName

        AlertDialog.Builder(this)
            .setTitle("About AnyDesk Helper")
            .setMessage(
                "Version: $versionName\n\n" +
                        "This app automatically accepts incoming AnyDesk connections.\n\n" +
                        "Features:\n" +
                        "• Auto-accept incoming connections\n" +
                        "• Advanced dialog detection\n" +
                        "• Real-time monitoring\n" +
                        "• Easy enable/disable\n\n" +
                        "Note: Requires Accessibility permission to work."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}