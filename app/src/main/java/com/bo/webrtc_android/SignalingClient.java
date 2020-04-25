package com.bo.webrtc_android;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.socket.client.IO;
import io.socket.client.Socket;

// 单例模式
public class SignalingClient {
    private static SignalingClient instance;
    private SignalingClient(){}

    public static SignalingClient get(){
        if (null == instance){
            // 线程安全
            synchronized (SignalingClient.class){
                if (null == instance){
                    instance = new SignalingClient();
                }
            }
        }
        return instance;
    }

    // io.socket.client.Socket
    private Socket socket;
    private String room = "OldPlace";
    // 自定义接口
    private Callback callback;
    // new(){}匿名内部类
    private final TrustManager[] trustAll = new TrustManager[]{
        new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }
    };

    public void setCallback(Callback callback){
        this.callback = callback;
    }

    public void init(Callback callback){
        this.callback = callback;
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, null);
            IO.setDefaultHostnameVerifier((hostname, session) -> true);
            IO.setDefaultSSLContext(sslContext);

            socket = IO.socket("https://bocode.xyz");
            socket.connect();

            socket.emit("create or join", room);

            socket.on("created", args -> {
                Log.e("bo", "room created");
                callback.onCreateRoom();
            });

            socket.on("full", args -> {
                Log.e("bo", "room full");
            });

            // 另一位用户加入房间
            socket.on("join", args -> {
                Log.e("bo", "peer joined" + Arrays.toString(args));
                //onPeerJoined(String socketId) 新加入用户的socketId
                callback.onPeerJoined(String.valueOf(args[1]));
            });

            // 自己加入房间
            socket.on("joined", args -> {
                Log.e("bo", "self joined:" + socket.id());
                callback.onSelfJoined();
            });

            // 接收日志
            socket.on("log", args -> {
                Log.e("bo", "log call " + Arrays.toString(args));
            });

            // 对端离开
            socket.on("bye", args -> {
                Log.e("bo", "bye " + args[0]);
                callback.onPeerLeave((String)args[0]);
            });

            socket.on("message", args -> {
                Log.e("bo", "message " + Arrays.toString(args));
                Object arg = args[0];
                if (arg instanceof JSONObject){
                    JSONObject data = (JSONObject) arg;
                    String type = data.optString("type");
                    if("offer".equals(type)) {
                        callback.onOfferReceived(data);
                    } else if("answer".equals(type)) {
                        callback.onAnswerReceived(data);
                    } else if("candidate".equals(type)) {
                        callback.onIceCandidateReceived(data);
                    }
                }
            });

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }

    public void destroy(){
        socket.emit("bye", socket.id());
        socket.disconnect();
        socket.close();
        instance = null;
    }

    // 向指定目标to发送ice候选
    public void sendIceCandidate(IceCandidate iceCandidate, String to) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "candidate");
            jo.put("label", iceCandidate.sdpMLineIndex);
            jo.put("id", iceCandidate.sdpMid);
            jo.put("candidate", iceCandidate.sdp);
            jo.put("from", socket.id());
            jo.put("to", to);

            socket.emit("message", jo);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // 发生sdp
    public void sendSessionDescription(SessionDescription sdp, String to) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", sdp.type.canonicalForm());
            jo.put("sdp", sdp.description);
            jo.put("from", socket.id());
            jo.put("to", to);

            socket.emit("message", jo);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public interface Callback {
        void onCreateRoom();
        void onPeerJoined(String socketId);
        void onSelfJoined();
        void onPeerLeave(String msg);

        void onOfferReceived(JSONObject data);
        void onAnswerReceived(JSONObject data);
        void onIceCandidateReceived(JSONObject data);
    }
}
