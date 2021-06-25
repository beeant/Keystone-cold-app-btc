package com.keystone.cold.ui.fragment.multisigs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Pair;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.keystone.cold.R;
import com.keystone.cold.Utilities;
import com.keystone.cold.databinding.MultisigModePreferenceBinding;
import com.keystone.cold.databinding.SettingItemSelectableBinding;
import com.keystone.cold.ui.MainActivity;
import com.keystone.cold.ui.common.BaseBindingAdapter;
import com.keystone.cold.ui.fragment.BaseFragment;
import com.keystone.cold.ui.fragment.setting.ListPreferenceCallback;
import com.keystone.cold.viewmodel.multisigs.MultiSigMode;

import java.util.ArrayList;
import java.util.List;

import static com.keystone.cold.ui.fragment.setting.MainPreferenceFragment.SETTING_MULTI_SIG_MODE;

public class MultiSigPreferenceFragment extends BaseFragment<MultisigModePreferenceBinding> implements ListPreferenceCallback {
    private Adapter adapter;
    protected SharedPreferences prefs;
    protected CharSequence[] values;
    protected String value;
    protected CharSequence[] entries;
    protected CharSequence[] subTitles;
    private List<Pair<String, String>> displayItems;

    @Override
    protected int setView() {
        return R.layout.multisig_mode_preference;
    }

    @Override
    protected void init(View view) {
        prefs = Utilities.getPrefs(mActivity);
        mBinding.toolbar.setNavigationOnClickListener(((MainActivity) mActivity)::toggleDrawer);
        adapter = new Adapter(mActivity);
        mBinding.list.setAdapter(adapter);
        mBinding.confirm.setOnClickListener(v -> prefs.edit().putString(getKey(), value).apply());
        entries = getResources().getStringArray(getEntries());
        values = getResources().getStringArray(getValues());
        value = prefs.getString(getKey(), defaultValue());
        displayItems = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            displayItems.add(Pair.create(values[i].toString(), entries[i].toString()));
        }
        adapter.setItems(displayItems);
    }

    protected int getEntries() {
        return R.array.multisig_mode_list;
    }

    protected int getValues() {
        return R.array.multisig_mode_list_values;
    }

    protected String getKey() {
        return SETTING_MULTI_SIG_MODE;
    }

    protected String defaultValue() {
        return MultiSigMode.LEGACY.getModeId();
    }

    @Override
    protected void initData(Bundle savedInstanceState) {

    }

    @Override
    public void onSelect(int modeId) {
        String old = value;
        value = String.valueOf(modeId);
        if (!old.equals(value)) {
            adapter.notifyDataSetChanged();
        }

    }

    protected class Adapter extends BaseBindingAdapter<Pair<String,String>, SettingItemSelectableBinding> {

        public Adapter(Context context) {
            super(context);
        }

        @Override
        protected int getLayoutResId(int viewType) {
            return R.layout.setting_item_selectable;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            SettingItemSelectableBinding binding = DataBindingUtil.getBinding(holder.itemView);
            binding.title.setText(displayItems.get(position).second);
            binding.setIndex(Integer.parseInt(displayItems.get(position).first));
            binding.setCallback(MultiSigPreferenceFragment.this);
            binding.setChecked(displayItems.get(position).first.equals(value));
        }

        @Override
        protected void onBindItem(SettingItemSelectableBinding binding, Pair<String,String> item) {
        }
    }
}
