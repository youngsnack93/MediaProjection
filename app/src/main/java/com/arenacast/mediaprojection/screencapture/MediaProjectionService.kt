package com.arenacast.mediaprojection.screencapture

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.LocalServerSocket
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import android.view.WindowManager
import com.arenacast.mediaprojection.*
import com.arenacast.mediaprojection.ACTION_INIT
import com.arenacast.mediaprojection.ACTION_SELF_STOP
import com.arenacast.mediaprojection.EXTRA_RESULT_CODE
import com.arenacast.mediaprojection.model.MessageRoute
import com.pedro.encoder.video.VideoEncoder
import com.pedro.rtplibrary.util.RecordController
import org.greenrobot.eventbus.EventBus
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/*
RTMP: Realtime Messaging Protocol, port: 80 or 1935
 */
open class MediaProjectionService : Service() {

    private val TAG = "MediaProjectionService"

    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var pfd: ParcelFileDescriptor

    private var mediaRecorder: MediaRecorder? = null
    private var socket: Socket? = null
    //private var localServerSocket: LocalServerSocket? = null

    private val ORIENTATIONS = SparseIntArray().apply {
        this.append(Surface.ROTATION_0, 90)
        this.append(Surface.ROTATION_90, 0)
        this.append(Surface.ROTATION_180, 270)
        this.append(Surface.ROTATION_270, 180)
    }

    //----------------------------------------------------------------------------------------
    companion object {
        //private lateinit var context: Context
        //private const val FOREGROUND_SERVICE_ID = 1000
        //private const val CHANNEL_ID = "MediaProjectionService"

        fun newService(context: Context): Intent {
            //this.context = context
            return Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_INIT
            }
        }

        fun newStopService(context: Context): Intent =
            newService(context).apply {
                action = ACTION_SELF_STOP
            }

