package mpc;

import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.KeyPair;

/**
 *
 * @author Vasilios Mavroudis and Petr Svenda
 */
public class QuorumContext {
    private MPCCryptoOperations cryptoOps = null;

    public short CARD_INDEX_THIS = 0;   // index of player realised by this card
    public short NUM_PLAYERS = 0;       // current number of players

    class Player {
        public boolean bYsValid = false;            // Is player's share (Ys) currently valid?
        public byte[] YsCommitment = null;          // Value of comitment of player's share  (hash(Ys))
        public boolean bYsCommitmentValid = false;  // Is comitment currently valid?
    }
    private Player[] players = null;                // contexts for all protocol participants (including this card)

    // Signing
    public Bignat signature_counter = null;
    public byte[] signature_secret_seed = null; 

    // Distributed keypair generation share
    ECCurve theCurve = null;
    private KeyPair pair = null;
    private byte[] x_i_Bn = null;           // share xi, which is a randomly sampled element from Zn
    private byte[] this_card_Ys = null;     // Ys for this card (not stored in Player[] context as shares are combined on the fly) 
    private mpc.ECPointBase Y_EC_onTheFly = null; // aggregated Ys computed on the fly instead of in one shot once all shares are provided (COMPUTE_Y_ONTHEFLY)
    private short Y_EC_onTheFly_shares_count = 0; // number of public key shares already provided and combined during KeyGen_StorePublicKey

    // BUGBUG: Check thoroughly for all state transitions (automata-based programming)
    private short STATE = -1; // current state of the protocol run - some operations are not available in given state    
    
    
    public QuorumContext(ECConfig eccfg, ECCurve curve, MPCCryptoOperations cryptoOperations) {
        cryptoOps = cryptoOperations;
        signature_counter = new Bignat(Consts.SHARE_BASIC_SIZE, JCSystem.MEMORY_TYPE_TRANSIENT_RESET, eccfg.bnh);
        signature_secret_seed = new byte[Consts.SECRET_SEED_SIZE];
        
        theCurve = curve;
        this.pair = theCurve.newKeyPair(this.pair);
        x_i_Bn = JCSystem.makeTransientByteArray(Consts.SHARE_BASIC_SIZE, JCSystem.MEMORY_TYPE_TRANSIENT_RESET);

        players = new Player[Consts.MAX_NUM_PLAYERS];
        this_card_Ys = JCSystem.makeTransientByteArray(Consts.PUBKEY_YS_SHARE_SIZE, JCSystem.MEMORY_TYPE_TRANSIENT_RESET);
        for (short i = 0; i < Consts.MAX_NUM_PLAYERS; i++) {
            players[i] = new Player();
            if (Consts.PLAYERS_IN_RAM) {
                players[i].YsCommitment = JCSystem.makeTransientByteArray(Consts.SHARE_BASIC_SIZE, JCSystem.MEMORY_TYPE_TRANSIENT_RESET);
            } else {
                players[i].YsCommitment = new byte[Consts.SHARE_BASIC_SIZE];
            }
        }

        Y_EC_onTheFly = ECPointBuilder.createPoint(SecP256r1.KEY_LENGTH);
        Y_EC_onTheFly.initializeECPoint_SecP256r1();

        STATE = 0;
    }
    
    public void Reset() {
        Invalidate(true);
    }
    
    short getState() {
        return STATE;
    }

