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

package com.keystone.cold.ui;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.databinding.DataBindingUtil;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.keystone.cold.R;
import com.keystone.cold.Utilities;
import com.keystone.cold.databinding.ActivityMainBinding;
import com.keystone.cold.fingerprint.FingerprintKit;
import com.keystone.cold.ui.common.FullScreenActivity;
import com.keystone.cold.ui.fragment.AboutFragment;
import com.keystone.cold.ui.fragment.main.AssetFragment;
import com.keystone.cold.ui.fragment.multisigs.MultiSigPreferenceFragment;
import com.keystone.cold.ui.fragment.setting.SettingFragment;
import com.keystone.cold.ui.views.DrawerAdapter;
import com.keystone.cold.ui.views.FullScreenDrawer;
import com.keystone.cold.ui.views.UpdatingHelper;
import com.keystone.cold.viewmodel.GlobalViewModel;
import com.keystone.cold.update.data.UpdateManifest;
import com.keystone.cold.viewmodel.multisigs.MultiSigMode;


import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.keystone.cold.update.utils.Storage.hasSdcard;

public class MainActivity extends FullScreenActivity {

    private ActivityMainBinding mBinding;
    private NavController mNavController;

    private SharedPreferences prefs;

    private Toolbar toolbar;
    private final Handler mHandler = new Handler();

    private String belongTo;
    private String vaultId;
    private boolean hasMultiSigMode;
    private String multiSigMode;

    int currentFragmentIndex = R.id.drawer_wallet;
    private DrawerAdapter drawerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        if (savedInstanceState != null) {
            currentFragmentIndex = savedInstanceState.getInt("currentFragmentIndex");
        }
        initViews();
        initNavController();
        prefs = Utilities.getPrefs(this);
        belongTo = Utilities.getCurrentBelongTo(this);
        vaultId = Utilities.getVaultId(this);
        hasMultiSigMode = Utilities.hasMultiSigMode(this);
        multiSigMode = Utilities.getMultiSigMode(this);

