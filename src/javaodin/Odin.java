package javaodin;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.JSONObject;

class OdinInfo {

    public String fullOdin;
    public Integer shortOdin;
    public Integer txIndex;
    public String register;
    public String admin;
    public String txHash;
    public Integer blockIndex;
    public Integer blockTime;
    public Integer txSnInBlock;
    public String validity;
    public JSONObject odinSet;
}

public class Odin {

    static Logger logger = LoggerFactory.getLogger(Odin.class);
    public static Byte id = Config.FUNC_ID_ODIN_REGIST; //for registing new ODIN 

    public static void init() {
        createTables(null);
    }

    public static void createTables(Database db) {
        if (db == null) {
            db = Database.getInstance();
        }
        try {
            db.executeUpdate("CREATE TABLE IF NOT EXISTS odins (tx_index INTEGER PRIMARY KEY, tx_hash TEXT UNIQUE,block_index INTEGER,full_odin TEXT UNIQUE,short_odin INTEGER UNIQUE , register TEXT, admin TEXT,odin_set TEXT, validity TEXT)");
            db.executeUpdate("CREATE INDEX IF NOT EXISTS block_index_idx ON odins (block_index)");
            db.executeUpdate("CREATE INDEX IF NOT EXISTS tx_index ON odins (tx_index)");
            db.executeUpdate("CREATE INDEX IF NOT EXISTS full_odin ON odins (full_odin)");
            db.executeUpdate("CREATE INDEX IF NOT EXISTS short_odin ON odins (short_odin)");

            db.executeUpdate("CREATE TABLE IF NOT EXISTS odin_update_logs (log_id TEXT, tx_index INTEGER PRIMARY KEY,block_index INTEGER,full_odin TEXT, updater TEXT,destination TEXT,update_set TEXT, validity TEXT,required_confirmer TEXT);");
            db.executeUpdate("CREATE INDEX IF NOT EXISTS logid_idx ON odin_update_logs (log_id);");
            db.executeUpdate("CREATE INDEX IF NOT EXISTS odin_idx ON odin_update_logs (full_odin);");
        } catch (Exception e) {
            logger.error(e.toString());
        }
    }

    public static void parse(Integer txIndex, List<Byte> message) {
        logger.info("\n=============================\n Parsing ODIN txIndex=" + txIndex.toString() + "\n=====================\n");

        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("SELECT * FROM transactions tx  WHERE tx.tx_index=" + txIndex.toString());
        try {
            if (rs.next()) {
                String source = rs.getString("source");
                String destination = rs.getString("destination");
                BigInteger btcAmount = BigInteger.valueOf(rs.getLong("btc_amount"));
                BigInteger fee = BigInteger.valueOf(rs.getLong("fee"));
                Integer blockIndex = rs.getInt("block_index");
                Integer blockTime = rs.getInt("block_time");
                String txHash = rs.getString("tx_hash");
                Integer txSnInBlock = rs.getInt("sn_in_block");
                String full_odin = blockIndex + "." + txSnInBlock;
                Integer short_odin = getLastShortOdin() + 1;

                ResultSet rsCheck = db.executeQuery("select * from odins where tx_index='" + txIndex.toString() + "'");
                if (rsCheck.next()) {
                    return;
                }
                String validity = "invalid";
                JSONObject odin_set = new JSONObject();
                String odin_set_admin = destination.length() == 0 ? source : destination;

                if (message.size() > 2) {
                    ByteBuffer byteBuffer = ByteBuffer.allocate(message.size());
                    for (byte b : message) {
                        byteBuffer.put(b);
                    }
                    Byte odin_set_data_type = byteBuffer.get(0);
                    BitcoinVarint odin_set_len_varint = BitcoinVarint.getFromBuffer(byteBuffer, 1);
                    int odin_set_length = odin_set_len_varint.intValue();
                    int odin_set_start = 1 + odin_set_len_varint.size();
                    logger.info("\n=============================\n message.size()=" + message.size() + ",odin_set_start=" + odin_set_start + ",odin_set_length=" + odin_set_length + "\n=====================\n");

                    if (!source.equals("") && message.size() == odin_set_start + odin_set_length) {
                        validity = "valid";

                        byte[] odin_set_byte_array = new byte[odin_set_length];

                        for (int off = 0; off < odin_set_length; off++) {
                            odin_set_byte_array[off] = byteBuffer.get(odin_set_start + off);
                        }

                        try {
                            if (odin_set_data_type == Config.DATA_BIN_GZIP) {
                                odin_set = new JSONObject(Util.uncompress(new String(odin_set_byte_array, Config.BINARY_DATA_CHARSET)));
                            } else {
                                odin_set = new JSONObject(new String(odin_set_byte_array, Config.PPK_TEXT_CHARSET));
                            }

                            logger.info("\n=============================\n odin_set=" + odin_set.toString() + "\n=====================\n");
                        } catch (Exception e) {
                            odin_set = new JSONObject();
                            logger.error(e.toString());
                        }

                    }
                }

                PreparedStatement ps = db.connection.prepareStatement("insert into odins(tx_index, tx_hash, block_index, full_odin,short_odin,admin, register,odin_set,validity) values('" + txIndex.toString() + "','" + txHash + "','" + blockIndex.toString() + "','" + full_odin + "','" + short_odin.toString() + "',?,'" + source + "',?,'" + validity + "');");

                ps.setString(1, odin_set_admin);
                ps.setString(2, odin_set.toString());
                ps.execute();
            }
        } catch (SQLException e) {
            logger.error(e.toString());
        }
    }

