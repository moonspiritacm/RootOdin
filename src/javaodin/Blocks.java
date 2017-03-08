package javaodin;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.H2FullPrunedBlockStore;
import org.bitcoinj.store.UnreadableWalletException;

public class Blocks implements Runnable {

    public NetworkParameters params;
    public Logger logger = LoggerFactory.getLogger(Blocks.class);
    private static Blocks instance = null;
    public Wallet wallet;
    public String walletFile = "resources/db/wallet";
    public PeerGroup peerGroup;
    public BlockChain blockChain;
    public BlockStore blockStore;
    public Boolean working = false;
    public Boolean parsing = false;
    public Boolean initializing = false;
    public Boolean initialized = false;
    public Integer parsingBlock = 0;
    public Integer versionCheck = 0;
    public Integer bitcoinBlock = 0;
    public Integer ppkBlock = 0;
    public String statusMessage = "";

    private static String lastTransctionSource = null;
    private static String lastTransctionDestination = null;
    private static BigInteger lastTransctionBtcAmount = null;
    private static BigInteger lastTransctionFee = null;
    private static String lastTransctionDataString = null;

    /**
     * 获取当前区块对象，忽略版本检查;1次调用:PPkTool.java Line17
     *
     * @return
     */
    public static Blocks getInstanceSkipVersionCheck() {
        if (instance == null) {
            instance = new Blocks();
        }
        return instance;
    }

    /**
     * 获取当前区块对象，同时进行版本检查;2次调用:GUI.java Line105、NoGUI.java Line22
     *
     * @return
     */
    public static Blocks getInstanceFresh() {
        if (instance == null) {
            instance = new Blocks();
            instance.versionCheck();
        }
        return instance;
    }

    /**
     * 0次调用
     *
     * @return
     */
    public static Blocks getInstanceAndWait() {
        if (instance == null) {
            instance = new Blocks();
            instance.versionCheck();
            new Thread() {
                public void run() {
                    instance.init();
                }
            }.start();
        }
        instance.follow();
        return instance;
    }

    /**
     * 获得当前区块对象，创建新线程;25次调用
     *
     * @return
     */
    public static Blocks getInstance() {
        //为空，则创建新区块对象，进行版本检查，新建线程执行init()
        if (instance == null) {
            instance = new Blocks();
            instance.versionCheck();
            new Thread() {
                public void run() {
                    instance.init();
                }
            }.start();
        }
        //对象初始化完成且未处于工作状态，新建线程执行follow()
        if (!instance.working && instance.initialized) {
            new Thread() {
                public void run() {
                    instance.follow();
                }
            }.start();
        }
        return instance;
    }

    /**
     * 版本检查，默认关闭自动更新;5次调用
     */
    public void versionCheck() {
        versionCheck(false);
    }

