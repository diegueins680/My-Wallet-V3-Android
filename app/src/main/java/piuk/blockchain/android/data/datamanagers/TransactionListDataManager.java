package piuk.blockchain.android.data.datamanagers;

import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.Account;
import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.payload.Transaction;
import info.blockchain.wallet.payload.Tx;
import info.blockchain.wallet.payload.TxMostRecentDateComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.data.services.TransactionDetailsService;
import piuk.blockchain.android.data.stores.TransactionListStore;
import piuk.blockchain.android.util.ListUtil;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

public class TransactionListDataManager {

    private final static String TAG_ALL = "TAG_ALL";
    private final static String TAG_IMPORTED_ADDRESSES = "TAG_IMPORTED_ADDRESSES";
    private PayloadManager payloadManager;
    private TransactionDetailsService transactionDetails;
    private TransactionListStore transactionListStore;
    private Subject<List<Tx>, List<Tx>> listUpdateSubject;

    public TransactionListDataManager(PayloadManager payloadManager,
                                      TransactionDetailsService transactionDetails,
                                      TransactionListStore transactionListStore) {
        this.payloadManager = payloadManager;
        this.transactionDetails = transactionDetails;
        this.transactionListStore = transactionListStore;
        listUpdateSubject = PublishSubject.create();
    }

    /**
     * Generates a list of transactions for a specific {@link Account} or {@link LegacyAddress}.
     * Will throw an exception if the object passed isn't either of the two types. The list will be
     * sorted by date.
     *
     * @param object Either a {@link Account} or a {@link LegacyAddress}
     */
    public void generateTransactionList(Object object) {
        if (object instanceof Account) {
            // V3
            transactionListStore.insertTransactions(getV3Transactions((Account) object));
        } else if (object instanceof LegacyAddress) {
            // V2
            transactionListStore.insertTransactions(MultiAddrFactory.getInstance().getAddressLegacyTxs(((LegacyAddress) object).getAddress()));
        } else {
            throw new IllegalArgumentException("Object must be instance of Account.class or LegacyAddress.class");
        }

        Collections.sort(transactionListStore.getList(), new TxMostRecentDateComparator());
        listUpdateSubject.onNext(transactionListStore.getList());
        listUpdateSubject.onCompleted();
    }

    /**
     * Returns a list of {@link Tx} objects generated by {@link #getTransactionList()}
     *
     * @return A list of Txs sorted by date.
     */
    @NonNull
    public List<Tx> getTransactionList() {
        return transactionListStore.getList();
    }

    /**
     * Resets the list of Transactions.
     */
    public void clearTransactionList() {
        transactionListStore.clearList();
    }

    /**
     * Allows insertion of a single new {@link Tx} into the main transaction list.
     *
     * @param transaction A new, most likely temporary {@link Tx}
     * @return An updated list of Txs sorted by date
     */
    @NonNull
    public List<Tx> insertTransactionIntoListAndReturnSorted(Tx transaction) {
        transactionListStore.insertTransactionIntoListAndSort(transaction);
        return transactionListStore.getList();
    }

    /**
     * Returns a subject that lets ViewModels subscribe to changes in the transaction list - specifically
     * this subject will return the transaction list when it's first updated and then call onCompleted()
     *
     * @return  The list of transactions after initial sync
     */
    public Subject<List<Tx>, List<Tx>> getListUpdateSubject() {
        return listUpdateSubject;
    }

