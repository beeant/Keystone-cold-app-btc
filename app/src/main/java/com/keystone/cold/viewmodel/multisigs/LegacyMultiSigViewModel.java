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

package com.keystone.cold.viewmodel.multisigs;

import android.app.Application;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.keystone.coinlib.ExtendPubkeyFormat;
import com.keystone.coinlib.accounts.Account;
import com.keystone.coinlib.accounts.ExtendedPublicKeyVersion;
import com.keystone.coinlib.accounts.MultiSig;
import com.keystone.coinlib.coins.BTC.Deriver;
import com.keystone.coinlib.utils.B58;
import com.keystone.cold.AppExecutors;
import com.keystone.cold.DataRepository;
import com.keystone.cold.MainApplication;
import com.keystone.cold.R;
import com.keystone.cold.Utilities;
import com.keystone.cold.db.entity.MultiSigAddressEntity;
import com.keystone.cold.db.entity.MultiSigWalletEntity;
import com.keystone.cold.db.entity.TxEntity;
import com.keystone.cold.update.utils.FileUtils;
import com.keystone.cold.update.utils.Storage;
import com.keystone.cold.util.HashUtil;
import com.keystone.cold.viewmodel.exceptions.XfpNotMatchException;
import com.sparrowwallet.hummingbird.registry.CryptoAccount;
import com.sparrowwallet.hummingbird.registry.CryptoCoinInfo;
import com.sparrowwallet.hummingbird.registry.CryptoHDKey;
import com.sparrowwallet.hummingbird.registry.CryptoKeypath;
import com.sparrowwallet.hummingbird.registry.CryptoOutput;
import com.sparrowwallet.hummingbird.registry.PathComponent;
import com.sparrowwallet.hummingbird.registry.ScriptExpression;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.keystone.coinlib.Util.getExpubFingerprint;
import static com.keystone.coinlib.accounts.Account.*;

public class LegacyMultiSigViewModel extends ViewModelBase {

    private final LiveData<List<MultiSigWalletEntity>> mObservableWallets;
    private final MutableLiveData<Boolean> addComplete = new MutableLiveData<>();
    private final DataRepository repo;

    public LegacyMultiSigViewModel(@NonNull Application application) {
        super(application);
        repo = ((MainApplication) application).getRepository();
        mObservableWallets = repo.loadAllMultiSigWallet();
    }

