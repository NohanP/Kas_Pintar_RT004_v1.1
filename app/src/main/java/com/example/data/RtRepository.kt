package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.CategoryBreakdown
import com.example.model.CitizenEntity
import com.example.model.CitizenType
import com.example.model.MonthlyRecap
import com.example.model.PaymentMethod
import com.example.model.TransactionCategory
import com.example.model.TransactionEntity
import com.example.model.TransactionType
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.util.ProofPhotoStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class SyncResult(
    val success: Boolean,
    val transactionsCount: Int,
    val citizensCount: Int,
    val photosCount: Int,
    val message: String
)

class RtRepository(
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    private val tag = "RtRepository"
    private val citizenDao = database.citizenDao()
    private val transactionDao = database.transactionDao()

    val cloudSyncEngine = RealtimeCloudSyncEngine(scope)

    val allCitizens: Flow<List<CitizenEntity>> = citizenDao.getAllCitizens()
    val allTransactions: Flow<List<TransactionEntity>> = transactionDao.getAllTransactions()

    init {
        scope.launch(Dispatchers.IO) {
            checkAndSeedInitialData()
            syncAllLocalDataToFirestore()
            initFirestoreListeners()
        }
    }

    private suspend fun checkAndSeedInitialData() {
        val existingCitizens = citizenDao.getAllCitizens().first()
        if (existingCitizens.isEmpty()) {
            val initialCitizens = SampleDataProvider.getInitialCitizens()
            val initialTx = SampleDataProvider.getInitialTransactions()

            citizenDao.insertAll(initialCitizens)
            transactionDao.insertAll(initialTx)
        }
    }

    /**
     * Upload all local records to Firestore (root and room collections) and upload any local photos to Firebase Storage.
     */
    suspend fun syncAllLocalDataToFirestore(context: Context? = null): SyncResult = withContext(Dispatchers.IO) {
        var txCount = 0
        var citizenCount = 0
        var photosCount = 0
        val room = cloudSyncEngine.syncCode.value

        try {
            // 1. Sync App Metadata
            cloudSyncEngine.firestoreService.saveAppMetadataToFirestore(room)

            // 2. Sync User Profiles
            val ketuaProfile = UserProfile(
                role = UserRole.KETUA_RT,
                name = UserRole.KETUA_RT.defaultName,
                phone = "0812-3456-7890",
                email = "Nohan.P@gmail.com",
                notes = "Ketua RT 04 / RW 08 Jati Pulogadung - Hak Akses Penuh"
            )
            val sekreProfile = UserProfile(
                role = UserRole.SEKRETARIS_RT,
                name = UserRole.SEKRETARIS_RT.defaultName,
                phone = "0815-6677-8899",
                email = "sekretaris.rt04@gmail.com",
                notes = "Sekretaris RT 04 - Administrasi & Kepesertaan Warga"
            )
            val bendaharaProfile = UserProfile(
                role = UserRole.BENDAHARA_RT,
                name = UserRole.BENDAHARA_RT.defaultName,
                phone = "0813-8899-1122",
                email = "bendahara.rt04@gmail.com",
                notes = "Bendahara RT 04 - Pengelolaan Kas & Rekapitulasi"
            )
            cloudSyncEngine.firestoreService.saveUserProfileToFirestore(ketuaProfile)
            cloudSyncEngine.firestoreService.saveUserProfileToFirestore(sekreProfile)
            cloudSyncEngine.firestoreService.saveUserProfileToFirestore(bendaharaProfile)

            // 3. Sync all citizens
            val citizens = citizenDao.getAllCitizens().first()
            for (c in citizens) {
                val ok = cloudSyncEngine.firestoreService.saveCitizenToFirestore(room, c)
                if (ok) citizenCount++
            }

            // 4. Sync all transactions & Upload any pending receipts to Cloudinary CDN
            val transactions = transactionDao.getAllTransactions().first()
            for (tx in transactions) {
                var updatedTx = tx
                // If there is a photo not yet on Cloudinary CDN, upload & migrate
                val needsCloudinary = (!tx.proofPhotoCloudUrl.isNullOrBlank() && !ProofPhotoStorageManager.isCloudinaryUrl(tx.proofPhotoCloudUrl)) ||
                        (!tx.proofPhotoUri.isNullOrBlank() && tx.proofPhotoCloudUrl.isNullOrBlank())
                if (needsCloudinary && context != null) {
                    try {
                        var cloudinaryUrl: String? = null
                        if (!tx.proofPhotoUri.isNullOrBlank()) {
                            cloudinaryUrl = ProofPhotoStorageManager.uploadToCloudinary(
                                context = context,
                                localPhotoPathOrUri = tx.proofPhotoUri,
                                transactionSyncId = tx.syncId
                            )
                        }
                        if (cloudinaryUrl.isNullOrBlank() && !tx.proofPhotoCloudUrl.isNullOrBlank()) {
                            cloudinaryUrl = ProofPhotoStorageManager.uploadToCloudinary(
                                context = context,
                                localPhotoPathOrUri = tx.proofPhotoCloudUrl,
                                transactionSyncId = tx.syncId
                            )
                        }
                        if (!cloudinaryUrl.isNullOrBlank()) {
                            updatedTx = tx.copy(proofPhotoCloudUrl = cloudinaryUrl)
                            transactionDao.updateTransaction(updatedTx)
                            photosCount++
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Photo upload notice during full sync: ${e.message}")
                    }
                }

                val ok = cloudSyncEngine.firestoreService.saveTransactionToFirestore(room, updatedTx)
                if (ok) txCount++
            }

            // 5. Refresh sync status
            cloudSyncEngine.triggerRealtimeSync()
            Log.d(tag, "Full sync to Firestore & Cloudinary completed: $txCount tx, $citizenCount citizens, $photosCount photos")
            SyncResult(true, txCount, citizenCount, photosCount, "Sinkronisasi ke Firestore & Cloudinary berhasil ($photosCount foto diunggah)")
        } catch (e: Throwable) {
            Log.w(tag, "Full sync notice: ${e.message}")
            SyncResult(false, txCount, citizenCount, photosCount, "Gagal sinkronisasi: ${e.message}")
        }
    }

    /**
     * Dedicated migration method: scans all transactions with legacy Base64 or local photos
     * and uploads them to Cloudinary CDN to prevent app memory bloating.
     */
    suspend fun migrateAllPhotosToCloudinary(
        context: Context,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val allTx = transactionDao.getAllTransactions().first()
        val toMigrate = allTx.filter { tx ->
            val hasPhoto = !tx.proofPhotoUri.isNullOrBlank() || !tx.proofPhotoCloudUrl.isNullOrBlank()
            val alreadyOnCloudinary = ProofPhotoStorageManager.isCloudinaryUrl(tx.proofPhotoCloudUrl)
            hasPhoto && !alreadyOnCloudinary
        }

        var successCount = 0
        val total = toMigrate.size

        if (total == 0) {
            return@withContext Pair(0, 0)
        }

        val roomCode = cloudSyncEngine.syncCode.value

        toMigrate.forEachIndexed { index, tx ->
            try {
                var cloudinaryUrl: String? = null
                // Try local uri first (contains original uncompressed/clean capture)
                if (!tx.proofPhotoUri.isNullOrBlank()) {
                    cloudinaryUrl = ProofPhotoStorageManager.uploadToCloudinary(
                        context = context,
                        localPhotoPathOrUri = tx.proofPhotoUri,
                        transactionSyncId = tx.syncId.ifBlank { "TX-${tx.id}" }
                    )
                }

                // If local uri wasn't present or failed, try existing cloud/base64 url
                if (cloudinaryUrl.isNullOrBlank() && !tx.proofPhotoCloudUrl.isNullOrBlank()) {
                    cloudinaryUrl = ProofPhotoStorageManager.uploadToCloudinary(
                        context = context,
                        localPhotoPathOrUri = tx.proofPhotoCloudUrl,
                        transactionSyncId = tx.syncId.ifBlank { "TX-${tx.id}" }
                    )
                }

                if (!cloudinaryUrl.isNullOrBlank()) {
                    val updatedTx = tx.copy(proofPhotoCloudUrl = cloudinaryUrl)
                    transactionDao.updateTransaction(updatedTx)
                    cloudSyncEngine.firestoreService.saveTransactionToFirestore(roomCode, updatedTx)
                    successCount++
                }
            } catch (e: Throwable) {
                Log.w(tag, "Error migrating photo for tx ${tx.id}: ${e.message}")
            }
            onProgress?.invoke(index + 1, total)
        }

        cloudSyncEngine.triggerRealtimeSync()
        Pair(successCount, total)
    }

    private fun initFirestoreListeners() {
        val room = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.startRealtimeRoomListeners(
            roomCode = room,
            onTransactionsUpdated = { remoteTransactions ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val localTx = transactionDao.getAllTransactions().first()
                        val localSyncIds = localTx.map { it.syncId }.toSet()
                        val newOrUpdated = remoteTransactions.filter { remote ->
                            val match = localTx.find { it.syncId == remote.syncId }
                            match == null || match.proofPhotoCloudUrl != remote.proofPhotoCloudUrl || match.amount != remote.amount
                        }
                        if (newOrUpdated.isNotEmpty()) {
                            transactionDao.insertAll(newOrUpdated)
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Error merging Firestore transactions: ${e.message}")
                    }
                }
            },
            onCitizensUpdated = { remoteCitizens ->
                scope.launch(Dispatchers.IO) {
                    try {
                        if (remoteCitizens.isNotEmpty()) {
                            citizenDao.insertAll(remoteCitizens)
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Error merging Firestore citizens: ${e.message}")
                    }
                }
            },
            onDevicesUpdated = { remoteDevices ->
                // Devices updated via Firestore
            }
        )
    }

    fun getTransactionsByPeriod(month: Int, year: Int): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByMonthYear(month, year)
    }

    suspend fun insertTransaction(
        transaction: TransactionEntity,
        selectedImageUri: Uri? = null,
        context: Context? = null
    ): Long = withContext(Dispatchers.IO) {
        var txToSave = transaction
        if (txToSave.syncId.isBlank()) {
            txToSave = txToSave.copy(syncId = "TX-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4)}")
        }

        // Save local photo and upload to Cloudinary CDN
        if (selectedImageUri != null && context != null) {
            val localPath = ProofPhotoStorageManager.saveLocalReceiptPhoto(context, selectedImageUri)
            if (localPath != null) {
                // Upload directly to Cloudinary (Kas-Pintar-RT004 preset)
                val (cloudUrl, isUploaded) = ProofPhotoStorageManager.uploadReceiptPhoto(
                    context = context,
                    localPhotoPathOrUri = localPath,
                    transactionSyncId = txToSave.syncId
                )
                txToSave = txToSave.copy(
                    proofPhotoUri = localPath,
                    proofPhotoCloudUrl = cloudUrl
                )
                if (isUploaded) {
                    Log.i(tag, "Photo successfully uploaded to Cloudinary: $cloudUrl")
                }
            }
        }

        val generatedId = transactionDao.insertTransaction(txToSave)
        val savedTx = txToSave.copy(id = generatedId)

        // Sync to Cloud Firestore
        val roomCode = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.saveTransactionToFirestore(roomCode, savedTx)
        cloudSyncEngine.triggerRealtimeSync()

        generatedId
    }

    suspend fun updateTransaction(
        transaction: TransactionEntity,
        selectedImageUri: Uri? = null,
        context: Context? = null
    ) = withContext(Dispatchers.IO) {
        var txToUpdate = transaction
        if (selectedImageUri != null && context != null) {
            val localPath = ProofPhotoStorageManager.saveLocalReceiptPhoto(context, selectedImageUri)
            if (localPath != null) {
                val syncKey = txToUpdate.syncId.ifBlank { "TX-${txToUpdate.id}" }
                val (cloudUrl, isUploaded) = ProofPhotoStorageManager.uploadReceiptPhoto(
                    context = context,
                    localPhotoPathOrUri = localPath,
                    transactionSyncId = syncKey
                )
                txToUpdate = txToUpdate.copy(
                    proofPhotoUri = localPath,
                    proofPhotoCloudUrl = cloudUrl
                )
                if (isUploaded) {
                    Log.i(tag, "Photo updated to Cloudinary: $cloudUrl")
                }
            }
        }

        transactionDao.updateTransaction(txToUpdate)
        val roomCode = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.saveTransactionToFirestore(roomCode, txToUpdate)
        cloudSyncEngine.triggerRealtimeSync()
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) = withContext(Dispatchers.IO) {
        transactionDao.deleteTransaction(transaction)
        val roomCode = cloudSyncEngine.syncCode.value
        val syncKey = if (transaction.syncId.isNotBlank()) transaction.syncId else "TX-${transaction.id}-${transaction.createdAtMillis}"
        cloudSyncEngine.firestoreService.deleteTransactionFromFirestore(roomCode, syncKey)
        cloudSyncEngine.triggerRealtimeSync()
    }

    suspend fun insertCitizen(citizen: CitizenEntity): Long = withContext(Dispatchers.IO) {
        val id = citizenDao.insertCitizen(citizen)
        val savedCitizen = citizen.copy(id = id)
        val roomCode = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.saveCitizenToFirestore(roomCode, savedCitizen)
        cloudSyncEngine.triggerRealtimeSync()
        id
    }

    suspend fun updateCitizen(citizen: CitizenEntity) = withContext(Dispatchers.IO) {
        citizenDao.updateCitizen(citizen)
        val roomCode = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.saveCitizenToFirestore(roomCode, citizen)
        cloudSyncEngine.triggerRealtimeSync()
    }

    suspend fun deleteCitizen(citizen: CitizenEntity) = withContext(Dispatchers.IO) {
        citizenDao.deleteCitizen(citizen)
        val roomCode = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.deleteCitizenFromFirestore(roomCode, citizen.id)
        cloudSyncEngine.triggerRealtimeSync()
    }

    suspend fun payCitizenDues(
        citizen: CitizenEntity,
        month: Int,
        year: Int,
        amount: Long = citizen.monthlyFee,
        paymentMethod: PaymentMethod = PaymentMethod.TUNAI,
        recordedBy: String = "Bendahara RT"
    ): Long = withContext(Dispatchers.IO) {
        val isBusiness = citizen.type == CitizenType.PELAKU_USAHA || citizen.type == CitizenType.WARUNG_PKL
        val category = if (isBusiness) TransactionCategory.IURAN_USAHA else TransactionCategory.IURAN_WARGA
        val receiptPrefix = if (isBusiness) "KW-USH" else "KW-WRG"
        val receiptNumber = "$receiptPrefix-${year}${String.format("%02d", month)}-${UUID.randomUUID().toString().take(6).uppercase()}"

        val title = if (isBusiness) {
            "Iuran Usaha - ${citizen.name}"
        } else {
            "Iuran Warga - ${citizen.name}"
        }

        val transaction = TransactionEntity(
            title = title,
            amount = amount,
            type = TransactionType.PEMASUKAN,
            category = category,
            citizenId = citizen.id,
            citizenName = citizen.name,
            address = citizen.houseNumber.ifBlank { null },
            month = month,
            year = year,
            dateMillis = System.currentTimeMillis(),
            recordedBy = recordedBy,
            paymentMethod = paymentMethod,
            receiptNumber = receiptNumber,
            notes = "Iuran periode ${getMonthName(month)} $year (${citizen.houseNumber})",
            syncId = "SYNC-${System.currentTimeMillis()}"
        )

        val id = transactionDao.insertTransaction(transaction)
        val savedTx = transaction.copy(id = id)
        val roomCode = cloudSyncEngine.syncCode.value
        cloudSyncEngine.firestoreService.saveTransactionToFirestore(roomCode, savedTx)
        cloudSyncEngine.triggerRealtimeSync()
        id
    }

    suspend fun calculateMonthlyRecap(month: Int, year: Int): MonthlyRecap = withContext(Dispatchers.IO) {
        val allTx = transactionDao.getAllTransactions().first()
        val allCitizensList = citizenDao.getAllCitizens().first().filter { it.isActive }

        // Calculate starting balance before this month
        var startingBalance = 0L
        for (tx in allTx) {
            val isPrior = (tx.year < year) || (tx.year == year && tx.month < month)
            if (isPrior) {
                if (tx.type == TransactionType.PEMASUKAN) {
                    startingBalance += tx.amount
                } else {
                    startingBalance -= tx.amount
                }
            }
        }

        val monthTx = allTx.filter { it.month == month && it.year == year }
        val incomeTx = monthTx.filter { it.type == TransactionType.PEMASUKAN }
        val expenseTx = monthTx.filter { it.type == TransactionType.PENGELUARAN }

        val totalIncome = incomeTx.sumOf { it.amount }
        val totalExpense = expenseTx.sumOf { it.amount }
        val netBalance = totalIncome - totalExpense
        val endingBalance = startingBalance + netBalance

        // Citizen payment compliance
        val paidCitizenIds = monthTx
            .filter { it.type == TransactionType.PEMASUKAN && it.citizenId != null }
            .mapNotNull { it.citizenId }
            .toSet()

        val paidCount = allCitizensList.count { it.id in paidCitizenIds }
        val unpaidCount = allCitizensList.size - paidCount
        val complianceRate = if (allCitizensList.isNotEmpty()) {
            (paidCount.toFloat() / allCitizensList.size) * 100f
        } else 0f

        // Category breakdowns
        val incomeBreakdowns = incomeTx
            .groupBy { it.category }
            .map { (cat, list) ->
                val sum = list.sumOf { it.amount }
                val pct = if (totalIncome > 0) (sum.toFloat() / totalIncome) * 100f else 0f
                CategoryBreakdown(cat, sum, pct, list.size)
            }
            .sortedByDescending { it.totalAmount }

        val expenseBreakdowns = expenseTx
            .groupBy { it.category }
            .map { (cat, list) ->
                val sum = list.sumOf { it.amount }
                val pct = if (totalExpense > 0) (sum.toFloat() / totalExpense) * 100f else 0f
                CategoryBreakdown(cat, sum, pct, list.size)
            }
            .sortedByDescending { it.totalAmount }

        MonthlyRecap(
            month = month,
            year = year,
            startingBalance = startingBalance,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netBalance = netBalance,
            endingBalance = endingBalance,
            totalCitizens = allCitizensList.size,
            paidCitizensCount = paidCount,
            unpaidCitizensCount = unpaidCount,
            complianceRate = complianceRate,
            incomeCategories = incomeBreakdowns,
            expenseCategories = expenseBreakdowns,
            transactions = monthTx,
            isApprovedByKetua = true,
            approvalNotes = "Laporan telah diverifikasi dan disahkan oleh Ketua RT"
        )
    }

    suspend fun calculatePettyCashRecap(month: Int, year: Int): com.example.model.PettyCashRecap = withContext(Dispatchers.IO) {
        val allTx = transactionDao.getAllTransactions().first()
        val pettyCashAll = allTx.filter {
            it.isPettyCash || it.paymentMethod == PaymentMethod.TUNAI ||
            it.category == TransactionCategory.PENGISIAN_KAS_KECIL ||
            it.category == TransactionCategory.PENGEMBALIAN_SISA_KAS_KECIL
        }

        // Calculate starting balance of petty cash prior to this month
        var startingBalance = 0L
        for (tx in pettyCashAll) {
            val isPrior = (tx.year < year) || (tx.year == year && tx.month < month)
            if (isPrior) {
                if (tx.type == TransactionType.PEMASUKAN) {
                    startingBalance += tx.amount
                } else {
                    startingBalance -= tx.amount
                }
            }
        }

        val monthPettyTx = pettyCashAll.filter { it.month == month && it.year == year }
        val topUpTx = monthPettyTx.filter { it.type == TransactionType.PEMASUKAN }
        val disbursementTx = monthPettyTx.filter { it.type == TransactionType.PENGELUARAN }

        val totalTopUp = topUpTx.sumOf { it.amount }
        val totalDisbursement = disbursementTx.sumOf { it.amount }
        val netFluctuation = totalTopUp - totalDisbursement
        val endingBalance = startingBalance + netFluctuation

        val expenseBreakdowns = disbursementTx
            .groupBy { it.category }
            .map { (cat, list) ->
                val sum = list.sumOf { it.amount }
                val pct = if (totalDisbursement > 0) (sum.toFloat() / totalDisbursement) * 100f else 0f
                CategoryBreakdown(cat, sum, pct, list.size)
            }
            .sortedByDescending { it.totalAmount }

        com.example.model.PettyCashRecap(
            month = month,
            year = year,
            startingBalance = startingBalance,
            totalTopUp = totalTopUp,
            totalDisbursement = totalDisbursement,
            netFluctuation = netFluctuation,
            endingBalance = endingBalance,
            totalVouchers = disbursementTx.size,
            expenseCategoryBreakdowns = expenseBreakdowns,
            transactions = monthPettyTx.sortedByDescending { it.dateMillis }
        )
    }

    private fun getMonthName(month: Int): String = when (month) {
        1 -> "Januari"
        2 -> "Februari"
        3 -> "Maret"
        4 -> "April"
        5 -> "Mei"
        6 -> "Juni"
        7 -> "Juli"
        8 -> "Agustus"
        9 -> "September"
        10 -> "Oktober"
        11 -> "November"
        12 -> "Desember"
        else -> "Bulan $month"
    }
}