        fun permissionInit(context: Context, resultCode: Int, resultData: Intent): Intent =
            newService(context).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_REQUEST_DATA, resultData)
                action = ACTION_PERMISSION_INIT
            }

        fun newStartMediaProjection(context: Context, surface: Surface,
                                    projectionName: String,
                                    width: Int,
                                    height: Int,
                                    dpi: Int): Intent {
            return newService(context).apply {
                putExtra(EXTRA_SURFACE, surface)
                putExtra(EXTRA_PROJECTION_NAME, projectionName)
                putExtra(EXTRA_SIZE_WIDTH, width)
                putExtra(EXTRA_SIZE_HEIGHT, height)
                action = ACTION_START
            }
        }

        fun newStopMediaProjection(context: Context): Intent {
            return newService(context).apply {
                action = ACTION_STOP
            }
        }
    }

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    //----------------------------------------------------------------------------------------
    /*
    ???????????? ?????? ??????????????? onCreate -> onStartCommand ????????? ????????????
    ????????? startService ????????? onStartCommand ??? ??????
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate -------------------")
        startForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy -------------------")
        tearDownMediaProjection()
    }

    /*
    START_STICKY : Service??? ?????? ??????????????? ?????? ???????????? ?????? Service??? ????????? ?????? ????????? intent ?????? null??? ????????? ????????? ????????? ?????????.
    Service ????????? startService(Intent service) ???????????? ?????? ????????? onStartCommand(Intent intent, int flags, int startId)
    ???????????? intent??? value??? ?????? ??? ??? ????????????. ????????? intent??? value?????? ????????? ???????????? ???????????? Service ???????????? intent ?????? null??? ????????? ?????? ????????? ?????????.

    START_NOT_STICKY : ??? Flag??? ????????? ?????????, ????????? ?????? ??? Service??? ????????? ?????? ????????????.
    ???????????? ?????? ?????? ??????????????? ????????? ????????? ?????? ??? ??? ????????? ????????? ?????????.

    START_REDELIVER_INTENT : START_STICKY??? ??????????????? Service??? ?????? ????????? ?????? ???????????? ?????? Service??? ????????? ?????? ?????????
    intent ?????? ????????? ?????? ?????? ?????????.
    startService() ????????? ????????? Intent value?????? ????????? ???????????? ?????? Flag??? ???????????? ???????????? ????????? ?????? ?????????.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand -------------------")
        action(intent)
        return START_REDELIVER_INTENT
    }

    override fun onBind(p0: Intent?): IBinder? = null

    //----------------------------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
                createNotificationChannel(serviceChannel)
            }
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "startForegroundService ----------------")

        createNotificationChannel()
        val notificationIntent = Intent(this, MediaProjectionActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, 0
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("Foreground Service")
            .setSmallIcon(R.drawable.ic_baseline_fiber_manual_record_24)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(FOREGROUND_SERVICE_ID, notification)
    }

    //----------------------------------------------------------------------------------------
    private fun action(intent: Intent?) {
        Log.d(TAG, "action -------------------")
        when (intent?.action) {
            ACTION_INIT -> getPermission(intent)
            ACTION_PERMISSION_INIT -> permissionInitMediaProjection(intent, false)
            ACTION_REJECT -> rejectMediaProjection()
            ACTION_START -> startMediaProjection(intent)
            ACTION_STOP -> stopMediaProjection()
            ACTION_SELF_STOP -> stopSelf()
        }
    }

    fun permissionInitMediaProjection(intent: Intent, isAvailableMediaRecorder: Boolean) {
        //unregisterReceiver()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(EXTRA_REQUEST_DATA)
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                sendEvent(EB_MEDIA_PROJECTION_ON_STOP)
            }
        }, null)

        // for media recorder
        if (isAvailableMediaRecorder) {
            mediaRecorder = MediaRecorder()
            //initRecorder()

            // ?????? ????????? ???????????? android.os.networkonmainthreadexception ?????? ??????
            /*Thread(Runnable {
                try {
                    socket = Socket(InetAddress.getByName("192.168.1.1"), 3333)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }).start()*/
        }

        sendEvent(EB_MEDIA_PROJECTION_ON_INIT)
    }

    private fun getPermission(intent: Intent? = null) {
        Log.d(TAG, "getPermission ----------------")

        sendEvent(EB_MEDIA_PROJECTION_PERMISSION_GET)
    }

    private fun rejectMediaProjection() {
        //unregisterReceiver()
        //sendEvent(MediaProjectionStatus.OnReject)
        sendEvent(EB_MEDIA_PROJECTION_PERMISSION_REJECT)
    }

    private fun startMediaProjection(intent: Intent) {
        startMediaProjection(
            surface = intent.getParcelableExtra(EXTRA_SURFACE) as Surface,
            projectionName = intent.getStringExtra(EXTRA_PROJECTION_NAME) ?: EXTRA_PROJECTION_NAME,
            width = intent.getIntExtra(EXTRA_SIZE_WIDTH, DEFAULT_VALUE_SIZE_WIDTH),
            height = intent.getIntExtra(EXTRA_SIZE_HEIGHT, DEFAULT_VALUE_SIZE_HEIGHT)
        )
    }

    fun startMediaProjection(
        surface: Surface,
        projectionName: String = EXTRA_PROJECTION_NAME,
        width: Int = DEFAULT_VALUE_SIZE_WIDTH,
        height: Int = DEFAULT_VALUE_SIZE_HEIGHT
    ) {
        if (::mediaProjection.isInitialized) {
            val orientation = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            Log.d(TAG, "orientation - $orientation --------------------")

            var surfaceTmp = surface
            var widthTmp = DEFAULT_VALUE_SIZE_WIDTH
            var heightTmp = DEFAULT_VALUE_SIZE_HEIGHT

            if (orientation == Surface.ROTATION_90 ||
                orientation == Surface.ROTATION_270) {
                widthTmp = DEFAULT_VALUE_SIZE_HEIGHT
                heightTmp = DEFAULT_VALUE_SIZE_WIDTH
            }

            initRecorder(widthTmp, heightTmp, surface)

            mediaRecorder?.let {
                surfaceTmp = it.surface
                //previewSurface = surface
            }

            virtualDisplay = mediaProjection.createVirtualDisplay(
                projectionName,
                widthTmp,//width,
                heightTmp,//height,
                application.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surfaceTmp,
                null,
                null
            )
            sendEvent(EB_MEDIA_PROJECTION_ON_STARTED)

            mediaRecorder?.start() // Recording is now started
        } else {
            sendEvent(EB_MEDIA_PROJECTION_ON_FAIL)
        }
    }

    fun stopMediaProjection() {
        try {
            //stop??? ???????????? ???????????? ????????? ???????????? ?????? ???????????? ??????. stop??? ????????? ?????? ????????? ?????? ??????.
            /*if (::mediaProjection.isInitialized) {
                mediaProjection.stop()
            }*/
            if (::virtualDisplay.isInitialized) {
                virtualDisplay.release()
            }
            mediaRecorder?.let {
                it.stop()
                it.reset()//reset??? ???????????? ???????????? initRecorder?????? ?????? ???????????? ??????.
            }
            sendEvent(EB_MEDIA_PROJECTION_ON_STOP)
        } catch (e: Exception) {
        }
    }

    private fun tearDownMediaProjection() {
        Log.d(TAG, "tearDownMediaProjection ----------------")
        try {
            if (::mediaProjection.isInitialized) {
                mediaProjection.stop()
            }
            if (::virtualDisplay.isInitialized) {
                virtualDisplay.release()
            }
        } catch (e: Exception) {
        }

        mediaRecorder?.let {
            it.stop()
            it.reset() // You can reuse the object by going back to setAudioSource() step
            it.release() // Now the object cannot be reused
            mediaRecorder = null
        }

        socket?.let {
            it.close()
            socket = null
        }
    }

    // only media projection request permission
    fun createMediaProjection() {
        startActivity(MediaProjectionActivity.newInstance(this, true))
    }

    //----------------------------------------------------------------------------------------
    private fun initRecorder(sizeW: Int, sizeH: Int, previewSurface: Surface?) {
        try {
            mediaRecorder?.run {
                setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)

                // socket????????? ?????? ????????? ???????????? ??????
                //socket = Socket(InetAddress.getByName("192.168.1.1"), 3333)
                socket?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pfd = ParcelFileDescriptor.fromSocket(socket)
                    }
                    else {
                        pfd = ParcelFileDescriptor.fromSocket(socket).dup()
                    }

                    this.setOutputFile(pfd.fileDescriptor)
                }?: run {
                    // ??????????????? ????????? ?????? ??????????????? ????????? ??????
                    val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    Log.d(TAG, path.path)
                    setOutputFile(path.path + "/mediaProjectionVideo.mp4")
                }

                setVideoSize(sizeW, sizeH)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                //setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                //setVideoEncodingBitRate(512 * 1000)//low quality
                setVideoEncodingBitRate(512 * 5000)//medium quality (1m per 20mb)
                //setVideoEncodingBitRate(512 * 10000)//high quality (1m per 40mb)
                setVideoFrameRate(30)
                previewSurface?.let {
                    Log.d(TAG, "setup previewSurface")
                    setPreviewDisplay(it)
                }

                //val rotation = (context as Activity).windowManager.defaultDisplay.rotation
                //val rotation = (applicationContext as Activity).windowManager.defaultDisplay.rotation
                //val orientaton = ORIENTATIONS.get(rotation + 90)
                //setOrientationHint(90)

                prepare()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    //----------------------------------------------------------------------------------------
    private fun sendEvent(status: String) {
        EventBus.getDefault().post(MessageRoute(status, ""))
        onChangeStatus(status)
    }

    // abstract method
    open fun onChangeStatus(statusData: String) {
        // Do nothing
    }
}