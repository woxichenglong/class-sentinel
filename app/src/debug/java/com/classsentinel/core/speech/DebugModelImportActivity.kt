package com.classsentinel.core.speech

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
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

        val stagedRequest = runCatching {
            request.copy(sourceDirectory = prepareStagingDirectory(request.sourceDirectory))
        }.getOrElse {
            Log.e(TAG, "IMPORT_STAGING_REJECTED")
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_PREPARE_ONLY, false)) {
            Log.i(TAG, "STAGING_READY profile=${stagedRequest.profile.id}")
            setResult(RESULT_OK)
            finish()
            return
        }

        scope.launch {
            val imported = try {
                withContext(Dispatchers.IO) {
                    DebugModelImporter(applicationContext.filesDir)
                        .importFromDirectory(stagedRequest.profile, stagedRequest.sourceDirectory)
                    SherpaModelInstaller.isInstalled(applicationContext.filesDir, stagedRequest.profile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (imported) {
                Log.i(TAG, "IMPORT_PASS profile=${stagedRequest.profile.id}")
                setResult(RESULT_OK)
            } else {
                Log.e(TAG, "IMPORT_FAILED profile=${stagedRequest.profile.id}")
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    private fun prepareStagingDirectory(sourceDirectory: File): File {
        val externalRoot = requireNotNull(getExternalFilesDir(null)).canonicalFile
        val source = sourceDirectory.canonicalFile
        val prefix = externalRoot.path + File.separator
        require(source.path.startsWith(prefix)) { "ASR_MODEL_SOURCE_OUTSIDE_EXTERNAL_ROOT" }
        require(source.mkdirs() || source.isDirectory) { "ASR_MODEL_SOURCE_NOT_DIRECTORY" }
        return source
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SOURCE_PATH = "source_path"
        const val EXTRA_PREPARE_ONLY = "prepare_only"
        private const val TAG = "ClassSentinelDebugImport"
    }
}
