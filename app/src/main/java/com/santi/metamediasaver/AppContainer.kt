package com.santi.metamediasaver

import android.content.Context
import com.santi.metamediasaver.data.auth.AuthRepository
import com.santi.metamediasaver.data.auth.FirebaseAuthRepository
import com.santi.metamediasaver.data.download.DownloadRepository
import com.santi.metamediasaver.data.download.WorkManagerDownloadRepository
import com.santi.metamediasaver.data.meta.FirebaseMetaRepository
import com.santi.metamediasaver.data.meta.MetaRepository

class AppContainer(context: Context) {
    val authRepository: AuthRepository = FirebaseAuthRepository()
    val metaRepository: MetaRepository = FirebaseMetaRepository()
    val downloadRepository: DownloadRepository = WorkManagerDownloadRepository(context)
}