    /**
     * Initialize new quorum context and generates initial keypair for this card. 
     * Sets quorum size (numPlayers), id of this card. Prepares necessary initial structires
     * @param numPlayers number of participants in this quorum
     * @param cardID participant index assigned to this card 
     * @param bPrepareDecryption if true, speedup engines for fast decryption are pre-prepared
     */
    public void InitAndGenerateKeyPair(short numPlayers, short cardID, boolean bPrepareDecryption) {
        if (numPlayers > Consts.MAX_NUM_PLAYERS) {
            ISOException.throwIt(Consts.SW_TOOMANYPLAYERS);
        }

        // Invalidate previously generated keypair  
        Invalidate(false);

        NUM_PLAYERS = numPlayers;
        CARD_INDEX_THIS = cardID;

        pair.genKeyPair();

        if (Consts.IS_BACKDOORED_EXAMPLE) {
            // This branch demonstrates behavior of malicious attacker 
            GenerateExampleBackdooredKeyPair();
        } else {
            // Legitimate generation of key as per protocol by non-compromised participants
            ((ECPrivateKey) pair.getPrivate()).getS(x_i_Bn, (short) 0);
        }

        // Add this card share into (future) aggregate key
        cryptoOps.placeholder.ScalarMultiplication(cryptoOps.GenPoint, x_i_Bn, this_card_Ys); // yG
        Y_EC_onTheFly.setW(this_card_Ys, (short) 0, (short) this_card_Ys.length);
        Y_EC_onTheFly_shares_count++; // share for this card is included
        // Update stored x_i properties
        players[CARD_INDEX_THIS].bYsValid = true;
        // Compute commitment
        cryptoOps.md.reset();
        cryptoOps.md.doFinal(this_card_Ys, (short) 0, (short) this_card_Ys.length, players[CARD_INDEX_THIS].YsCommitment, (short) 0);
        players[CARD_INDEX_THIS].bYsCommitmentValid = true;

        // Pre-prepare engine for faster Decrypt later
        if (bPrepareDecryption) {
            if (ECPointBase.ECMultiplHelperDecrypt != null) { // Use prepared engine - cards with native support for EC
                ECPointBase.disposable_privDecrypt.setS(x_i_Bn, (short) 0, (short) x_i_Bn.length);
                ECPointBase.ECMultiplHelperDecrypt.init(ECPointBase.disposable_privDecrypt);
            }
        }
        STATE = 0;
    }
    
