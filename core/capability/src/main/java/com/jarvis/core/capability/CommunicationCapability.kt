package com.jarvis.core.capability

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jarvis.core.database.repository.ReversibleActionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Communication capabilities - send SMS, make calls, lookup contacts
 */
class CommunicationCapability(
    private val context: Context,
    private val reversibleActionRepository: ReversibleActionRepository? = null,
) : Capability {

    override val id = CapabilityIds.MESSAGES

    override val description = "Send SMS messages, make phone calls, and lookup contacts"

    override val requiredPermissions = listOf(
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_CONTACTS,
    )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            ContextCompat.getSystemService(context, SmsManager::class.java) != null
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: send_sms, call, lookup_contact",
        )

        return when (action) {
            "send_sms" -> sendSms(request)
            "call" -> makeCall(request)
            "lookup_contact" -> lookupContact(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown communication action: $action",
            )
        }
    }

    private suspend fun sendSms(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val to = request.parameters["to"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER",
            message = "Missing 'to' parameter (phone number)",
        )
        val body = request.parameters["body"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER",
            message = "Missing 'body' parameter (message text)",
        )

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "SMS permission not granted",
                missingPermissions = listOf(android.Manifest.permission.SEND_SMS),
            )
        }

        try {
            val manager = ContextCompat.getSystemService(context, SmsManager::class.java)
                ?: return@withContext CapabilityResult.Failure(code = "UNAVAILABLE", message = "SMS manager unavailable")
            if (body.length <= 160) {
                manager.sendTextMessage(to, null, body, null, null)
            } else {
                manager.sendMultipartTextMessage(
                    to,
                    null,
                    manager.divideMessage(body),
                    null,
                    null,
                )
            }
            reversibleActionRepository?.recordAction(
                com.jarvis.core.common.ReversibleAction(
                    actionType = "sms_sent",
                    target = to,
                    inverseActionJson = "{\"action\":\"sms_sent\",\"to\":\"$to\"}",
                )
            )
            CapabilityResult.Success(
                output = "SMS sent to $to",
                structuredData = mapOf("to" to to, "length" to body.length),
                verificationHint = VerificationHint("communication.sms_sent", mapOf("to" to to)),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("SMS_ERROR", "Failed to send SMS: ${e.message}")
        }
    }

    private suspend fun makeCall(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val number = request.parameters["number"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER",
            message = "Missing 'number' parameter",
        )

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Call permission not granted",
                missingPermissions = listOf(android.Manifest.permission.CALL_PHONE),
            )
        }

        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CapabilityResult.Success(
                output = "Call initiated to $number",
                structuredData = mapOf("number" to number),
                verificationHint = VerificationHint("communication.call_started", mapOf("number" to number)),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CALL_ERROR", "Failed to place call: ${e.message}")
        }
    }

    private suspend fun lookupContact(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val name = request.parameters["name"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER",
            message = "Missing 'name' parameter",
        )

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Contacts permission not granted",
                missingPermissions = listOf(android.Manifest.permission.READ_CONTACTS),
            )
        }

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val contacts = context.contentResolver
                .query(uri, projection, selection, arrayOf("%$name%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                mapOf<String, Any>(
                                    "name" to (cursor.getString(0) ?: "(unnamed)"),
                                    "phone" to cursor.getString(1),
                                )
                            )
                        }
                    }.distinctBy { (it["name"] as String) to (it["phone"] as String) }
                } ?: emptyList()

            CapabilityResult.Success(
                output = if (contacts.isNotEmpty()) {
                    "Found ${contacts.size} contact(s) for '$name': ${contacts.joinToString(", ") { "${it["name"]} (${it["phone"]})" }}"
                } else {
                    "No contacts found for '$name'"
                },
                structuredData = mapOf("contacts" to contacts),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CONTACTS_ERROR", "Failed to lookup contacts: ${e.message}")
        }
    }
}

/**
 * App capabilities - launch app, list installed apps
 */
class AppCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.LAUNCH_APP

    override val description = "Launch installed applications and list available apps"

    override val requiredPermissions = listOf(
        android.Manifest.permission.QUERY_ALL_PACKAGES,
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: launch_app, list_apps",
        )

        return when (action) {
            "launch_app" -> launchApp(request)
            "list_apps" -> listApps(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown app action: $action",
            )
        }
    }

    private suspend fun launchApp(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.Main) {
        val target = request.parameters["target"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER",
            message = "Missing 'target' parameter (app name or package)",
        )

        try {
            val pm = context.packageManager
            val clean = target.lowercase().trim()

            val directIntent = when (clean) {
                "camera", "open camera", "take a picture", "take photo" -> {
                    val camIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    if (camIntent.resolveActivity(pm) != null) {
                        camIntent
                    } else {
                        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val apps = pm.queryIntentActivities(mainIntent, 0)
                        val camApp = apps.firstOrNull {
                            it.activityInfo.packageName.contains("camera", ignoreCase = true) ||
                                it.loadLabel(pm).toString().contains("camera", ignoreCase = true)
                        }
                        camApp?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                    }
                }
                else -> null
            }

            val intent = directIntent
                ?: pm.getLaunchIntentForPackage(target.trim())
                ?: run {
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val activities = pm.queryIntentActivities(launcherIntent, 0)
                    val match = activities.firstOrNull {
                        it.loadLabel(pm).toString().equals(clean, ignoreCase = true)
                    } ?: activities.firstOrNull {
                        it.loadLabel(pm).toString().contains(clean, ignoreCase = true)
                    } ?: activities.firstOrNull {
                        it.activityInfo.packageName.contains(clean, ignoreCase = true)
                    }
                    match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                }
                ?: run {
                    val fallbackPackage = when (clean) {
                        "youtube" -> "com.google.android.youtube"
                        "chrome", "google chrome", "browser" -> "com.android.chrome"
                        "maps", "google maps" -> "com.google.android.apps.maps"
                        "gmail", "email" -> "com.google.android.gm"
                        "photos", "google photos" -> "com.google.android.apps.photos"
                        "calendar", "google calendar" -> "com.google.android.calendar"
                        "play store", "google play", "store" -> "com.android.vending"
                        "clock" -> "com.google.android.deskclock"
                        "calculator" -> "com.google.android.calculator"
                        else -> null
                    }
                    fallbackPackage?.let { pm.getLaunchIntentForPackage(it) }
                }
                ?: return@withContext CapabilityResult.Failure("NOT_FOUND", "Could not find installed application matching \"$target\"")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            CapabilityResult.Success(
                output = "Launched $target",
                structuredData = mapOf<String, Any>("target" to target, "package" to (intent.`package` ?: "")),
                verificationHint = VerificationHint("app.launched", mapOf("package" to (intent.`package` ?: ""))),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("LAUNCH_ERROR", "Failed to launch app: ${e.message}")
        }
    }

    private suspend fun listApps(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launcherIntent, 0)
            val apps = activities.map { it.loadLabel(pm).toString() }.distinct().sorted()

            CapabilityResult.Success(
                output = "Found ${apps.size} installed applications",
                structuredData = mapOf("apps" to apps),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("LIST_APPS_ERROR", "Failed to list apps: ${e.message}")
        }
    }
}
