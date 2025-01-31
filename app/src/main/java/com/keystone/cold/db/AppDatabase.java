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

package com.keystone.cold.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.keystone.cold.AppExecutors;
import com.keystone.cold.db.dao.AccountDao;
import com.keystone.cold.db.dao.AddressDao;
import com.keystone.cold.db.dao.CasaDao;
import com.keystone.cold.db.dao.CoinDao;
import com.keystone.cold.db.dao.MultiSigAddressDao;
import com.keystone.cold.db.dao.MultiSigWalletDao;
import com.keystone.cold.db.dao.TxDao;
import com.keystone.cold.db.dao.WhiteListDao;
import com.keystone.cold.db.entity.AccountEntity;
import com.keystone.cold.db.entity.AddressEntity;
import com.keystone.cold.db.entity.CasaSignature;
import com.keystone.cold.db.entity.CoinEntity;
import com.keystone.cold.db.entity.MultiSigAddressEntity;
import com.keystone.cold.db.entity.MultiSigWalletEntity;
import com.keystone.cold.db.entity.TxEntity;
import com.keystone.cold.db.entity.WhiteListEntity;

@Database(entities = {CoinEntity.class, AddressEntity.class,
        TxEntity.class, WhiteListEntity.class,
        AccountEntity.class, MultiSigWalletEntity.class,
        MultiSigAddressEntity.class, CasaSignature.class}, version = 6)
public abstract class AppDatabase extends RoomDatabase {
    private static final String DATABASE_NAME = "keystone-db";
    private static AppDatabase sInstance;

    public abstract CoinDao coinDao();

    public abstract AddressDao addressDao();

    public abstract TxDao txDao();

    public abstract CasaDao casaDao();

    public abstract WhiteListDao whiteListDao();

    public abstract AccountDao accountDao();

    public abstract MultiSigAddressDao multiSigAddressDao();

    public abstract MultiSigWalletDao multiSigWalletDao();

    private final MutableLiveData<Boolean> mIsDatabaseCreated = new MutableLiveData<>();

    public static AppDatabase getInstance(final Context context, final AppExecutors executors) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = buildDatabase(context.getApplicationContext(), executors);
                    sInstance.updateDatabaseCreated(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private static AppDatabase buildDatabase(final Context appContext,
                                             final AppExecutors executors) {

        return Room.databaseBuilder(appContext, AppDatabase.class, DATABASE_NAME)
                .addCallback(new Callback() {
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                        super.onCreate(db);
                        executors.diskIO().execute(() -> {
                            // Generate the data for pre-population
                            AppDatabase database = AppDatabase.getInstance(appContext, executors);
                            // notify that the database was created and it's ready to be used
                            database.setDatabaseCreated();
                        });
                    }
                })
                .addMigrations(MIGRATION_1_5)
                .addMigrations(MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build();
    }

    private static final Migration MIGRATION_1_5 = new Migration(1, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.beginTransaction();
            try {
                database.execSQL("DELETE FROM coins WHERE coinCode != 'BTC'");
                database.execSQL("ALTER TABLE txs ADD COLUMN signStatus TEXT");
                database.execSQL("CREATE TABLE IF NOT EXISTS `multi_sig_wallet` " +
                        "(`walletFingerPrint` TEXT PRIMARY KEY NOT NULL, " +
                        "`walletName` TEXT, " +
                        "`threshold` INTEGER NOT NULL, " +
                        "`total` INTEGER NOT NULL, " +
                        "`exPubPath` TEXT NOT NULL, " +
                        "`exPubs` TEXT NOT NULL , " +
                        "`belongTo` TEXT NOT NULL , " +
                        "`verifyCode` TEXT NOT NULL , " +
                        "`creator` TEXT NOT NULL default '' , " +
                        "`network` TEXT NOT NULL)");
                database.execSQL("CREATE INDEX index_multi_sig_wallet_walletFingerPrint ON multi_sig_wallet (walletFingerPrint)");
                database.execSQL("CREATE TABLE IF NOT EXISTS `multi_sig_address` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`address` TEXT NOT NULL, " +
                        "`index` INTEGER NOT NULL, " +
                        "`walletFingerPrint` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`changeIndex` INTEGER NOT NULL, " +
                        "`name` TEXT, " +
                        "FOREIGN KEY(`walletFingerPrint`) REFERENCES `multi_sig_wallet`(`walletFingerPrint`) ON UPDATE NO ACTION ON DELETE CASCADE )");
                database.execSQL("CREATE UNIQUE INDEX index_multi_sig_address_id ON multi_sig_address (id)");
                database.execSQL("CREATE INDEX index_multi_sig_address_walletFingerPrint ON multi_sig_address (walletFingerPrint)");
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    };
    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.beginTransaction();
            try {
                database.execSQL("CREATE TABLE IF NOT EXISTS `casa_signature` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`txId` TEXT, " +
                        "`signedHex` TEXT, " +
                        "`signStatus` TEXT , " +
                        "`amount` TEXT , " +
                        "`from` TEXT  , " +
                        "`to` TEXT  , " +
                        "`fee` TEXT  , " +
                        "`memo` TEXT )");
                database.execSQL("CREATE UNIQUE INDEX index_casa_signature_id ON casa_signature (id)");
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    };

    private void updateDatabaseCreated(final Context context) {
        if (context.getDatabasePath(DATABASE_NAME).exists()) {
            setDatabaseCreated();
        }
    }

    private void setDatabaseCreated() {
        mIsDatabaseCreated.postValue(true);
    }

    public LiveData<Boolean> getDatabaseCreated() {
        return mIsDatabaseCreated;
    }
}
