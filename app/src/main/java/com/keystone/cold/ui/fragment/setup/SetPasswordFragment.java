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

package com.keystone.cold.ui.fragment.setup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;

import androidx.databinding.DataBindingUtil;
import androidx.databinding.Observable;

import com.keystone.cold.AppExecutors;
import com.keystone.cold.R;
import com.keystone.cold.Utilities;
import com.keystone.cold.callables.ChangePasswordCallable;
import com.keystone.cold.callables.ResetPasswordCallable;
import com.keystone.cold.databinding.ModalBinding;
import com.keystone.cold.databinding.SetPasswordBinding;
import com.keystone.cold.ui.AttackWarningActivity;
import com.keystone.cold.ui.UnlockActivity;
import com.keystone.cold.ui.modal.ModalDialog;
import com.keystone.cold.util.HashUtil;
import com.keystone.cold.util.Keyboard;
import com.keystone.cold.viewmodel.SetupVaultViewModel;

import org.spongycastle.util.encoders.Hex;

import java.util.Objects;

import static com.keystone.cold.Utilities.IS_SETUP_VAULT;
import static com.keystone.cold.viewmodel.SetupVaultViewModel.PasswordValidationResult.RESULT_OK;

public class SetPasswordFragment extends SetupVaultBaseFragment<SetPasswordBinding> {

    private boolean isSetupVault;

    private boolean deleteAll;

    private boolean inputValid;

    private String mnemonic; // mnemonic to reset password
    private String slip39MasterSeed; // masterSeed to reset password
    private int slip39Id;

    private String currentPassword; //old password hash

    public static final String PASSWORD = "password";
    public static final String SIGNATURE = "signature";
    public static final String MNEMONIC = "mnemonic";
    public static final String SLIP39_SEED = "slip39_seed";
    public static final String SLIP39_ID = "slip39_id";

    private boolean paused;

    @Override
    protected int setView() {
        return R.layout.set_password;
    }

