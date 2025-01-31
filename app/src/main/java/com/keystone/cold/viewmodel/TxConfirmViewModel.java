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

package com.keystone.cold.viewmodel;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.googlecode.protobuf.format.JsonFormat;
import com.keystone.coinlib.ExtendPubkeyFormat;
import com.keystone.coinlib.Util;
import com.keystone.coinlib.accounts.MultiSig;
import com.keystone.coinlib.coins.AbsCoin;
import com.keystone.coinlib.coins.AbsTx;
import com.keystone.coinlib.coins.BTC.Btc;
import com.keystone.coinlib.coins.BTC.BtcImpl;
import com.keystone.coinlib.coins.BTC.Deriver;
import com.keystone.coinlib.coins.BTC.UtxoTx;
import com.keystone.coinlib.exception.InvalidPathException;
import com.keystone.coinlib.exception.InvalidTransactionException;
import com.keystone.coinlib.interfaces.SignCallback;
import com.keystone.coinlib.interfaces.SignPsbtCallback;
import com.keystone.coinlib.interfaces.Signer;
import com.keystone.coinlib.path.AddressIndex;
import com.keystone.coinlib.path.CoinPath;
import com.keystone.coinlib.utils.Account;
import com.keystone.coinlib.utils.Coins;
import com.keystone.cold.AppExecutors;
import com.keystone.cold.DataRepository;
import com.keystone.cold.MainApplication;
import com.keystone.cold.Utilities;
import com.keystone.cold.callables.ClearTokenCallable;
import com.keystone.cold.callables.GetExtendedPublicKeyCallable;
import com.keystone.cold.callables.GetMasterFingerprintCallable;
import com.keystone.cold.callables.GetMessageCallable;
import com.keystone.cold.callables.GetPasswordTokenCallable;
import com.keystone.cold.callables.VerifyFingerprintCallable;
import com.keystone.cold.db.entity.AccountEntity;
import com.keystone.cold.db.entity.AddressEntity;
import com.keystone.cold.db.entity.CasaSignature;
import com.keystone.cold.db.entity.CoinEntity;
import com.keystone.cold.db.entity.MultiSigAddressEntity;
import com.keystone.cold.db.entity.MultiSigWalletEntity;
import com.keystone.cold.db.entity.TxEntity;
import com.keystone.cold.encryption.ChipSigner;
import com.keystone.cold.protobuf.TransactionProtoc;
import com.keystone.cold.ui.views.AuthenticateModal;
import com.keystone.cold.util.HashUtil;
import com.keystone.cold.viewmodel.exceptions.NoMatchedMultisigWalletException;
import com.keystone.cold.viewmodel.exceptions.WatchWalletNotMatchException;
import com.keystone.cold.viewmodel.multisigs.LegacyMultiSigViewModel;
import com.keystone.cold.viewmodel.multisigs.MultiSigMode;
import com.keystone.cold.viewmodel.multisigs.exceptions.NotMyCasaKeyException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;

import java.security.SignatureException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.keystone.coinlib.Util.getExpubFingerprint;
import static com.keystone.coinlib.Util.reverseHex;
import static com.keystone.cold.ui.fragment.main.FeeAttackChecking.FeeAttackCheckingResult.DUPLICATE_TX;
import static com.keystone.cold.ui.fragment.main.FeeAttackChecking.FeeAttackCheckingResult.NORMAL;
import static com.keystone.cold.ui.fragment.main.FeeAttackChecking.FeeAttackCheckingResult.SAME_OUTPUTS;
import static com.keystone.cold.viewmodel.AddAddressViewModel.AddAddressTask.getAddressType;
import static com.keystone.cold.viewmodel.GlobalViewModel.getAccount;
import static com.keystone.cold.viewmodel.WatchWallet.ELECTRUM;

public class TxConfirmViewModel extends AndroidViewModel {

    public static final String STATE_NONE = "";
    public static final String STATE_SIGNING = "signing";
    public static final String STATE_SIGN_FAIL = "signing_fail";
    public static final String STATE_SIGN_SUCCESS = "signing_success";
    public static final String TAG = "Vault.TxConfirm";
    private final DataRepository mRepository;
    private final MutableLiveData<TxEntity> observableTx = new MutableLiveData<>();
    private final MutableLiveData<CasaSignature> observableCasaSignature = new MutableLiveData<>();
    private final MutableLiveData<Exception> parseTxException = new MutableLiveData<>();
    private final MutableLiveData<Boolean> addingAddress = new MutableLiveData<>();
    private final MutableLiveData<Integer> feeAttachCheckingResult = new MutableLiveData<>();
    private final MutableLiveData<String> signState = new MutableLiveData<>();
    private final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private AbsTx transaction;
    private String coinCode;
    private AuthenticateModal.OnVerify.VerifyToken token;
    private boolean isMultisig;
    private MultiSigWalletEntity wallet;
    public MultiSigMode mode;
    private String mfp;

    public boolean isCasaMainnet = true;

    public TxConfirmViewModel(@NonNull Application application) {
        super(application);
        observableTx.setValue(null);
        observableCasaSignature.setValue(null);
        mRepository = MainApplication.getApplication().getRepository();
        mfp = new GetMasterFingerprintCallable().call();
    }

