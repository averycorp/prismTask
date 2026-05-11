package com.averycorp.prismtask.data.remote

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import com.google.api.services.drive.model.File as DriveFile

@Singleton
class GoogleDriveService
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val APP_FOLDER_NAME = "PrismTask Backups"
        private const val BACKUP_FILE_NAME = "prismtask_backup.json"
        private const val MIME_JSON = "application/json"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        return Drive
            .Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("PrismTask")
            .build()
    }

    private suspend fun getOrCreateAppFolder(drive: Drive): String = withContext(Dispatchers.IO) {
        val result = drive
            .files()
            .list()
            .setQ("name = '$APP_FOLDER_NAME' and mimeType = '$MIME_FOLDER' and trashed = false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        if (result.files.isNotEmpty()) {
            result.files[0].id
        } else {
            val folderMetadata = DriveFile().apply {
                name = APP_FOLDER_NAME
                mimeType = MIME_FOLDER
            }
            drive
                .files()
                .create(folderMetadata)
                .setFields("id")
                .execute()
                .id
        }
    }

    suspend fun exportToDrive(jsonData: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService()
                ?: return@withContext Result.failure(Exception("Not signed in to Google. Please sign in first."))

            val folderId = getOrCreateAppFolder(drive)

            // Check if backup file already exists
            val existing = drive
                .files()
                .list()
                .setQ("name = '$BACKUP_FILE_NAME' and '$folderId' in parents and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            val content = ByteArrayContent.fromString(MIME_JSON, jsonData)

            if (existing.files.isNotEmpty()) {
                // Update existing file
                val fileId = existing.files[0].id
                drive.files().update(fileId, null, content).execute()
            } else {
                // Create new file
                val fileMetadata = DriveFile().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf(folderId)
                }
                drive
                    .files()
                    .create(fileMetadata, content)
                    .setFields("id")
                    .execute()
            }

            Result.success("Backup saved to Google Drive")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromDrive(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService()
                ?: return@withContext Result.failure(Exception("Not signed in to Google. Please sign in first."))

            val folderId = getOrCreateAppFolder(drive)

            val result = drive
                .files()
                .list()
                .setQ("name = '$BACKUP_FILE_NAME' and '$folderId' in parents and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime)")
                .execute()

            if (result.files.isEmpty()) {
                return@withContext Result.failure(Exception("No backup found on Google Drive"))
            }

            val fileId = result.files[0].id
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)

            Result.success(outputStream.toString("UTF-8"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isAvailable(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null
}