    @Override
    protected void init(View view) {
        super.init(view);
        mBinding.setViewModel(viewModel);
        Bundle bundle = getArguments();
        isSetupVault = bundle != null && bundle.getBoolean(IS_SETUP_VAULT);
        currentPassword = bundle != null ? bundle.getString(PASSWORD): null;
        mnemonic = bundle != null ? bundle.getString(MNEMONIC): null;
        slip39MasterSeed = bundle != null ? bundle.getString(SLIP39_SEED): null;
        slip39Id = bundle != null ? bundle.getInt(SLIP39_ID): 0;
        mBinding.pwd1.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        mBinding.pwd2.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});

        mBinding.pwd1.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                if (!paused) {
                    SetupVaultViewModel.PasswordValidationResult result = viewModel.validatePassword();
                    if (result != RESULT_OK) {
                        mBinding.hint.setTextColor(mActivity.getColor(R.color.red));
                        mBinding.hint.setText(getHint(result));
                        deleteAll = true;
                        inputValid = false;
                    } else {
                        inputValid = true;
                    }
                }
            } else {
                mBinding.hint.setTextColor(mActivity.getColor(R.color.white));
                mBinding.hint.setText(R.string.text_password_required);
            }
        });

        mBinding.pwd1.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (deleteAll) {
                    mBinding.pwd1.setText("");
                    deleteAll = false;
                }
            }
            return false;
        });

        viewModel.getPwd2().addOnPropertyChangedCallback(new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable sender, int propertyId) {

                String password1 = viewModel.getPwd1().get();
                String password2 = viewModel.getPwd2().get();
                if (password2.length() >= password1.length()) {
                    if (password1.equals(password2)) {
                        mBinding.confirm.setEnabled(inputValid);
                        mBinding.hint2.setVisibility(View.GONE);
                    } else {
                        mBinding.hint2.setVisibility(View.VISIBLE);
                        mBinding.confirm.setEnabled(false);
                    }
                } else {
                    mBinding.hint2.setVisibility(View.GONE);
                    mBinding.confirm.setEnabled(false);
                }
            }
        });


        if (isSetupVault) {
            mBinding.toolbar.setVisibility(View.GONE);
            mBinding.divider.setVisibility(View.GONE);
        } else {
            mBinding.step.setVisibility(View.GONE);
            mBinding.toolbar.setNavigationOnClickListener(v -> {
                Keyboard.hide(mActivity, mBinding.pwd1);
                // change pwd by old pwd
                if (!TextUtils.isEmpty(currentPassword)) {
                    navigateUp();
                } else {
                    popBackStack(R.id.preImportFragment, false);
                }
            });
        }
        mBinding.confirm.setOnClickListener(v -> validatePassword());

        Keyboard.show(mActivity, mBinding.pwd1);

    }

    private int getHint(SetupVaultViewModel.PasswordValidationResult result) {
        int hintRes = 0;
        switch (result) {
            case RESULT_TOO_SHORT:
                hintRes = R.string.password_verify_too_short;
                break;
            case RESULT_NOT_MATCH:
                hintRes = R.string.password_verify_wrong;
                break;
            case RESULT_INPUT_WRONG:
                hintRes = R.string.password_input_wrong;
                break;
        }
        return hintRes;
    }

    private void validatePassword() {
        SetupVaultViewModel.PasswordValidationResult result = viewModel.validatePassword();
        if (result != RESULT_OK) {
            showInvalidPasswordHint(result);
        } else {
            setPassword();
        }
    }

    private void setPassword() {
        Handler handler = new Handler();
        if (!TextUtils.isEmpty(viewModel.getPwd1().get())) {
            mBinding.confirm.setVisibility(View.GONE);
            mBinding.progress.setVisibility(View.VISIBLE);
            AppExecutors.getInstance().networkIO().execute(() -> {
                String password = Objects.requireNonNull(viewModel.getPwd1().get());
                String passwordHash = Hex.toHexString(Objects.requireNonNull(HashUtil.twiceSha256(password)));
                Runnable action;
                if (mActivity instanceof UnlockActivity) {
                    new ResetPasswordCallable(passwordHash, mnemonic, slip39MasterSeed, slip39Id).call();
                    action = () -> {
                        Utilities.setPatternRetryTimes(mActivity,0);
                        mActivity.finish();
                    };
                } else if(!isSetupVault) {
                    if (!TextUtils.isEmpty(currentPassword)) {
                        new ChangePasswordCallable(passwordHash, currentPassword).call();
                        action = this::navigateUp;
                    } else {
                        new ResetPasswordCallable(passwordHash, mnemonic, slip39MasterSeed, slip39Id).call();
                        action = () -> popBackStack(R.id.preImportFragment, true);
                    }
                } else {
                    if (new ResetPasswordCallable(passwordHash, mnemonic, slip39MasterSeed, slip39Id).call()) {
                        viewModel.setPassword(passwordHash);
                        action = () -> {
                            Bundle data = new Bundle();
                            data.putBoolean(IS_SETUP_VAULT, true);
                            navigate(R.id.action_setPasswordFragment_to_setupVaultFragment, data);
                        };
                    } else {
                        handleSeStateAbnormal(mActivity);
                        return;
                    }
                }

                handler.post(() -> {
                    Keyboard.hide(mActivity, mBinding.pwd2);
                    action.run();
                });
            });
        }
    }

    public static void handleSeStateAbnormal(Activity activity) {
        Utilities.setAttackDetected(activity, true);
        Bundle data = new Bundle();
        data.putInt("firmware", 0x0600);
        data.putInt("system", 0);
        data.putInt("signature", 0);
        Intent intent = new Intent(activity, AttackWarningActivity.class);
        intent.putExtras(data);
        activity.startActivity(intent);
    }

    private void showInvalidPasswordHint(SetupVaultViewModel.PasswordValidationResult result) {
        ModalBinding binding = DataBindingUtil
                .inflate(LayoutInflater.from(mActivity), R.layout.modal, null, false);
        ModalDialog modal = ModalDialog.newInstance();
        modal.setBinding(binding);

        int hintRes = R.string.password_verify_too_short;
        int iconRes = R.drawable.circle_info;
        switch (result) {
            case RESULT_TOO_SHORT:
                hintRes = R.string.password_verify_too_short;
                iconRes = R.drawable.circle_info;
                break;
            case RESULT_NOT_MATCH:
            case RESULT_INPUT_WRONG:
                hintRes = R.string.password_verify_wrong;
                iconRes = R.drawable.circle_info;
                break;
        }
        binding.text.setText(hintRes);
        binding.icon.setImageResource(iconRes);
        modal.show(Objects.requireNonNull(mActivity.getSupportFragmentManager()), "");
        new Handler().postDelayed(modal::dismiss, 3000);
    }

    @Override
    public void onPause() {
        super.onPause();
        viewModel.getPwd1().set("");
        viewModel.getPwd2().set("");
        paused = true;
    }
}
