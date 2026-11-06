package io.github.denhaiz.exmail.data

import io.github.denhaiz.exmail.ews.EwsAuthException
import io.github.denhaiz.exmail.ews.EwsException
import io.github.denhaiz.exmail.ews.EwsHttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Понятное пользователю описание ошибки. */
fun Throwable.userMessage(): String = when (this) {
    is EwsAuthException -> "Неверный логин или пароль. Используйте те же данные, что и для входа в Outlook или на рабочий компьютер."
    is UnknownHostException, is ConnectException -> "Нет связи с почтовым сервером. Проверьте подключение к интернету."
    is SocketTimeoutException -> "Сервер долго не отвечает. Попробуйте ещё раз."
    is SSLException -> "Не удалось установить защищённое соединение с сервером."
    is EwsHttpException -> when (httpCode) {
        403 -> "Доступ к почте с мобильных устройств запрещён. Обратитесь в ИТ-отдел."
        404 -> "Адрес почтового сервера указан неверно."
        in 500..599 -> "Почтовый сервер временно недоступен. Попробуйте позже."
        else -> message ?: "Ошибка сервера"
    }
    is EwsException -> when (code) {
        "ErrorServerBusy" -> "Сервер перегружен. Попробуйте через минуту."
        "ErrorQuotaExceeded" -> "Почтовый ящик переполнен. Удалите ненужные письма."
        "ErrorItemNotFound" -> "Элемент не найден — возможно, он уже удалён."
        "ErrorMessageSizeExceeded" -> "Письмо слишком большое. Уменьшите размер вложений."
        "ErrorInvalidRecipients", "ErrorInvalidSmtpAddress" -> "Проверьте адреса получателей."
        "ErrorAccessDenied" -> "Недостаточно прав для этого действия."
        "ErrorIrresolvableConflict", "ErrorChangeKeyRequired", "ErrorStaleObject" ->
            "Письмо изменилось на сервере. Обновите список и повторите действие."
        "ErrorSchemaValidation", "ErrorInvalidRequest" -> "Сервер не принял запрос. Сообщите в ИТ-отдел, что случилось."
        else -> "Ошибка сервера: ${message ?: code}"
    }
    is IOException -> "Проблема с сетью. Проверьте подключение к интернету."
    is IllegalArgumentException, is IllegalStateException -> message ?: "Что-то пошло не так"
    else -> message ?: "Что-то пошло не так"
}
