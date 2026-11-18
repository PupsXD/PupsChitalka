package com.ozvuchka.app.importer

import kotlinx.coroutines.delay

/**
 * The end of a web chapter import: either the chapter, or a typed failure. Replaces the old
 * exception-message plumbing, so the interface can show a Russian hint and a retry button
 * for exactly the failures that can succeed on another try.
 */
sealed interface ImportResult {
    data class Success(val chapter: WebChapter) : ImportResult
    data class Failure(val error: ImportError) : ImportResult
}

/** Why a web import failed; [ImportError] carries the reader-facing wording. */
enum class ImportReason {
    BAD_URL,
    CLEARTEXT,
    NETWORK,
    TIMEOUT,
    HTTP_401,
    HTTP_403,
    HTTP_404,
    ANTIBOT,
    LOGIN_REQUIRED,
    PAYWALL,
    NON_HTML,
    EMPTY_CONTENT,
    NEXT_LINK_MISSING,

    /** The page or the announced book file is bigger than the import may hold in memory. */
    TOO_LARGE,

    /** The book page announces no FB2 file this app may download. */
    BOOK_FILE_MISSING,

    /** The downloaded file is not the announced FB2 book: a web page, another format or a broken archive. */
    BOOK_FORMAT,
}

data class ImportError(val reason: ImportReason, val hint: String, val retryable: Boolean) {
    companion object {
        fun of(reason: ImportReason): ImportError = ImportError(reason, reason.hint(), reason.retryable())

        /** The same failure with the technical cause appended, for the cases a reader can act on. */
        fun of(reason: ImportReason, detail: String?): ImportError =
            if (detail.isNullOrBlank()) of(reason) else ImportError(reason, "${reason.hint()}: $detail", reason.retryable())
    }
}

private fun ImportReason.hint(): String = when (this) {
    ImportReason.BAD_URL -> "Это не ссылка на страницу: нужен адрес, начинающийся с http:// или https://"
    ImportReason.CLEARTEXT -> "Приложение не открывает незащищённые http-адреса. Возьмите ссылку, начинающуюся с https://"
    ImportReason.NETWORK -> "Не удалось связаться с сайтом. Проверьте интернет и попробуйте ещё раз"
    ImportReason.TIMEOUT -> "Сайт отвечал слишком долго. Попробуйте ещё раз позже"
    ImportReason.HTTP_401 -> "Сайт требует авторизацию для этой главы"
    ImportReason.HTTP_403 -> "Сайт отказал в доступе. Попробуйте позже или откройте главу в браузере"
    ImportReason.HTTP_404 -> "Страница не найдена. Проверьте ссылку"
    ImportReason.ANTIBOT -> "Сайт требует проверку от ботов. Подождите немного и попробуйте ещё раз"
    ImportReason.LOGIN_REQUIRED -> "Для чтения этой главы нужно войти на сайт"
    ImportReason.PAYWALL -> "Глава доступна только по подписке или за оплату"
    ImportReason.NON_HTML -> "По ссылке не текст главы, а другой файл"
    ImportReason.EMPTY_CONTENT -> "На странице не удалось найти текст главы"
    ImportReason.NEXT_LINK_MISSING -> "Следующая глава не найдена: у сайта нет ссылки «вперёд»"
    ImportReason.TOO_LARGE -> "Страница или файл больше, чем приложение готово загрузить"
    ImportReason.BOOK_FILE_MISSING -> "На странице книги нет файла FB2 для скачивания"
    ImportReason.BOOK_FORMAT -> "Скачанный файл не похож на книгу FB2"
}

private fun ImportReason.retryable(): Boolean = when (this) {
    ImportReason.NETWORK, ImportReason.TIMEOUT, ImportReason.ANTIBOT, ImportReason.HTTP_403 -> true
    else -> false
}

/** The typed failure a URL earns before any network touch; null means it may be fetched. */
internal fun classifyUrl(url: String): ImportError? {
    val requestedUrl = canonicalWebUrl(url) ?: return ImportError.of(ImportReason.BAD_URL)
    // The manifest forbids cleartext traffic; name the cause instead of a network error.
    if (requestedUrl.startsWith("http://")) return ImportError.of(ImportReason.CLEARTEXT)
    return null
}

/** Stages of one web import, reported to the interface as they pass. */
enum class WebImportStage { CONNECTING, DOWNLOADING, EXTRACTING, PARSING, SAVING, DONE, FAILED }

/** What the import sheet shows about a web import. */
data class WebImportUi(
    val stage: WebImportStage,
    val url: String,
    val site: String,
    val error: ImportError? = null,
    /** The run downloads a whole book rather than one chapter: the sheet words its stages so. */
    val wholeBook: Boolean = false,
) {
    /** A running import: the sheet shows progress and blocks another start. */
    val isActive: Boolean get() = stage != WebImportStage.DONE && stage != WebImportStage.FAILED
}

/**
 * Retires a finished import's state. The sheet closes itself when it sees [WebImportStage.DONE],
 * but a share-intent import has no sheet and a stale DONE would slam the sheet shut the next
 * time it opens: the stage is gone only after [settleMillis], and only if nothing else reset it.
 */
suspend fun retireFinishedWebImport(
    stage: () -> WebImportStage?,
    clear: () -> Unit,
    settleMillis: Long,
) {
    if (stage() != WebImportStage.DONE) return
    delay(settleMillis)
    if (stage() == WebImportStage.DONE) clear()
}
