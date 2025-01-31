/*
 * Copyright (c) 2021 Keystone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * in the file COPYING.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.keystone.cold.ui.fragment.main.scan.legacy;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProviders;

import com.keystone.coinlib.accounts.Account;
import com.keystone.coinlib.accounts.MultiSig;
import com.keystone.coinlib.exception.CoinNotFindException;
import com.keystone.coinlib.exception.InvalidTransactionException;
import com.keystone.coinlib.utils.Base43;
import com.keystone.cold.R;
import com.keystone.cold.Utilities;
import com.keystone.cold.databinding.QrcodeScanFragmentBinding;
import com.keystone.cold.scan.CaptureHandler;
import com.keystone.cold.scan.Host;
import com.keystone.cold.scan.QREncoding;
import com.keystone.cold.scan.bean.ZxingConfig;
import com.keystone.cold.scan.bean.ZxingConfigBuilder;
import com.keystone.cold.scan.camera.CameraManager;
import com.keystone.cold.ui.fragment.BaseFragment;
import com.keystone.cold.ui.fragment.main.QrScanPurpose;
import com.keystone.cold.ui.fragment.multisigs.legacy.CollectExpubFragment;
import com.keystone.cold.ui.modal.ModalDialog;
import com.keystone.cold.viewmodel.exceptions.CollectExPubWrongDataException;
import com.keystone.cold.viewmodel.exceptions.InvalidMultisigWalletException;
import com.keystone.cold.viewmodel.multisigs.LegacyMultiSigViewModel;
import com.keystone.cold.viewmodel.QrScanViewModel;
import com.keystone.cold.viewmodel.SharedDataViewModel;
import com.keystone.cold.viewmodel.exceptions.UnknowQrCodeException;
import com.keystone.cold.viewmodel.WatchWallet;
import com.keystone.cold.viewmodel.exceptions.WatchWalletNotMatchException;
import com.keystone.cold.viewmodel.exceptions.XfpNotMatchException;
import com.sparrowwallet.hummingbird.registry.CryptoAccount;
import com.sparrowwallet.hummingbird.registry.CryptoOutput;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Base64;
import org.spongycastle.util.encoders.EncoderException;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.keystone.coinlib.Util.getExpubFingerprint;
import static com.keystone.cold.Utilities.IS_SETUP_VAULT;
import static com.keystone.cold.viewmodel.multisigs.LegacyMultiSigViewModel.decodeCaravanWalletFile;
import static com.keystone.cold.viewmodel.multisigs.LegacyMultiSigViewModel.decodeColdCardWalletFile;
import static com.keystone.cold.viewmodel.WatchWallet.getWatchWallet;

public class QRCodeScanFragment extends BaseFragment<QrcodeScanFragmentBinding>
        implements SurfaceHolder.Callback, Host {

    private CameraManager mCameraManager;
    private CaptureHandler mHandler;
    private boolean hasSurface;
    private ZxingConfig mConfig;
    private SurfaceHolder mSurfaceHolder;

    private QrScanPurpose qrScanPurpose;

    private QrScanViewModel viewModel;
    private ModalDialog dialog;
    private WatchWallet watchWallet;

    private ObjectAnimator scanLineAnimator;

    @Override
    protected int setView() {
        return R.layout.qrcode_scan_fragment;
    }

    @Override
    protected void init(View view) {
        watchWallet = getWatchWallet(mActivity);
        mBinding.scanHint.setText(getScanHint());
        boolean isSetupVault = getArguments() != null && getArguments().getBoolean(IS_SETUP_VAULT);
        String purpose = getArguments() != null ? getArguments().getString("purpose") : "";
        mBinding.toolbar.setNavigationOnClickListener(v -> navigateUp());
        mConfig = new ZxingConfigBuilder()
                .setIsFullScreenScan(true)
                .setFrameColor(R.color.colorAccent)
                .createZxingConfig();
        mCameraManager = new CameraManager(mActivity, mConfig);
        mBinding.frameView.setCameraManager(mCameraManager);
        mBinding.frameView.setZxingConfig(mConfig);
        QrScanViewModel.Factory factory = new QrScanViewModel.Factory(mActivity.getApplication(), isSetupVault);
        viewModel = ViewModelProviders.of(this, factory).get(QrScanViewModel.class);
        if (!TextUtils.isEmpty(purpose)) {
            mBinding.scanHint.setVisibility(View.GONE);
        }
        qrScanPurpose = QrScanPurpose.ofPurpose(purpose);

        scanLineAnimator = ObjectAnimator.ofFloat(mBinding.scanLine, "translationY", 0, 600);
        scanLineAnimator.setDuration(2000L);
        scanLineAnimator.setRepeatCount(ValueAnimator.INFINITE);
    }

    private String getScanHint() {
        switch (watchWallet) {
            case ELECTRUM:
                return getString(R.string.scan_electrum_hint);
            case BLUE:
                return getString(R.string.scan_blue_hint);
            case WASABI:
                return getString(R.string.scan_wasabi_hint);
        }
        return "";
    }

    @Override
    public void onResume() {
        super.onResume();
        mSurfaceHolder = mBinding.preview.getHolder();
        if (hasSurface) {
            initCamera(mSurfaceHolder);
        } else {
            mSurfaceHolder.addCallback(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mHandler != null) {
            mHandler.quitSynchronously();
            mHandler = null;
        }
        mCameraManager.closeDriver();

        if (!hasSurface) {
            mSurfaceHolder.removeCallback(this);
        }
        scanLineAnimator.cancel();
    }

    @Override
    protected void initData(Bundle savedInstanceState) {

    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        scanLineAnimator.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
        if (!hasSurface) {
            hasSurface = true;
            initCamera(surfaceHolder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        hasSurface = false;
    }

    private void initCamera(@NonNull SurfaceHolder surfaceHolder) {
        if (mCameraManager.isOpen()) {
            return;
        }
        try {
            mCameraManager.openDriver(surfaceHolder);
            if (mHandler == null) {
                mHandler = new CaptureHandler(this, mCameraManager, watchWallet.getQrEncoding());
            }
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unexpected error initializing camera", e);
        }
    }

    @Override
    public ZxingConfig getConfig() {
        return mConfig;
    }

    @Override
    public void handleDecode(String res, QREncoding encoding) {
        SharedDataViewModel sharedDataViewModel =
                ViewModelProviders.of(mActivity).get(SharedDataViewModel.class);
        if (encoding == QREncoding.PLAINTEXT) {
            sharedDataViewModel.updateScanResult(res);
            if (QrScanPurpose.WEB_AUTH == qrScanPurpose) {
                alert(getString(R.string.invalid_webauth_qrcode_hint));
            } else if (QrScanPurpose.ADDRESS == qrScanPurpose) {
                navigateUp();
            } else if (QrScanPurpose.COLLECT_XPUB == qrScanPurpose) {
                navigateUp();
            } else if (QrScanPurpose.IMPORT_MULTISIG_WALLET == qrScanPurpose) {
                alert(getString(R.string.invalid_multisig_wallet),
                        getString(R.string.invalid_multisig_wallet_hint));
            } else if (isElectrumPsbtTx(res)) {
                String psbtBase64 = Base64.toBase64String(Base43.decode(res));
                Bundle bundle = new Bundle();
                bundle.putString("psbt_base64", psbtBase64);
                bundle.putBoolean("multisig", qrScanPurpose == QrScanPurpose.MULTISIG_TX);
//                navigate(R.id.action_to_psbtTxConfirmFragment, bundle);
            } else {
                alert(getString(R.string.unsupported_qrcode));
            }
        } else if (encoding == QREncoding.UR) {
            try {
                if (qrScanPurpose == QrScanPurpose.COLLECT_XPUB) {
                    CryptoAccount cryptoAccount = viewModel.decodeCryptoAccount(res);
                    Account targetAccount = sharedDataViewModel.getTargetMultiSigAccount();
                    if (cryptoAccount != null) {
                        CryptoOutput cryptoOutput = viewModel.collectMultiSigCryptoOutputFromCryptoAccount(cryptoAccount, targetAccount);
                        if (cryptoOutput != null) {
                            String jsonStr = viewModel.handleCollectExPubWithCryptoOutput(cryptoOutput);
                            sharedDataViewModel.updateScanResult(jsonStr);
                            navigateUp();
                        } else {
                            throw new CollectExPubWrongDataException("Cannot find any xpub");
                        }
                    } else {
                        CryptoOutput cryptoOutput = viewModel.decodeCryptoOutput(res);
                        if (cryptoOutput != null) {
                            String jsonStr = viewModel.handleCollectExPubWithCryptoOutput(cryptoOutput);
                            sharedDataViewModel.updateScanResult(jsonStr);
                            navigateUp();
                        }
                        throw new CollectExPubWrongDataException("Cannot find any xpub");
                    }
                } else if (qrScanPurpose != QrScanPurpose.UNDEFINE && !qrScanPurpose.isAnimateQr()) {
                    alert(getString(R.string.unsupported_qrcode));
                } else {
                    viewModel.handleUrQrCode(this, res);
                }
            } catch (InvalidTransactionException e) {
                e.printStackTrace();
                alert(getString(R.string.incorrect_tx_data));
            } catch (JSONException e) {
                e.printStackTrace();
                alert(getString(R.string.incorrect_qrcode));
            } catch (CoinNotFindException e) {
                e.printStackTrace();
                alert(null, getString(R.string.only_support_btc), null);
            } catch (XfpNotMatchException e) {
                e.printStackTrace();
                alert(getString(R.string.uuid_not_match));
            } catch (UnknowQrCodeException e) {
                e.printStackTrace();
                alert(getString(R.string.unsupported_qrcode));
            } catch (WatchWalletNotMatchException e) {
                e.printStackTrace();
                alert(getString(R.string.identification_failed),
                        getString(R.string.master_pubkey_not_match)
                                + getString(R.string.watch_wallet_not_match,
                                WatchWallet.getWatchWallet(mActivity).getWalletName(mActivity)));
            } catch (InvalidMultisigWalletException e) {
                e.printStackTrace();
                alert(getString(R.string.invalid_multisig_wallet), getString(R.string.invalid_multisig_wallet_hint));
            } catch (CollectExPubWrongDataException e) {
                e.printStackTrace();
                CollectExpubFragment.showCommonModal(mActivity, getString(R.string.invalid_xpub_file),
                        getString(R.string.invalid_xpub_file_hint),
                        getString(R.string.know), () -> {
                            mBinding.scanProgress.setText("");
                            if (mHandler != null) {
                                mHandler.restartPreviewAndDecode();
                            }
                        });
            }
        }
    }

    public QrScanPurpose getPurpose() {
        return qrScanPurpose;
    }

    public void handleImportMultisigWallet(String hex) {
        try {
            LegacyMultiSigViewModel viewModel = ViewModelProviders.of(mActivity).get(LegacyMultiSigViewModel.class);
            String xfp = viewModel.getXfp();
            JSONObject obj;
            //try decode cc format
            obj = decodeColdCardWalletFile(new String(Hex.decode(hex), StandardCharsets.UTF_8));
            //try decode caravan format
            if (obj == null) {
                obj = decodeCaravanWalletFile(new String(Hex.decode(hex), StandardCharsets.UTF_8));
            }
            if (obj == null) {
                alert(getString(R.string.invalid_multisig_wallet), getString(R.string.invalid_multisig_wallet_hint));
                return;
            }

            boolean isWalletFileTest = obj.optBoolean("isTest", false);
            Account account = MultiSig.ofPath(obj.getString("Derivation"), !isWalletFileTest).get(0);
            boolean isTestnet = !Utilities.isMainNet(mActivity);
            if (isWalletFileTest != isTestnet) {
                String currentNet = isTestnet ? getString(R.string.testnet) : getString(R.string.mainnet);
                String walletFileNet = isWalletFileTest ? getString(R.string.testnet) : getString(R.string.mainnet);
                alert(getString(R.string.import_failed),
                        getString(R.string.import_failed_network_not_match, currentNet, walletFileNet, walletFileNet));
                return;
            }

            Bundle bundle = new Bundle();
            bundle.putString("wallet_info", obj.toString());
            JSONArray array = obj.getJSONArray("Xpubs");
            boolean matchXfp = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject xpubInfo = array.getJSONObject(i);
                String thisXfp = xpubInfo.getString("xfp");
                if (thisXfp.equalsIgnoreCase(xfp)
                        || thisXfp.equalsIgnoreCase(getExpubFingerprint(viewModel.getXPub(account)))) {
                    matchXfp = true;
                    break;
                }
            }
            if (!matchXfp) {
                throw new XfpNotMatchException("xfp not match");
            } else {
                navigate(R.id.import_multisig_wallet, bundle);
            }
        } catch (XfpNotMatchException e) {
            e.printStackTrace();
            alert(getString(R.string.import_multisig_wallet_fail), getString(R.string.import_multisig_wallet_fail_hint));
        } catch (JSONException e) {
            e.printStackTrace();
            alert(getString(R.string.incorrect_qrcode));
        }

    }

    private boolean isElectrumPsbtTx(String res) {
        try {
            byte[] data = Base43.decode(res);
            for (int i = 0; i < data.length; i++) {
                Log.d(TAG, "isElectrumPsbtTx: " + data[i]);
            }
            Log.d(TAG, "isElectrumPsbtTx: " + new String(data));
            return new String(data).startsWith("psbt");
        } catch (EncoderException | IllegalArgumentException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void handleProgress(int total, int scan) {
        mActivity.runOnUiThread(() -> mBinding.scanProgress.setText(getString(R.string.scan_progress, scan + "/" + total)));
    }

    @Override
    public void handleProgressPercent(double percent) {
        mActivity.runOnUiThread(() -> mBinding.scanProgress.setText(getString(R.string.scan_progress, (int) Math.floor((percent * 100)) + "%")));
    }

    @Override
    public CameraManager getCameraManager() {
        return mCameraManager;
    }

    @Override
    public Handler getHandler() {
        return mHandler;
    }

    public void alert(String title, String message, Runnable run) {
        super.alert(title, message, () -> {
            if (run != null) {
                run.run();
            } else {
                mBinding.scanProgress.setText("");
                if (mHandler != null) {
                    mHandler.restartPreviewAndDecode();
                }
            }
        });
    }
}