        if (savedInstanceState == null) {
            if (hasSdcard()) {
                UpdatingHelper updatingHelper = new UpdatingHelper(this);
                MutableLiveData<UpdateManifest> manifestLiveData = updatingHelper.getUpdateManifest();
                manifestLiveData.observe(this, updateManifest -> {
                    if (updateManifest != null) {
                        updatingHelper.onUpdatingDetect(updateManifest);
                        manifestLiveData.removeObservers(this);
                    }
                });
            }
        }
        ViewModelProviders.of(this).get(GlobalViewModel.class);
    }

    private void initNavController() {
        mNavController = Navigation.findNavController(this, R.id.nav_host_fragment);
        mNavController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            String label = Objects.requireNonNull(destination.getLabel()).toString();

            int index = getFragmentIndexByLabel(label);
            if (index != -1) {
                mBinding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
                currentFragmentIndex = index;
                if (drawerAdapter != null) {
                    drawerAdapter.setSelectIndex(currentFragmentIndex);
                }
            } else {
                mBinding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            }

        });
    }

    private int getFragmentIndexByLabel(String label) {
        Set<Map.Entry<Integer, String>> set = mMainFragments.entrySet();
        for (Map.Entry<Integer, String> entry : set) {
            if (entry.getValue().equals(label)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("currentFragmentIndex", currentFragmentIndex);
    }

    @Override
    public boolean onSupportNavigateUp() {
        return mNavController.navigateUp();
    }

    private void initViews() {
        mBinding.drawer.setScrimColor(Color.TRANSPARENT);

        drawerAdapter = new DrawerAdapter(currentFragmentIndex);
        drawerAdapter.setOnItemClickListener(new DrawerClickListener());
        mBinding.menu.setLayoutManager(new LinearLayoutManager(this));
        mBinding.menu.setAdapter(drawerAdapter);
        mBinding.menu.setItemViewCacheSize(0);
        mBinding.drawer.addDrawerListener(new FullScreenDrawer.DrawerListenerAdapter() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                mBinding.drawer.getChildAt(0).setX(mBinding.menu.getWidth() + mBinding.menu.getX());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        String oldBelongTo = belongTo;
        belongTo = Utilities.getCurrentBelongTo(this);

        String oldVaultId = vaultId;
        vaultId = Utilities.getVaultId(this);

        if (!oldBelongTo.equals(belongTo) || !oldVaultId.equals(vaultId)) {
            recreate();
        }
    }

    public class DrawerClickListener implements DrawerAdapter.OnItemClickListener {

        @Override
        public void itemClick(int position) {
            if (currentFragmentIndex == position) {
                mBinding.drawer.closeDrawer(GravityCompat.START);
                return;
            }

            switch (position) {
                case R.id.drawer_wallet:
                    mNavController.navigateUp();
                    break;
                case R.id.drawer_multisig:
                    mNavController.navigateUp();
                    if (hasMultiSigMode) {
                        if (multiSigMode.equals(MultiSigMode.LEGACY.getModeId())) {
                            mNavController.navigate(R.id.action_to_legacyMultisigFragment);
                        } else {
                            mNavController.navigate(R.id.action_to_casaMultisigFragment);
                        }
                    } else {
                        mNavController.navigate(R.id.action_to_multisigSelectionFragment);
                    }
                    break;
                case R.id.drawer_settings:
                    mNavController.navigateUp();
                    mNavController.navigate(R.id.action_to_settingFragment);
                    break;
                case R.id.drawer_about:
                    mNavController.navigateUp();
                    mNavController.navigate(R.id.action_to_aboutFragment);
                    break;

            }
            mHandler.postDelayed(() -> mBinding.drawer.closeDrawer(GravityCompat.START), 100);
        }
    }

    public NavController getNavController() {
        return mNavController;
    }

    @Override
    public void onBackPressed() {
        if (mBinding.drawer.isDrawerOpen(GravityCompat.START)) {
            mBinding.drawer.closeDrawer(GravityCompat.START);
        } else {
            NavDestination destination = mNavController.getCurrentDestination();
            if (destination != null && destination.getLabel() != null) {
                if (AssetFragment.TAG.equals(destination.getLabel().toString())) {
                    return;
                }
            }
            super.onBackPressed();
        }
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        boolean supportFingerprint = FingerprintKit.isHardwareDetected(this);
        if (!Utilities.hasUserClickPatternLock(this)
                || (supportFingerprint && !Utilities.hasUserClickFingerprint(this))) {
            this.toolbar = toolbar;
            showBadge(toolbar);
        }
    }

    public void updateBadge() {
        boolean supportFingerprint = FingerprintKit.isHardwareDetected(this);
        if (Utilities.hasUserClickPatternLock(this)
                && (!supportFingerprint || Utilities.hasUserClickFingerprint(this))) {
            toolbar.setNavigationIcon(R.drawable.menu);
        }
        drawerAdapter.notifyDataSetChanged();
    }

    private void showBadge(@Nullable Toolbar toolbar) {
        Drawable menu = Objects.requireNonNull(toolbar).getNavigationIcon();
        int badgeSize = (int) getResources().getDimension(R.dimen.default_badge_size);
        int radius = badgeSize / 2;

        int width = Objects.requireNonNull(menu).getIntrinsicWidth() + badgeSize;
        int height = menu.getIntrinsicHeight() + badgeSize;

        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        final Canvas canvas = new Canvas(bitmap);
        menu.setBounds(radius, radius, width - radius, height - radius);
        menu.draw(canvas);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.RED);
        canvas.drawCircle(width - radius, radius, radius, paint);
        toolbar.setNavigationIcon(new BitmapDrawable(getResources(), bitmap));
    }

    public void toggleDrawer(View v) {
        if (mBinding.drawer.isDrawerOpen(GravityCompat.START)) {
            mBinding.drawer.closeDrawer(GravityCompat.START);
        } else {
            mBinding.drawer.openDrawer(GravityCompat.START);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        NavDestination nav = getNavController().getCurrentDestination();
        if (nav != null) {
            if (securePages.contains(nav.getId())) {
                getNavController().popBackStack(R.id.settingFragment, false);
            }
        }
    }

    private final List<Integer> securePages = Arrays.asList(
            R.id.fingerprintSettingFragment,
            R.id.fingerprintManageFragment,
            R.id.fingerprintEnrollFragment,
            R.id.fingerprintGuideFragment,
            R.id.setPasswordFragment,
            R.id.setPatternUnlockFragment);

    private final static Map<Integer, String> mMainFragments = new HashMap<>();

    static {
        mMainFragments.put(R.id.drawer_wallet, AssetFragment.TAG);
        mMainFragments.put(R.id.drawer_multisig, MultiSigPreferenceFragment.TAG);
        mMainFragments.put(R.id.drawer_settings, SettingFragment.TAG);
        mMainFragments.put(R.id.drawer_about, AboutFragment.TAG);
    }
}
