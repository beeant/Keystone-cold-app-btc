/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.keystone.cold.scan;

import android.os.Handler;
import android.os.Message;

import com.google.zxing.Result;
import com.keystone.cold.AppExecutors;
import com.keystone.cold.scan.camera.CameraManager;
import com.keystone.cold.scan.common.Constant;
import com.keystone.cold.scan.decode.DecodeThread;
import com.sparrowwallet.hummingbird.ResultType;
import com.sparrowwallet.hummingbird.UR;
import com.sparrowwallet.hummingbird.URDecoder;

import org.spongycastle.util.encoders.Hex;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CaptureHandler extends Handler {

    private final Host host;
    private final DecodeThread decodeThread;
    private State state;
    private final CameraManager cameraManager;
    private URDecoder decoder = new URDecoder();
    private final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    private final QREncoding encoding;

    private enum State {
        PREVIEW, SUCCESS, DONE
    }


    public CaptureHandler(Host host, CameraManager cameraManager, QREncoding qrEncoding) {
        this.host = host;
        this.encoding = qrEncoding;
        decodeThread = new DecodeThread(host);
        decodeThread.start();
        state = State.SUCCESS;

        // Start ourselves capturing previews and decoding.
        this.cameraManager = cameraManager;
        cameraManager.startPreview();
        restartPreviewAndDecode();
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case Constant.RESTART_PREVIEW:
                restartPreviewAndDecode();
                break;
            case Constant.DECODE_SUCCEEDED:
                String text = ((Result) message.obj).getText();
                tryDecodeAsUR(text);
                break;
            case Constant.DECODE_FAILED:
                state = State.PREVIEW;
                cameraManager.requestPreviewFrame(decodeThread.getHandler(), Constant.DECODE);
                break;
            case Constant.RETURN_SCAN_RESULT:
                break;
        }
    }


    private void tryDecodeAsUR(String text) {
        sExecutor.submit(() -> {
            if (decoder.receivePart(text)) {
                //done scan
                if (decoder.getResult() != null) {
                    if (decoder.getResult().type == ResultType.SUCCESS) {
                        try {
                            byte[] data = decoder.getResult().ur.toBytes();
                            decodeComplete(Hex.toHexString(data), QREncoding.UR);
                        } catch (UR.InvalidCBORException e) {
                            e.printStackTrace();
                            decodeComplete(text, QREncoding.PLAINTEXT);
                        } catch (IllegalArgumentException e) {
                            byte[] cborPayload = decoder.getResult().ur.getCborBytes();
                            decodeComplete(Hex.toHexString(cborPayload), QREncoding.UR);
                        }
                    } else {
                        decodeComplete(text, QREncoding.PLAINTEXT);
                    }
                } else {
                    //continue scan
                    state = State.PREVIEW;
                    host.handleProgressPercent(decoder.getEstimatedPercentComplete());
                    cameraManager.requestPreviewFrame(decodeThread.getHandler(), Constant.DECODE);
                }
                return;
            } else {
                // in UR scan progress and receive a invalid part
                if (decoder.getProcessedPartsCount() != 0) {
                    decodeComplete(text, QREncoding.PLAINTEXT);
                    return;
                }
            }
            decodeComplete(text, QREncoding.PLAINTEXT);
        });
    }

    private void decodeComplete(String text, QREncoding codec) {
        AppExecutors.getInstance().mainThread().execute(() -> {
            state = State.SUCCESS;
            host.handleDecode(text, codec);
        });
    }

    public void quitSynchronously() {
        state = State.DONE;
        cameraManager.stopPreview();
        Message quit = Message.obtain(decodeThread.getHandler(), Constant.QUIT);
        quit.sendToTarget();
        try {
            // Wait at most half a second; should be enough time, and onPause()
            // will timeout quickly
            decodeThread.join(500L);
        } catch (InterruptedException e) {
            // continue
        }

        // Be absolutely sure we don't send any queued up messages
        removeMessages(Constant.DECODE_SUCCEEDED);
        removeMessages(Constant.DECODE_FAILED);
    }

    public void restartPreviewAndDecode() {
        if (state == State.SUCCESS) {
            state = State.PREVIEW;
            decoder = new URDecoder();
            cameraManager.requestPreviewFrame(decodeThread.getHandler(), Constant.DECODE);
        }
    }

}
