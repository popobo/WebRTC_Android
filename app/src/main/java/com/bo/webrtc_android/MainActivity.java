package com.bo.webrtc_android;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

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
import java.util.List;

public class MainActivity extends AppCompatActivity {
    PeerConnectionFactory peerConnectionFactory;
    PeerConnection peerConnectionLocal;
    PeerConnection peerConnectionRemote;
    SurfaceViewRenderer localView;
    SurfaceViewRenderer remoteView;
    MediaStream mediaStreamLocal;
    MediaStream mediaStreamRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        verifyStoragePermissions(this);
        // create PeerConnectionFactory

        //创建EglBase对象, WebRTC 把 EGL 的操作封装在了 EglBase 中，并针对 EGL10 和 EGL14 提供了不同的实现
        EglBase.Context eglBaseContext = EglBase.create().getEglBaseContext();

        // create PeerConnectionFactory
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

        SurfaceTextureHelper localSurfaceTextureHelper = SurfaceTextureHelper.create("localCaptureThread", eglBaseContext);
        // create VideoCapturer
        // 获取前置摄像头
        VideoCapturer localVideoCapturer = createCameraCapturer(true);
        VideoSource localVideoSource = peerConnectionFactory.createVideoSource(localVideoCapturer.isScreencast());
        localVideoCapturer.initialize(localSurfaceTextureHelper, getApplicationContext(), localVideoSource.getCapturerObserver());
        localVideoCapturer.startCapture(480, 640, 30);

        localView = findViewById(R.id.localView);
        localView.setMirror(true);
        localView.init(eglBaseContext, null);

        // create VideoTrack
        VideoTrack localVideoTrack = peerConnectionFactory.createVideoTrack("100", localVideoSource);
//        // display in localView
//        localVideoTrack.addSink(localView);

        SurfaceTextureHelper remoteSurfaceTextureHelper = SurfaceTextureHelper.create("remoteCaptureThread", eglBaseContext);
        // create VideoCapturer
        // 获取后置摄像头
        VideoCapturer remoteVideoCapturer = createCameraCapturer(false);
        VideoSource remoteVideoSource = peerConnectionFactory.createVideoSource(remoteVideoCapturer.isScreencast());
        remoteVideoCapturer.initialize(remoteSurfaceTextureHelper, getApplicationContext(), remoteVideoSource.getCapturerObserver());
        remoteVideoCapturer.startCapture(480, 640, 30);

        remoteView = findViewById(R.id.remoteView);
        remoteView.setMirror(true);
        remoteView.init(eglBaseContext, null);

        // create VideoTrack
        VideoTrack remoteVideoTrack = peerConnectionFactory.createVideoTrack("100", remoteVideoSource);
//        // display in remoteView
//        remoteVideoTrack.addSink(remoteView);

        mediaStreamLocal = peerConnectionFactory.createLocalMediaStream("mediaStreamLocal");
        mediaStreamLocal.addTrack(localVideoTrack);

        mediaStreamRemote = peerConnectionFactory.createLocalMediaStream("mediaStreamRemote");
        mediaStreamRemote.addTrack(remoteVideoTrack);

        call(mediaStreamLocal, mediaStreamRemote);
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

    private void call(MediaStream mediaStreamLocal, MediaStream mediaStreamRemote){
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        peerConnectionLocal = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("localConnection"){
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                peerConnectionRemote.addIceCandidate(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                runOnUiThread(()->{
                    remoteVideoTrack.addSink(localView);
                });
            }
        });

        peerConnectionRemote = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("remoteConnection"){
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                peerConnectionLocal.addIceCandidate(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                VideoTrack localVideoTrack = mediaStream.videoTracks.get(0);
                runOnUiThread(()->{
                    localVideoTrack.addSink(remoteView);
                });
            }
        });

        peerConnectionLocal.addStream(mediaStreamLocal);
        peerConnectionLocal.createOffer(new SdpAdapter("local offer sdp"){
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnectionLocal.setLocalDescription(new SdpAdapter("local set local"), sessionDescription);
                peerConnectionRemote.addStream(mediaStreamRemote);
                peerConnectionRemote.setRemoteDescription(new SdpAdapter("remote set remote"), sessionDescription);
                peerConnectionRemote.createAnswer(new SdpAdapter("remote answer sdp"){
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {
                        super.onCreateSuccess(sessionDescription);
                        peerConnectionRemote.setLocalDescription(new SdpAdapter("remote set local"), sessionDescription);
                        peerConnectionLocal.setRemoteDescription(new SdpAdapter("local set remote"), sessionDescription);
                    }
                }, new MediaConstraints());
            }
        }, new MediaConstraints());
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
}
