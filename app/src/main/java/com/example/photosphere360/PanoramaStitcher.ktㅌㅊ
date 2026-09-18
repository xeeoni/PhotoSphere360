package com.example.photosphere360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.stitching.Stitcher
import java.io.File

object PanoramaStitcher {

    fun stitch(files: List<File>): File {
        val mats = ArrayList<Mat>()

        // Resize very large camera images before stitching to reduce RAM use.
        for (file in files) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                ?: error("이미지를 읽을 수 없습니다: ${file.name}")

            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            bitmap.recycle()

            // OpenCV Stitcher works better with a moderate working size on phones.
            val maxWidth = 1800.0
            if (mat.width() > maxWidth) {
                val scaled = Mat()
                val ratio = maxWidth / mat.width()
                Imgproc.resize(mat, scaled, Size(0.0, mat.height() * ratio))
                mat.release()
                mats.add(scaled)
            } else {
                mats.add(mat)
            }
        }

        val stitcher = Stitcher.create(Stitcher.PANORAMA)
        val result = Mat()
        val code = stitcher.stitch(mats, result)

        mats.forEach { it.release() }

        if (code != Stitcher.OK || result.empty()) {
            result.release()
            error("사진 겹침을 충분히 찾지 못했습니다. 촬영할 때 인접 사진이 약 30~50% 겹치게 해주세요.")
        }

        val bitmap = Bitmap.createBitmap(
            result.cols(), result.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(result, bitmap)
        result.release()

        val out = File.createTempFile("photosphere_", ".jpg")
        out.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        bitmap.recycle()
        return out
    }
}
