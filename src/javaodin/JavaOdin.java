package javaodin;

public class JavaOdin {

    public static void main(String[] args) throws Exception {
        Config.PPK_ODIN_MARK_PUBKEY_HEX = Config.PPK_ODIN_MARK_PUBKEY_HEX_TESTNET;
        Config.appName += "Test";
        System.out.println("Loading " + Config.appName + " V" + Config.version + "  server without GUI ...");
        while (true) {
            // Start Blocks
            final Blocks blocks = Blocks.getInstanceFresh();
            Thread progressUpdateThread = new Thread(blocks) {
                public void run() {
                    Integer lastParsedBlock = Util.getLastParsedBlock();
                    while (blocks.ppkBlock == 0 || blocks.working || blocks.parsing || lastParsedBlock < blocks.bitcoinBlock) {
                        if (blocks.ppkBlock > 0) {
                            if (blocks.ppkBlock < blocks.bitcoinBlock) {
                                System.out.println("Getting block" + " " + blocks.ppkBlock + "/" + blocks.bitcoinBlock);
                            } else {
                                System.out.println("Parsing" + " " + blocks.ppkBlock + "/" + blocks.bitcoinBlock);
                            }
                        } else {
                            System.out.println(blocks.statusMessage);
                        }
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        lastParsedBlock = Util.getLastParsedBlock();
                    }
                }
            };
            progressUpdateThread.start();
            blocks.init();
            blocks.versionCheck();
            blocks.follow();
            Thread.sleep(8000);
        }
    }
}