    public static JSONObject decodeColdCardWalletFile(String content) {
        /*
        # Coldcard Multisig setup file (created on 5271C071)
        #
        Name: CC-2-of-3
        Policy: 2 of 3
        Derivation: m/48'/0'/0'/2'
        Format: P2WSH

        748CC6AA: xpub6F6iZVTmc3KMgAUkV9JRNaouxYYwChRswPN1ut7nTfecn6VPRYLXFgXar1gvPUX27QH1zaVECqVEUoA2qMULZu5TjyKrjcWcLTQ6LkhrZAj
        C2202A77: xpub6EiTGcKqBQy2uTat1QQPhYQWt8LGmZStNqKDoikedkB72sUqgF9fXLUYEyPthqLSb6VP4akUAsy19MV5LL8SvqdzvcABYUpKw45jA1KZMhm
        5271C071: xpub6EWksRHwPbDmXWkjQeA6wbCmXZeDPXieMob9hhbtJjmrmk647bWkh7om5rk2eoeDKcKG6NmD8nT7UZAFxXQMjTnhENTwTEovQw3MDQ8jJ16
         */

        content = content.replaceAll("P2WSH-P2SH", MULTI_P2SH_P2WSH.getScript());
        JSONObject object = new JSONObject();
        JSONArray xpubs = new JSONArray();
        Pattern pattern = Pattern.compile("[0-9a-fA-F]{8}");
        boolean isTest = false;
        int total = 0;
        int threshold = 0;
        String path = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    if (line.contains("Coldcard")) {
                        object.put("Creator", "Coldcard");
                    } else if (line.contains("Keystone")) {
                        object.put("Creator", "Keystone");
                    }
                }
                String[] splits = line.split(": ");
                if (splits.length != 2) continue;
                String label = splits[0];
                String value = splits[1];
                if (label.equals("Name")) {
                    object.put(label, value);
                } else if (label.equals("Policy")) {
                    String[] policy = value.split(" of ");
                    if (policy.length == 2) {
                        threshold = Integer.parseInt(policy[0]);
                        total = Integer.parseInt(policy[1]);
                    }
                    object.put(label, value);
                } else if (label.equals("Derivation")) {
                    path = value;
                } else if (label.equals("Format")) {
                    object.put(label, value);
                } else if (pattern.matcher(label).matches()) {
                    JSONObject xpub = new JSONObject();
                    if (ExtendPubkeyFormat.isValidXpub(value)) {
                        isTest = value.startsWith("tpub") || value.startsWith("Upub") || value.startsWith("Vpub");
                        object.put("isTest", isTest);
                        xpub.put("xfp", label);
                        xpub.put("xpub", ExtendedPublicKeyVersion.convertXPubVersion(value, MultiSig.ofPath(path).get(0).getXPubVersion()));
                        xpubs.put(xpub);
                    }
                }
            }

            if (!isValidMultisigPolicy(total, threshold) || xpubs.length() != total) {
                Log.w("Multisig", "invalid wallet policy");
                return null;
            }

            String format = object.getString("Format");

            boolean validDerivation = false;
            for (Account account : Account.values()) {
                if (account.isMainNet() == !isTest && account.getScript().equals(format)) {
                    validDerivation = true;
                    object.put("Derivation", account.getPath());
                    break;
                }
            }

            if (!validDerivation) {
                Log.w("Multisig", "invalid wallet validDerivation");
                return null;
            }

            object.put("Xpubs", xpubs);

        } catch (IOException | JSONException | NumberFormatException e) {
            e.printStackTrace();
            Log.w("Multisig", "invalid wallet ", e);
            return null;
        }

        return object;
    }

    private static boolean isValidMultisigPolicy(int total, int threshold) {
        return total <= 15 && total >= 2 && threshold <= total || threshold >= 1;
    }

    public LiveData<List<MultiSigWalletEntity>> getAllMultiSigWallet() {
        return mObservableWallets;
    }

    public LiveData<List<MultiSigAddressEntity>> getMultiSigAddress(String walletFingerprint) {
        return repo.loadAddressForWallet(walletFingerprint);
    }

    public LiveData<List<TxEntity>> loadTxs(String walletFingerprint) {
        return repo.loadMultisigTxs(walletFingerprint);
    }

    public LiveData<MultiSigWalletEntity> getCurrentWallet() {
        String netmode = Utilities.isMainNet(getApplication()) ? "main" : "testnet";
        MutableLiveData<MultiSigWalletEntity> result = new MutableLiveData<>();
        AppExecutors.getInstance().diskIO().execute(() -> {
            String defaultMultisgWalletFp = Utilities.getDefaultMultisigWallet(getApplication(), getXfp());
            if (!TextUtils.isEmpty(defaultMultisgWalletFp)) {
                MultiSigWalletEntity wallet = repo.loadMultisigWallet(defaultMultisgWalletFp);
                if (wallet != null && wallet.getNetwork().equals(netmode)) {
                    result.postValue(wallet);
                } else {
                    List<MultiSigWalletEntity> list = repo.loadAllMultiSigWalletSync();
                    if (!list.isEmpty()) {
                        result.postValue(list.get(0));
                        Utilities.setDefaultMultisigWallet(getApplication(), getXfp(), list.get(0).getWalletFingerPrint());
                    } else {
                        result.postValue(null);
                    }
                }
            } else {
                List<MultiSigWalletEntity> list = repo.loadAllMultiSigWalletSync();
                if (!list.isEmpty()) {
                    result.postValue(list.get(0));
                    Utilities.setDefaultMultisigWallet(getApplication(), getXfp(), list.get(0).getWalletFingerPrint());
                } else {
                    result.postValue(null);
                }
            }
        });
        return result;
    }

    public LiveData<JSONObject> exportWalletToCaravan(String walletFingerprint) {
        MutableLiveData<JSONObject> result = new MutableLiveData<>();
        AppExecutors.getInstance().diskIO().execute(() -> {
            MultiSigWalletEntity wallet = repo.loadMultisigWallet(walletFingerprint);
            if (wallet != null) {
                try {
                    String creator = wallet.getCreator();
                    String path = wallet.getExPubPath();
                    int threshold = wallet.getThreshold();
                    int total = wallet.getTotal();
                    boolean isTest = "testnet".equals(wallet.getNetwork());
                    JSONObject object = new JSONObject();
                    object.put("name", wallet.getWalletName());
                    object.put("addressType", MultiSig.ofPath(path, !isTest).get(0).getScript());
                    object.put("network", isTest ? "testnet" : "mainnet");

                    JSONObject client = new JSONObject();
                    client.put("type", "public");
                    object.put("client", client);

                    JSONObject quorum = new JSONObject();
                    quorum.put("requiredSigners", threshold);
                    quorum.put("totalSigners", total);
                    object.put("quorum", quorum);

                    JSONArray extendedPublicKeys = new JSONArray();
                    JSONArray xpubArray = new JSONArray(wallet.getExPubs());
                    for (int i = 0; i < xpubArray.length(); i++) {
                        JSONObject xpubKey = new JSONObject();
                        xpubKey.put("name", "Extended Public Key " + (i + 1));
                        xpubKey.put("bip32Path", xpubArray.getJSONObject(i).optString("path", path));
                        String xpub = xpubArray.getJSONObject(i).getString("xpub");
                        if (isTest) {
                            xpub = ExtendPubkeyFormat.convertExtendPubkey(xpub, ExtendPubkeyFormat.tpub);
                        } else {
                            xpub = ExtendPubkeyFormat.convertExtendPubkey(xpub, ExtendPubkeyFormat.xpub);
                        }
                        xpubKey.put("xpub", xpub);
                        xpubKey.put("xfp", xpubArray.getJSONObject(i).optString("xfp"));
                        xpubKey.put("method", "Caravan".equals(creator) ? xpubArray.getJSONObject(i).optString("method") : "text");
                        extendedPublicKeys.put(xpubKey);
                    }
                    object.put("extendedPublicKeys", extendedPublicKeys);
                    object.put("startingAddressIndex", 0);
                    result.postValue(object);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        return result;
    }

    public LiveData<String> exportWalletToCosigner(String walletFingerprint) {
        MutableLiveData<String> result = new MutableLiveData<>();
        AppExecutors.getInstance().diskIO().execute(() -> {
            MultiSigWalletEntity wallet = repo.loadMultisigWallet(walletFingerprint);
            if (wallet != null) {
                try {
                    StringBuilder builder = new StringBuilder();
                    String path = wallet.getExPubPath();
                    int threshold = wallet.getThreshold();
                    int total = wallet.getTotal();
                    builder.append(String.format("# Keystone Multisig setup file (created on %s)", getXfp())).append("\n")
                            .append("#").append("\n")
                            .append("Name: ").append(wallet.getWalletName()).append("\n")
                            .append(String.format("Policy: %d of %d", threshold, total)).append("\n")
                            .append("Derivation: ").append(path).append("\n")
                            .append("Format: ").append(MultiSig.ofPath(path).get(0).getScript()).append("\n\n");
                    JSONArray xpubs = new JSONArray(wallet.getExPubs());
                    for (int i = 0; i < xpubs.length(); i++) {
                        JSONObject xpub = xpubs.getJSONObject(i);
                        builder.append(xpub.getString("xfp")).append(": ").append(xpub.getString("xpub")).append("\n");
                    }
                    result.postValue(builder.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        return result;
    }

    public LiveData<MultiSigWalletEntity> getWalletEntity(String walletFingerprint) {
        MutableLiveData<MultiSigWalletEntity> result = new MutableLiveData<>();
        AppExecutors.getInstance().diskIO().execute(() -> {
            MultiSigWalletEntity wallet = repo.loadMultisigWallet(walletFingerprint);
            if (wallet != null) {
                result.postValue(wallet);
            }
        });
        return result;
    }

    public String getExportXpubInfo(Account account) {
        JSONObject object = new JSONObject();
        try {
            object.put("xfp", getXfp());
            object.put("xpub", getXPub(account));
            object.put("path", account.getPath());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return object.toString();
    }

    public CryptoAccount getCryptoAccount(Account account) {
        return new CryptoAccount(Hex.decode(getXfp()), Arrays.asList(getCryptoOutput(account)));
    }

    public CryptoOutput getCryptoOutput(Account account) {
        String masterFingerprint = getXfp();
        String xpub = getXPub(account);
        byte[] xpubBytes = new B58().decodeAndCheck(xpub);
        byte[] parentFp = Arrays.copyOfRange(xpubBytes, 5, 9);
        byte[] key = Arrays.copyOfRange(xpubBytes, 45, 78);
        byte[] chainCode = Arrays.copyOfRange(xpubBytes, 13, 45);
        int depth = xpubBytes[4];
        List<ScriptExpression> scriptExpressions = new ArrayList<>();
        CryptoKeypath origin;
        int network = 0;
        switch (account) {
            case MULTI_P2SH_TEST:
                network = 1;
                scriptExpressions.add(ScriptExpression.SCRIPT_HASH);
                origin = new CryptoKeypath(Arrays.asList(new PathComponent(45, true)), Hex.decode(masterFingerprint), depth);
                break;
            case MULTI_P2SH_P2WSH_TEST:
                network = 1;
                scriptExpressions.add(ScriptExpression.SCRIPT_HASH);
                scriptExpressions.add(ScriptExpression.WITNESS_SCRIPT_HASH);
                origin = new CryptoKeypath(Arrays.asList(new PathComponent(48, true),
                        new PathComponent(1, true),
                        new PathComponent(0, true),
                        new PathComponent(1, true)
                ), Hex.decode(masterFingerprint), depth);
                break;
            case MULTI_P2WSH_TEST:
                network = 1;
                scriptExpressions.add(ScriptExpression.WITNESS_SCRIPT_HASH);
                origin = new CryptoKeypath(Arrays.asList(new PathComponent(48, true),
                        new PathComponent(1, true),
                        new PathComponent(0, true),
                        new PathComponent(2, true)
                ), Hex.decode(masterFingerprint), depth);
                break;
            case MULTI_P2SH:
                scriptExpressions.add(ScriptExpression.SCRIPT_HASH);
                origin = new CryptoKeypath(Arrays.asList(new PathComponent(45, true)), Hex.decode(masterFingerprint), depth);
                break;
            case MULTI_P2SH_P2WSH:
                scriptExpressions.add(ScriptExpression.SCRIPT_HASH);
                scriptExpressions.add(ScriptExpression.WITNESS_SCRIPT_HASH);
                origin = new CryptoKeypath(Arrays.asList(new PathComponent(48, true),
                        new PathComponent(0, true),
                        new PathComponent(0, true),
                        new PathComponent(1, true)
                ), Hex.decode(masterFingerprint), depth);
                break;
            case MULTI_P2WSH:
                scriptExpressions.add(ScriptExpression.WITNESS_SCRIPT_HASH);
                origin = new CryptoKeypath(Arrays.asList(new PathComponent(48, true),
                        new PathComponent(0, true),
                        new PathComponent(0, true),
                        new PathComponent(2, true)
                ), Hex.decode(masterFingerprint), depth);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + account);
        }
        CryptoCoinInfo coinInfo = new CryptoCoinInfo(1, network);


        CryptoHDKey hdKey = new CryptoHDKey(false, key, chainCode, coinInfo, origin, null, parentFp);
        return new CryptoOutput(scriptExpressions, hdKey);
    }

    public String getExportAllXpubInfo() {
        JSONObject object = new JSONObject();
        try {
            Account[] accounts = Utilities.isMainNet(getApplication()) ?
                    new Account[]{MULTI_P2WSH, MULTI_P2SH_P2WSH, MULTI_P2SH} :
                    new Account[]{MULTI_P2WSH_TEST, MULTI_P2SH_P2WSH_TEST, MULTI_P2SH_TEST};
            for (Account value : accounts) {
                String format = value.getScript().toLowerCase().replace("-", "_");
                object.put(format + "_deriv", value.getPath());
                object.put(format, getXPub(value));
            }
            object.put("xfp", getXfp().toUpperCase());
            return object.toString(2).replace("\\", "");
        } catch (JSONException e) {
            return "";
        }
    }

    public String getExportXpubFileName(Account account) {
        return getXfp() + "_" + account.getScript() + ".json";
    }

    public String getExportAllXpubFileName() {
        return "ccxp-" + getXfp() + ".json";
    }

    public String getAddressTypeString(Account account) {
        int id = R.string.multi_sig_account_segwit;

        if (account == MULTI_P2SH_P2WSH || account == MULTI_P2SH_P2WSH_TEST) {
            id = R.string.multi_sig_account_p2sh;
        } else if (account == MULTI_P2SH || account == MULTI_P2SH_TEST) {
            id = R.string.multi_sig_account_legacy;
        }

        return getApplication().getString(id);
    }

    public LiveData<MultiSigWalletEntity> createMultisigWallet(int threshold,
                                                               Account account,
                                                               String name,
                                                               JSONArray xpubsInfo, String creator) throws XfpNotMatchException {
        MutableLiveData<MultiSigWalletEntity> result = new MutableLiveData<>();
        int total = xpubsInfo.length();
        boolean xfpMatch = false;
        List<String> xpubs = new ArrayList<>();
        for (int i = 0; i < xpubsInfo.length(); i++) {
            JSONObject obj;
            try {
                obj = xpubsInfo.getJSONObject(i);
                String xfp = obj.getString("xfp");
                String xpub = ExtendedPublicKeyVersion.convertXPubVersion(obj.getString("xpub"), account.getXPubVersion());
                if ((xfp.equalsIgnoreCase(getXfp()) || xfp.equalsIgnoreCase(getExpubFingerprint(getXPub(account))))
                        && ExtendPubkeyFormat.isEqualIgnorePrefix(getXPub(account), xpub)) {
                    xfpMatch = true;
                }
                xpubs.add(xpub);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (!xfpMatch) {
            throw new XfpNotMatchException("xfp not match");
        }
        String verifyCode = calculateWalletVerifyCode(threshold, xpubs, account.getPath());
        String walletFingerprint = verifyCode + getXfp();
        String walletName = !TextUtils.isEmpty(name) ? name : "KT_" + verifyCode + "_" + threshold + "-" + total;
        MultiSigWalletEntity wallet = new MultiSigWalletEntity(
                walletName,
                threshold,
                total,
                account.getPath(),
                xpubsInfo.toString(),
                getXfp(),
                Utilities.isMainNet(getApplication()) ? "main" : "testnet", verifyCode, creator);
        wallet.setWalletFingerPrint(walletFingerprint);
        AppExecutors.getInstance().diskIO().execute(() -> {
            boolean exist = repo.loadMultisigWallet(walletFingerprint) != null;
            if (!exist) {
                repo.addMultisigWallet(wallet);
                new AddAddressTask(walletFingerprint, repo, null, 0).execute(1);
                new AddAddressTask(walletFingerprint, repo, () -> result.postValue(wallet), 1).execute(1);
            } else {
                repo.updateWallet(wallet);
                result.postValue(wallet);
            }
            Utilities.setDefaultMultisigWallet(getApplication(), getXfp(), walletFingerprint);
        });
        return result;
    }

    public void addAddress(String walletFingerprint, int number, int changeIndex) {
        addComplete.postValue(Boolean.FALSE);
        new AddAddressTask(walletFingerprint, repo, () -> addComplete.setValue(Boolean.TRUE), changeIndex).execute(number);
    }

    public String calculateWalletVerifyCode(int threshold, List<String> xpubs, String path) {
        String info = xpubs.stream()
                .map(s -> ExtendPubkeyFormat.convertExtendPubkey(s, ExtendPubkeyFormat.xpub))
                .sorted()
                .reduce((s1, s2) -> s1 + " " + s2)
                .orElse("") + threshold + "of" + xpubs.size() + path;
        return Hex.toHexString(Objects.requireNonNull(HashUtil.sha256(info))).substring(0, 8).toUpperCase();
    }

    public List<MultiSigAddressEntity> filterChangeAddress(List<MultiSigAddressEntity> entities) {
        return entities.stream()
                .filter(entity -> entity.getChangeIndex() == 1)
                .collect(Collectors.toList());
    }

    public List<MultiSigAddressEntity> filterReceiveAddress(List<MultiSigAddressEntity> entities) {
        return entities.stream()
                .filter(entity -> entity.getChangeIndex() == 0)
                .collect(Collectors.toList());
    }

    public LiveData<Boolean> getObservableAddState() {
        return addComplete;
    }

    public void deleteWallet(String walletFingerPrint) {
        repo.deleteMultisigWallet(walletFingerPrint);
        repo.deleteTxs(walletFingerPrint);
    }

    public LiveData<Map<String, JSONObject>> loadWalletFile() {
        MutableLiveData<Map<String, JSONObject>> result = new MutableLiveData<>();
        AppExecutors.getInstance().diskIO().execute(() -> {
            Map<String, JSONObject> fileList = new HashMap<>();
            Storage storage = Storage.createByEnvironment();
            if (storage != null && storage.getExternalDir() != null) {
                File[] files = storage.getExternalDir().listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(".txt")) {
                            JSONObject object = decodeColdCardWalletFile(FileUtils.readString(f));
                            if (object != null) {
                                fileList.put(f.getName(), object);
                            }
                        } else if (f.getName().endsWith(".json")) {
                            String fileContent = FileUtils.readString(f);
                            JSONObject object = decodeCaravanWalletFile(fileContent);
                            if (object != null) {
                                fileList.put(f.getName(), object);
                            }
                        }
                    }
                }
            }
            result.postValue(fileList);
        });
        return result;
    }

    public void updateWallet(MultiSigWalletEntity entity) {
        AppExecutors.getInstance().diskIO().execute(() -> repo.updateWallet(entity));
    }

    public static JSONObject decodeCaravanWalletFile(String content) {
        JSONObject result = new JSONObject();
        int total;
        int threshold;
        String format;
        boolean isTest;
        try {
            content = content.replaceAll("P2WSH-P2SH", MULTI_P2SH_P2WSH.getScript());
            JSONObject object = new JSONObject(content);

            //Creator
            result.put("Creator", "Caravan");

            //Name
            result.put("Name", object.getString("name"));

            //Policy
            JSONObject quorum = object.getJSONObject("quorum");
            threshold = quorum.getInt("requiredSigners");
            total = quorum.getInt("totalSigners");
            result.put("Policy", threshold + " of " + total);

            //Format
            format = object.getString("addressType");
            result.put("Format", format);

            //isTest
            isTest = "testnet".equals(object.getString("network"));
            result.put("isTest", isTest);

            //Derivation
            Account account = MultiSig.ofScript(format, isTest).get(0);
            if (account == null) {
                Log.w("Multisig", "invalid format");
                return null;
            }
            result.put("Derivation", account.getPath());

            //Xpubs
            JSONArray xpubs = object.getJSONArray("extendedPublicKeys");
            JSONArray xpubsArray = new JSONArray();
            for (int i = 0; i < xpubs.length(); i++) {
                JSONObject xpubJson = new JSONObject();
                String xpub = xpubs.getJSONObject(i).getString("xpub");
                String path = xpubs.getJSONObject(i).getString("bip32Path");
                String xfp = xpubs.getJSONObject(i).getString("xfp");
                String method = xpubs.getJSONObject(i).getString("method");
                if (ExtendPubkeyFormat.isValidXpub(xpub)) {
                    if (xpub.startsWith("tpub") || xpub.startsWith("Upub") || xpub.startsWith("Vpub")) {
                        object.put("isTest", true);
                    }
                    if (TextUtils.isEmpty(xfp)) {
                        xfp = getExpubFingerprint(xpub);
                    }
                    xpubJson.put("path", path);
                    xpubJson.put("xfp", xfp);
                    xpubJson.put("xpub", ExtendedPublicKeyVersion.convertXPubVersion(xpub, account.getXPubVersion()));
                    xpubJson.put("method", method);
                    xpubsArray.put(xpubJson);
                }
            }
            if (!isValidMultisigPolicy(total, threshold) || xpubs.length() != total) {
                Log.w("Multisig", "invalid wallet Policy");
                return null;
            }
            result.put("Xpubs", xpubsArray);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            Log.w("Multisig", "invalid wallet ", e);
        }
        return null;
    }

    public static class AddAddressTask extends AsyncTask<Integer, Void, Void> {
        private final String walletFingerprint;
        private final DataRepository repo;
        private final Runnable onComplete;
        private final int changeIndex;

        public AddAddressTask(String walletFingerprint,
                              DataRepository repo,
                              Runnable onComplete,
                              int changeIndex) {
            this.walletFingerprint = walletFingerprint;
            this.repo = repo;
            this.onComplete = onComplete;
            this.changeIndex = changeIndex;
        }

        @Override
        protected Void doInBackground(Integer... count) {
            boolean isMainNet = Utilities.isMainNet(MainApplication.getApplication());
            MultiSigWalletEntity wallet = repo.loadMultisigWallet(walletFingerprint);
            List<MultiSigAddressEntity> address = repo.loadAddressForWalletSync(walletFingerprint);
            Optional<MultiSigAddressEntity> optional = address.stream()
                    .filter(addressEntity -> addressEntity.getPath()
                            .startsWith(wallet.getExPubPath() + "/" + changeIndex))
                    .max((o1, o2) -> o1.getIndex() - o2.getIndex());
            int index = -1;
            if (optional.isPresent()) {
                index = optional.get().getIndex();
            }
            List<MultiSigAddressEntity> entities = new ArrayList<>();
            int addressCount = index + 1;
            List<String> xpubList = new ArrayList<>();
            try {
                JSONArray jsonArray = new JSONArray(wallet.getExPubs());
                for (int i = 0; i < jsonArray.length(); i++) {
                    xpubList.add(jsonArray.getJSONObject(i).getString("xpub"));
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            Deriver deriver = new Deriver(isMainNet);
            for (int i = 0; i < count[0]; i++) {
                MultiSigAddressEntity multisigAddress = new MultiSigAddressEntity();
                String addr = deriver.deriveMultiSigAddress(wallet.getThreshold(),
                        xpubList, new int[]{changeIndex, addressCount + i},
                        MultiSig.ofPath(wallet.getExPubPath()).get(0));
                multisigAddress.setPath(wallet.getExPubPath() + "/" + changeIndex + "/" + (addressCount + i));
                multisigAddress.setAddress(addr);
                multisigAddress.setIndex(i + addressCount);
                multisigAddress.setName("BTC-" + (i + addressCount));
                multisigAddress.setWalletFingerPrint(walletFingerprint);
                multisigAddress.setChangeIndex(changeIndex);
                entities.add(multisigAddress);
            }
            repo.insertMultisigAddress(entities);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }
}


