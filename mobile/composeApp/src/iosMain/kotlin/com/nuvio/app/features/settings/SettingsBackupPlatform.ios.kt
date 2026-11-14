package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

/**
 * Saving writes the backup to a temporary file and opens the system "Save to Files" sheet, so it can go to
 * iCloud Drive, On My iPhone or any other Files location. Restoring opens the Files picker.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
internal actual fun rememberBackupFileSaver(onDone: (saved: Boolean) -> Unit): (fileName: String, text: String) -> Unit {
    val presenter = LocalUIViewController.current
    val latestDone = rememberUpdatedState(onDone)
    val tempPath = remember { mutableStateOf<String?>(null) }
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                removeTemp(tempPath.value)
                tempPath.value = null
                latestDone.value(didPickDocumentsAtURLs.isNotEmpty())
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                removeTemp(tempPath.value)
                tempPath.value = null
            }
        }
    }
    return { fileName, text ->
        val path = NSTemporaryDirectory().trimEnd('/') + "/" + fileName
        val written = NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
        if (!written) {
            latestDone.value(false)
        } else {
            tempPath.value = path
            val picker = UIDocumentPickerViewController(
                forExportingURLs = listOf(NSURL.fileURLWithPath(path)),
                asCopy = true,
            )
            picker.delegate = delegate
            presenter.presentViewController(picker, true, null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
internal actual fun rememberBackupFileOpener(onText: (String?) -> Unit): () -> Unit {
    val presenter = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val latestText = rememberUpdatedState(onText)
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                scope.launch {
                    val text = withContext(Dispatchers.Default) {
                        val accessed = url.startAccessingSecurityScopedResource()
                        try {
                            runCatching {
                                val data = requireNotNull(NSData.create(contentsOfURL = url))
                                require(data.length.toLong() <= MAX_BACKUP_BYTES)
                                NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
                            }.getOrNull()
                        } finally {
                            if (accessed) url.stopAccessingSecurityScopedResource()
                        }
                    }
                    latestText.value(text)
                }
            }
        }
    }
    return {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.json", "public.text", "public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, true, null)
    }
}

internal actual val backupFilesSupported: Boolean = true

@OptIn(ExperimentalForeignApi::class)
private fun removeTemp(path: String?) {
    if (path != null) NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}