    public static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;

    }

    public MutableLiveData<TxEntity> getObservableTx() {
        return observableTx;
    }

    public MutableLiveData<CasaSignature> getObservableCasaSignature() {
        return observableCasaSignature;
    }

    public MutableLiveData<Exception> parseTxException() {
        return parseTxException;
    }

    public void parseTxData(String json) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                JSONObject object = new JSONObject(json);
                Log.i(TAG, "object = " + object.toString(4));
                transaction = AbsTx.newInstance(object);
                if (transaction == null) {
                    observableTx.postValue(null);
                    parseTxException.postValue(new InvalidTransactionException("invalid transaction"));
                    return;
                }

                boolean isMultisig = transaction.isMultisig();
                String walletFingerprint = null;
                if (isMultisig) {
                    if (mode.equals(MultiSigMode.LEGACY)) {
                        if (object.has("btcTx")) {
                            walletFingerprint = object.getJSONObject("btcTx").getString("wallet_fingerprint");
                        } else if (object.has("xtnTx")) {
                            walletFingerprint = object.getJSONObject("xtnTx").getString("wallet_fingerprint");
                        }
                    }
                }
                if (transaction instanceof UtxoTx) {
                    if (!checkUtxoChangeAddress()) return;
                }
                if (isMultisig) {
                    if (mode.equals(MultiSigMode.LEGACY)) {
                        TxEntity tx = generateMultisigTxEntity(object, walletFingerprint);
                        observableTx.postValue(tx);
                        if (Coins.BTC.coinCode().equals(transaction.getCoinCode())
                                || Coins.XTN.coinCode().equals(transaction.getCoinCode())) {
                            feeAttackChecking(tx);
                        }
                    } else {
                        CasaSignature sig = generateCasaSignature(object);
                        observableCasaSignature.postValue(sig);
                    }
                } else {
                    TxEntity tx = generateTxEntity(object);
                    observableTx.postValue(tx);
                    if (Coins.BTC.coinCode().equals(transaction.getCoinCode())
                            || Coins.XTN.coinCode().equals(transaction.getCoinCode())) {
                        feeAttackChecking(tx);
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private boolean checkUtxoChangeAddress() {
        if (transaction.isMultisig()) {
            if (!checkMultisigChangeAddress(transaction)) {
                observableTx.postValue(null);
                parseTxException.postValue(new InvalidTransactionException("invalid change address"));
                return false;
            }
        } else if (!checkChangeAddress(transaction)) {
            observableTx.postValue(null);
            parseTxException.postValue(new InvalidTransactionException("invalid change address"));
            return false;
        }
        return true;
    }

    private void feeAttackChecking(TxEntity txEntity) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            String inputs = txEntity.getFrom();
            String outputs = txEntity.getTo();
            List<TxEntity> txs = mRepository.loadAllTxSync(Utilities.currentCoin(getApplication()).coinId());
            for (TxEntity tx : txs) {
                if (inputs.equals(tx.getFrom()) && outputs.equals(tx.getTo())) {
                    feeAttachCheckingResult.postValue(DUPLICATE_TX);
                    break;
                } else if (outputs.equals(tx.getTo())) {
                    feeAttachCheckingResult.postValue(SAME_OUTPUTS);
                    break;
                } else {
                    feeAttachCheckingResult.postValue(NORMAL);
                }
            }
        });
    }

    public LiveData<Integer> feeAttackChecking() {
        return feeAttachCheckingResult;
    }

    private TxEntity generateTxEntity(JSONObject object) throws JSONException {
        TxEntity tx = new TxEntity();
        NumberFormat nf = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(20);
        coinCode = Objects.requireNonNull(transaction).getCoinCode();
        tx.setSignId(object.getString("signId"));
        tx.setTimeStamp(object.optLong("timestamp"));
        tx.setCoinCode(coinCode);
        tx.setCoinId(Coins.coinIdFromCoinCode(coinCode));
        tx.setFrom(getFromAddress());
        tx.setTo(getToAddress());
        tx.setAmount(nf.format(transaction.getAmount()) + " " + transaction.getUnit());
        tx.setFee(nf.format(transaction.getFee()) + " BTC");
        tx.setMemo(transaction.getMemo());
        tx.setBelongTo(mRepository.getBelongTo());
        return tx;
    }

    private TxEntity generateMultisigTxEntity(JSONObject object, String walletFingerprint) throws JSONException {
        wallet = mRepository.loadMultisigWallet(walletFingerprint);
        TxEntity tx = new TxEntity();
        NumberFormat nf = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(20);
        coinCode = Objects.requireNonNull(transaction).getCoinCode();
        tx.setSignId(object.getString("signId"));
        tx.setTimeStamp(object.optLong("timestamp"));
        tx.setCoinCode(coinCode);
        tx.setCoinId(Coins.coinIdFromCoinCode(coinCode));
        tx.setFrom(getMultiSigFromAddress());
        tx.setTo(getToAddress());
        tx.setAmount(nf.format(transaction.getAmount()) + " " + transaction.getUnit());
        tx.setFee(nf.format(transaction.getFee()) + " BTC");
        tx.setMemo(transaction.getMemo());
        tx.setBelongTo(wallet.getWalletFingerPrint());
        String signStatus = null;
        if (object.has("btcTx")) {
            signStatus = object.getJSONObject("btcTx").getString("signStatus");
        } else if (object.has("xtnTx")) {
            signStatus = object.getJSONObject("xtnTx").getString("signStatus");
        }
        tx.setSignStatus(signStatus);
        return tx;
    }

    private CasaSignature generateCasaSignature(JSONObject object) throws JSONException {
        CasaSignature sig = new CasaSignature();
        NumberFormat nf = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(20);
        sig.setFrom(getMultiSigFromAddress());
        sig.setTo(getToAddress());
        sig.setAmount(nf.format(transaction.getAmount()) + " " + transaction.getUnit());
        sig.setFee(nf.format(transaction.getFee()) + " BTC");
        sig.setMemo(transaction.getMemo());
        String signStatus = null;
        if (object.has("btcTx")) {
            signStatus = object.getJSONObject("btcTx").getString("signStatus");
        } else if (object.has("xtnTx")) {
            signStatus = object.getJSONObject("xtnTx").getString("signStatus");
        }
        sig.setSignStatus(signStatus);
        return sig;
    }

    public void parsePsbtBase64(String psbtBase64, boolean multisig) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            Btc btc = new Btc(new BtcImpl(Utilities.isMainNet(getApplication())));
            JSONObject psbtTx = btc.parsePsbt(psbtBase64);
            if (psbtTx == null) {
                parseTxException.postValue(new InvalidTransactionException("parse failed,invalid psbt data"));
                return;
            }

            try {
                boolean isMultigisTx = psbtTx.getJSONArray("inputs").getJSONObject(0).getBoolean("isMultiSign");
                JSONObject adaptTx;
                if (!multisig) {
                    if (isMultigisTx) {
                        parseTxException.postValue(
                                new InvalidTransactionException("", InvalidTransactionException.IS_MULTISIG_TX));
                        return;
                    }
                    adaptTx = new PsbtTxAdapter().adapt(psbtTx);
                    if (adaptTx.getJSONArray("inputs").length() == 0) {
                        parseTxException.postValue(
                                new InvalidTransactionException("master xfp not match, or nothing can be sign"));
                        return;
                    }
                } else {
                    if (!isMultigisTx) {
                        parseTxException.postValue(
                                new InvalidTransactionException("", InvalidTransactionException.IS_NOTMULTISIG_TX));
                        return;
                    }
                    adaptTx = new PsbtMultiSigTxAdapter().adapt(psbtTx);
                }

                JSONObject signTx = parsePsbtTx(adaptTx);
                parseTxData(signTx.toString());
            } catch (JSONException e) {
                e.printStackTrace();
                parseTxException.postValue(new InvalidTransactionException("adapt failed,invalid psbt data"));
            } catch (WatchWalletNotMatchException | NoMatchedMultisigWalletException | NotMyCasaKeyException e) {
                e.printStackTrace();
                parseTxException.postValue(e);
            }

        });
    }

    private JSONObject parsePsbtTx(JSONObject adaptTx) throws JSONException {
        Account account = getAccount(getApplication());
        WatchWallet wallet = WatchWallet.getWatchWallet(getApplication());
        String signId = WatchWallet.getWatchWallet(getApplication()).getSignId();
        if ((account == Account.P2WPKH || account == Account.P2WPKH_TESTNET) && wallet == ELECTRUM) {
            signId += "_NATIVE_SEGWIT";
        }
        boolean isMultisig = adaptTx.optBoolean("multisig");
        TransactionProtoc.SignTransaction.Builder builder = TransactionProtoc.SignTransaction.newBuilder();
        boolean isMainNet = Utilities.isMainNet(getApplication());
        builder.setCoinCode(Utilities.currentCoin(getApplication()).coinCode())
                .setSignId(isMultisig ? "PSBT_MULTISIG" : signId)
                .setTimestamp(generateAutoIncreaseId())
                .setDecimal(8);
        String signTransaction = new JsonFormat().printToString(builder.build());
        JSONObject signTx = new JSONObject(signTransaction);
        signTx.put(isMainNet ? "btcTx" : "xtnTx", adaptTx);
        return signTx;
    }

    private long generateAutoIncreaseId() {
        List<TxEntity> txEntityList = mRepository.loadAllTxsSync(Utilities.currentCoin(getApplication()).coinId());
        if (txEntityList == null || txEntityList.isEmpty()) {
            return 0;
        }
        return txEntityList.stream()
                .max(Comparator.comparing(TxEntity::getTimeStamp))
                .get()
                .getTimeStamp() + 1;
    }

    private boolean checkChangeAddress(AbsTx utxoTx) {
        List<UtxoTx.ChangeAddressInfo> changeAddressInfoList = ((UtxoTx) utxoTx).getChangeAddressInfo();

        if (changeAddressInfoList == null || changeAddressInfoList.isEmpty()) {
            return true;
        }
        Deriver deriver = new Deriver(Utilities.isMainNet(getApplication()));
        for (UtxoTx.ChangeAddressInfo changeAddressInfo : changeAddressInfoList) {
            String hdPath = changeAddressInfo.hdPath;
            String address = changeAddressInfo.address;
            String accountHdPath = getAccountHdPath(changeAddressInfo.hdPath);
            AccountEntity accountEntity = getAccountEntityByPath(accountHdPath,
                    mRepository.loadCoinEntityByCoinCode(utxoTx.getCoinCode()));
            if (accountEntity == null) {
                return false;
            }
            String exPub = accountEntity.getExPub();
            try {
                AddressIndex addressIndex = CoinPath.parsePath(hdPath);
                int change = addressIndex.getParent().getValue();
                int index = addressIndex.getValue();
                String expectAddress = Objects.requireNonNull(deriver).derive(exPub, change,
                        index, getAddressType(accountEntity));
                if (!address.equals(expectAddress)) {
                    return false;
                }
            } catch (InvalidPathException e) {
                e.printStackTrace();
                return false;
            }
        }

        return true;
    }

    private boolean checkMultisigChangeAddress(AbsTx utxoTx) {
        List<UtxoTx.ChangeAddressInfo> changeAddressInfo = ((UtxoTx) utxoTx).getChangeAddressInfo();
        if (changeAddressInfo == null || changeAddressInfo.isEmpty()) {
            return true;
        }

        try {
            String exPubPath = wallet.getExPubPath();
            for (UtxoTx.ChangeAddressInfo info : changeAddressInfo) {
                String path = info.hdPath;
                String address = info.address;
                if (!path.startsWith(exPubPath)) return false;
                path = path.replace(exPubPath + "/", "");

                String[] index = path.split("/");

                if (index.length != 2) return false;
                String expectedAddress = wallet.deriveAddress(
                        new int[]{Integer.parseInt(index[0]), Integer.parseInt(index[1])},
                        Utilities.isMainNet(getApplication()));

                if (!expectedAddress.equals(address)) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    private String getToAddress() {
        String to = transaction.getTo();

        if (transaction instanceof UtxoTx) {
            JSONArray outputs = ((UtxoTx) transaction).getOutputs();
            if (outputs != null) {
                return outputs.toString();
            }
        }

        return to;
    }

    private String getMultiSigFromAddress() {
        if (mode.equals(MultiSigMode.CASA)) return "";
        String[] paths = transaction.getHdPath().split(AbsTx.SEPARATOR);
        String[] externalPath = Stream.of(paths)
                .filter(this::isExternalMulisigPath)
                .toArray(String[]::new);
        ensureMultisigAddressExist(externalPath);

        try {
            if (transaction instanceof UtxoTx) {
                JSONArray inputsClone = new JSONArray();
                JSONArray inputs = ((UtxoTx) transaction).getInputs();

                for (int i = 0; i < inputs.length(); i++) {
                    JSONObject input = inputs.getJSONObject(i);
                    long value = input.getJSONObject("utxo").getLong("value");
                    String hdpath = input.getString("ownerKeyPath");
                    hdpath = hdpath.replace(wallet.getExPubPath() + "/", "");
                    String[] index = hdpath.split("/");
                    String from = wallet.deriveAddress(
                            new int[]{Integer.parseInt(index[0]), Integer.parseInt(index[1])},
                            Utilities.isMainNet(getApplication()));
                    inputsClone.put(new JSONObject().put("value", value)
                            .put("address", from));
                }

                return inputsClone.toString();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return "";
    }

    private String getFromAddress() {
        if (!TextUtils.isEmpty(transaction.getFrom())) {
            return transaction.getFrom();
        }
        String[] paths = transaction.getHdPath().split(AbsTx.SEPARATOR);
        String[] externalPath = Stream.of(paths)
                .filter(this::isExternalPath)
                .toArray(String[]::new);
        ensureAddressExist(externalPath);

        try {
            if (transaction instanceof UtxoTx) {
                JSONArray inputsClone = new JSONArray();
                JSONArray inputs = ((UtxoTx) transaction).getInputs();

                CoinEntity coinEntity = mRepository.loadCoinSync(Coins.coinIdFromCoinCode(transaction.getCoinCode()));
                AccountEntity accountEntity =
                        mRepository.loadAccountsByPath(coinEntity.getId(), getAccount(getApplication()).getPath());

                for (int i = 0; i < inputs.length(); i++) {
                    JSONObject input = inputs.getJSONObject(i);
                    long value = input.getJSONObject("utxo").getLong("value");
                    String hdpath = input.getString("ownerKeyPath");
                    AddressIndex addressIndex = CoinPath.parsePath(hdpath);
                    int index = addressIndex.getValue();
                    int change = addressIndex.getParent().getValue();

                    String from = new Deriver(Utilities.isMainNet(getApplication())).derive(accountEntity.getExPub()
                            , change, index, getAddressType(accountEntity));
                    inputsClone.put(new JSONObject().put("value", value)
                            .put("address", from));
                }

                return inputsClone.toString();
            }
        } catch (JSONException | InvalidPathException e) {
            e.printStackTrace();
        }

        return Stream.of(externalPath)
                .distinct()
                .map(path -> mRepository.loadAddressBypath(path).getAddressString())
                .reduce((s1, s2) -> s1 + AbsTx.SEPARATOR + s2)
                .orElse("");
    }

    private boolean isExternalPath(@NonNull String path) {
        try {
            return CoinPath.parsePath(path).getParent().isExternal();
        } catch (InvalidPathException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isExternalMulisigPath(@NonNull String path) {
        String[] split = path.replace(wallet.getExPubPath() + "/", "").split("/");
        return split.length == 2 && split[0].equals("0");
    }

    private void ensureAddressExist(String[] paths) {
        if (paths == null || paths.length == 0) {
            return;
        }
        String maxIndexHdPath = paths[0];
        if (paths.length > 1) {
            int max = getAddressIndex(paths[0]);
            for (String path : paths) {
                if (getAddressIndex(path) > max) {
                    max = getAddressIndex(path);
                    maxIndexHdPath = path;
                }
            }
        }
        AddressEntity address = mRepository.loadAddressBypath(maxIndexHdPath);
        if (address == null) {
            addAddress(maxIndexHdPath);
        }
    }

    private void ensureMultisigAddressExist(String[] paths) {
        if (paths == null || paths.length == 0) {
            return;
        }
        String maxIndexHdPath = paths[0];
        int max = getAddressIndex(maxIndexHdPath);
        if (paths.length > 1) {
            max = getAddressIndex(paths[0]);
            for (String path : paths) {
                if (getAddressIndex(path) > max) {
                    max = getAddressIndex(path);
                    maxIndexHdPath = path;
                }
            }
        }

        MultiSigAddressEntity entity = mRepository.loadAllMultiSigAddress(wallet.getWalletFingerPrint(), maxIndexHdPath);
        if (entity == null) {
            List<MultiSigAddressEntity> address = mRepository.loadAllMultiSigAddressSync(wallet.getWalletFingerPrint());
            Optional<MultiSigAddressEntity> optional = address.stream()
                    .filter(addressEntity -> addressEntity.getPath()
                            .startsWith(wallet.getExPubPath() + "/" + 0))
                    .max((o1, o2) -> o1.getIndex() - o2.getIndex());
            int index = optional.map(MultiSigAddressEntity::getIndex).orElse(-1);
            if (index < max) {
                final CountDownLatch mLatch = new CountDownLatch(1);
                addingAddress.postValue(true);
                new LegacyMultiSigViewModel.AddAddressTask(wallet.getWalletFingerPrint(),
                        mRepository, mLatch::countDown, 0).execute(max - index);
                try {
                    mLatch.await();
                    addingAddress.postValue(false);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public LiveData<Boolean> getAddingAddressState() {
        return addingAddress;
    }

    private void addAddress(String hdPath) {
        String accountHdPath;
        int pathIndex;
        try {
            AddressIndex index = CoinPath.parsePath(hdPath);
            pathIndex = index.getValue();
            accountHdPath = index.getParent().getParent().toString();
        } catch (InvalidPathException e) {
            e.printStackTrace();
            return;
        }

        CoinEntity coin = mRepository.loadCoinSync(Coins.coinIdFromCoinCode(coinCode));
        AccountEntity accountEntity = getAccountEntityByPath(accountHdPath, coin);
        if (accountEntity == null) return;

        List<AddressEntity> addressEntities = mRepository.loadAddressSync(coin.getCoinId());
        Optional<AddressEntity> addressEntityOptional = addressEntities
                .stream()
                .filter(addressEntity -> addressEntity.getPath()
                        .startsWith(accountEntity.getHdPath() + "/" + 0))
                .max((o1, o2) -> o1.getPath().compareTo(o2.getPath()));

        int index = -1;
        if (addressEntityOptional.isPresent()) {
            try {
                AddressIndex addressIndex = CoinPath.parsePath(addressEntityOptional.get().getPath());
                index = addressIndex.getValue();
            } catch (InvalidPathException e) {
                e.printStackTrace();
            }
        }

        if (index < pathIndex) {
            final CountDownLatch mLatch = new CountDownLatch(1);
            addingAddress.postValue(true);
            new AddAddressViewModel.AddAddressTask(coin, mRepository, mLatch::countDown,
                    accountEntity.getExPub(), 0).execute(pathIndex - index);
            try {
                mLatch.await();
                addingAddress.postValue(false);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    private AccountEntity getAccountEntityByPath(String accountHdPath, CoinEntity coin) {
        List<AccountEntity> accountEntities = mRepository.loadAccountsForCoin(coin);
        Optional<AccountEntity> optional = accountEntities.stream()
                .filter(accountEntity ->
                        accountEntity.getHdPath().equals(accountHdPath.toUpperCase()))
                .findFirst();

        AccountEntity accountEntity;
        if (optional.isPresent()) {
            accountEntity = optional.get();
        } else {
            return null;
        }
        return accountEntity;
    }

    private int getAddressIndex(String hdPath) {
        String[] splits = hdPath.split("/");
        try {
            if (splits.length > 1) {
                return Integer.parseInt(splits[splits.length - 1]);
            }
        } catch (NumberFormatException ignore) {
        }
        return 0;
    }

    public MutableLiveData<String> getSignState() {
        return signState;
    }

    public void setToken(AuthenticateModal.OnVerify.VerifyToken token) {
        this.token = token;
    }

    public void handleSign() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            SignCallback callback = initSignCallback();
            callback.startSign();
            Signer[] signer = initSigners();
            signTransaction(transaction, callback, signer);
        });
    }

    public void handleSignPsbt(String psbt) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            SignPsbtCallback callback = new SignPsbtCallback() {
                @Override
                public void startSign() {
                    signState.postValue(STATE_SIGNING);
                }

                @Override
                public void onFail() {
                    signState.postValue(STATE_SIGN_FAIL);
                    new ClearTokenCallable().call();
                }

                @Override
                public void onSuccess(String txId, String psbtB64) {
                    if (mode == null || mode.equals(MultiSigMode.LEGACY)) {
                        TxEntity tx = observableTx.getValue();
                        Objects.requireNonNull(tx);
                        if (isMultisig) {
                            updateTxSignStatus(tx);
                        }
                        if (TextUtils.isEmpty(txId)) {
                            txId = "unknown_txid_" + Math.abs(tx.hashCode());
                        }
                        tx.setTxId(txId);
                        tx.setSignedHex(psbtB64);
                        mRepository.insertTx(tx);
                    } else {
                        CasaSignature casaSignature = observableCasaSignature.getValue();
                        Objects.requireNonNull(casaSignature);
                        if (TextUtils.isEmpty(txId)) {
                            txId = "unknown_txid_" + Math.abs(casaSignature.hashCode());
                        }
                        updateCasaSignatureStatus(casaSignature);
                        casaSignature.setTxId(txId);
                        casaSignature.setSignedHex(psbtB64);
                        Long id = mRepository.insertCasaSignature(casaSignature);
                        casaSignature.setId(id);
                    }
                    signState.postValue(STATE_SIGN_SUCCESS);
                    new ClearTokenCallable().call();
                }

                @Override
                public void postProgress(int progress) {

                }

                private void updateTxSignStatus(TxEntity tx) {
                    String signStatus = tx.getSignStatus();
                    String[] splits = signStatus.split("-");
                    int sigNumber = Integer.parseInt(splits[0]);
                    int reqSigNumber = Integer.parseInt(splits[1]);
                    int keyNumber = Integer.parseInt(splits[2]);
                    tx.setSignStatus((sigNumber + 1) + "-" + reqSigNumber + "-" + keyNumber);
                }

                private void updateCasaSignatureStatus(CasaSignature casaSignature) {
                    String signStatus = casaSignature.getSignStatus();
                    String[] splits = signStatus.split("-");
                    int sigNumber = Integer.parseInt(splits[0]);
                    int reqSigNumber = Integer.parseInt(splits[1]);
                    int keyNumber = Integer.parseInt(splits[2]);
                    casaSignature.setSignStatus((sigNumber + 1) + "-" + reqSigNumber + "-" + keyNumber);
                }
            };
            callback.startSign();
            Signer[] signer = initSigners();
            Btc btc = new Btc(new BtcImpl(Utilities.isMainNet(getApplication())));
            if (isMultisig || WatchWallet.getWatchWallet(getApplication()) == ELECTRUM) {
                btc.signPsbt(psbt, callback, false, signer);
            } else {
                btc.signPsbt(psbt, callback, signer);
            }
        });
    }

    private SignCallback initSignCallback() {
        return new SignCallback() {
            @Override
            public void startSign() {
                signState.postValue(STATE_SIGNING);
            }

            @Override
            public void onFail() {
                signState.postValue(STATE_SIGN_FAIL);
                new ClearTokenCallable().call();
            }

            @Override
            public void onSuccess(String txId, String rawTx) {
                TxEntity tx = observableTx.getValue();
                Objects.requireNonNull(tx).setTxId(txId);
                tx.setSignedHex(rawTx);
                mRepository.insertTx(tx);
                signState.postValue(STATE_SIGN_SUCCESS);
                new ClearTokenCallable().call();
            }

            @Override
            public void postProgress(int progress) {

            }
        };
    }

    private void signTransaction(@NonNull AbsTx transaction, @NonNull SignCallback callback, Signer... signer) {
        if (signer == null) {
            callback.onFail();
            return;
        }
        switch (transaction.getTxType()) {
            case "OMNI":
            case "OMNI_USDT":
                Btc btc = new Btc(new BtcImpl(Utilities.isMainNet(getApplication())));
                btc.generateOmniTx(transaction, callback, signer);
                break;
            default:
                AbsCoin coin = AbsCoin.newInstance(coinCode);
                Objects.requireNonNull(coin).generateTransaction(transaction, callback, signer);
        }
    }

    public String getPath() {
        return transaction.getHdPath();
    }

    private Signer[] initSigners() {
        String[] paths = transaction.getHdPath().split(AbsTx.SEPARATOR);
        String coinCode = transaction.getCoinCode();
        String[] distinctPaths = Stream.of(paths).distinct().toArray(String[]::new);
        Signer[] signer = new Signer[distinctPaths.length];

        String authToken = getAuthToken();
        if (TextUtils.isEmpty(authToken)) {
            Log.w(TAG, "authToken null");
            return null;
        }

        if (transaction.isMultisig() && mode.equals(MultiSigMode.LEGACY)) {
            for (int i = 0; i < distinctPaths.length; i++) {
                String path = distinctPaths[i].replace(wallet.getExPubPath() + "/", "");
                String[] index = path.split("/");
                if (index.length != 2) return null;
                String expub = new GetExtendedPublicKeyCallable(wallet.getExPubPath()).call();
                String pubKey = Util.getPublicKeyHex(
                        ExtendPubkeyFormat.convertExtendPubkey(expub, ExtendPubkeyFormat.xpub),
                        Integer.parseInt(index[0]), Integer.parseInt(index[1]));
                signer[i] = new ChipSigner(distinctPaths[i].toLowerCase(), authToken, pubKey);
            }
        } else if (transaction.isMultisig() && mode.equals(MultiSigMode.CASA)) {
            for (int i = 0; i < distinctPaths.length; i++) {
                int point = Math.max(distinctPaths[i].lastIndexOf("'"), 0);
                String XPubPath = distinctPaths[i].substring(0, point + 1);
                String path = distinctPaths[i].replace(XPubPath + "/", "");
                String[] index = path.split("/");
                String expub = new GetExtendedPublicKeyCallable(XPubPath).call();
                String pubKey = Util.deriveFromKey(
                        expub, index);
                signer[i] = new ChipSigner(distinctPaths[i].toLowerCase(), authToken, pubKey);
            }
        } else {
            CoinEntity coinEntity = mRepository.loadCoinEntityByCoinCode(coinCode);
            for (int i = 0; i < distinctPaths.length; i++) {
                String accountHdPath = getAccountHdPath(distinctPaths[i]);
                if (accountHdPath == null) {
                    return null;
                }
                AccountEntity accountEntity = getAccountEntityByPath(accountHdPath, coinEntity);
                if (accountEntity == null) {
                    return null;
                }
                String pubKey = Util.getPublicKeyHex(accountEntity.getExPub(), distinctPaths[i]);
                signer[i] = new ChipSigner(distinctPaths[i].toLowerCase(), authToken, pubKey);
            }
        }
        return signer;
    }

    private String getAccountHdPath(String addressPath) {
        String accountHdPath;
        try {
            accountHdPath = CoinPath.parsePath(addressPath).getParent().getParent().toString();
        } catch (InvalidPathException e) {
            e.printStackTrace();
            return null;
        }
        return accountHdPath;
    }

    private String getAuthToken() {
        String authToken = null;
        if (!TextUtils.isEmpty(token.password)) {
            authToken = new GetPasswordTokenCallable(token.password).call();
        } else if (token.signature != null) {
            String message = new GetMessageCallable().call();
            if (!TextUtils.isEmpty(message)) {
                try {
                    token.signature.update(Hex.decode(message));
                    byte[] signature = token.signature.sign();
                    byte[] rs = Util.decodeRSFromDER(signature);
                    if (rs != null) {
                        authToken = new VerifyFingerprintCallable(Hex.toHexString(rs)).call();
                    }
                } catch (SignatureException e) {
                    e.printStackTrace();
                }
            }
        }
        AuthenticateModal.OnVerify.VerifyToken.invalid(token);
        return authToken;
    }

    public TxEntity getSignedTxEntity() {
        return observableTx.getValue();
    }

    public CasaSignature getSignedCasaSignature() {
        return observableCasaSignature.getValue();
    }

    public String getTxId() {
        return Objects.requireNonNull(observableTx.getValue()).getTxId();
    }

    public String getCasaSignatureId() {
        return String.valueOf(Objects.requireNonNull(observableCasaSignature.getValue()).getId());
    }

    public String getTxHex() {
        return Objects.requireNonNull(observableTx.getValue()).getSignedHex();
    }

    public boolean isAddressInWhiteList(String address) {
        try {
            return sExecutor.submit(() -> mRepository.queryWhiteList(address) != null).get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setIsMultisig(boolean multisig) {
        this.isMultisig = multisig;
    }

    public void setMultisigMode(MultiSigMode multisigMode) {
        mode = multisigMode;
    }

    static class PsbtTxAdapter {
        JSONObject adapt(JSONObject psbt) throws JSONException, WatchWalletNotMatchException {
            JSONObject object = new JSONObject();
            JSONArray inputs = new JSONArray();
            JSONArray outputs = new JSONArray();
            adaptInputs(psbt.getJSONArray("inputs"), inputs);
            if (inputs.length() < 1) {
                throw new WatchWalletNotMatchException("no input match masterFingerprint");
            }

            adaptOutputs(psbt.getJSONArray("outputs"), outputs);
            object.put("inputs", inputs);
            object.put("outputs", outputs);
            return object;
        }

        private void adaptInputs(JSONArray psbtInputs, JSONArray inputs) throws JSONException {
            Account account = getAccount(MainApplication.getApplication());
            for (int i = 0; i < psbtInputs.length(); i++) {
                JSONObject psbtInput = psbtInputs.getJSONObject(i);
                JSONObject in = new JSONObject();
                JSONObject utxo = new JSONObject();
                in.put("hash", psbtInput.getString("txId"));
                in.put("index", psbtInput.getInt("index"));
                JSONArray bip32Derivation = psbtInput.getJSONArray("hdPath");
                for (int j = 0; j < bip32Derivation.length(); j++) {
                    JSONObject item = bip32Derivation.getJSONObject(j);
                    String hdPath = item.getString("path");
                    String fingerprint = item.getString("masterFingerprint");
                    boolean match = false;
                    if (matchRootXfp(fingerprint, hdPath)) {
                        match = true;
                    }
                    if (!match) {
                        match = matchKeyXfp(fingerprint);
                        hdPath = account.getPath() + hdPath.substring(1);
                    }
                    if (match) {
                        utxo.put("publicKey", item.getString("pubkey"));
                        utxo.put("value", psbtInput.optInt("value"));
                        in.put("utxo", utxo);
                        in.put("ownerKeyPath", hdPath);
                        in.put("masterFingerprint", item.getString("masterFingerprint"));
                        inputs.put(in);
                        break;
                    }
                }

            }

        }

        boolean matchRootXfp(String fingerprint, String path) {
            String rootXfp = new GetMasterFingerprintCallable().call();
            Account account = getAccount(MainApplication.getApplication());
            return (fingerprint.equalsIgnoreCase(rootXfp)
                    || reverseHex(fingerprint).equalsIgnoreCase(rootXfp))
                    && path.toUpperCase().startsWith(account.getPath());
        }

        boolean matchKeyXfp(String fingerprint) {
            Account account = getAccount(MainApplication.getApplication());
            String xpub = new GetExtendedPublicKeyCallable(account.getPath()).call();
            String xfp = getExpubFingerprint(xpub);
            return (fingerprint.equalsIgnoreCase(xfp)
                    || reverseHex(fingerprint).equalsIgnoreCase(xfp));
        }

        private void adaptOutputs(JSONArray psbtOutputs, JSONArray outputs) throws JSONException {
            Account account = getAccount(MainApplication.getApplication());
            for (int i = 0; i < psbtOutputs.length(); i++) {
                JSONObject psbtOutput = psbtOutputs.getJSONObject(i);
                JSONObject out = new JSONObject();
                String address = psbtOutput.getString("address");
                out.put("address", address);
                out.put("value", psbtOutput.getInt("value"));
                JSONArray bip32Derivation = psbtOutput.optJSONArray("hdPath");
                if (bip32Derivation != null) {
                    for (int j = 0; j < bip32Derivation.length(); j++) {
                        JSONObject item = bip32Derivation.getJSONObject(j);
                        String hdPath = item.getString("path");
                        String fingerprint = item.getString("masterFingerprint");
                        boolean match = false;
                        if (matchRootXfp(fingerprint, hdPath)) {
                            match = true;
                        }
                        if (!match) {
                            match = matchKeyXfp(fingerprint);
                            hdPath = account.getPath().toLowerCase() + hdPath.substring(1);
                        }

                        if (match) {
                            out.put("isChange", true);
                            out.put("changeAddressPath", hdPath);

                        }
                    }
                }
                outputs.put(out);
            }
        }
    }


    class PsbtMultiSigTxAdapter {
        private int total;
        private int threshold;
        private String fingerprintsHash;
        private JSONObject object;

        JSONObject adapt(JSONObject psbt) throws JSONException, WatchWalletNotMatchException, NoMatchedMultisigWalletException, NotMyCasaKeyException {
            object = new JSONObject();
            JSONArray inputs = new JSONArray();
            JSONArray outputs = new JSONArray();
            adaptInputs(psbt.getJSONArray("inputs"), inputs);
            if (inputs.length() < 1) {
                throw new WatchWalletNotMatchException("no input match masterFingerprint");
            }
            adaptOutputs(psbt.getJSONArray("outputs"), outputs);
            object.put("inputs", inputs);
            object.put("outputs", outputs);
            object.put("multisig", true);
            if (mode.equals(MultiSigMode.LEGACY)) {
                object.put("wallet_fingerprint", wallet != null ? wallet.getWalletFingerPrint() : null);
            }
            return object;
        }

        private void adaptInputs(JSONArray psbtInputs, JSONArray inputs) throws JSONException, NoMatchedMultisigWalletException, NotMyCasaKeyException {
            for (int i = 0; i < psbtInputs.length(); i++) {
                JSONObject psbtInput = psbtInputs.getJSONObject(i);
                JSONObject in = new JSONObject();
                JSONObject utxo = new JSONObject();
                in.put("hash", psbtInput.getString("txId"));
                in.put("index", psbtInput.getInt("index"));

                if (i == 0) {
                    String[] signStatus = psbtInput.getString("signStatus").split("-");
                    threshold = Integer.parseInt(signStatus[1]);
                    total = Integer.parseInt(signStatus[2]);
                    object.put("signStatus", psbtInput.getString("signStatus"));
                }

                JSONArray bip32Derivation = psbtInput.getJSONArray("hdPath");
                int length = bip32Derivation.length();
                if (length != total) break;
                String hdPath = "";
                List<String> fps = new ArrayList<>();
                for (int j = 0; j < total; j++) {
                    JSONObject item = bip32Derivation.getJSONObject(j);
                    String fingerprint = item.getString("masterFingerprint");
                    if (fingerprint.equalsIgnoreCase(new GetMasterFingerprintCallable().call())) {
                        hdPath = item.getString("path");
                    }
                    fps.add(fingerprint);
                }

                // the first input xpub info
                if (i == 0) {
                    fingerprintsHash = fingerprintsHash(fps);
                }

                //all input should have the same xpub info
                if (!fingerprintsHash(fps).equals(fingerprintsHash)) break;

                if (mode.equals(MultiSigMode.LEGACY)) {
                    //find the exists multisig wallet match the xpub info
                    if (wallet == null) {
                        List<MultiSigWalletEntity> wallets = mRepository.loadAllMultiSigWalletSync()
                                .stream()
                                .filter(w -> w.getTotal() == total && w.getThreshold() == threshold)
                                .collect(Collectors.toList());
                        for (MultiSigWalletEntity w : wallets) {
                            if (w.getTotal() != total || w.getThreshold() != threshold) continue;
                            JSONArray array = new JSONArray(w.getExPubs());
                            List<String> walletFps = new ArrayList<>();
                            List<String> walletRootXfps = new ArrayList<>();
                            for (int k = 0; k < array.length(); k++) {
                                JSONObject xpub = array.getJSONObject(k);
                                walletFps.add(getExpubFingerprint(xpub.getString("xpub")));
                                walletRootXfps.add(xpub.getString("xfp"));
                            }
                            if (fingerprintsHash(walletFps).equalsIgnoreCase(fingerprintsHash)
                                    || (fingerprintsHash(walletRootXfps).equalsIgnoreCase(fingerprintsHash)
                                    && hdPath.startsWith(w.getExPubPath()))) {
                                wallet = w;
                                break;
                            }
                        }
                    }

                    if (wallet != null) {
                        if (!hdPath.startsWith(wallet.getExPubPath())) {
                            hdPath = wallet.getExPubPath() + hdPath.substring(1);
                        }
                        utxo.put("publicKey", findMyPubKey(bip32Derivation));
                        utxo.put("value", psbtInput.optInt("value"));
                        in.put("utxo", utxo);
                        in.put("ownerKeyPath", hdPath);
                        in.put("masterFingerprint", wallet.getBelongTo());
                        inputs.put(in);
                    } else {
                        throw new NoMatchedMultisigWalletException("no matched multisig wallet");
                    }
                } else {
                    String myCasaKey = findMyCasaKey(bip32Derivation);
                    if (myCasaKey != null) {
                        if (!hdPath.startsWith(MultiSig.CASA.getPath())) {
                            hdPath = MultiSig.CASA.getPath() + hdPath.substring(1);
                        }
                        String path = hdPath.replace("m/", "");
                        String[] index = path.split("/");
                        isCasaMainnet = !index[1].equals("1'");
                        utxo.put("publicKey", myCasaKey);
                        utxo.put("value", psbtInput.optInt("value"));
                        in.put("utxo", utxo);
                        in.put("ownerKeyPath", hdPath);
                        in.put("masterFingerprint", mfp);
                        inputs.put(in);
                    } else {
                        throw new NotMyCasaKeyException("no matched casa key found");
                    }
                }
            }
        }

        private String findMyCasaKey(JSONArray bip32Derivation) throws JSONException {
            for (int i = 0; i < bip32Derivation.length(); i++) {
                if (mfp.equalsIgnoreCase(bip32Derivation.getJSONObject(i).getString("masterFingerprint"))) {
                    return bip32Derivation.getJSONObject(i).getString("pubkey");
                }
            }
            return null;
        }

        private String findMyPubKey(JSONArray bip32Derivation)
                throws JSONException {
            String xfp = wallet.getBelongTo();
            String fp = null;
            JSONArray array = new JSONArray(wallet.getExPubs());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.getString("xfp").equalsIgnoreCase(xfp)) {
                    fp = getExpubFingerprint(obj.getString("xpub"));
                }
            }

            if (fp != null) {
                for (int i = 0; i < bip32Derivation.length(); i++) {
                    if (fp.equalsIgnoreCase(bip32Derivation.getJSONObject(i)
                            .getString("masterFingerprint"))) {
                        return bip32Derivation.getJSONObject(i).getString("pubkey");
                    }
                }
            }
            return "";
        }


        private String fingerprintsHash(List<String> fps) {
            String concat = fps.stream()
                    .map(String::toUpperCase)
                    .sorted()
                    .reduce((s1, s2) -> s1 + s2).orElse("");

            return Hex.toHexString(HashUtil.sha256(concat));
        }

        private void adaptOutputs(JSONArray psbtOutputs, JSONArray outputs) throws JSONException {
            for (int i = 0; i < psbtOutputs.length(); i++) {
                JSONObject psbtOutput = psbtOutputs.getJSONObject(i);
                JSONObject out = new JSONObject();
                String address = psbtOutput.getString("address");
                if (mode.equals(MultiSigMode.CASA)) {
                    if (!isCasaMainnet) {
                        address = Util.convertAddressToTestnet(address);
                    }
                }
                out.put("address", address);
                out.put("value", psbtOutput.getInt("value"));
                JSONArray bip32Derivation = psbtOutput.optJSONArray("hdPath");
                if (bip32Derivation != null && mode.equals(MultiSigMode.LEGACY)) {
                    for (int j = 0; j < bip32Derivation.length(); j++) {
                        JSONObject item = bip32Derivation.getJSONObject(j);
                        String hdPath = item.getString("path");
                        String xfp = item.getString("masterFingerprint");
                        String rootXfp = new GetMasterFingerprintCallable().call();
                        if (xfp.equalsIgnoreCase(rootXfp)) {
                            if (!hdPath.startsWith(wallet.getExPubPath())) {
                                hdPath = wallet.getExPubPath() + hdPath.substring(1);
                            }
                            out.put("isChange", true);
                            out.put("changeAddressPath", hdPath);
                            break;
                        }
                    }
                }
                outputs.put(out);
            }
        }
    }
}