    public static OdinInfo getOdinInfo(String odin) {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("select cp.full_odin,cp.short_odin,cp.register,cp.admin ,cp.tx_hash ,cp.tx_index ,cp.block_index,transactions.block_time,cp.odin_set, cp.validity from odins cp,transactions where (cp.full_odin='" + odin + "' or cp.short_odin='" + odin + "') and cp.tx_index=transactions.tx_index;");
        try {
            if (rs.next()) {
                OdinInfo odinInfo = new OdinInfo();
                odinInfo.fullOdin = rs.getString("full_odin");
                odinInfo.shortOdin = rs.getInt("short_odin");
                odinInfo.register = rs.getString("register");
                odinInfo.admin = rs.getString("admin");
                odinInfo.txIndex = rs.getInt("tx_index");
                odinInfo.txHash = rs.getString("tx_hash");
                odinInfo.blockIndex = rs.getInt("block_index");
                odinInfo.blockTime = rs.getInt("block_time");
                odinInfo.validity = rs.getString("validity");
                try {
                    odinInfo.odinSet = new JSONObject(rs.getString("odin_set"));
                } catch (Exception e) {
                    odinInfo.odinSet = new JSONObject();
                    logger.error(e.toString());
                }
                return odinInfo;
            }
        } catch (SQLException e) {
        }

        return null;
    }

    public static Integer getShortOdin(String full_odin) {
        Database db = Database.getInstance();

        ResultSet rs = db.executeQuery("select full_odin,short_odin from odins  where full_odin='" + full_odin + "';");

        try {
            if (rs.next()) {
                return rs.getInt("short_odin");
            }
        } catch (SQLException e) {
        }

        return -1;
    }

    public static boolean checkUpdatable(String authSet, String updater, String register, String admin) {
        if (((authSet == null || authSet.length() == 0 || authSet.equals("1")) && updater.equals(admin))
                || ((authSet.equals("0") || authSet.equals("2")) && (updater.equals(register) || updater.equals(admin)))) {
            return true;
        } else {
            return false;
        }
    }

    public static Integer getLastShortOdin() {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("SELECT short_odin from odins order by short_odin DESC LIMIT 1;");
        try {
            while (rs.next()) {
                return rs.getInt("short_odin");
            }
        } catch (SQLException e) {
        }
        return -1;
    }
}
