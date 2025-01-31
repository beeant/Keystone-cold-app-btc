/*
 *
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
 *
 */

package com.keystone.cold.ui.fragment.multisigs.legacy;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.keystone.cold.R;
import com.keystone.cold.databinding.CommonModalBinding;
import com.keystone.cold.databinding.ExportMultisigWalletToWatchWalletBinding;
import com.keystone.cold.databinding.ExportSdcardModalBinding;
import com.keystone.cold.ui.modal.ModalDialog;
import com.keystone.cold.update.utils.Storage;

import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;

import java.util.Objects;

import static com.keystone.cold.viewmodel.GlobalViewModel.showExportResult;
import static com.keystone.cold.viewmodel.GlobalViewModel.showNoSdcardModal;
import static com.keystone.cold.viewmodel.GlobalViewModel.writeToSdcard;

public class ExportMultiSigWalletToWatchWallet extends MultiSigBaseFragment<ExportMultisigWalletToWatchWalletBinding> {

    private JSONObject caravanWalletJson;
    private String walletName;
    @Override
    protected int setView() {
        return R.layout.export_multisig_wallet_to_watch_wallet;
    }

    @Override
    protected void init(View view) {
        super.init(view);
        Bundle data = getArguments();
        Objects.requireNonNull(data);
        mBinding.toolbar.setNavigationOnClickListener(v -> navigateUp());
        legacyMultiSigViewModel.exportWalletToCaravan(data.getString("wallet_fingerprint")).observe(this, jsonObject -> {
            caravanWalletJson = jsonObject;
            walletName = caravanWalletJson.optString("name");
            mBinding.qrcodeLayout.hint.setVisibility(View.GONE);
            mBinding.qrcodeLayout.qrcode.setData(Hex.toHexString(jsonObject.toString().getBytes()));
        });

        mBinding.exportToSdcard.setOnClickListener(v -> exportToSdcard());
        mBinding.info.setOnClickListener(v -> showCaravanImportGuide(mActivity));
        mBinding.done.setOnClickListener(v -> popBackStack(R.id.legacyMultisigFragment, false));
    }

    private void showCaravanImportGuide(AppCompatActivity activity) {
        ModalDialog modalDialog = ModalDialog.newInstance();
        CommonModalBinding binding = DataBindingUtil.inflate(
                LayoutInflater.from(activity), R.layout.common_modal,
                null, false);
        binding.title.setText(R.string.caravan_import_guide_title);
        binding.subTitle.setText(R.string.caravan_import_guide);
        binding.subTitle.setGravity(Gravity.START);
        binding.close.setVisibility(View.GONE);
        binding.confirm.setText(R.string.know);
        binding.confirm.setOnClickListener(vv -> modalDialog.dismiss());
        modalDialog.setBinding(binding);
        modalDialog.show(activity.getSupportFragmentManager(), "");
    }

    private void exportToSdcard() {
        Storage storage = Storage.createByEnvironment();
        if (storage == null || storage.getExternalDir() == null) {
            showNoSdcardModal(mActivity);
        } else {
            ModalDialog modalDialog = ModalDialog.newInstance();
            ExportSdcardModalBinding binding = DataBindingUtil.inflate(LayoutInflater.from(mActivity),
                    R.layout.export_sdcard_modal, null, false);
            binding.title.setText("导出钱包文件");
            binding.fileName.setText(walletName + ".json");
            binding.actionHint.setVisibility(View.GONE);
            binding.cancel.setOnClickListener(vv -> modalDialog.dismiss());
            binding.confirm.setOnClickListener(vv -> {
                modalDialog.dismiss();
                try {
                    boolean result = writeToSdcard(storage, caravanWalletJson.toString(2), walletName + ".json");
                    showExportResult(mActivity, null, result, walletName + ".json");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            });
            modalDialog.setBinding(binding);
            modalDialog.show(mActivity.getSupportFragmentManager(), "");
        }
    }
}
