package com.example.torrentorv2

import android.content.Context

object RustBridge {
    init { System.loadLibrary("torrentor_engine") }

    external fun initEngine(downloadDir: String, context: Context, dhtEnabled: Boolean, lsdEnabled: Boolean): String
    external fun addMagnet(magnetUri: String, outputFolder: String): String
    external fun addTorrentFile(torrentBytes: ByteArray, outputFolder: String): String
    external fun listOnlyAddMagnet(magnetOrHash: String): String
    external fun listOnlyAddTorrentFile(torrentBytes: ByteArray): String
    external fun confirmAdd(token: String, onlyFilesCsv: String, outputFolder: String): String
    external fun cancelPendingAdd(token: String): String
    external fun getTorrentInfo(): String
    external fun getTorrentPieces(hash: String): String
    external fun getTorrentPeers(hash: String): String
    external fun getTorrentFiles(hash: String): String
    external fun getTorrentExtra(hash: String): String
    external fun updateOnlyFiles(hash: String, onlyFilesCsv: String): String
    external fun getGlobalStats(): String
    external fun getNetworkStatus(): String
    external fun setSpeedLimits(downloadBps: Long, uploadBps: Long): String
    external fun getSpeedLimits(): String
    external fun pauseTorrent(hash: String): String
    external fun resumeTorrent(hash: String): String
    external fun deleteTorrent(hash: String, deleteFiles: Boolean): String
}