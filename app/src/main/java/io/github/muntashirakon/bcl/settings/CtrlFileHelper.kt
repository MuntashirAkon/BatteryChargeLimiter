package io.github.muntashirakon.bcl.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils

object CtrlFileHelper {

    fun validateFiles(context: Context, callback: Runnable?) {
        val handler = Handler(Looper.getMainLooper())
        // Replaces the deprecated android.app.ProgressDialog. An indeterminate
        // progress bar in a themed dialog keeps the same behaviour while
        // picking up the current Material theme.
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null, false)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()
        Utils.executor.submit {
            Utils.validateCtrlFiles(context)
            handler.post {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                callback?.run()
            }
        }
    }

}
