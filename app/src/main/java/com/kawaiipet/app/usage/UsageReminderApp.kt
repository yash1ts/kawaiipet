package com.kawaiipet.app.usage

/**
 * One app watched for continuous-usage break reminders.
 */
data class UsageReminderApp(
    val packageName: String,
    val label: String,
) {
    init {
        require(packageName.isNotBlank())
    }
}
