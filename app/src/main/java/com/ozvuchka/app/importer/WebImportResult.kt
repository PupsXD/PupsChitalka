package com.ozvuchka.app.importer

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
}

data class ImportError(val reason: ImportReason, val hint: String, val retryable: Boolean) {
    companion object {
        fun of(reason: ImportReason): ImportError = ImportError(reason, reason.hint(), reason.retryable())
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
enum class WebImportStage { CONNECTING, DOWNLOADING, EXTRACTING, SAVING, DONE, FAILED }

/** What the import sheet shows about a web chapter import. */
data class WebImportUi(
    val stage: WebImportStage,
    val url: String,
    val site: String,
    val error: ImportError? = null,
) {
    /** A running import: the sheet shows progress and blocks another start. */
    val isActive: Boolean get() = stage != WebImportStage.DONE && stage != WebImportStage.FAILED
}
