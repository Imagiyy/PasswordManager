package com.vaultmanager.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.google.gson.Gson
import com.vaultmanager.app.MainActivity
import com.vaultmanager.app.R
import com.vaultmanager.app.VaultState
import com.vaultmanager.app.ui.VaultData

/**
 * Autofill service for filling username/password fields in other apps.
 *
 * Behavior:
 *   - If vault is unlocked (VaultState.encKey != null):
 *     Parse AssistStructure for username/password fields,
 *     build FillResponse with matching credentials from the decrypted vault.
 *
 *   - If vault is locked:
 *     Build authentication FillResponse with PendingIntent to MainActivity
 *     (shows biometric/password prompt before returning fill data).
 *
 *   - onSaveRequest: notifies user about saving new credentials.
 */
class AutofillService : AutofillService() {

    private val gson = Gson()

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        // Get the latest AssistStructure
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        // Parse the structure to find autofill-eligible fields
        val parsedFields = parseStructure(structure)
        if (parsedFields.usernameId == null && parsedFields.passwordId == null) {
            callback.onSuccess(null)
            return
        }

        // Check if vault is unlocked
        val encKey = VaultState.encKey
        val vaultJson = VaultState.vaultJson

        if (encKey != null && vaultJson != null) {
            // Vault is unlocked — build fill response from decrypted vault
            try {
                val vaultData = gson.fromJson(vaultJson, VaultData::class.java)
                val responseBuilder = FillResponse.Builder()
                var hasDatasets = false

                for (item in vaultData.items) {
                    if (item.username.isBlank() && item.password.isBlank()) continue

                    val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1)
                    presentation.setTextViewText(android.R.id.text1, item.name)

                    val datasetBuilder = Dataset.Builder(presentation)

                    if (parsedFields.usernameId != null && item.username.isNotBlank()) {
                        datasetBuilder.setValue(
                            parsedFields.usernameId,
                            AutofillValue.forText(item.username)
                        )
                    }

                    if (parsedFields.passwordId != null && item.password.isNotBlank()) {
                        datasetBuilder.setValue(
                            parsedFields.passwordId,
                            AutofillValue.forText(item.password)
                        )
                    }

                    try {
                        responseBuilder.addDataset(datasetBuilder.build())
                        hasDatasets = true
                    } catch (e: Exception) {
                        // Skip invalid datasets
                    }
                }

                if (hasDatasets) {
                    callback.onSuccess(responseBuilder.build())
                } else {
                    callback.onSuccess(null)
                }
            } catch (e: Exception) {
                callback.onSuccess(null)
            }
        } else {
            // Vault is locked — send authentication intent
            try {
                val authIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                val pendingIntent = PendingIntent.getActivity(
                    this,
                    1001,
                    authIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1)
                presentation.setTextViewText(android.R.id.text1, "Unlock VaultManager")

                val responseBuilder = FillResponse.Builder()
                responseBuilder.setAuthentication(
                    arrayOf(parsedFields.usernameId ?: parsedFields.passwordId!!),
                    pendingIntent.intentSender,
                    presentation
                )

                callback.onSuccess(responseBuilder.build())
            } catch (e: Exception) {
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Extract saved values from the structure
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess()
            return
        }

        val parsedFields = parseStructure(structure)

        // In a full implementation, this would:
        // 1. Extract username and password values from the structure
        // 2. Prompt the user via a notification or Activity
        // 3. Save as a new vault item if the user confirms

        // For now, acknowledge the save request
        callback.onSuccess()
    }

    /**
     * Parse an AssistStructure to find username and password fields.
     */
    private fun parseStructure(structure: AssistStructure): ParsedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null

        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val rootNode = windowNode.rootViewNode
            parseNode(rootNode, object : FieldCollector {
                override fun onUsernameField(id: AutofillId) {
                    if (usernameId == null) usernameId = id
                }
                override fun onPasswordField(id: AutofillId) {
                    if (passwordId == null) passwordId = id
                }
            })
        }

        return ParsedFields(usernameId, passwordId)
    }

    /**
     * Recursively parse ViewNodes to find autofill-eligible fields.
     */
    private fun parseNode(node: AssistStructure.ViewNode, collector: FieldCollector) {
        val autofillHints = node.autofillHints
        val autofillId = node.autofillId

        if (autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            if (autofillHints != null) {
                for (hint in autofillHints) {
                    when {
                        hint.equals(View.AUTOFILL_HINT_USERNAME, ignoreCase = true) ||
                        hint.equals(View.AUTOFILL_HINT_EMAIL_ADDRESS, ignoreCase = true) -> {
                            collector.onUsernameField(autofillId)
                        }
                        hint.equals(View.AUTOFILL_HINT_PASSWORD, ignoreCase = true) -> {
                            collector.onPasswordField(autofillId)
                        }
                    }
                }
            } else {
                // Heuristic: check field name / hint text
                val fieldName = (node.idEntry ?: "").lowercase()
                val hintText = (node.hint ?: "").lowercase()
                val combined = "$fieldName $hintText"

                when {
                    combined.contains("password") || combined.contains("passwd") -> {
                        collector.onPasswordField(autofillId)
                    }
                    combined.contains("user") || combined.contains("email") ||
                    combined.contains("login") || combined.contains("account") -> {
                        collector.onUsernameField(autofillId)
                    }
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            parseNode(node.getChildAt(i), collector)
        }
    }

    private interface FieldCollector {
        fun onUsernameField(id: AutofillId)
        fun onPasswordField(id: AutofillId)
    }

    private data class ParsedFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?
    )
}
