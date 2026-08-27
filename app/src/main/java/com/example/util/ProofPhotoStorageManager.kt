package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.model.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ProofPhotoStorageManager handles:
 * 1. Direct photo upload to Cloudinary (Unsigned Preset: Kas-Pintar-RT004, Cloud: mmfvqpa3).
 * 2. Migration of legacy Base64 / local device photos to Cloudinary CDN to prevent memory bloating.
 * 3. Local caching and compression for instant offline responsiveness.
 * 4. Image source resolution for Coil AsyncImage.
 */
object ProofPhotoStorageManager {
    private const val TAG = "ProofPhotoCloudinary"

    // Cloudinary Credentials & Configuration
    const val CLOUDINARY_CLOUD_NAME = "mmfvqpa3"
    const val CLOUDINARY_UPLOAD_PRESET = "Kas-Pintar-RT004"
    const val CLOUDINARY_FOLDER = "Kas-Pintas-RT004/Bukti_Transaksi"
    const val CLOUDINARY_UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUDINARY_CLOUD_NAME/image/upload"
    const val CLOUDINARY_CONSOLE_URL = "https://console.cloudinary.com/pm/c-$CLOUDINARY_CLOUD_NAME/media-explorer"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Open Cloudinary media explorer in browser
     */
    fun openCloudinaryFolder(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CLOUDINARY_CONSOLE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Cloudinary URL: ${e.message}")
        }
    }

    /**
     * Open Google Drive folder as secondary backup reference
     */
    fun openGoogleDriveFolder(context: Context) {
        try {
            val gDriveUrl = "https://drive.google.com/drive/folders/1bJv0MpL6ezihNozexcmclFV_44H5bVW5"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(gDriveUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Drive folder: ${e.message}")
        }
    }

    /**
     * Upload an image (from local path, Content Uri, or Base64) directly to Cloudinary.
     * Returns the permanent HTTPS CDN URL (secure_url).
     */
    suspend fun uploadToCloudinary(
        context: Context,
        localPhotoPathOrUri: String,
        transactionSyncId: String = ""
    ): String? = withContext(Dispatchers.IO) {
        try {
            val imageBytes = extractAndCompressImageBytes(context, localPhotoPathOrUri)
            if (imageBytes == null || imageBytes.isEmpty()) {
                Log.e(TAG, "Failed to prepare image bytes for Cloudinary upload")
                return@withContext null
            }

            val mediaType = "image/jpeg".toMediaTypeOrNull()
            val requestFile = imageBytes.toRequestBody(mediaType)
            val fileName = "nota_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", CLOUDINARY_UPLOAD_PRESET)
                .addFormDataPart("folder", CLOUDINARY_FOLDER)
                .addFormDataPart("file", fileName, requestFile)

            if (transactionSyncId.isNotBlank()) {
                val cleanTag = transactionSyncId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                multipartBuilder.addFormDataPart("tags", "rt004,transaksi,$cleanTag")
            }

            val requestBody = multipartBuilder.build()
            val request = Request.Builder()
                .url(CLOUDINARY_UPLOAD_URL)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful && responseBody.isNotBlank()) {
                val json = JSONObject(responseBody)
                val secureUrl = json.optString("secure_url").ifBlank { json.optString("url") }
                if (secureUrl.isNotBlank()) {
                    Log.i(TAG, "Image successfully uploaded to Cloudinary: $secureUrl")
                    return@withContext secureUrl
                }
            } else {
                Log.w(TAG, "Cloudinary upload response unsuccessful: code=${response.code}, body=$responseBody")
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during Cloudinary upload: ${e.message}", e)
            null
        }
    }

    /**
     * Upload receipt with automatic fallback to Base64 thumbnail if offline/network fails,
     * ensuring that no user flow is ever blocked.
     */
    suspend fun uploadReceiptPhoto(
        context: Context,
        localPhotoPathOrUri: String,
        transactionSyncId: String = ""
    ): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        // Try uploading to Cloudinary
        val cloudinaryUrl = uploadToCloudinary(context, localPhotoPathOrUri, transactionSyncId)
        if (!cloudinaryUrl.isNullOrBlank()) {
            return@withContext Pair(cloudinaryUrl, true)
        }

        // Fallback to Base64 thumbnail if Cloudinary is unreachable (offline mode)
        val fallbackBase64 = generateBase64Thumbnail(context, localPhotoPathOrUri)
        Pair(fallbackBase64, false)
    }

    /**
     * Migrates an old transaction's photo (Base64 or local cache) to Cloudinary.
     * When successful, replaces the heavy Base64 payload with the clean Cloudinary URL.
     */
    suspend fun migrateTransactionPhotoToCloudinary(
        context: Context,
        transaction: TransactionEntity
    ): TransactionEntity = withContext(Dispatchers.IO) {
        // Already on Cloudinary CDN
        if (isCloudinaryUrl(transaction.proofPhotoCloudUrl)) {
            return@withContext transaction
        }

        val photoSource = transaction.proofPhotoCloudUrl?.takeIf { it.isNotBlank() }
            ?: transaction.proofPhotoUri?.takeIf { it.isNotBlank() }

        if (photoSource.isNullOrBlank()) {
            return@withContext transaction
        }

        val uploadedUrl = uploadToCloudinary(
            context = context,
            localPhotoPathOrUri = photoSource,
            transactionSyncId = transaction.syncId.ifBlank { "TX-${transaction.id}" }
        )

        if (!uploadedUrl.isNullOrBlank()) {
            Log.i(TAG, "Migrated transaction #${transaction.id} (${transaction.title}) to Cloudinary CDN: $uploadedUrl")
            // Clean up heavy Base64 and point to Cloudinary CDN
            transaction.copy(
                proofPhotoCloudUrl = uploadedUrl
            )
        } else {
            transaction
        }
    }

    /**
     * Check whether a URL is hosted on Cloudinary
     */
    fun isCloudinaryUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("cloudinary.com", ignoreCase = true) || url.contains("res.cloudinary.com", ignoreCase = true)
    }

    /**
     * Extract bytes and compress appropriately (max dimension 1400px, 80% JPEG)
     */
    private suspend fun extractAndCompressImageBytes(
        context: Context,
        source: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap? = when {
                // 1. Remote HTTP/HTTPS image URL (e.g. legacy Firebase Storage or external link)
                source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true) -> {
                    try {
                        val req = Request.Builder().url(source).build()
                        val res = httpClient.newCall(req).execute()
                        if (res.isSuccessful) {
                            val bytes = res.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } else null
                        } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download remote photo for migration: ${e.message}")
                        null
                    }
                }

                // 2. Data URI Base64 (data:image/...)
                source.startsWith("data:image", ignoreCase = true) || source.startsWith("data:", ignoreCase = true) -> {
                    val commaIdx = source.indexOf(",")
                    val clean = if (commaIdx != -1) source.substring(commaIdx + 1) else source
                    val bytes = Base64.decode(clean.trim(), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                // 3. Android Content URI (content://...)
                source.startsWith("content://", ignoreCase = true) -> {
                    val uri = Uri.parse(source)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Content resolver stream error: ${e.message}")
                        null
                    }
                }

                // 4. File URI (file://...)
                source.startsWith("file://", ignoreCase = true) -> {
                    val path = Uri.parse(source).path
                    if (!path.isNullOrBlank() && File(path).exists()) {
                        BitmapFactory.decodeFile(path)
                    } else null
                }

                // 5. Direct or Relative File Path on Device
                else -> {
                    val file = File(source)
                    val receiptDirFile = File(context.filesDir, "receipt_photos/$source")
                    val cameraDirFile = File(context.cacheDir, "camera/$source")
                    val cacheSharedFile = File(context.cacheDir, "shared_receipts/$source")
                    val filesRootFile = File(context.filesDir, source)

                    when {
                        file.exists() && file.length() > 0 -> BitmapFactory.decodeFile(file.absolutePath)
                        receiptDirFile.exists() -> BitmapFactory.decodeFile(receiptDirFile.absolutePath)
                        cameraDirFile.exists() -> BitmapFactory.decodeFile(cameraDirFile.absolutePath)
                        cacheSharedFile.exists() -> BitmapFactory.decodeFile(cacheSharedFile.absolutePath)
                        filesRootFile.exists() -> BitmapFactory.decodeFile(filesRootFile.absolutePath)
                        else -> {
                            // 6. Try parsing as raw Base64 string if long enough
                            if (source.length > 100 && !source.contains(" ")) {
                                try {
                                    val bytes = Base64.decode(source.trim(), Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (e: Exception) {
                                    null
                                }
                            } else null
                        }
                    }
                }
            }

            if (bitmap == null) return@withContext null

            val maxDimension = 1400
            val width = bitmap.width
            val height = bitmap.height
            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val ratio = width.toFloat() / height.toFloat()
                val newWidth = if (width > height) maxDimension else (maxDimension * ratio).toInt()
                val newHeight = if (height > width) maxDimension else (maxDimension / ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val resultBytes = baos.toByteArray()

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            resultBytes
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting bitmap bytes: ${e.message}")
            null
        }
    }

    /**
     * Share photo or Cloudinary link via system Intent
     */
    fun shareReceiptImage(context: Context, photoUrlOrPath: String, title: String) {
        try {
            if (isCloudinaryUrl(photoUrlOrPath) || photoUrlOrPath.startsWith("http://") || photoUrlOrPath.startsWith("https://")) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Bukti Transaksi RT 004/08: $title")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Bukti Transaksi RT 004 / 08 Jati Pulogadung\nPerihal: $title\nLink Cloudinary CDN:\n$photoUrlOrPath"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Bagikan Link Bukti Cloudinary RT"))
                return
            }

            val uri: Uri = when {
                photoUrlOrPath.startsWith("content://") -> Uri.parse(photoUrlOrPath)
                photoUrlOrPath.startsWith("data:image") -> {
                    val tempFile = writeBase64ToTempFile(context, photoUrlOrPath)
                    if (tempFile != null) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                    } else return
                }
                else -> {
                    val file = File(photoUrlOrPath)
                    if (file.exists()) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } else Uri.parse(photoUrlOrPath)
                }
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Bukti Transaksi RT 004/08: $title")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Bukti Transaksi RT 004/08 Jati Pulogadung\nPerihal: $title\nCloudinary: $CLOUDINARY_FOLDER"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Bagikan Foto Bukti Transaksi"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing receipt image: ${e.message}")
        }
    }

    private fun writeBase64ToTempFile(context: Context, base64DataUri: String): File? {
        return try {
            val commaIdx = base64DataUri.indexOf(",")
            val cleanBase64 = if (commaIdx != -1) base64DataUri.substring(commaIdx + 1) else base64DataUri
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val cacheDir = File(context.cacheDir, "shared_receipts").apply { if (!exists()) mkdirs() }
            val file = File(cacheDir, "nota_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (e: Throwable) {
            Log.e(TAG, "Error decoding base64 to temp file: ${e.message}")
            null
        }
    }

    /**
     * Create a temporary file and FileProvider content URI for capturing photos via system camera
     */
    fun createTempCameraUri(context: Context): Uri? {
        return try {
            val cameraDir = File(context.cacheDir, "camera").apply {
                if (!exists()) mkdirs()
            }
            val tempFile = File(cameraDir, "camera_capture_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.jpg")
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error creating camera temp URI: ${e.message}", e)
            null
        }
    }

    /**
     * Copy picked image URI to app's persistent internal storage
     */
    suspend fun saveLocalReceiptPhoto(context: Context, sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "receipt_photos").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "proof_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
            val destFile = File(dir, fileName)

            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                if (originalBitmap != null) {
                    val maxDimension = 1400
                    val width = originalBitmap.width
                    val height = originalBitmap.height
                    val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                        val ratio = width.toFloat() / height.toFloat()
                        val newWidth = if (width > height) maxDimension else (maxDimension * ratio).toInt()
                        val newHeight = if (height > width) maxDimension else (maxDimension / ratio).toInt()
                        Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
                    } else {
                        originalBitmap
                    }

                    FileOutputStream(destFile).use { out ->
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.flush()
                    }
                    if (scaledBitmap != originalBitmap) {
                        scaledBitmap.recycle()
                    }
                    originalBitmap.recycle()
                    destFile.absolutePath
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving local receipt photo: ${e.message}", e)
            null
        }
    }

    /**
     * Generate an inline compressed Base64 data string (~20-40KB) for offline fallback
     */
    suspend fun generateBase64Thumbnail(
        context: Context,
        localPhotoPathOrUri: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap? = when {
                localPhotoPathOrUri.startsWith("content://") -> {
                    val uri = Uri.parse(localPhotoPathOrUri)
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                else -> {
                    val file = File(localPhotoPathOrUri)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    } else null
                }
            }

            if (bitmap == null) return@withContext null

            val maxDimension = 650
            val width = bitmap.width
            val height = bitmap.height
            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val ratio = width.toFloat() / height.toFloat()
                val newWidth = if (width > height) maxDimension else (maxDimension * ratio).toInt()
                val newHeight = if (height > width) maxDimension else (maxDimension / ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
            val bytes = baos.toByteArray()

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64Str"
        } catch (e: Throwable) {
            Log.w(TAG, "Error generating base64 thumbnail: ${e.message}")
            null
        }
    }

    /**
     * Resolves the best available image source for Coil AsyncImage:
     * - Returns Cloudinary HTTPS URL if available.
     * - Returns File if local file exists.
     * - Returns Uri if content:// URI.
     * - Returns Base64 data URI if legacy.
     */
    fun resolvePhotoSource(proofPhotoUri: String?, proofPhotoCloudUrl: String?): Any? {
        // 1. Cloudinary / Web CDN URL takes highest priority for crisp & shared access
        if (!proofPhotoCloudUrl.isNullOrBlank()) {
            if (proofPhotoCloudUrl.startsWith("http://") || proofPhotoCloudUrl.startsWith("https://")) {
                return proofPhotoCloudUrl
            }
        }

        // 2. Check local file on device
        if (!proofPhotoUri.isNullOrBlank()) {
            if (proofPhotoUri.startsWith("content://")) {
                return Uri.parse(proofPhotoUri)
            }
            val localFile = File(proofPhotoUri)
            if (localFile.exists() && localFile.length() > 0) {
                return localFile
            }
        }

        // 3. Check legacy base64 or other cloud url
        if (!proofPhotoCloudUrl.isNullOrBlank()) {
            if (proofPhotoCloudUrl.startsWith("data:image")) {
                return proofPhotoCloudUrl
            }
        }

        // 4. Fallback to raw proofPhotoUri string
        if (!proofPhotoUri.isNullOrBlank() && !proofPhotoUri.startsWith("gdrive://")) {
            return proofPhotoUri
        }

        return null
    }

    fun hasValidPhoto(proofPhotoUri: String?, proofPhotoCloudUrl: String?): Boolean {
        return resolvePhotoSource(proofPhotoUri, proofPhotoCloudUrl) != null
    }

    // Backward-compatible aliases
    suspend fun uploadReceiptToFirebaseStorage(
        context: Context,
        localPhotoPathOrUri: String,
        roomCode: String,
        transactionSyncId: String
    ): String? = uploadToCloudinary(context, localPhotoPathOrUri, transactionSyncId)

    suspend fun uploadReceiptToGoogleDrive(
        context: Context,
        localPhotoPathOrUri: String,
        roomCode: String,
        transactionSyncId: String
    ): String? = uploadToCloudinary(context, localPhotoPathOrUri, transactionSyncId)

    fun shareReceiptToGoogleDrive(context: Context, localPhotoPathOrUri: String, title: String) {
        shareReceiptImage(context, localPhotoPathOrUri, title)
    }
}
