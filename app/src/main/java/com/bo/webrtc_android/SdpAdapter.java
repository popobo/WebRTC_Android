package com.bo.webrtc_android;

import android.util.Log;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

public class SdpAdapter implements SdpObserver {

    private String tag;

    public SdpAdapter(String tag){
        this.tag =  "bo" + tag;
    }

    public void log(String str){
        Log.d(tag, str);
    }

    @Override
    public void onCreateSuccess(SessionDescription sessionDescription) {
        log("onCreateSuccess " + sessionDescription);
    }

    @Override
    public void onSetSuccess() {
        log("onSetSuccess ");
    }

    @Override
    public void onCreateFailure(String s) {
        log("onCreateFailure " + s);
    }

    @Override
    public void onSetFailure(String s) {
        log("onSetFailure " + s);
    }
}
