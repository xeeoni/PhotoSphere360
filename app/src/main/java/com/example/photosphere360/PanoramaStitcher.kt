package com.example.photosphere360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File

// OpenCV의 Stitcher 클래스는 Java/Android 바인딩에 포함되어 있지 않아
// ORB 특징점 매칭 + 호모그래피 방식으로 직접 스티칭을 구현합니다.
object PanoramaStitcher {

    private const val MAX_WIDTH = 1600.0

    fun stitch(files: List<File>): File {
        require(files.size >= 2) { "사진이 2장 이상 필요합니다." }

        var result = loadResizedMat(files[0])
        for (i in 1 until files.size) {
            val next = loadResizedMat(files[i])
            val merged = stitchPair(result, next)
            result.release()
            next.release()
            result = merged
        }

        val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, bitmap)
        result.release()

        val out = File.createTempFile("photosphere_", ".jpg")
        out.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        bitmap.recycle()
        return out
    }

    private fun loadResizedMat(file: File): Mat {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("이미지를 읽을 수 없습니다: ${file.name}")

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        bitmap.recycle()

        return if (mat.width() > MAX_WIDTH) {
            val scaled = Mat()
            val ratio = MAX_WIDTH / mat.width()
            Imgproc.resize(mat, scaled, Size(MAX_WIDTH, mat.height() * ratio))
            mat.release()
            scaled
        } else {
            mat
        }
    }

    // left 위에 right를 정합해 이어붙입니다.
    private fun stitchPair(left: Mat, right: Mat): Mat {
        val grayL = Mat()
        val grayR = Mat()
        Imgproc.cvtColor(left, grayL, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(right, grayR, Imgproc.COLOR_RGBA2GRAY)

        val orb = ORB.create(2000)
        val kpL = MatOfKeyPoint()
        val kpR = MatOfKeyPoint()
        val descL = Mat()
        val descR = Mat()
        orb.detectAndCompute(grayL, Mat(), kpL, descL)
        orb.detectAndCompute(grayR, Mat(), kpR, descR)
        grayL.release()
        grayR.release()

        if (descL.empty() || descR.empty()) {
            descL.release(); descR.release()
            error("이미지에서 충분한 특징점을 찾지 못했습니다.")
        }

        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val matches = MatOfDMatch()
        matcher.match(descR, descL, matches)
        descL.release()
        descR.release()

        val matchList = matches.toArray().sortedBy { it.distance }
        val goodCount = maxOf(20, matchList.size / 4)
        val good = matchList.take(minOf(goodCount, matchList.size))

        if (good.size < 4) {
            error("겹치는 부분을 충분히 찾지 못했습니다. 사진이 30~50% 겹치게 촬영해주세요.")
        }

        val kpLList = kpL.toArray()
        val kpRList = kpR.toArray()

        val srcPts = MatOfPoint2f(*good.map { kpRList[it.queryIdx].pt }.toTypedArray())
        val dstPts = MatOfPoint2f(*good.map { kpLList[it.trainIdx].pt }.toTypedArray())

        val homography = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 5.0)
        srcPts.release()
        dstPts.release()

        if (homography.empty()) {
            error("이미지 정합에 실패했습니다. 다시 촬영해주세요.")
        }

        val outWidth = left.cols() + right.cols()
        val outHeight = maxOf(left.rows(), right.rows())

        val warped = Mat()
        Imgproc.warpPerspective(
            right, warped, homography,
            Size(outWidth.toDouble(), outHeight.toDouble())
        )
        homography.release()

        val canvas = Mat(outHeight, outWidth, warped.type(), Scalar(0.0, 0.0, 0.0, 0.0))
        warped.copyTo(canvas)
        warped.release()

        val leftRoi = canvas.submat(0, left.rows(), 0, left.cols())
        left.copyTo(leftRoi)
        leftRoi.release()

        return canvas
    }
}