    public final byte[] privbytes_backdoored = {(byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55};
    /**
     * Generates intentionally insecure private key to demonstrate behaviour when 
     * some participants are malicious. Private key bytes are all 0x55 ... 0x55
     */
    void GenerateExampleBackdooredKeyPair() {
        
        // If enabled, key is not generated randomly as required per protocol, but fixed to vulnerable value instead
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();

        // Set "backdoored" (known) private key - all 0x55 ... 0x55
        priv.setS(privbytes_backdoored, (short) 0, (short) privbytes_backdoored.length);
        ((ECPrivateKey) pair.getPrivate()).getS(x_i_Bn, (short) 0);
        // Compute and set corresponding public key (to backdoored private one)
        cryptoOps.placeholder.ScalarMultiplication(cryptoOps.GenPoint, privbytes_backdoored, cryptoOps.tmp_arr);
        pub.setW(cryptoOps.tmp_arr, (short) 0, (short) 65);
    }

    public short GetShareCommitment(byte[] array, short offset) {
        if (players[CARD_INDEX_THIS].bYsCommitmentValid) {
            Util.arrayCopyNonAtomic(players[CARD_INDEX_THIS].YsCommitment, (short) 0, array, offset, (short) players[CARD_INDEX_THIS].YsCommitment.length);
            return (short) players[CARD_INDEX_THIS].YsCommitment.length;
        } else {
            ISOException.throwIt(Consts.SW_INVALIDCOMMITMENT);
            return (short) -1;
        }
    }

    // State 0
    public void SetShareCommitment(short id, byte[] commitment, short commitmentOffset, short commitmentLength) {
        if (id < 0 || id == CARD_INDEX_THIS || id >= NUM_PLAYERS) {
            ISOException.throwIt(Consts.SW_INVALIDPLAYERINDEX);
        }
        Util.arrayCopyNonAtomic(commitment, commitmentOffset, players[id].YsCommitment, (short) 0, commitmentLength);
        players[id].bYsCommitmentValid = true;
    }

    /** 
     * Sets public key share of other participant after verification of commitment match.
     * @param id    index of target participant
     * @param Y     buffer with target participant share
     * @param YOffset start offset within Y
     * @param YLength length of share
     */
    public void SetYs(short id, byte[] Y, short YOffset, short YLength) {
        if (players[id].bYsValid) {
            ISOException.throwIt(Consts.SW_SHAREALREADYSTORED);
        }
        if (id == CARD_INDEX_THIS) {
            ISOException.throwIt(Consts.SW_INVALIDPLAYERINDEX);
        }
        if (YLength != Consts.PUBKEY_YS_SHARE_SIZE) {
            ISOException.throwIt(Consts.SW_INVALIDYSHARE);
        }
        
        // Verify against previously stored hash
        if (!players[id].bYsCommitmentValid) {
            ISOException.throwIt(Consts.SW_INVALIDCOMMITMENT);
        }
        if (!cryptoOps.VerifyPair(Y, YOffset, YLength, players[id].YsCommitment)) {
            ISOException.throwIt(Consts.SW_INVALIDCOMMITMENT);
        }

        // Directly add into Y_EC_onTheFly, no storage into RAM
        ECPointBase.ECPointAddition(Y_EC_onTheFly, Y, YOffset, Y_EC_onTheFly);
        players[id].bYsValid = true;
        Y_EC_onTheFly_shares_count++;

        // check if shares for all players were included. If yes, change the state 
        if (Y_EC_onTheFly_shares_count == NUM_PLAYERS) {
            for (short i = 0; i < NUM_PLAYERS; i++) {
                if (!players[i].bYsValid) {
                    ISOException.throwIt(Consts.SW_INTERNALSTATEMISMATCH);
                }
            }
            STATE = 2;
        }
    }

    /**
     * Returns this card public key share
     * @param commitmentBuffer output buffer where to store commitment
     * @param commitmentOffset start offset within target output buffer
     * @return 
     */
    public short GetYi(byte[] commitmentBuffer, short commitmentOffset) {
        //If not on state 1 already:
        if (STATE < 1) {
            // Ready to move to state 1?
            short number_valid_commitments = 0;
            for (short i = 0; i < NUM_PLAYERS; i++) {
                if (players[i].bYsCommitmentValid) {
                    number_valid_commitments += 1;
                }
            }

            if (number_valid_commitments == NUM_PLAYERS) {
                STATE = 1; // All commitments were collected, allow for export of this card share 
            }
        }

        if (STATE >= 1) {
            if (players[CARD_INDEX_THIS].bYsValid) {
                Util.arrayCopyNonAtomic(this_card_Ys, (short) 0, commitmentBuffer, commitmentOffset, (short) this_card_Ys.length);
                return (short) this_card_Ys.length;
            } else {
                ISOException.throwIt(Consts.SW_INVALIDYSHARE);
            }
        } else {
            ISOException.throwIt(Consts.SW_INCORRECTSTATE);
        }
        return 0;
    }

    // State 2
    public byte[] Getxi() { // Used to sign and decrypt
        if ((STATE >= 2) || (NUM_PLAYERS == 1)) {
            return x_i_Bn;
        } else {
            ISOException.throwIt(Consts.SW_INCORRECTSTATE);
            return null;
        }
    }

    public short Getxi(byte[] array, short offset) {
        if ((STATE >= 2) || (NUM_PLAYERS == 1)) {
            Util.arrayCopyNonAtomic(x_i_Bn, (short) 0, array, offset, (short) x_i_Bn.length);
            return (short) x_i_Bn.length;
        } else {
            return (short) -1;
        }
    }

    // State 2
    public ECPointBase GetY() {
        if ((STATE >= 2) || (NUM_PLAYERS == 1)) {
            return Y_EC_onTheFly;
        }

        return null;
    }

    // State -1
    public void Invalidate(boolean bEraseAllArrays) {
        NUM_PLAYERS = 0;
        CARD_INDEX_THIS = 0;

        if (bEraseAllArrays) {
            cryptoOps.randomData.generateData(cryptoOps.tmp_arr, (short) 0, (short) cryptoOps.tmp_arr.length);
            cryptoOps.randomData.generateData(x_i_Bn, (short) 0, (short) x_i_Bn.length);
            cryptoOps.randomData.generateData(signature_secret_seed, (short) 0, (short) signature_secret_seed.length);
            cryptoOps.randomData.generateData(this_card_Ys, (short) 0, (short) this_card_Ys.length);
            
        }
        // Invalidate all items
        for (short i = 0; i < Consts.MAX_NUM_PLAYERS; i++) {
            players[i].bYsCommitmentValid = false;
            players[i].bYsValid = false;
            if (bEraseAllArrays) {
                cryptoOps.randomData.generateData(players[i].YsCommitment, (short) 0, (short) players[i].YsCommitment.length);
            }
        }

        // TODO: clear Y_EC_onTheFly
        STATE = -1;
        Y_EC_onTheFly_shares_count = 0;
        
        signature_counter.zero();
    }

}
