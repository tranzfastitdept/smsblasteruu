package com.example.email

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Direct Gmail SMTP sender client.
 * Connects directly to smtp.gmail.com:465 via SSL/TLS socket, performs AUTH LOGIN
 * with the pre-configured credentials, and sends RFC 2822 email reports silently
 * in the background without needing user interaction or external email app intents.
 */
object GmailSmtpHelper {
    private const val TAG = "GmailSmtpHelper"

    // Hardcoded Gmail SMTP credentials specified by user
    const val SENDER_EMAIL = "itdepttranzfast@gmail.com"
    const val APP_PASSWORD = "xaswjzxvfuoclyys" // "xasw jzxv fuoc lyys" with spaces stripped
    const val SMTP_HOST = "smtp.gmail.com"
    const val SMTP_PORT = 465 // SSL port

    suspend fun sendEmailAsync(
        toEmail: String,
        subject: String,
        bodyText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        sendEmailSync(toEmail, subject, bodyText)
    }

    /**
     * Synchronous blocking SMTP dispatch method.
     * Can be invoked from background coroutines, WorkManager, ForegroundService,
     * or JavascriptInterface threads.
     */
    fun sendEmailSync(
        toEmail: String,
        subject: String,
        bodyText: String
    ): Result<String> {
        val recipient = toEmail.trim()
        if (recipient.isBlank()) {
            return Result.failure(IllegalArgumentException("Recipient email address is blank"))
        }

        Log.d(TAG, "Starting Gmail SMTP dispatch to $recipient via $SMTP_HOST:$SMTP_PORT...")
        var socket: SSLSocket? = null
        var reader: BufferedReader? = null
        var writer: BufferedWriter? = null

        return try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val rawSocket = sslFactory.createSocket(SMTP_HOST, SMTP_PORT) as SSLSocket
            socket = rawSocket
            socket.soTimeout = 20000 // 20s read/write timeout
            socket.startHandshake()

            reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))

            // 1. Initial 220 banner greeting from server
            val greeting = readReply(reader)
            Log.d(TAG, "SMTP <-- $greeting")
            if (!greeting.startsWith("220")) {
                throw IllegalStateException("Unexpected SMTP greeting: $greeting")
            }

            // 2. EHLO localhost
            sendLine(writer, "EHLO localhost")
            val ehloReply = readReply(reader)
            Log.d(TAG, "SMTP <-- EHLO response: ${ehloReply.lines().firstOrNull()}")

            // 3. AUTH LOGIN command
            sendLine(writer, "AUTH LOGIN")
            val authPrompt1 = readReply(reader)
            if (!authPrompt1.startsWith("334")) {
                throw IllegalStateException("AUTH LOGIN prompt failed: $authPrompt1")
            }

            // 4. Send Base64 encoded Username
            val userB64 = Base64.encodeToString(SENDER_EMAIL.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            sendLine(writer, userB64)
            val authPrompt2 = readReply(reader)
            if (!authPrompt2.startsWith("334")) {
                throw IllegalStateException("Username prompt failed: $authPrompt2")
            }

            // 5. Send Base64 encoded Password
            val passB64 = Base64.encodeToString(APP_PASSWORD.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            sendLine(writer, passB64)
            val authSuccess = readReply(reader)
            if (!authSuccess.startsWith("235")) {
                throw IllegalStateException("Authentication failed: $authSuccess")
            }
            Log.d(TAG, "SMTP <-- Authenticated successfully as $SENDER_EMAIL")

            // 6. MAIL FROM
            sendLine(writer, "MAIL FROM:<$SENDER_EMAIL>")
            val mailFromReply = readReply(reader)
            if (!mailFromReply.startsWith("250")) {
                throw IllegalStateException("MAIL FROM error: $mailFromReply")
            }

            // 7. RCPT TO
            sendLine(writer, "RCPT TO:<$recipient>")
            val rcptReply = readReply(reader)
            if (!rcptReply.startsWith("250")) {
                throw IllegalStateException("RCPT TO error: $rcptReply")
            }

            // 8. DATA
            sendLine(writer, "DATA")
            val dataReply = readReply(reader)
            if (!dataReply.startsWith("354")) {
                throw IllegalStateException("DATA start error: $dataReply")
            }

            // 9. Send standard RFC 2822 email headers
            val rfcDate = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
            val encodedSubject = "=?UTF-8?B?" + Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

            writer.write("From: ACR Services <$SENDER_EMAIL>\r\n")
            writer.write("To: <$recipient>\r\n")
            writer.write("Subject: $encodedSubject\r\n")
            writer.write("Date: $rfcDate\r\n")
            writer.write("MIME-Version: 1.0\r\n")
            writer.write("Content-Type: text/plain; charset=UTF-8\r\n")
            writer.write("Content-Transfer-Encoding: 8bit\r\n")
            writer.write("X-Mailer: ACR-Services-BulkSMS/1.0\r\n")
            writer.write("\r\n")

            // 10. Send email body with proper dot-stuffing (RFC 5321)
            val bodyLines = bodyText.replace("\r\n", "\n").split("\n")
            for (line in bodyLines) {
                if (line.startsWith(".")) {
                    writer.write("." + line + "\r\n")
                } else {
                    writer.write(line + "\r\n")
                }
            }
            writer.write(".\r\n")
            writer.flush()

            val sendReply = readReply(reader)
            Log.d(TAG, "SMTP <-- $sendReply")
            if (!sendReply.startsWith("250")) {
                throw IllegalStateException("Message delivery rejected: $sendReply")
            }

            // 11. Graceful QUIT
            try {
                sendLine(writer, "QUIT")
                readReply(reader)
            } catch (ignored: Exception) {}

            Log.i(TAG, "Successfully sent email report to $recipient via Gmail SMTP!")
            Result.success("Email successfully dispatched to $recipient via Gmail SMTP.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending email report to $recipient", e)
            Result.failure(e)
        } finally {
            try { writer?.close() } catch (ignored: Exception) {}
            try { reader?.close() } catch (ignored: Exception) {}
            try { socket?.close() } catch (ignored: Exception) {}
        }
    }

    private fun sendLine(writer: BufferedWriter, line: String) {
        writer.write(line + "\r\n")
        writer.flush()
    }

    private fun readReply(reader: BufferedReader): String {
        val firstLine = reader.readLine() ?: throw java.io.EOFException("SMTP connection closed unexpectedly")
        if (firstLine.length >= 4 && firstLine[3] == '-') {
            // Multi-line reply: begins with 'XYZ-', continues until line begins with 'XYZ '
            val sb = StringBuilder(firstLine)
            val code = firstLine.substring(0, 3)
            while (true) {
                val nextLine = reader.readLine() ?: break
                sb.append("\n").append(nextLine)
                if (nextLine.length >= 4 && nextLine.startsWith(code) && nextLine[3] == ' ') {
                    break
                }
            }
            return sb.toString()
        }
        return firstLine
    }
}
