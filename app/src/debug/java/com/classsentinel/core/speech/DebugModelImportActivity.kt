package com.classsentinel.core.speech

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only, non-UI bridge for importing a prepared model directory.
 *
 * Start with explicit extras from ADB; release builds do not contain this activity. The actual
 * copy is still owned by [DebugModelImporter], so every file passes the shared integrity gate.
 */
internal class DebugModelImportActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = runCatching {
            parseDebugModelImportRequest(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID),
                sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH),
            )
        }.getOrElse {
            Log.e(TAG, "IMPORT_REQUEST_REJECTED")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        scope.launch {
            val imported = try {
                withContext(Dispatchers.IO) {
                    DebugModelImporter(applicationContext.filesDir)
                        .importFromDirectory(request.profile, request.sourceDirectory)
                    SherpaModelInstaller.isInstalled(applicationContext.filesDir, request.profile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (imported) {
                Log.i(TAG, "IMPORT_PASS profile=${request.profile.id}")
                setResult(RESULT_OK)
            } else {
                Log.e(TAG, "IMPORT_FAILED profile=${request.profile.id}")
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SOURCE_PATH = "source_path"
        private const val TAG = "ClassSentinelDebugImport"
    }
}
