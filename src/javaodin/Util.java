package javaodin;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.io.FileOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ipfs.api.IPFS;
import org.ipfs.api.Multihash;

public class Util {

    static Logger logger = LoggerFactory.getLogger(Util.class);
    private static String mMinVersion = null;
    private static Boolean mIpfsRunning = null;

    public static String getPage(String urlString) {
        return getPage(urlString, 1);
    }

    public static String getPage(String urlString, int retries) {
        try {
            logger.info("Getting URL: " + urlString);
            doTrustCertificates();
            URL url = new URL(urlString);
            HttpURLConnection connection = null;
            connection = (HttpURLConnection) url.openConnection();
            connection.setUseCaches(false);
            connection.addRequestProperty("User-Agent", Config.appName + " " + Config.version);
            connection.setRequestMethod("GET");
            connection.setDoOutput(true);
            connection.setReadTimeout(10000);
            connection.connect();
            BufferedReader rd = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                sb.append(line + '\n');
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("Fetch URL error: " + e.toString());
        }
        return "";
    }

    public static void doTrustCertificates() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                }
            }
        };
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    private static boolean isRedirected(Map<String, List<String>> header) {
        for (String hv : header.get(null)) {
            if (hv.contains(" 301 ") || hv.contains(" 302 ")) {
                return true;
            }
        }
        return false;
    }

    public static void downloadToFile(String link, String fileName) {
        try {
            URL url = new URL(link);
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            Map<String, List<String>> header = http.getHeaderFields();
            while (isRedirected(header)) {
                link = header.get("Location").get(0);
                url = new URL(link);
                http = (HttpURLConnection) url.openConnection();
                header = http.getHeaderFields();
            }
            InputStream input = http.getInputStream();
            byte[] buffer = new byte[4096];
            int n = -1;
            OutputStream output = new FileOutputStream(new File(fileName));
            while ((n = input.read(buffer)) != -1) {
                output.write(buffer, 0, n);
            }
            output.close();
        } catch (IOException e) {
            logger.info(e.toString());
        }
    }

    public static String format(Double input) {
        return format(input, "#.00");
    }

    public static Long getNowTimestamp() {
        return (new Date()).getTime() / (long) 1000;
    }

    public static String format(Double input, String format) {
        return (new DecimalFormat(format)).format(input);
    }

    public static String timeFormat(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); // the format of your date
        String formattedDate = sdf.format(date);
        return formattedDate;
    }

    public static String timeFormat(Integer timestamp) {
        Date date = new Date(timestamp * 1000L); // *1000 is to convert seconds to milliseconds
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); // the format of your date
        String formattedDate = sdf.format(date);
        return formattedDate;
    }

    public static Integer getLastBlock() {
        Blocks blocks = Blocks.getInstance();
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("select * from blocks order by block_index desc limit 1;");
        try {
            while (rs.next()) {
                return rs.getInt("block_index");
            }
        } catch (SQLException e) {
        }
        return blocks.ppkBlock;
    }

    public static Integer getLastTxIndex() {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("SELECT * FROM transactions WHERE tx_index = (SELECT MAX(tx_index) from transactions);");
        try {
            while (rs.next()) {
                return rs.getInt("tx_index");
            }
        } catch (SQLException e) {
        }
        return 0;
    }

    public static void updateLastParsedBlock(Integer block_index) {
        Database db = Database.getInstance();
        db.executeUpdate("REPLACE INTO sys_parameters (para_name,para_value) values ('last_parsed_block','" + block_index.toString() + "');");
    }

    public static Integer getLastParsedBlock() {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("SELECT para_value FROM sys_parameters WHERE para_name='last_parsed_block'");
        try {
            while (rs.next()) {
                return rs.getInt("para_value");
            }
        } catch (SQLException e) {
        }
        return 0;
    }

    public static byte[] toByteArray(List<Byte> in) {
        final int n = in.size();
        byte ret[] = new byte[n];
        for (int i = 0; i < n; i++) {
            ret[i] = in.get(i);
        }
        return ret;
    }

    public static List<Byte> toByteArrayList(byte[] in) {
        List<Byte> arrayList = new ArrayList<Byte>();

        for (byte b : in) {
            arrayList.add(b);
        }
        return arrayList;
    }

    public static String getMinVersion() {
        if (mMinVersion != null && mMinVersion.length() > 0) {
            return mMinVersion;
        }
        mMinVersion = getPage(Config.minVersionPage);
        if (mMinVersion == null || mMinVersion.trim().length() == 0) {
            mMinVersion = "0.0";
        } else {
            mMinVersion = mMinVersion.trim();
        }
        return mMinVersion;
    }

    public static Integer getMinMajorVersion() {
        String minVersion = getMinVersion();
        String[] pieces = minVersion.split("\\.");
        return Integer.parseInt(pieces[0].trim());
    }

    public static Integer getMinMinorVersion() {
        String minVersion = getMinVersion();
        String[] pieces = minVersion.split("\\.");
        return Integer.parseInt(pieces[1].trim());
    }

    public static String getBlockHash(Integer blockIndex) {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("select block_hash from blocks where block_index='" + blockIndex.toString() + "';");
        try {
            if (rs.next()) {
                return rs.getString("block_hash");
            }
        } catch (SQLException e) {
        }
        return null;
    }

    public static Integer getLastBlockTimestamp() {
        Database db = Database.getInstance();
        ResultSet rs = db.executeQuery("select * from blocks order by block_index desc limit 1;");
        try {
            while (rs.next()) {
                return rs.getInt("block_time");
            }
        } catch (SQLException e) {
        }
        return 0;
    }

    //Compress string
    public static String compress(String str) throws Exception {
        if (str == null || str.length() == 0) {
            return str;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(str.getBytes(Config.PPK_TEXT_CHARSET));
        gzip.close();
        return out.toString(Config.BINARY_DATA_CHARSET);
    }

    //Uncompress string
    public static String uncompress(String str) throws Exception {
        if (str == null || str.length() == 0) {
            return str;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(str.getBytes(Config.BINARY_DATA_CHARSET));
        GZIPInputStream gunzip = new GZIPInputStream(in);
        byte[] buffer = new byte[256];
        int n;
        while ((n = gunzip.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return out.toString(Config.PPK_TEXT_CHARSET);
    }

    /*
   * Convert byte[] to hex string.。   
   * @param src byte[] data   
   * @return hex string   
     */
    public static String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return null;
        }
        for (int i = 0; i < src.length; i++) {
            int v = src[i] & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) {
                stringBuilder.append(0);
            }
            stringBuilder.append(hv);
        }
        return stringBuilder.toString();
    }

    /**
     * Convert char to byte
     *
     * @param c char
     * @return byte
     */
    private static byte charToByte(char c) {
        return (byte) "0123456789ABCDEF".indexOf(c);
    }

    //生成有效的公钥数据块来嵌入指定的数据内容
    public static byte[] generateValidPubkey(String data_str) {
        System.out.println("Util.generateValidPubkey() data_str=" + data_str);
        byte[] data = null;

        try {
            data = data_str.getBytes(Config.BINARY_DATA_CHARSET);

            return generateValidPubkey(data);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] generateValidPubkey(byte[] data) {
        if (data.length > Config.PPK_PUBKEY_EMBED_DATA_MAX_LENGTH) {
            System.out.println("The data segment length should be less than " + Config.PPK_PUBKEY_EMBED_DATA_MAX_LENGTH);
            return null;
        }
        List<Byte> dataArrayList = new ArrayList<Byte>();
        try {
            dataArrayList = Util.toByteArrayList(data);

            for (int kk = dataArrayList.size(); kk < Config.PPK_PUBKEY_EMBED_DATA_MAX_LENGTH; kk++) {
                dataArrayList.add((byte) 0x20); //追加空格
            }
        } catch (Exception e) {
            return null;
        }
        dataArrayList.add(0, (byte) data.length);
        dataArrayList.add(0, Config.PPK_PUBKEY_TYPE_FLAG);
        while (dataArrayList.size() < Config.PPK_PUBKEY_LENGTH) {
            dataArrayList.add((byte) 0x20);
        }
        data = Util.toByteArray(dataArrayList);

        return data;
    }

    public static String getIpfsData(String ipfs_hash_address) {
        try {
            IPFS ipfs = new IPFS(Config.IPFS_API_ADDRESS);
            Multihash filePointer = Multihash.fromBase58(ipfs_hash_address);
            byte[] fileContents = ipfs.cat(filePointer);
            return new String(fileContents);
        } catch (Exception e) {
            System.out.println("Util.getIpfsData() error:" + e.toString());
            String tmp_url = Config.IPFS_PROXY_URL + ipfs_hash_address;
            System.out.println("Using IPFS Proxy to fetch:" + tmp_url);
            return getPage(tmp_url);
        }
    }

    public static String fetchURI(String uri) {
        try {
            String[] uri_chunks = uri.split(":");
            if (uri_chunks.length < 2) {
                logger.error("Util.fetchURI() meet invalid uri:" + uri);
                return null;
            }
            if (uri_chunks[0].equalsIgnoreCase("ipfs")) {
                return getIpfsData(uri_chunks[1]);
            } else if (uri_chunks[0].equalsIgnoreCase("ppk")) {
                //return fetchPPkURI(uri);
            } else if (uri_chunks[0].equalsIgnoreCase("data")) {
                int from = uri_chunks[1].indexOf(",");
                if (from >= 0) {
                    return uri_chunks[1].substring(from + 1, uri_chunks[1].length());
                } else {
                    return uri_chunks[1];
                }
            } else {
                return getPage(uri);
            }
        } catch (Exception e) {
            logger.error("Util.fetchURI(" + uri + ") error:" + e.toString());
        }
        return null;
    }
}