    /**
     * 版本检查;2次调用
     *
     * @param autoUpdate
     */
    public void versionCheck(Boolean autoUpdate) {
        Integer minMajorVersion = Util.getMinMajorVersion();
        Integer minMinorVersion = Util.getMinMinorVersion();
        if (Config.majorVersion < minMajorVersion || (Config.majorVersion.equals(minMajorVersion) && Config.minorVersion < minMinorVersion)) {
            if (autoUpdate) {
                statusMessage = "Version is out of date, updating now";
                logger.info(statusMessage);
                try {
                    Runtime.getRuntime().exec("java -jar update/update.jar");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } else {
                logger.info("Version is out of date. Please upgrade to version " + Util.getMinVersion() + ".");
            }
            System.exit(0);
        }
    }

    @Override
    public void run() {
        while (true) {
            logger.info("Looping blocks");
            Blocks.getInstance();
            try {
                Thread.sleep(1000 * 60); //once a minute, we run blocks.follow()
            } catch (InterruptedException e) {
                logger.error("Error during loop: " + e.toString());
            }
        }
    }

    public void init() {
        if (!initializing) {
            initializing = true;

            params = MainNetParams.get();

            try {
                if ((new File(walletFile)).exists()) {  //钱包已存在
                    logger.info("Found wallet file");
                    wallet = Wallet.loadFromFile(new File(walletFile));
                } else {  //钱包不存在
                    logger.info("Creating new wallet file");
                    wallet = new Wallet(params);
                    ECKey newKey = new ECKey();
                    newKey.setCreationTimeSeconds(Config.ppkToolCreationTime);
                    wallet.addKey(newKey);
                }
                String fileODINdb = Database.dbFile;  //
                if (!new File(fileODINdb).exists()) {  //数据库文件不存在
                    logger.info("Downloading ODIN database");
                    Util.downloadToFile(Config.downloadUrl + Config.appName.toLowerCase() + "-" + Config.majorVersionDB.toString() + ".db", fileODINdb);
                }
                statusMessage = "Downloading Bitcoin blocks";
                blockStore = new H2FullPrunedBlockStore(params, Config.dbPath + Config.appName.toLowerCase(), 2000);
                blockChain = new BlockChain(params, wallet, blockStore);
                peerGroup = new PeerGroup(params, blockChain);
                peerGroup.addWallet(wallet);
                peerGroup.setFastCatchupTimeSecs(Config.ppkToolCreationTime);
                wallet.autosaveToFile(new File(walletFile), 1, TimeUnit.MINUTES, null);
                peerGroup.addPeerDiscovery(new DnsDiscovery(params));
                peerGroup.start();//peerGroup.startAndWait(); //for bitcoinj0.14
                peerGroup.addEventListener(new PPkPeerEventListener());
                peerGroup.downloadBlockChain();
                while (!hasChainHead()) {
                    try {
                        logger.info("Blockstore doesn't yet have a chain head, so we are sleeping.");
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }

                Database db = Database.getInstance();
                try {
                    Integer lastParsedBlock = Util.getLastParsedBlock();
                    if (lastParsedBlock.equals(0)) {
                        db.executeUpdate("CREATE TABLE IF NOT EXISTS sys_parameters (para_name VARCHAR(32) PRIMARY KEY, para_value TEXT )");
                        lastParsedBlock = Util.getLastBlock();
                        Util.updateLastParsedBlock(lastParsedBlock);
                    }
                } catch (Exception e) {
                    logger.error(e.toString());
                }
                Odin.init();
            } catch (BlockStoreException | UnreadableWalletException e) {
                logger.error("Error during init: " + e.toString());
                e.printStackTrace();
                deleteDatabases();
                initialized = false;
                initializing = false;
                init();
            }
            initialized = true;
            initializing = false;
        }

    }

    public void deleteDatabases() {
        logger.info("Deleting Bitcoin and ODIN databases");
        String fileBTCdb = Config.dbPath + Config.appName.toLowerCase() + ".h2.db";
        new File(fileBTCdb).delete();
        String fileODINdb = Database.dbFile;
        new File(fileODINdb).delete();
    }

    public Boolean hasChainHead() {
        try {
            Integer blockHeight = blockStore.getChainHead().getHeight();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void follow() {
        follow(false);
    }

    public void follow(Boolean force) {
        logger.info("Working status: " + working);
        if ((!working && initialized) || force) {
            statusMessage = "Checking block height";
            logger.info(statusMessage);
            if (!force) {
                working = true;
            }
            try {
                //catch ODIN up to Bitcoin
                Integer blockHeight = blockStore.getChainHead().getHeight();
                Integer lastBlock = Util.getLastBlock();
                Integer lastBlockTime = Util.getLastBlockTimestamp();

                bitcoinBlock = blockHeight;
                ppkBlock = lastBlock;

                if (lastBlock == 0) {
                    lastBlock = Config.firstBlock - 1;
                }
                Integer nextBlock = lastBlock + 1;

                logger.info("Bitcoin block height: " + blockHeight);
                logger.info("PPk block height: " + lastBlock);
                if (lastBlock < blockHeight) {
                    //traverse new blocks
                    parsing = true;
                    Integer blocksToScan = blockHeight - lastBlock;
                    List<Sha256Hash> blockHashes = new ArrayList<Sha256Hash>();

                    Block block = peerGroup.getDownloadPeer().getBlock(blockStore.getChainHead().getHeader().getHash()).get(59, TimeUnit.SECONDS);
                    while (blockStore.get(block.getHash()).getHeight() > lastBlock) {
                        blockHashes.add(block.getHash());
                        block = blockStore.get(block.getPrevBlockHash()).getHeader();
                    }
                    for (int i = blockHashes.size() - 1; i >= 0; i--) { //traverse blocks in reverse order
                        block = peerGroup.getDownloadPeer().getBlock(blockHashes.get(i)).get(59, TimeUnit.SECONDS);
                        blockHeight = blockStore.get(block.getHash()).getHeight();
                        ppkBlock = blockHeight;
                        statusMessage = "Catching ODIN up to Bitcoin " + Util.format((blockHashes.size() - i) / ((double) blockHashes.size()) * 100.0) + "%";
                        logger.info("Catching ODIN up to Bitcoin (block " + blockHeight.toString() + "): " + Util.format((blockHashes.size() - i) / ((double) blockHashes.size()) * 100.0) + "%");
                        importBlock(block, blockHeight);
                    }

                    parsing = false;
                }
            } catch (InterruptedException | ExecutionException | TimeoutException | BlockStoreException e) {
                logger.error("Error during follow: " + e.toString());
                e.printStackTrace();
            }

            //Ensure to parse new imported blocks while follow finished or failed
            try {
                Integer lastImportedBlock = Util.getLastBlock();
                Integer lastImportedBlockTime = Util.getLastBlockTimestamp();
                Integer lastParsedBlock = Util.getLastParsedBlock();
                if (lastParsedBlock < lastImportedBlock) {
                    parsing = true;
                    if (getDBMinorVersion() < Config.minorVersionDB) {
                        reparse(true);
                        Database db = Database.getInstance();
                        db.updateMinorVersion();
                    } else {
                        parseFrom(lastParsedBlock + 1, true);
                    }
                    parsing = false;
                }
            } catch (Exception e) {
                logger.error("Error during parse: " + e.toString());
                e.printStackTrace();
            }

            if (!force) {
                working = false;
            }
        }
    }

    public void importBlock(Block block, Integer blockHeight) {
        statusMessage = "Importing block " + blockHeight;
        logger.info(statusMessage);
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("select * from blocks where block_hash='" + block.getHashAsString() + "';");
        try {
            if (!rs.next()) {
                db.executeUpdate("INSERT INTO blocks(block_index,block_hash,block_time,block_nonce) VALUES('" + blockHeight.toString() + "','" + block.getHashAsString() + "','" + block.getTimeSeconds() + "','" + block.getNonce() + "')");
            }
            Integer txSnInBlock = 0;
            for (Transaction tx : block.getTransactions()) {
                importPPkTransaction(tx, txSnInBlock, block, blockHeight);
                txSnInBlock++;
            }
        } catch (SQLException e) {
        }
    }

    public void reparse() {
        reparse(false);
    }

    public void reparse(final Boolean force) {
        Database db = Database.getInstance();
        db.executeUpdate("delete from odins;");
        db.executeUpdate("delete from odin_update_logs;");
        db.executeUpdate("delete from balances;");
        db.executeUpdate("delete from sends;");
        db.executeUpdate("delete from messages;");
        db.executeUpdate("delete from sys_parameters;");
        new Thread() {
            public void run() {
                parseFrom(Config.firstBlock, force);
            }
        }.start();
    }

    public void parseFrom(Integer blockNumber) {
        parseFrom(blockNumber, false);
    }

    public void parseFrom(Integer blockNumber, Boolean force) {
        if (!working || force) {
            parsing = true;
            if (!force) {
                working = true;
            }
            Database db = Database.getInstance();
            ResultSet rs = db.executeQuery("select * from blocks where block_index>=" + blockNumber.toString() + " order by block_index asc;");
            try {
                while (rs.next()) {
                    Integer blockIndex = rs.getInt("block_index");
                    Integer blockTime = rs.getInt("block_time");  //Added for POS
                    parseBlock(blockIndex, blockTime);

                    Util.updateLastParsedBlock(blockIndex);
                }
            } catch (SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            if (!force) {
                working = false;
            }
            parsing = false;
        }
    }

    public List<Byte> getMessageFromTransaction(String txDataString) {
        byte[] data;
        List<Byte> message = null;
        try {
            data = txDataString.getBytes(Config.BINARY_DATA_CHARSET);
            List<Byte> dataArrayList = Util.toByteArrayList(data);

            message = dataArrayList.subList(4, dataArrayList.size());
            return message;
        } catch (UnsupportedEncodingException e) {
        }
        return message;
    }

    public List<Byte> getMessageTypeFromTransaction(String txDataString) {
        byte[] data;
        List<Byte> messageType = null;
        try {
            data = txDataString.getBytes(Config.BINARY_DATA_CHARSET);
            List<Byte> dataArrayList = Util.toByteArrayList(data);

            messageType = dataArrayList.subList(0, 4);
            return messageType;
        } catch (UnsupportedEncodingException e) {
        }
        return messageType;
    }

    public void parseBlock(Integer blockIndex, Integer blockTime) {
        Database db = Database.getInstance();
        ResultSet rsTx = db.executeQuery("select * from transactions where block_index=" + blockIndex.toString() + " order by tx_index asc;");
        parsingBlock = blockIndex;
        statusMessage = "\n++++++++++++++++++++++++++++++++++\n Parsing block " + blockIndex.toString() + "\n++++++++++++++++++++++++++++++++++\n";
        logger.info(statusMessage);
        try {
            while (rsTx.next()) {
                Integer txIndex = rsTx.getInt("tx_index");
                String source = rsTx.getString("source");
                String destination = rsTx.getString("destination");
                BigInteger btcAmount = BigInteger.valueOf(rsTx.getInt("btc_amount"));
                String dataString = rsTx.getString("data");
                Integer prefix_type = rsTx.getInt("prefix_type");

                if (1 == prefix_type) { //PPk ODIN
                    Byte messageType = getPPkMessageTypeFromTransaction(dataString);
                    List<Byte> message = getPPkMessageFromTransaction(dataString);
                    logger.info("\n--------------------\n Parsing PPk txIndex " + txIndex.toString() + "\n------------\n");

                    if (messageType != null && message != null) {
                        logger.info("\n--------------------\n Parsing PPk messageType " + messageType.toString() + "\n------------\n");
                        if (messageType == Odin.id) {
                            Odin.parse(txIndex, message);
                        } else if (messageType == OdinUpdate.id) {
                            OdinUpdate.parse(txIndex, message);
                        }
                    }
                } else { //normal bitcoin operation

                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Integer getDBMinorVersion() {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("PRAGMA user_version;");
        try {
            while (rs.next()) {
                return rs.getInt("user_version");
            }
        } catch (SQLException e) {
        }
        return 0;
    }

    public void updateMinorVersion() {
        // Update minor version
        Database db = Database.getInstance();
        db.executeUpdate("PRAGMA user_version = " + Config.minorVersionDB.toString());
    }

    public Integer getHeight() {
        try {
            Integer height = blockStore.getChainHead().getHeight();
            return height;
        } catch (BlockStoreException e) {
        }
        return 0;
    }

    public boolean importPPkTransaction(Transaction tx, Integer txSnInBlock, Block block, Integer blockHeight) {
        BigInteger fee = BigInteger.ZERO;
        String destination = "";
        BigInteger btcAmount = BigInteger.ZERO;
        List<Byte> dataArrayList = new ArrayList<Byte>();
        byte[] data = null;
        String source = "";

        Database db = Database.getInstance();

        boolean matched_ppk_odin_prefix = false;

        for (TransactionOutput out : tx.getOutputs()) {
            try {
                Script script = out.getScriptPubKey();
                List<ScriptChunk> asm = script.getChunks();
                int asm_num = asm.size();

                boolean isFirstMultiSigTx = false;
                if (asm_num >= 5 && asm.get(0).equalsOpCode(0x51) && asm.get(asm_num - 2).isOpCode() && asm.get(asm_num - 1).equalsOpCode(0xAE)) { //MULTISIG
                    int multisig_n = asm.get(asm_num - 2).decodeOpN();

                    if (!matched_ppk_odin_prefix) {
                        if (asm.get(2).data.length == Config.PPK_ODIN_MARK_PUBKEY_HEX.length() / 2) {
                            String tmp_pubkey_hex = Util.bytesToHexString(asm.get(2).data);

                            if (Config.PPK_ODIN_MARK_PUBKEY_HEX.equals(tmp_pubkey_hex)) {
                                matched_ppk_odin_prefix = true;
                                isFirstMultiSigTx = true;
                            }
                        }
                    }

                    if (matched_ppk_odin_prefix) {
                        int from = isFirstMultiSigTx ? 3 : 2;
                        for (; from < multisig_n + 1; from++) {
                            byte[] tmp_data = asm.get(from).data;
                            byte embed_data_len = tmp_data[1];
                            if (embed_data_len > 0 && embed_data_len <= tmp_data.length - 2) {
                                for (byte i = 0; i < embed_data_len; i++) {
                                    dataArrayList.add(tmp_data[2 + i]);
                                }
                            }
                        }
                    }
                } else if (matched_ppk_odin_prefix && asm.get(0).equalsOpCode(0x6A)) {  //OP_RETURN
                    System.out.println("asm_num=" + asm_num + "  " + asm.toString());

                    for (int i = 0; i < asm.get(1).data.length; i++) {
                        dataArrayList.add(asm.get(1).data[i]);
                    }
                }

                if (destination.equals("") && btcAmount == BigInteger.ZERO && dataArrayList.size() == 0) {
                    Address address = script.getToAddress(params);
                    destination = address.toString();
                    btcAmount = BigInteger.valueOf(out.getValue().getValue());
                }
            } catch (ScriptException e) {
            }
        }

        if (dataArrayList.size() > 0) {
            data = Util.toByteArray(dataArrayList);  //截取特征前缀后的有效字节数据
        } else {
            return false;
        }

        for (TransactionInput in : tx.getInputs()) {
            if (in.isCoinBase()) {
                return false;
            }
            try {
                Script script = in.getScriptSig();
                Address address = script.getFromAddress(params);
                if (source.equals("")) {
                    source = address.toString();
                } else if (!source.equals(address.toString())) { //require all sources to be the same
                    return false;
                }
            } catch (ScriptException e) {
            }
        }

        logger.info("Incoming PPk transaction from " + source + " to " + destination + " (" + tx.getHashAsString() + ")");

        if (!source.equals("") && dataArrayList.size() > 0) {
            String dataString = "";
            try {
                dataString = new String(data, Config.BINARY_DATA_CHARSET);
                logger.info("PPk dataString : [" + dataString + "] length=" + dataString.length());
            } catch (UnsupportedEncodingException e) {
            }
            db.executeUpdate("delete from transactions where tx_hash='" + tx.getHashAsString() + "' and block_index<0");
            ResultSet rs = db.executeQuery("select * from transactions where tx_hash='" + tx.getHashAsString() + "';");
            try {
                if (!rs.next()) {
                    if (block != null) {
                        Integer newTxIndex = Util.getLastTxIndex() + 1;
                        PreparedStatement ps = db.connection.prepareStatement("INSERT INTO transactions(tx_index, tx_hash, block_index, block_time, source, destination, btc_amount, fee, data,prefix_type,sn_in_block) VALUES('" + newTxIndex + "','" + tx.getHashAsString() + "','" + blockHeight + "','" + block.getTimeSeconds() + "','" + source + "','" + destination + "','" + btcAmount.toString() + "','" + fee.toString() + "',?,1,'" + txSnInBlock.toString() + "')");
                        ps.setString(1, dataString);
                        ps.execute();
                    } else {
                        PreparedStatement ps = db.connection.prepareStatement("INSERT INTO transactions(tx_index, tx_hash, block_index, block_time, source, destination, btc_amount, fee, data,prefix_type,sn_in_block) VALUES('" + (Util.getLastTxIndex() + 1) + "','" + tx.getHashAsString() + "','-1','" + Util.getNowTimestamp() + "','" + source + "','" + destination + "','" + btcAmount.toString() + "','" + fee.toString() + "',?,1,-1)");
                        ps.setString(1, dataString);
                        ps.execute();
                    }

                }
            } catch (SQLException e) {
                logger.error(e.toString());
            }
        }

        return true;
    }

    public static Byte getPPkMessageTypeFromTransaction(String txDataString) {
        byte[] data;
        Byte messageType = null;
        try {
            data = txDataString.getBytes(Config.BINARY_DATA_CHARSET);
            messageType = data[0];
            return messageType;
        } catch (UnsupportedEncodingException e) {
        }
        return messageType;
    }

    public static List<Byte> getPPkMessageFromTransaction(String txDataString) {
        byte[] data;
        List<Byte> message = null;
        try {
            data = txDataString.getBytes(Config.BINARY_DATA_CHARSET);
            List<Byte> dataArrayList = Util.toByteArrayList(data);

            message = dataArrayList.subList(1, dataArrayList.size());
            return message;
        } catch (UnsupportedEncodingException e) {
        }
        return message;
    }
}
