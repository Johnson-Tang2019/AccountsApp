package com.abyssredemption.accounts

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {
    fun isServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabledServices.isBlank()) return false

        val expectedPackage = context.packageName
        val expectedClass = PaymentAccessibilityService::class.java.name
        val expectedShort = "$expectedPackage/.${PaymentAccessibilityService::class.java.simpleName}"
        val expectedFull = "$expectedPackage/$expectedClass"

        return enabledServices
            .split(':')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .any { entry ->
                entry.equals(expectedFull, ignoreCase = true) ||
                    entry.equals(expectedShort, ignoreCase = true) ||
                    ComponentName.unflattenFromString(entry)?.let { component ->
                        component.packageName == expectedPackage &&
                            component.className == expectedClass
                    } == true
            }
    }
}
