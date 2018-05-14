package science.credo.mobiledetector.detection

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Camera
import android.util.Base64
import android.util.Log
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import science.credo.mobiledetector.CredoApplication
import science.credo.mobiledetector.database.DataManager
import science.credo.mobiledetector.info.ConfigurationInfo
import science.credo.mobiledetector.info.IdentityInfo
import science.credo.mobiledetector.info.LocationInfo
import science.credo.mobiledetector.network.ServerInterface
import science.credo.mobiledetector.network.messages.DetectionRequest
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.math.max
import kotlin.math.min

const val MAX_HITS_ONE_FRAME = 5


class CameraPreviewCallbackNative(private val mContext: Context) : Camera.PreviewCallback {
    private val mServerInterface = ServerInterface.getDefault(mContext)
    private val mLocationInfo: LocationInfo = LocationInfo.getInstance(mContext)

    companion object {
        val TAG = "CameraPreviewClbkNative"
        val aDataSize = 24
        var detectionStatsManager: DetectionStatsManager? = null
    }

    override fun onPreviewFrame(data: ByteArray, hCamera: Camera) {

        if (detectionStatsManager == null) {
            detectionStatsManager = DetectionStatsManager()
        }

        val config = ConfigurationInfo(mContext)
        val sensorsState = (mContext.applicationContext as CredoApplication).detectorState

        doAsync {

            val parameters = hCamera.parameters
            val width = parameters.previewSize.width
            val height = parameters.previewSize.height
            val analysisData = LongArray(aDataSize)

            var loop = -1
            detectionStatsManager!!.frameAchieved(width, height)
            val hits = LinkedList<Hit>()

            while (loop < MAX_HITS_ONE_FRAME) {
                loop++
                calcHistogram(data, analysisData, width, height, config.blackFactor)

                val max = analysisData[aDataSize - 1]
                val maxIndex = analysisData[aDataSize - 2]
                val sum = analysisData[aDataSize - 3]
                val zeroes = analysisData[aDataSize - 4]
                val average: Double = sum.toDouble() / (width * height).toDouble()
                val blacks: Double = zeroes * 1000 / (width * height).toDouble()

                if (loop == 0) {
                    detectionStatsManager!!.updateStats(max, average, blacks)
                }

                // frames not rejected conditions
                val averageBrightCondition = average < config.averageFactor
                val blackPixelsCondition = blacks >= config.blackCount

                // found Hit condition
                val brightestPixelCondition = max > config.maxFactor

                if (averageBrightCondition && blackPixelsCondition) {
                    if (loop == 0) {
                        detectionStatsManager!!.framePerformed()
                    }

                    if (brightestPixelCondition) {
                        val centerX = maxIndex.rem(width).toInt()
                        val centerY = (maxIndex / width).toInt()

                        val margin = config.cropSize / 2
                        val offsetX = max(0, centerX - margin)
                        val offsetY = max(0, centerY - margin)
                        val endX = min(width, centerX + margin)
                        val endY = min(height, centerY + margin)

                        val cropBitmap = ImageConversion.yuv2rgb(data, width, height, offsetX, offsetY, endX, endY)
                        detectionStatsManager!!.hitRegistered()
                        val cropDataPNG = bitmap2png(cropBitmap)
                        val dataString = Base64.encodeToString(cropDataPNG, Base64.DEFAULT)
                        val location = mLocationInfo.getLocationData()

                        val hit = Hit(
                                dataString,
                                System.currentTimeMillis(),
                                location.latitude,
                                location.longitude,
                                location.altitude,
                                location.accuracy,
                                location.provider,
                                width,
                                height,
                                centerX,
                                centerY,
                                max.toInt(),
                                average,
                                blacks,
                                config.blackFactor,
                                sensorsState.accX,
                                sensorsState.accY,
                                sensorsState.accZ,
                                sensorsState.orientation,
                                sensorsState.temperature
                        )
                        hits.add(hit)

                        fillHited(data, width, height, maxIndex.toInt(), config.cropSize)
                    } else {
                        break
                    }
                } else {
                    break
                }
            }

            uiThread {
                hCamera.addCallbackBuffer(data)
            }
            detectionStatsManager!!.flush(mContext, false)

            if (hits.size > 0) {
                val mDataManager: DataManager = DataManager.getDefault(mContext)

                for (hit in hits) {
                    try {
                        mDataManager.storeHit(hit)
                    } catch (e: Exception) {
                        Log.e(TAG, "Can't store hit", e)
                    }
                }
                // FIXME: in separated service because "send and check as toSend=false" should be atomic
                try {
                    mDataManager.sendHitsToNetwork(mServerInterface)
                } catch (e: Exception) {
                    Log.w(TAG, "Can't sent hit to server", e)
                }
                mDataManager.closeDb()
            }
        }
    }

    external fun calcHistogram(
        data: ByteArray,
        analysisData: LongArray,
        width: Int,
        height: Int,
        black: Int
    )

    fun bitmap2png (bitmap: Bitmap) : ByteArray {
        val pngData = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngData)
        return pngData.toByteArray()
    }

    // FIXME: limit to ~5
    fun fillHited(data: ByteArray, width: Int, height: Int, maxPosition: Int, sideLength: Int){

        //Point (maxX,maxY) is center(brightest pixel) of hit
        val maxX = maxPosition.rem(width)
        val maxY = maxPosition / width

        //Point (x,y) is upper-left corner of square with we want to fill
        var x = maxX - sideLength / 2
        var y = maxY - sideLength / 2


        when {
            x < 0 -> x = 0
            x >= width - sideLength -> x = width - sideLength
        }

        when {
        //We want to make sure that upper-left point of square is at least sideLength from bottom and right side of image
            y < 0 -> y = 0
            y >= height - sideLength -> y = height - sideLength
        }

        //Loops iterates from upper-left point sideLength times
        for (i in y..y + sideLength) {
            for (j in x..x + sideLength) {
                data[i * width + j] = 0
            }
        }

    }

}