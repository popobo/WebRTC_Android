package com.bo.webrtc_android;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.app.Activity;
import android.content.PeriodicSync;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Capturer;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SignalingClient.Callback {
    EglBase.Context eglBaseContext;
    PeerConnectionFactory peerConnectionFactory;
    SurfaceViewRenderer localView;
    MediaStream mediaStream;
    //存储ICE服务器
    List<PeerConnection.IceServer> iceServers;

    //peerConnectionHashMap存储其他客户端的socketId和对应的PeerConnection
    HashMap<String, PeerConnection> peerConnectionHashMap;
    //视频数据在 native 层处理完毕后会抛出到 VideoRenderer.Callbacks#renderFrame 回调中，在这里也就是 SurfaceViewRenderer#renderFrame，而 SurfaceViewRenderer 又会把数据交给 EglRenderer 进行渲染
    //remoteViews 三个远端客户端画面的渲染
    SurfaceViewRenderer[] remoteViews;
    int remoteViewsIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        verifyStoragePermissions(this);
        // create PeerConnectionFactory

        // 此处初始化类成员
        peerConnectionHashMap = new HashMap<>();
        iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("turn:bocode.xyz:3478").setUsername("bo_turn").setPassword("123654").createIceServer());

        //创建EglBase对象, WebRTC 把 EGL 的操作封装在了 EglBase 中，并针对 EGL10 和 EGL14 提供了不同的实现
        eglBaseContext = EglBase.create().getEglBaseContext();

        // create PeerConnectionFactory
        // PeerConnectionFactory负责创建PeerConnection、VideoTrack、AudioTrack等重要对象
        PeerConnectionFactory.initialize(PeerConnectionFactory.
                InitializationOptions.
                builder(this).
                createInitializationOptions());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(eglBaseContext,
                true,
                true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(eglBaseContext);
        peerConnectionFactory = PeerConnectionFactory.
                builder().
                setOptions(options).
                setVideoEncoderFactory(defaultVideoEncoderFactory).
                setVideoDecoderFactory(defaultVideoDecoderFactory).
                createPeerConnectionFactory();

        // SurfaceTextureHelper 负责创建 SurfaceTexture，接收 SurfaceTexture 数据，相机线程的管理
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        // create VideoCapturer
        // 获取前置摄像头
        VideoCapturer videoCapturer = createCameraCapturer(true);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        videoCapturer.startCapture(480, 640, 30);

        localView = findViewById(R.id.localView);
        localView.setMirror(true);
        localView.init(eglBaseContext, null);

        // create VideoTrack
        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
//        // display in localView
        videoTrack.addSink(localView);

        // SurfaceViewRenderer 数组
        remoteViews = new SurfaceViewRenderer[]{
                findViewById(R.id.remoteView),
                findViewById(R.id.remoteView2),
                findViewById(R.id.remoteView3)
        };

        for (SurfaceViewRenderer remoteView : remoteViews){
            remoteView.setMirror(false);
            remoteView.init(eglBaseContext, null);
        }

//        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
//        AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);

        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);
//        mediaStream.addTrack(audioTrack);

        SignalingClient.get().init(this);
    }

    // 获取或创建其他客户端的peerConnection
    private synchronized PeerConnection getOrCreatePeerConnection(String socketId){
        PeerConnection peerConnection = peerConnectionHashMap.get(socketId);
        if (peerConnection != null){
            return peerConnection;
        }
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("PC" + socketId){
            @Override
            // RTCPeerConnection 的属性 onIceCandidate （是一个事件触发器 EventHandler） 能够让函数在事件icecandidate发生在实例  RTCPeerConnection 上时被调用。 只要本地代理ICE 需要通过信令服务器传递信息给其他对等端时就会触发。
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                SignalingClient.get().sendIceCandidate(iceCandidate, socketId);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                runOnUiThread(()->{
                    remoteVideoTrack.addSink(remoteViews[remoteViewsIndex++]);
                });
            }
        });
        peerConnection.addStream(mediaStream);
        peerConnectionHashMap.put(socketId, peerConnection);
        return peerConnection;
    }

    // isFront==true 获取前置摄像头, 反之获取后置摄像头
    private VideoCapturer createCameraCapturer(boolean isFront){
        Camera1Enumerator enumerator = new Camera1Enumerator(false);
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing cammera
        for (String deviceName : deviceNames){
            if (isFront ? enumerator.isFrontFacing(deviceName) : enumerator.isBackFacing(deviceName)){
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null){
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private static final int REQUEST_ALL = 1;

    private static String[] PERMISSIONS_ALL = {
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"
    };

    //然后通过一个函数来申请
    public static void verifyStoragePermissions(Activity activity) {
        try {
            int permission = 0;
            //检测所有需要的权限
            for(String temp : PERMISSIONS_ALL){
                permission = ActivityCompat.checkSelfPermission(activity, temp);
                if (permission != PackageManager.PERMISSION_GRANTED){
                    break;
                }
            }

            if (permission != PackageManager.PERMISSION_GRANTED) {
                // 没有写的权限，去申请写的权限，会弹出对话框
                ActivityCompat.requestPermissions(activity, PERMISSIONS_ALL,REQUEST_ALL);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreateRoom() {

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SignalingClient.get().destroy();
    }

    @Override
    public void onPeerJoined(String socketId) {
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.createOffer(new SdpAdapter("createOfferSdp" + socketId){
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                //设置本地的
                peerConnection.setLocalDescription(new SdpAdapter("setLocalSdp" + socketId), sessionDescription);
                // 向加入房间的用户发送SDP
                SignalingClient.get().sendSessionDescription(sessionDescription, socketId);
            }
        }, new MediaConstraints());
    }

    @Override
    public void onSelfJoined() {

    }

    @Override
    public void onPeerLeave(String msg) {

    }

    @Override
    public void onOfferReceived(JSONObject data) {
        runOnUiThread(() -> {
            String socketId = data.optString("from");
            PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
            peerConnection.setRemoteDescription(new SdpAdapter("setRemoteSdp" + socketId),
                    new SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp")));
            peerConnection.createAnswer(new SdpAdapter("localAnswerSdp") {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    super.onCreateSuccess(sdp);
                    peerConnection.setLocalDescription(new SdpAdapter("setLocalSdp"), sdp);
                    SignalingClient.get().sendSessionDescription(sdp, socketId);
                }
            }, new MediaConstraints());
        });
    }

    @Override
    public void onAnswerReceived(JSONObject data) {
        String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.setRemoteDescription(new SdpAdapter("setRemoteSdp" + socketId),
                new SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp")));
    }

    @Override
    public void onIceCandidateReceived(JSONObject data) {
        String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.addIceCandidate(new IceCandidate(
                data.optString("id"),
                data.optInt("label"),
                data.optString("candidate")
        ));
    }
}