    /**
     * Get total BTC balance from an {@link Account} or {@link LegacyAddress}. Will throw an
     * exception if the object passed isn't either of the two types.
     *
     * @param object Either a {@link Account} or a {@link LegacyAddress}
     * @return A BTC value as a double.
     */
    public double getBtcBalance(Object object) {
        // Update Balance
        double balance = 0D;
        if (object instanceof Account) {
            // V3
            Account account = ((Account) object);
            // V3 - All
            if (account.getTags().contains(TAG_ALL)) {
                if (payloadManager.getPayload().isUpgraded()) {
                    // Balance = all xpubs + all legacy address balances
                    balance = ((double) MultiAddrFactory.getInstance().getXpubBalance())
                            + ((double) MultiAddrFactory.getInstance().getLegacyActiveBalance());
                } else {
                    // Balance = all legacy address balances
                    balance = ((double) MultiAddrFactory.getInstance().getLegacyActiveBalance());
                }
            } else if (account.getTags().contains(TAG_IMPORTED_ADDRESSES)) {
                balance = ((double) MultiAddrFactory.getInstance().getLegacyActiveBalance());
            } else {
                // V3 - Individual
                if (MultiAddrFactory.getInstance().getXpubAmounts().containsKey(account.getXpub())) {
                    HashMap<String, Long> xpubAmounts = MultiAddrFactory.getInstance().getXpubAmounts();
                    Long bal = (xpubAmounts.get(account.getXpub()) == null ? 0L : xpubAmounts.get(account.getXpub()));
                    balance = ((double) (bal));
                }
            }
        } else if (object instanceof LegacyAddress) {
            // V2
            LegacyAddress legacyAddress = ((LegacyAddress) object);
            balance = MultiAddrFactory.getInstance().getLegacyBalance(legacyAddress.getAddress());
        } else {
            throw new IllegalArgumentException("Object must be instance of Account.class or LegacyAddress.class");
        }

        return balance;
    }

    /**
     * Get a specific {@link Transaction} from a {@link Tx} hash.
     *
     * @param transactionHash The hash of the transaction to be returned
     * @return A Transaction object
     */
    public Observable<Transaction> getTransactionFromHash(String transactionHash) {
        return transactionDetails.getTransactionDetailsFromHash(transactionHash);
    }

    /**
     * Update notes for a specific transaction hash and then sync the payload to the server
     *
     * @param transactionHash The hash of the transaction to be updated
     * @param notes           Transaction notes
     * @return If save was successful
     */
    public Observable<Boolean> updateTransactionNotes(String transactionHash, String notes) {
        payloadManager.getPayload().getNotes().put(transactionHash, notes);
        return Observable.fromCallable(() -> payloadManager.savePayloadToServer())
                .compose(RxUtil.applySchedulers());
    }

    private List<Tx> getV3Transactions(Account account) {
        List<Tx> transactions = new ArrayList<>();

        if (account.getTags().contains(TAG_ALL)) {
            if (payloadManager.getPayload().isUpgraded()) {
                transactions.addAll(getAllXpubAndLegacyTxs());
            } else {
                transactions.addAll(MultiAddrFactory.getInstance().getLegacyTxs());
            }

        } else if (account.getTags().contains(TAG_IMPORTED_ADDRESSES)) {
            // V3 - Imported Addresses
            transactions.addAll(MultiAddrFactory.getInstance().getLegacyTxs());
        } else {
            // V3 - Individual
            String xpub = account.getXpub();
            if (MultiAddrFactory.getInstance().getXpubAmounts().containsKey(xpub)) {
                ListUtil.addAllIfNotNull(transactions, MultiAddrFactory.getInstance().getXpubTxs().get(xpub));
            }
        }

        return transactions;
    }

    @VisibleForTesting
    @NonNull
    List<Tx> getAllXpubAndLegacyTxs() {
        // Remove duplicate txs
        HashMap<String, Tx> consolidatedTxsList = new HashMap<>();

        List<Tx> allXpubTransactions = MultiAddrFactory.getInstance().getAllXpubTxs();
        for (Tx tx : allXpubTransactions) {
            if (!consolidatedTxsList.containsKey(tx.getHash()))
                consolidatedTxsList.put(tx.getHash(), tx);
        }

        List<Tx> allLegacyTransactions = MultiAddrFactory.getInstance().getLegacyTxs();
        for (Tx tx : allLegacyTransactions) {
            if (!consolidatedTxsList.containsKey(tx.getHash()))
                consolidatedTxsList.put(tx.getHash(), tx);
        }

        return new ArrayList<>(consolidatedTxsList.values());
    }
}
