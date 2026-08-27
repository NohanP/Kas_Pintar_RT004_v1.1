package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.R
import com.example.model.MonthlyRecap
import com.example.model.PettyCashRecap
import com.example.model.TransactionEntity
import com.example.model.TransactionType
import com.example.ui.viewmodel.RtCashViewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

object ReportExportHelper {

    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID"))
    private val printDateTimeFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("id", "ID"))

    // Brand theme colors from Color.kt
    private val COLOR_DEEP_SLATE_NAVY = AndroidColor.rgb(0x13, 0x4B, 0x70) // Primary brand #134B70
    private val COLOR_MEDIUM_SLATE_BLUE = AndroidColor.rgb(0x3B, 0x67, 0x90) // #3B6790
    private val COLOR_MINT_CYAN = AndroidColor.rgb(0x86, 0xD3, 0xB6) // Accent highlight #86D3B6
    private val COLOR_SOFT_MINT_BG = AndroidColor.rgb(0xEE, 0xF8, 0xF6) // Card container #EEF8F6
    private val COLOR_SOFT_MINT_BORDER = AndroidColor.rgb(0xC8, 0xEC, 0xE6) // Card border #C8ECE6
    private val COLOR_BANNER_TINT = AndroidColor.rgb(0xE8, 0xF4, 0xF8) // Subtle tint #E8F4F8
    private val COLOR_INCOME_GREEN = AndroidColor.rgb(0x16, 0xA3, 0x4A) // Income #16A34A
    private val COLOR_EXPENSE_RED = AndroidColor.rgb(0xDC, 0x26, 0x26) // Expense #DC2626
    private val COLOR_TEXT_MAIN = AndroidColor.rgb(0x1E, 0x29, 0x3B) // Slate 800
    private val COLOR_TEXT_MUTED = AndroidColor.rgb(0x64, 0x74, 0x8B) // Slate 500
    private val COLOR_ZEBRA_ROW = AndroidColor.rgb(0xF8, 0xFA, 0xFC) // Slate 50
    private val COLOR_DIVIDER = AndroidColor.rgb(0xEE, 0xF2, 0xF6)

    /**
     * Decode and scale the RT 004 logo from drawable resources.
     */
    private fun getRtLogoBitmap(context: Context, targetSizePx: Int = 160): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.logo_rt004_400px, options)
                ?: BitmapFactory.decodeResource(context.resources, R.mipmap.logo_rt004_app, options)

            if (rawBitmap != null) {
                Bitmap.createScaledBitmap(rawBitmap, targetSizePx, targetSizePx, true)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract only the first name (nama depan saja) from a citizen/recipient name string.
     * Handles honorifics like Pak, Bu, Bpk, Ibu gracefully.
     */
    fun extractFirstName(fullName: String?): String {
        if (fullName.isNullOrBlank()) return "-"
        val trimmed = fullName.trim().replace("\"", "").replace(";", ",")
        val parts = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.isEmpty()) return "-"
        val first = parts[0]
        val honorifics = listOf("bpk", "pak", "ibu", "bu", "sdr", "sdri", "ustadz", "ust", "h.", "hj.", "dr.", "dr")
        if (honorifics.contains(first.lowercase()) && parts.size > 1) {
            return parts[1]
        }
        return first
    }

    /**
     * Export and share PDF Report in LANDSCAPE orientation (A4 Landscape: 842 x 595 pt)
     * equipped with RT 004 Logo, matching theme colors, Debit, Kredit, and Running Balance.
     */
    fun exportAndSharePdf(context: Context, recap: MonthlyRecap) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val fileName = "Laporan_Kas_RT004_${recap.monthName}_${recap.year}_Landscape.pdf"
            val file = File(reportsDir, fileName)

            val pdfDocument = PdfDocument()
            val pageWidth = 842 // A4 Landscape width
            val pageHeight = 595 // A4 Landscape height

            val transactions = recap.transactions
            val itemsPerPage = 14
            val totalPages = max(1, ceil(transactions.size.toDouble() / itemsPerPage).toInt())

            val paint = Paint().apply { isAntiAlias = true }
            val printDate = printDateTimeFormatter.format(Date())
            val logoBitmap = getRtLogoBitmap(context, targetSizePx = 140)

            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                val isFirstPage = pageIndex == 0
                val isLastPage = pageIndex == totalPages - 1

                // 1. Header Banner (Deep Slate Navy #134B70)
                val headerHeight = if (isFirstPage) 70f else 48f
                paint.color = COLOR_DEEP_SLATE_NAVY
                canvas.drawRect(0f, 0f, pageWidth.toFloat(), headerHeight, paint)

                // Accent Stripe underneath header banner (Mint Cyan #86D3B6)
                paint.color = COLOR_MINT_CYAN
                canvas.drawRect(0f, headerHeight, pageWidth.toFloat(), headerHeight + 3f, paint)

                if (isFirstPage) {
                    // Draw RT 004 Logo in Header
                    val logoRect = RectF(24f, 10f, 74f, 60f)
                    paint.color = AndroidColor.WHITE
                    canvas.drawRoundRect(logoRect, 8f, 8f, paint)

                    if (logoBitmap != null) {
                        paint.isFilterBitmap = true
                        canvas.drawBitmap(logoBitmap, null, RectF(26f, 12f, 72f, 58f), paint)
                        paint.isFilterBitmap = false
                    }

                    // Header Titles beside Logo
                    val textStartX = 84f
                    paint.color = AndroidColor.WHITE
                    paint.textSize = 13.5f
                    paint.isFakeBoldText = true
                    canvas.drawText("RUKUN TETANGGA 004 / RW 08 KELURAHAN JATI, PULOGADUNG", textStartX, 27f, paint)

                    paint.textSize = 9.5f
                    paint.isFakeBoldText = false
                    paint.color = COLOR_BANNER_TINT
                    canvas.drawText(
                        "Laporan Rekapitulasi Arus Kas Bulanan (Debit & Kredit) • Periode: ${recap.monthName} ${recap.year}",
                        textStartX,
                        43f,
                        paint
                    )

                    paint.color = COLOR_MINT_CYAN
                    paint.textSize = 7.5f
                    canvas.drawText(
                        "Sistem Informasi Keuangan Digital Warga RT004 | Kel. Jati, Kec. Pulogadung, Jakarta Timur",
                        textStartX,
                        57f,
                        paint
                    )

                    // Right Metadata
                    val metaStartX = 580f
                    paint.color = COLOR_BANNER_TINT
                    paint.textSize = 8f
                    canvas.drawText("Dikeluarkan oleh Pengurus RT 004 / RW 08", metaStartX, 27f, paint)

                    paint.color = COLOR_MINT_CYAN
                    paint.textSize = 8f
                    paint.isFakeBoldText = true
                    canvas.drawText("Status: Laporan Sah & Disinkronkan", metaStartX, 43f, paint)

                    paint.color = COLOR_BANNER_TINT
                    paint.textSize = 7.5f
                    paint.isFakeBoldText = false
                    canvas.drawText("Cetak: $printDate • Hal ${pageIndex + 1}/$totalPages", metaStartX, 57f, paint)
                } else {
                    // Subsequent Page Header
                    val logoRect = RectF(24f, 8f, 58f, 42f)
                    paint.color = AndroidColor.WHITE
                    canvas.drawRoundRect(logoRect, 6f, 6f, paint)

                    if (logoBitmap != null) {
                        paint.isFilterBitmap = true
                        canvas.drawBitmap(logoBitmap, null, RectF(25f, 9f, 57f, 41f), paint)
                        paint.isFilterBitmap = false
                    }

                    val textStartX = 66f
                    paint.color = AndroidColor.WHITE
                    paint.textSize = 11.5f
                    paint.isFakeBoldText = true
                    canvas.drawText("BUKU KAS RT 004 / RW 08 • Periode: ${recap.monthName} ${recap.year} (Lanjutan)", textStartX, 29f, paint)

                    paint.color = COLOR_MINT_CYAN
                    paint.textSize = 8f
                    paint.isFakeBoldText = false
                    canvas.drawText("Cetak: $printDate • Hal ${pageIndex + 1}/$totalPages", 630f, 29f, paint)
                }

                var currentY = headerHeight + 12f

                // 2. Executive Summary Block (Only on First Page)
                if (isFirstPage) {
                    val summaryBoxHeight = 52f
                    val summaryRect = RectF(24f, currentY, 818f, currentY + summaryBoxHeight)

                    // Summary Background Card (Soft Mint #EEF8F6)
                    paint.color = COLOR_SOFT_MINT_BG
                    canvas.drawRoundRect(summaryRect, 10f, 10f, paint)

                    // Summary Border (Soft Mint Border #C8ECE6)
                    paint.color = COLOR_SOFT_MINT_BORDER
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.2f
                    canvas.drawRoundRect(summaryRect, 10f, 10f, paint)
                    paint.style = Paint.Style.FILL

                    val col1X = 38f
                    val col2X = 230f
                    val col3X = 425f
                    val col4X = 620f
                    val labelY = currentY + 16f
                    val valY = currentY + 33f

                    // Col 1: Saldo Awal
                    paint.textSize = 8f
                    paint.color = COLOR_TEXT_MUTED
                    paint.isFakeBoldText = true
                    canvas.drawText("SALDO AWAL BULAN", col1X, labelY, paint)
                    paint.textSize = 10.5f
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.startingBalance), col1X, valY, paint)

                    // Col 2: Total Debit
                    paint.textSize = 8f
                    paint.color = COLOR_INCOME_GREEN
                    canvas.drawText("TOTAL DEBIT (PENERIMAAN)", col2X, labelY, paint)
                    paint.textSize = 10.5f
                    canvas.drawText("+ ${RtCashViewModel.formatRupiah(recap.totalIncome)}", col2X, valY, paint)

                    // Col 3: Total Kredit
                    paint.textSize = 8f
                    paint.color = COLOR_EXPENSE_RED
                    canvas.drawText("TOTAL KREDIT (PENGELUARAN)", col3X, labelY, paint)
                    paint.textSize = 10.5f
                    canvas.drawText("- ${RtCashViewModel.formatRupiah(recap.totalExpense)}", col3X, valY, paint)

                    // Col 4: Saldo Akhir
                    paint.textSize = 8f
                    paint.color = COLOR_DEEP_SLATE_NAVY
                    canvas.drawText("SALDO AKHIR KAS RT", col4X, labelY, paint)
                    paint.textSize = 11.5f
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.endingBalance), col4X, valY, paint)

                    // Subtitle status line
                    paint.textSize = 7.5f
                    paint.isFakeBoldText = false
                    paint.color = COLOR_MEDIUM_SLATE_BLUE
                    canvas.drawText(
                        "Status Iuran Warga: ${recap.paidCitizensCount} dari ${recap.totalCitizens} Warga Lunas (${String.format(Locale("id", "ID"), "%.1f", recap.complianceRate)}%) • Belum Lunas: ${recap.unpaidCitizensCount} • Surplus/Defisit: ${RtCashViewModel.formatRupiah(recap.netBalance)}",
                        col1X,
                        currentY + 45f,
                        paint
                    )

                    currentY += summaryBoxHeight + 10f
                }

                // 3. Table Header (Landscape with Debit & Kredit Columns)
                val tableHeaderHeight = 20f
                paint.color = COLOR_DEEP_SLATE_NAVY
                canvas.drawRect(24f, currentY, 818f, currentY + tableHeaderHeight, paint)

                paint.color = AndroidColor.WHITE
                paint.textSize = 8f
                paint.isFakeBoldText = true
                val textHeaderY = currentY + 13.5f

                canvas.drawText("NO", 30f, textHeaderY, paint)
                canvas.drawText("TANGGAL", 52f, textHeaderY, paint)
                canvas.drawText("NO KWITANSI", 112f, textHeaderY, paint)
                canvas.drawText("KATEGORI", 192f, textHeaderY, paint)
                canvas.drawText("URAIAN / PERIHAL TRANSAKSI", 295f, textHeaderY, paint)
                canvas.drawText("PENERIMA (NAMA DEPAN)", 480f, textHeaderY, paint)
                canvas.drawText("METODE", 600f, textHeaderY, paint)
                canvas.drawText("DEBIT / MASUK (RP)", 658f, textHeaderY, paint)
                canvas.drawText("KREDIT / KELUAR (RP)", 738f, textHeaderY, paint)

                currentY += tableHeaderHeight

                // 4. Table Rows
                val startIdx = pageIndex * itemsPerPage
                val endIdx = minOf(startIdx + itemsPerPage, transactions.size)
                val pageTransactions = if (startIdx < transactions.size) transactions.subList(startIdx, endIdx) else emptyList()

                val rowHeight = 17f
                paint.isFakeBoldText = false
                paint.textSize = 7.5f

                pageTransactions.forEachIndexed { idxOnPage, tx ->
                    val globalIdx = startIdx + idxOnPage
                    val rowY = currentY

                    // Row Zebra Background
                    if (globalIdx % 2 == 1) {
                        paint.color = COLOR_ZEBRA_ROW
                        canvas.drawRect(24f, rowY, 818f, rowY + rowHeight, paint)
                    }

                    // Row divider line
                    paint.color = COLOR_DIVIDER
                    paint.strokeWidth = 0.5f
                    canvas.drawLine(24f, rowY + rowHeight, 818f, rowY + rowHeight, paint)

                    val textY = rowY + 11.5f

                    // No
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("${globalIdx + 1}", 30f, textY, paint)

                    // Tanggal
                    val dateFormatted = dateFormatter.format(Date(tx.dateMillis))
                    canvas.drawText(dateFormatted, 52f, textY, paint)

                    // No Kwitansi
                    canvas.drawText(tx.receiptNumber.take(16), 112f, textY, paint)

                    // Kategori
                    val shortCat = if (tx.category.title.length > 20) tx.category.title.take(18) + ".." else tx.category.title
                    canvas.drawText(shortCat, 192f, textY, paint)

                    // Uraian
                    val shortTitle = if (tx.title.length > 36) tx.title.take(34) + ".." else tx.title
                    canvas.drawText(shortTitle, 295f, textY, paint)

                    // Nama Penerima (Nama Depan Saja)
                    val firstName = extractFirstName(tx.citizenName ?: tx.recordedBy)
                    canvas.drawText(firstName.take(18), 480f, textY, paint)

                    // Metode
                    canvas.drawText(tx.paymentMethod.label.take(10), 600f, textY, paint)

                    // Debit (Masuk) Column
                    if (tx.type == TransactionType.PEMASUKAN) {
                        paint.color = COLOR_INCOME_GREEN
                        paint.isFakeBoldText = true
                        canvas.drawText(RtCashViewModel.formatRupiah(tx.amount).replace("Rp ", ""), 658f, textY, paint)
                        paint.isFakeBoldText = false
                    } else {
                        paint.color = COLOR_TEXT_MUTED
                        canvas.drawText("-", 690f, textY, paint)
                    }

                    // Kredit (Keluar) Column
                    if (tx.type == TransactionType.PENGELUARAN) {
                        paint.color = COLOR_EXPENSE_RED
                        paint.isFakeBoldText = true
                        canvas.drawText(RtCashViewModel.formatRupiah(tx.amount).replace("Rp ", ""), 738f, textY, paint)
                        paint.isFakeBoldText = false
                    } else {
                        paint.color = COLOR_TEXT_MUTED
                        canvas.drawText("-", 770f, textY, paint)
                    }

                    currentY += rowHeight
                }

                // If last page: Draw Totals Row & Signatures
                if (isLastPage) {
                    // Total Summary Row
                    paint.color = COLOR_SOFT_MINT_BG
                    canvas.drawRect(24f, currentY, 818f, currentY + 19f, paint)

                    // Top border for totals row
                    paint.color = COLOR_MINT_CYAN
                    paint.strokeWidth = 1f
                    canvas.drawLine(24f, currentY, 818f, currentY, paint)

                    paint.color = COLOR_DEEP_SLATE_NAVY
                    paint.textSize = 8f
                    paint.isFakeBoldText = true
                    canvas.drawText("TOTAL MUTASI KAS BULAN INI", 295f, currentY + 13f, paint)

                    paint.color = COLOR_INCOME_GREEN
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.totalIncome).replace("Rp ", ""), 658f, currentY + 13f, paint)

                    paint.color = COLOR_EXPENSE_RED
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.totalExpense).replace("Rp ", ""), 738f, currentY + 13f, paint)

                    // 5. Signatures Block (Pengesah, Sekretaris, Bendahara)
                    val sigY = 515f
                    paint.isFakeBoldText = false
                    paint.color = COLOR_TEXT_MUTED
                    paint.textSize = 8f

                    // Left: Ketua RT
                    canvas.drawText("Mengesahkan / Mengetahui,", 50f, sigY, paint)
                    paint.isFakeBoldText = true
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("Ketua RT 004 / RW 08", 50f, sigY + 13f, paint)
                    canvas.drawText("( Nohan Pancono )", 50f, sigY + 52f, paint)

                    // Center: Sekretaris
                    paint.isFakeBoldText = false
                    paint.color = COLOR_TEXT_MUTED
                    canvas.drawText("Mengetahui,", 340f, sigY, paint)
                    paint.isFakeBoldText = true
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("Sekretaris RT 004", 340f, sigY + 13f, paint)
                    canvas.drawText("( Muhammad Rijaldi Imam M. )", 340f, sigY + 52f, paint)

                    // Right: Bendahara RT
                    paint.isFakeBoldText = false
                    paint.color = COLOR_TEXT_MUTED
                    canvas.drawText("Jakarta Timur, ${recap.monthName} ${recap.year}", 610f, sigY, paint)
                    paint.isFakeBoldText = true
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("Bendahara RT 004 (Penyusun)", 610f, sigY + 13f, paint)
                    canvas.drawText("( Prihatini Endah Yulia M. )", 610f, sigY + 52f, paint)
                }

                pdfDocument.finishPage(page)
            }

            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            shareFile(context, file, "application/pdf", "Bagikan Laporan PDF Landscape Arus Kas RT004")
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuat PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Export and share PDF Report for Petty Cash (Kas Kecil RT 004)
     */
    fun exportAndSharePettyCashPdf(context: Context, recap: PettyCashRecap) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val fileName = "Laporan_Kas_Kecil_RT004_${recap.monthName}_${recap.year}.pdf"
            val file = File(reportsDir, fileName)

            val pdfDocument = PdfDocument()
            val pageWidth = 842 // A4 Landscape width
            val pageHeight = 595 // A4 Landscape height

            val transactions = recap.transactions
            val itemsPerPage = 14
            val totalPages = max(1, ceil(transactions.size.toDouble() / itemsPerPage).toInt())

            val paint = Paint().apply { isAntiAlias = true }
            val printDate = printDateTimeFormatter.format(Date())
            val logoBitmap = getRtLogoBitmap(context, targetSizePx = 140)

            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                val isFirstPage = pageIndex == 0
                val isLastPage = pageIndex == totalPages - 1

                // 1. Header Banner (Deep Slate Navy #134B70)
                val headerHeight = if (isFirstPage) 70f else 48f
                paint.color = COLOR_DEEP_SLATE_NAVY
                canvas.drawRect(0f, 0f, pageWidth.toFloat(), headerHeight, paint)

                // Accent Stripe underneath header (Mint Cyan #86D3B6)
                paint.color = COLOR_MINT_CYAN
                canvas.drawRect(0f, headerHeight, pageWidth.toFloat(), headerHeight + 3f, paint)

                if (isFirstPage) {
                    // Draw RT 004 Logo in Header
                    val logoRect = RectF(24f, 10f, 74f, 60f)
                    paint.color = AndroidColor.WHITE
                    canvas.drawRoundRect(logoRect, 8f, 8f, paint)

                    if (logoBitmap != null) {
                        paint.isFilterBitmap = true
                        canvas.drawBitmap(logoBitmap, null, RectF(26f, 12f, 72f, 58f), paint)
                        paint.isFilterBitmap = false
                    }

                    // Header Titles beside Logo
                    val textStartX = 84f
                    paint.color = AndroidColor.WHITE
                    paint.textSize = 13.5f
                    paint.isFakeBoldText = true
                    canvas.drawText("BUKU KAS KECIL RT 004 / RW 08 KELURAHAN JATI, PULOGADUNG", textStartX, 27f, paint)

                    paint.textSize = 9.5f
                    paint.isFakeBoldText = false
                    paint.color = COLOR_BANNER_TINT
                    canvas.drawText(
                        "Laporan Kas Kecil Operasional & Voucher BPKK • Periode: ${recap.monthName} ${recap.year}",
                        textStartX,
                        43f,
                        paint
                    )

                    paint.color = COLOR_MINT_CYAN
                    paint.textSize = 7.5f
                    canvas.drawText(
                        "Sistem Informasi Kas Kecil RT004 | Kel. Jati, Kec. Pulogadung, Jakarta Timur",
                        textStartX,
                        57f,
                        paint
                    )

                    // Right Metadata
                    val metaStartX = 580f
                    paint.color = COLOR_BANNER_TINT
                    paint.textSize = 8f
                    canvas.drawText("Kasir / Pemegang Kas: ${recap.custodianName}", metaStartX, 27f, paint)

                    paint.color = COLOR_MINT_CYAN
                    paint.textSize = 8f
                    paint.isFakeBoldText = true
                    canvas.drawText("Status: Sah Tercatat di Kasir RT", metaStartX, 43f, paint)

                    paint.color = COLOR_BANNER_TINT
                    paint.textSize = 7.5f
                    paint.isFakeBoldText = false
                    canvas.drawText("Cetak: $printDate • Hal ${pageIndex + 1}/$totalPages", metaStartX, 57f, paint)
                } else {
                    // Subsequent Page Header
                    val logoRect = RectF(24f, 8f, 58f, 42f)
                    paint.color = AndroidColor.WHITE
                    canvas.drawRoundRect(logoRect, 6f, 6f, paint)

                    if (logoBitmap != null) {
                        paint.isFilterBitmap = true
                        canvas.drawBitmap(logoBitmap, null, RectF(25f, 9f, 57f, 41f), paint)
                        paint.isFilterBitmap = false
                    }

                    val textStartX = 66f
                    paint.color = AndroidColor.WHITE
                    paint.textSize = 11.5f
                    paint.isFakeBoldText = true
                    canvas.drawText("BUKU KAS KECIL RT 004 • Periode: ${recap.monthName} ${recap.year} (Lanjutan)", textStartX, 29f, paint)

                    paint.color = COLOR_MINT_CYAN
                    paint.textSize = 8f
                    paint.isFakeBoldText = false
                    canvas.drawText("Cetak: $printDate • Hal ${pageIndex + 1}/$totalPages", 630f, 29f, paint)
                }

                var currentY = headerHeight + 12f

                // 2. Executive Petty Cash Summary Card (Only on First Page)
                if (isFirstPage) {
                    val summaryBoxHeight = 52f
                    val summaryRect = RectF(24f, currentY, 818f, currentY + summaryBoxHeight)

                    // Summary Background Card (Soft Mint #EEF8F6)
                    paint.color = COLOR_SOFT_MINT_BG
                    canvas.drawRoundRect(summaryRect, 10f, 10f, paint)

                    // Summary Border (Soft Mint Border #C8ECE6)
                    paint.color = COLOR_SOFT_MINT_BORDER
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.2f
                    canvas.drawRoundRect(summaryRect, 10f, 10f, paint)
                    paint.style = Paint.Style.FILL

                    val col1X = 38f
                    val col2X = 230f
                    val col3X = 425f
                    val col4X = 620f
                    val labelY = currentY + 16f
                    val valY = currentY + 33f

                    // Col 1: Saldo Awal Kas Kecil
                    paint.textSize = 8f
                    paint.color = COLOR_TEXT_MUTED
                    paint.isFakeBoldText = true
                    canvas.drawText("SALDO AWAL KAS KECIL", col1X, labelY, paint)
                    paint.textSize = 10.5f
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.startingBalance), col1X, valY, paint)

                    // Col 2: Total Top Up
                    paint.textSize = 8f
                    paint.color = COLOR_INCOME_GREEN
                    canvas.drawText("PENGISIAN / TOP UP (DEBET)", col2X, labelY, paint)
                    paint.textSize = 10.5f
                    canvas.drawText("+ ${RtCashViewModel.formatRupiah(recap.totalTopUp)}", col2X, valY, paint)

                    // Col 3: Total Pemakaian
                    paint.textSize = 8f
                    paint.color = COLOR_EXPENSE_RED
                    canvas.drawText("TOTAL PENGELUARAN (KREDIT)", col3X, labelY, paint)
                    paint.textSize = 10.5f
                    canvas.drawText("- ${RtCashViewModel.formatRupiah(recap.totalDisbursement)}", col3X, valY, paint)

                    // Col 4: Saldo Akhir
                    paint.textSize = 8f
                    paint.color = COLOR_DEEP_SLATE_NAVY
                    canvas.drawText("SALDO AKHIR KAS KECIL", col4X, labelY, paint)
                    paint.textSize = 11.5f
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.endingBalance), col4X, valY, paint)

                    // Subtitle status line
                    paint.textSize = 7.5f
                    paint.isFakeBoldText = false
                    paint.color = COLOR_MEDIUM_SLATE_BLUE
                    canvas.drawText(
                        "Jumlah Transaksi: ${recap.totalVouchers} Transaksi • Mutasi Bersih Kas Kecil: ${RtCashViewModel.formatRupiah(recap.netFluctuation)}",
                        col1X,
                        currentY + 45f,
                        paint
                    )

                    currentY += summaryBoxHeight + 10f
                }

                // 3. Table Header
                val tableHeaderHeight = 20f
                paint.color = COLOR_DEEP_SLATE_NAVY
                canvas.drawRect(24f, currentY, 818f, currentY + tableHeaderHeight, paint)

                paint.color = AndroidColor.WHITE
                paint.textSize = 8f
                paint.isFakeBoldText = true
                val textHeaderY = currentY + 13.5f

                canvas.drawText("NO", 30f, textHeaderY, paint)
                canvas.drawText("TANGGAL", 52f, textHeaderY, paint)
                canvas.drawText("NO. BUKTI / VOUCHER", 112f, textHeaderY, paint)
                canvas.drawText("POS BEBAN / KATEGORI", 220f, textHeaderY, paint)
                canvas.drawText("URAIAN PEMAKAIAN KAS KECIL", 330f, textHeaderY, paint)
                canvas.drawText("PENERIMA / DIBAYARKAN", 500f, textHeaderY, paint)
                canvas.drawText("TOP UP / DEBET (RP)", 645f, textHeaderY, paint)
                canvas.drawText("PEMAKAIAN / KREDIT (RP)", 730f, textHeaderY, paint)

                currentY += tableHeaderHeight

                // 4. Table Rows
                val startIdx = pageIndex * itemsPerPage
                val endIdx = minOf(startIdx + itemsPerPage, transactions.size)
                val pageTransactions = if (startIdx < transactions.size) transactions.subList(startIdx, endIdx) else emptyList()

                val rowHeight = 17f
                paint.isFakeBoldText = false
                paint.textSize = 7.5f

                pageTransactions.forEachIndexed { idxOnPage, tx ->
                    val globalIdx = startIdx + idxOnPage
                    val rowY = currentY

                    // Row Zebra Background
                    if (globalIdx % 2 == 1) {
                        paint.color = COLOR_ZEBRA_ROW
                        canvas.drawRect(24f, rowY, 818f, rowY + rowHeight, paint)
                    }

                    // Row divider line
                    paint.color = COLOR_DIVIDER
                    paint.strokeWidth = 0.5f
                    canvas.drawLine(24f, rowY + rowHeight, 818f, rowY + rowHeight, paint)

                    val textY = rowY + 11.5f

                    // No
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("${globalIdx + 1}", 30f, textY, paint)

                    // Tanggal
                    val dateFormatted = dateFormatter.format(Date(tx.dateMillis))
                    canvas.drawText(dateFormatted, 52f, textY, paint)

                    // No Bukti
                    val bpkkDisplay = if (tx.bpkkNumber.isNotBlank()) tx.bpkkNumber else tx.receiptNumber
                    canvas.drawText(bpkkDisplay.take(16), 112f, textY, paint)

                    // Kategori / Pos Beban
                    val shortCat = if (tx.category.title.length > 20) tx.category.title.take(18) + ".." else tx.category.title
                    canvas.drawText(shortCat, 220f, textY, paint)

                    // Uraian
                    val shortTitle = if (tx.title.length > 34) tx.title.take(32) + ".." else tx.title
                    canvas.drawText(shortTitle, 330f, textY, paint)

                    // Penerima Person
                    val recipientDisplay = tx.recipientPerson ?: tx.citizenName ?: tx.recordedBy
                    val shortRecipient = extractFirstName(recipientDisplay)
                    canvas.drawText(shortRecipient.take(18), 500f, textY, paint)

                    // Debet (Top Up Kas Kecil)
                    if (tx.type == TransactionType.PEMASUKAN) {
                        paint.color = COLOR_INCOME_GREEN
                        paint.isFakeBoldText = true
                        canvas.drawText(RtCashViewModel.formatRupiah(tx.amount).replace("Rp ", ""), 645f, textY, paint)
                        paint.isFakeBoldText = false
                    } else {
                        paint.color = COLOR_TEXT_MUTED
                        canvas.drawText("-", 675f, textY, paint)
                    }

                    // Kredit (Pemakaian Kas Kecil)
                    if (tx.type == TransactionType.PENGELUARAN) {
                        paint.color = COLOR_EXPENSE_RED
                        paint.isFakeBoldText = true
                        canvas.drawText(RtCashViewModel.formatRupiah(tx.amount).replace("Rp ", ""), 730f, textY, paint)
                        paint.isFakeBoldText = false
                    } else {
                        paint.color = COLOR_TEXT_MUTED
                        canvas.drawText("-", 760f, textY, paint)
                    }

                    currentY += rowHeight
                }

                // If last page: Totals and Signatures
                if (isLastPage) {
                    paint.color = COLOR_SOFT_MINT_BG
                    canvas.drawRect(24f, currentY, 818f, currentY + 19f, paint)

                    // Top border for totals row
                    paint.color = COLOR_MINT_CYAN
                    paint.strokeWidth = 1f
                    canvas.drawLine(24f, currentY, 818f, currentY, paint)

                    paint.color = COLOR_DEEP_SLATE_NAVY
                    paint.textSize = 8f
                    paint.isFakeBoldText = true
                    canvas.drawText("TOTAL MUTASI KAS KECIL PERIODE INI", 330f, currentY + 13f, paint)

                    paint.color = COLOR_INCOME_GREEN
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.totalTopUp).replace("Rp ", ""), 645f, currentY + 13f, paint)

                    paint.color = COLOR_EXPENSE_RED
                    canvas.drawText(RtCashViewModel.formatRupiah(recap.totalDisbursement).replace("Rp ", ""), 730f, currentY + 13f, paint)

                    // Signatures Block
                    val sigY = 515f
                    paint.isFakeBoldText = false
                    paint.color = COLOR_TEXT_MUTED
                    paint.textSize = 8f

                    // Left: Ketua RT
                    canvas.drawText("Mengetahui & Menyetujui,", 60f, sigY, paint)
                    paint.isFakeBoldText = true
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("Ketua RT 004 / RW 08", 60f, sigY + 13f, paint)
                    canvas.drawText("( Nohan Pancono )", 60f, sigY + 52f, paint)

                    // Right: Kasir / Pemegang Kas Kecil
                    paint.isFakeBoldText = false
                    paint.color = COLOR_TEXT_MUTED
                    canvas.drawText("Jakarta Timur, ${recap.monthName} ${recap.year}", 580f, sigY, paint)
                    paint.isFakeBoldText = true
                    paint.color = COLOR_TEXT_MAIN
                    canvas.drawText("Bendahara / Pemegang Kas Kecil", 580f, sigY + 13f, paint)
                    canvas.drawText("( Prihatini Endah Yulia M. )", 580f, sigY + 52f, paint)
                }

                pdfDocument.finishPage(page)
            }

            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            shareFile(context, file, "application/pdf", "Bagikan Laporan Kas Kecil PDF")
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuat PDF Kas Kecil: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Export and share official single PDF Kwitansi for a single transaction.
     * (A5 Portrait: 420 x 595 pt)
     */
    fun exportAndShareKwitansiPdf(context: Context, tx: TransactionEntity) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val receiptNum = if (tx.receiptNumber.isNotBlank()) tx.receiptNumber else "KW-${tx.id}"
            val fileName = "Kwitansi_RT004_${receiptNum.replace("/", "_")}.pdf"
            val file = File(reportsDir, fileName)

            val pdfDocument = PdfDocument()
            val pageWidth = 420 // A5 Portrait width
            val pageHeight = 595 // A5 Portrait height

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint().apply { isAntiAlias = true }

            val logoBitmap = getRtLogoBitmap(context, targetSizePx = 160)
            val isIncome = tx.type == TransactionType.PEMASUKAN

            // 1. Header Banner
            val headerHeight = 76f
            paint.color = COLOR_DEEP_SLATE_NAVY
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), headerHeight, paint)

            paint.color = COLOR_MINT_CYAN
            canvas.drawRect(0f, headerHeight, pageWidth.toFloat(), headerHeight + 3f, paint)

            // Logo in Header
            val logoRect = RectF(16f, 12f, 66f, 62f)
            paint.color = AndroidColor.WHITE
            canvas.drawRoundRect(logoRect, 8f, 8f, paint)
            if (logoBitmap != null) {
                paint.isFilterBitmap = true
                canvas.drawBitmap(logoBitmap, null, RectF(18f, 14f, 64f, 60f), paint)
                paint.isFilterBitmap = false
            }

            // Header Letterhead Text
            paint.color = AndroidColor.WHITE
            paint.textSize = 11.5f
            paint.isFakeBoldText = true
            canvas.drawText("RUKUN TETANGGA 004 / RW 08", 76f, 26f, paint)

            paint.textSize = 8.5f
            paint.isFakeBoldText = false
            paint.color = COLOR_BANNER_TINT
            canvas.drawText("Kelurahan Jati, Kecamatan Pulogadung, Jakarta Timur", 76f, 40f, paint)

            paint.color = COLOR_MINT_CYAN
            paint.textSize = 8f
            paint.isFakeBoldText = true
            canvas.drawText(if (isIncome) "KWITANSI PEMBAYARAN KAS RT" else "BUKTI PENGELUARAN KAS RT", 76f, 56f, paint)

            // 2. Receipt Card Body
            var currentY = headerHeight + 18f
            val bodyRect = RectF(16f, currentY, pageWidth - 16f, currentY + 360f)

            paint.color = COLOR_SOFT_MINT_BG
            canvas.drawRoundRect(bodyRect, 12f, 12f, paint)

            paint.color = COLOR_SOFT_MINT_BORDER
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            canvas.drawRoundRect(bodyRect, 12f, 12f, paint)
            paint.style = Paint.Style.FILL

            // Receipt Metadata Row
            var itemY = currentY + 24f
            val colLeft = 32f
            val colRight = pageWidth - 32f

            paint.color = COLOR_DEEP_SLATE_NAVY
            paint.textSize = 9.5f
            paint.isFakeBoldText = true
            canvas.drawText("NOMOR KWITANSI", colLeft, itemY, paint)
            canvas.drawText(receiptNum, colLeft + 120f, itemY, paint)

            itemY += 20f
            paint.color = COLOR_TEXT_MUTED
            paint.textSize = 8.5f
            paint.isFakeBoldText = false
            canvas.drawText("Tanggal", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(RtCashViewModel.formatDate(tx.dateMillis), colLeft + 120f, itemY, paint)

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Kategori / Pos", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(tx.category.title, colLeft + 120f, itemY, paint)

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Perihal / Uraian", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(tx.title.take(32), colLeft + 120f, itemY, paint)

            if (!tx.citizenName.isNullOrBlank()) {
                itemY += 18f
                paint.color = COLOR_TEXT_MUTED
                paint.isFakeBoldText = false
                canvas.drawText(if (isIncome) "Diterima Dari" else "Dibayarkan Kepada", colLeft, itemY, paint)
                paint.color = COLOR_TEXT_MAIN
                paint.isFakeBoldText = true
                canvas.drawText(tx.citizenName, colLeft + 120f, itemY, paint)
            }

            if (!tx.address.isNullOrBlank()) {
                itemY += 18f
                paint.color = COLOR_TEXT_MUTED
                paint.isFakeBoldText = false
                canvas.drawText("Alamat Warga", colLeft, itemY, paint)
                paint.color = COLOR_TEXT_MAIN
                paint.isFakeBoldText = true
                canvas.drawText(tx.address, colLeft + 120f, itemY, paint)
            }

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Metode Bayar", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(tx.paymentMethod.label, colLeft + 120f, itemY, paint)

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Petugas Kasir", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(tx.recordedBy, colLeft + 120f, itemY, paint)

            // Divider Line
            itemY += 18f
            paint.color = COLOR_SOFT_MINT_BORDER
            paint.strokeWidth = 1f
            canvas.drawLine(colLeft, itemY, colRight, itemY, paint)

            // Big Total Amount Box
            itemY += 14f
            val amountBoxRect = RectF(colLeft, itemY, colRight, itemY + 48f)
            paint.color = if (isIncome) AndroidColor.rgb(0xDC, 0xFC, 0xE7) else AndroidColor.rgb(0xFE, 0xE2, 0xE2)
            canvas.drawRoundRect(amountBoxRect, 8f, 8f, paint)

            paint.color = if (isIncome) COLOR_INCOME_GREEN else COLOR_EXPENSE_RED
            paint.textSize = 8.5f
            paint.isFakeBoldText = true
            canvas.drawText("TOTAL NOMINAL TRANSAKSI", colLeft + 14f, itemY + 18f, paint)

            paint.textSize = 14f
            canvas.drawText(RtCashViewModel.formatRupiah(tx.amount), colLeft + 14f, itemY + 38f, paint)

            // Stamp Status Box
            itemY += 60f
            val stampRect = RectF(colLeft, itemY, colRight, itemY + 28f)
            paint.color = AndroidColor.rgb(0xEE, 0xF8, 0xF6)
            canvas.drawRoundRect(stampRect, 6f, 6f, paint)
            paint.color = COLOR_DEEP_SLATE_NAVY
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            canvas.drawRoundRect(stampRect, 6f, 6f, paint)
            paint.style = Paint.Style.FILL

            paint.color = COLOR_INCOME_GREEN
            paint.textSize = 8f
            paint.isFakeBoldText = true
            canvas.drawText("✓ STATUS: SAH TERCATAT DI KAS RT 004 / RW 08", colLeft + 24f, itemY + 18f, paint)

            // 3. Signatures
            val sigY = pageHeight - 95f
            paint.color = COLOR_TEXT_MUTED
            paint.textSize = 8f
            paint.isFakeBoldText = false

            // Left: Warga / Pembayar
            canvas.drawText("Warga / Penyetor,", 40f, sigY, paint)
            paint.isFakeBoldText = true
            paint.color = COLOR_TEXT_MAIN
            canvas.drawText("( ${tx.citizenName ?: "Warga RT004"} )", 40f, sigY + 48f, paint)

            // Right: Bendahara RT
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Bendahara RT 004,", pageWidth - 160f, sigY, paint)
            paint.isFakeBoldText = true
            paint.color = COLOR_TEXT_MAIN
            canvas.drawText("( Prihatini Endah Y. M. )", pageWidth - 160f, sigY + 48f, paint)

            // Footer note
            paint.color = COLOR_TEXT_MUTED
            paint.textSize = 7f
            paint.isFakeBoldText = false
            canvas.drawText("Dicetak otomatis oleh Sistem Kas Digital RT 004 / RW 08 Jati, Pulogadung", 40f, pageHeight - 20f, paint)

            pdfDocument.finishPage(page)
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            shareFile(context, file, "application/pdf", "Bagikan Kwitansi Kas RT 004 PDF")
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuat PDF Kwitansi: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Export and share official PDF Voucher Kas Kecil (BPKK).
     * (A5 Portrait: 420 x 595 pt)
     */
    fun exportAndSharePettyCashVoucherPdf(context: Context, tx: TransactionEntity) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val voucherNum = if (tx.bpkkNumber.isNotBlank()) tx.bpkkNumber else tx.receiptNumber
            val fileName = "Voucher_BPKK_RT004_${voucherNum.replace("/", "_")}.pdf"
            val file = File(reportsDir, fileName)

            val pdfDocument = PdfDocument()
            val pageWidth = 420 // A5 Portrait width
            val pageHeight = 595 // A5 Portrait height

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint().apply { isAntiAlias = true }

            val logoBitmap = getRtLogoBitmap(context, targetSizePx = 160)
            val isExpense = tx.type == TransactionType.PENGELUARAN

            // 1. Header Banner
            val headerHeight = 76f
            paint.color = COLOR_DEEP_SLATE_NAVY
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), headerHeight, paint)

            paint.color = COLOR_MINT_CYAN
            canvas.drawRect(0f, headerHeight, pageWidth.toFloat(), headerHeight + 3f, paint)

            // Logo in Header
            val logoRect = RectF(16f, 12f, 66f, 62f)
            paint.color = AndroidColor.WHITE
            canvas.drawRoundRect(logoRect, 8f, 8f, paint)
            if (logoBitmap != null) {
                paint.isFilterBitmap = true
                canvas.drawBitmap(logoBitmap, null, RectF(18f, 14f, 64f, 60f), paint)
                paint.isFilterBitmap = false
            }

            // Header Letterhead Text
            paint.color = AndroidColor.WHITE
            paint.textSize = 11.5f
            paint.isFakeBoldText = true
            canvas.drawText("RUKUN TETANGGA 004 / RW 08", 76f, 26f, paint)

            paint.textSize = 8.5f
            paint.isFakeBoldText = false
            paint.color = COLOR_BANNER_TINT
            canvas.drawText("Kelurahan Jati, Kecamatan Pulogadung, Jakarta Timur", 76f, 40f, paint)

            paint.color = COLOR_MINT_CYAN
            paint.textSize = 8f
            paint.isFakeBoldText = true
            canvas.drawText(if (isExpense) "VOUCHER PENGELUARAN KAS KECIL (BPKK)" else "BUKTI PENERIMAAN / TOP UP KAS KECIL", 76f, 56f, paint)

            // 2. Voucher Card Body
            var currentY = headerHeight + 18f
            val bodyRect = RectF(16f, currentY, pageWidth - 16f, currentY + 360f)

            paint.color = COLOR_SOFT_MINT_BG
            canvas.drawRoundRect(bodyRect, 12f, 12f, paint)

            paint.color = COLOR_SOFT_MINT_BORDER
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            canvas.drawRoundRect(bodyRect, 12f, 12f, paint)
            paint.style = Paint.Style.FILL

            // Voucher Details Row
            var itemY = currentY + 24f
            val colLeft = 32f
            val colRight = pageWidth - 32f

            paint.color = COLOR_DEEP_SLATE_NAVY
            paint.textSize = 9.5f
            paint.isFakeBoldText = true
            canvas.drawText("NOMOR VOUCHER", colLeft, itemY, paint)
            canvas.drawText(voucherNum, colLeft + 120f, itemY, paint)

            itemY += 20f
            paint.color = COLOR_TEXT_MUTED
            paint.textSize = 8.5f
            paint.isFakeBoldText = false
            canvas.drawText("Tanggal", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(RtCashViewModel.formatDate(tx.dateMillis), colLeft + 120f, itemY, paint)

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Pos Beban", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(tx.category.title, colLeft + 120f, itemY, paint)

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Keperluan", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(tx.title.take(32), colLeft + 120f, itemY, paint)

            val recipient = tx.recipientPerson ?: tx.citizenName ?: "Kas RT"
            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText(if (isExpense) "Dibayarkan Kepada" else "Diterima Dari", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText(recipient, colLeft + 120f, itemY, paint)

            itemY += 18f
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Metode", colLeft, itemY, paint)
            paint.color = COLOR_TEXT_MAIN
            paint.isFakeBoldText = true
            canvas.drawText("Kas Kecil (Tunai di Bendahara)", colLeft + 120f, itemY, paint)

            if (tx.notes.isNotBlank()) {
                itemY += 18f
                paint.color = COLOR_TEXT_MUTED
                paint.isFakeBoldText = false
                canvas.drawText("Catatan", colLeft, itemY, paint)
                paint.color = COLOR_TEXT_MAIN
                paint.isFakeBoldText = true
                canvas.drawText(tx.notes.take(30), colLeft + 120f, itemY, paint)
            }

            // Divider Line
            itemY += 18f
            paint.color = COLOR_SOFT_MINT_BORDER
            paint.strokeWidth = 1f
            canvas.drawLine(colLeft, itemY, colRight, itemY, paint)

            // Nominal Amount Box
            itemY += 14f
            val amountBoxRect = RectF(colLeft, itemY, colRight, itemY + 48f)
            paint.color = if (isExpense) AndroidColor.rgb(0xFE, 0xE2, 0xE2) else AndroidColor.rgb(0xDC, 0xFC, 0xE7)
            canvas.drawRoundRect(amountBoxRect, 8f, 8f, paint)

            paint.color = if (isExpense) COLOR_EXPENSE_RED else COLOR_INCOME_GREEN
            paint.textSize = 8.5f
            paint.isFakeBoldText = true
            canvas.drawText("JUMLAH PENGELUARAN KAS KECIL", colLeft + 14f, itemY + 18f, paint)

            paint.textSize = 14f
            canvas.drawText(RtCashViewModel.formatRupiah(tx.amount), colLeft + 14f, itemY + 38f, paint)

            // Stamp Status Box
            itemY += 60f
            val stampRect = RectF(colLeft, itemY, colRight, itemY + 28f)
            paint.color = AndroidColor.rgb(0xEE, 0xF8, 0xF6)
            canvas.drawRoundRect(stampRect, 6f, 6f, paint)
            paint.color = COLOR_DEEP_SLATE_NAVY
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            canvas.drawRoundRect(stampRect, 6f, 6f, paint)
            paint.style = Paint.Style.FILL

            paint.color = COLOR_INCOME_GREEN
            paint.textSize = 8f
            paint.isFakeBoldText = true
            canvas.drawText("✓ STATUS: SAH TERCATAT DI BUKU KAS KECIL RT 004", colLeft + 18f, itemY + 18f, paint)

            // 3. Signatures
            val sigY = pageHeight - 95f
            paint.color = COLOR_TEXT_MUTED
            paint.textSize = 8f
            paint.isFakeBoldText = false

            // Left: Penerima Dana
            canvas.drawText("Penerima Dana,", 40f, sigY, paint)
            paint.isFakeBoldText = true
            paint.color = COLOR_TEXT_MAIN
            canvas.drawText("( ${recipient.take(20)} )", 40f, sigY + 48f, paint)

            // Right: Pemegang Kas Kecil
            paint.color = COLOR_TEXT_MUTED
            paint.isFakeBoldText = false
            canvas.drawText("Pemegang Kas Kecil,", pageWidth - 160f, sigY, paint)
            paint.isFakeBoldText = true
            paint.color = COLOR_TEXT_MAIN
            canvas.drawText("( Prihatini Endah Y. M. )", pageWidth - 160f, sigY + 48f, paint)

            // Footer note
            paint.color = COLOR_TEXT_MUTED
            paint.textSize = 7f
            paint.isFakeBoldText = false
            canvas.drawText("Dicetak otomatis oleh Sistem Kas Digital RT 004 / RW 08 Jati, Pulogadung", 40f, pageHeight - 20f, paint)

            pdfDocument.finishPage(page)
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            shareFile(context, file, "application/pdf", "Bagikan Voucher Kas Kecil RT 004 PDF")
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuat PDF Voucher: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Export and share CSV/Excel Spreadsheet file with:
     * - Debit (Pemasukan) column
     * - Kredit (Pengeluaran) column
     * - Nama Penerima / Warga (Nama Depan Saja)
     * Compatible with Microsoft Excel & Google Sheets.
     */
    fun exportAndShareExcelCsv(context: Context, recap: MonthlyRecap) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val fileName = "Laporan_Kas_RT004_${recap.monthName}_${recap.year}.csv"
            val file = File(reportsDir, fileName)

            val csvContent = StringBuilder().apply {
                append("LAPORAN ARUS KAS BULANAN RT004 / RW 08 JATI PULOGADUNG\n")
                append("Periode;,${recap.monthName} ${recap.year}\n")
                append("Saldo Awal;,${recap.startingBalance}\n")
                append("Total Penerimaan (Debit);,${recap.totalIncome}\n")
                append("Total Pengeluaran (Kredit);,${recap.totalExpense}\n")
                append("Surplus / Defisit;,${recap.netBalance}\n")
                append("Saldo Akhir Kas RT;,${recap.endingBalance}\n\n")
                append("Penyusun;,Prihatini Endah Yulia Maretiasari (Bendahara RT004)\n")
                append("Pengesah;,Nohan Pancono (Ketua RT 004)\n")
                append("Sekretaris;,Muhammad Rijaldi Imam Mustarih\n\n")
                append("No;Tanggal;No Kwitansi;Kategori;Perihal Transaksi;Jenis;Nama Penerima (Nama Depan);Metode Pembayaran;Debit / Masuk (Rp);Kredit / Keluar (Rp);Nominal Total (Rp);Keterangan Lengkap Warga\n")

                recap.transactions.forEachIndexed { index, tx ->
                    val cleanTitle = tx.title.replace(";", ",")
                    val citizenFull = tx.citizenName ?: "-"
                    val recipientFirstName = extractFirstName(tx.citizenName ?: tx.recordedBy)
                    val dateFormatted = dateFormatter.format(Date(tx.dateMillis))
                    val debitAmount = if (tx.type == TransactionType.PEMASUKAN) tx.amount else 0
                    val kreditAmount = if (tx.type == TransactionType.PENGELUARAN) tx.amount else 0

                    append("${index + 1};$dateFormatted;${tx.receiptNumber};${tx.category.title};\"$cleanTitle\";${tx.type.name};\"$recipientFirstName\";${tx.paymentMethod.label};$debitAmount;$kreditAmount;${tx.amount};\"$citizenFull\"\n")
                }
            }.toString()

            // Write with UTF-8 BOM for Microsoft Excel auto-encoding
            FileOutputStream(file).use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(csvContent.toByteArray(Charsets.UTF_8))
            }

            shareFile(context, file, "text/csv", "Buka di Microsoft Excel / Google Sheets")
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal ekspor Excel/CSV: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Export and open directly via Google Drive / Google Sheets Web or App
     */
    fun openInGoogleSheets(context: Context, recap: MonthlyRecap) {
        exportAndShareExcelCsv(context, recap)
    }

    /**
     * Export and share CSV/Excel Spreadsheet for Petty Cash
     */
    fun exportAndSharePettyCashExcelCsv(context: Context, recap: PettyCashRecap) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val fileName = "Laporan_Kas_Kecil_RT004_${recap.monthName}_${recap.year}.csv"
            val file = File(reportsDir, fileName)

            val csvContent = StringBuilder().apply {
                append("LAPORAN KAS KECIL RT004 / RW 08\n")
                append("Periode;,${recap.monthName} ${recap.year}\n")
                append("Saldo Awal Kas Kecil;,${recap.startingBalance}\n")
                append("Total Pengisian / Top Up;,${recap.totalTopUp}\n")
                append("Total Pengeluaran;,${recap.totalDisbursement}\n")
                append("Mutasi Bersih;,${recap.netFluctuation}\n")
                append("Saldo Akhir Kas Kecil;,${recap.endingBalance}\n")
                append("Jumlah Transaksi;,${recap.totalVouchers}\n\n")
                append("Pemegang Kas Kecil;,Prihatini Endah Yulia Maretiasari (Bendahara RT004)\n")
                append("Menyetujui;,Nohan Pancono (Ketua RT 004)\n\n")
                append("No;Tanggal;No Bukti;Pos Beban / Kategori;Uraian Pemakaian;Penerima Dana;Jenis;Debet / Top Up (Rp);Kredit / Keluar (Rp);Nominal Total (Rp);Catatan\n")

                recap.transactions.forEachIndexed { index, tx ->
                    val cleanTitle = tx.title.replace(";", ",")
                    val recipient = (tx.recipientPerson ?: tx.citizenName ?: tx.recordedBy).replace(";", ",")
                    val dateFormatted = dateFormatter.format(Date(tx.dateMillis))
                    val bpkk = if (tx.bpkkNumber.isNotBlank()) tx.bpkkNumber else tx.receiptNumber
                    val debitAmount = if (tx.type == TransactionType.PEMASUKAN) tx.amount else 0
                    val kreditAmount = if (tx.type == TransactionType.PENGELUARAN) tx.amount else 0

                    append("${index + 1};$dateFormatted;\"$bpkk\";${tx.category.title};\"$cleanTitle\";\"$recipient\";${tx.type.name};$debitAmount;$kreditAmount;${tx.amount};\"${tx.notes}\"\n")
                }
            }.toString()

            // Write with UTF-8 BOM for Microsoft Excel
            FileOutputStream(file).use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(csvContent.toByteArray(Charsets.UTF_8))
            }

            shareFile(context, file, "text/csv", "Buka Laporan Kas Kecil di Microsoft Excel / Google Sheets")
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal ekspor CSV Kas Kecil: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Laporan Arus Kas RT 04 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

